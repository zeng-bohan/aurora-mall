#!/usr/bin/env bash
# M5 S5 验收缝：优惠券全生命周期（领 → 用 → 回）+ 三类边界，全程经网关的真实用户路径。
#
# 前置条件：
#   1) docker compose up -d && bash nacos/import.sh
#   2) gateway(:8000)、user(:8081)、product(:8082)、order(:8084)、inventory(:8085) 已启动
#      （券生命周期的最后一跳"回券"由 order 的关单监听触发，它会调 inventory 释放预占）
#   3) 券表已建（幂等）：docker exec -i aurora-mysql mysql -uroot -p${MYSQL_PASSWORD:-aurora123} \
#        < docker/mysql/init/05-aurora_order.sql
#
# 「退款回券」在本项目里等价于**关单回券**：已支付的订单不会被关单（order 状态机保证），
# 所以不存在"已核销还要回券"的路径；关单（含支付超时）才是券回到用户手上的真实路径。
# 本脚本用配置中心把关单延迟压到 10s 来验证它（跑完还原；异常退出靠 trap 兜底）。
#
# 边界覆盖：重复领（50004）、券已领完（50003）、金额未达门槛（50007）、券已过期（50006）。
#
# 注意：**发给服务的请求体一律用纯 ASCII**。Git Bash 把命令行参数交给原生 curl 时会按
# ANSI 代码页转码，中文会变成非 UTF-8 字节，服务端 Jackson 直接以 10001 拒绝
# （实测报错：Invalid UTF-8 middle byte 0xfa）。断言字符串与屏幕输出不受影响。
set -uo pipefail

BASE="${BASE:-http://localhost:8000}"
NACOS_ADDR="${NACOS_ADDR:-localhost:8848}"
MYSQL_PASSWORD="${MYSQL_PASSWORD:-aurora123}"
STAMP=$(date +%s)
PASSWORD="secret123"

PASS=0
FAIL=0
STEP=0

# req：与 smoke-flows 同形，唯一差别是额外请求头改成可变参数——
# 用券下单要同时带 Idempotency-Key 与 Coupon-Id 两个头。
req() { # 方法 路径 token 请求体 [额外请求头...]
  local method="$1" path="$2" token="${3:-}" body="${4:-}"
  shift 4 || true
  local args=(-sS -X "$method" -o- -w $'\n%{http_code}' -H 'Content-Type: application/json')
  [[ -n "$token" ]] && args+=(-H "Authorization: Bearer $token")
  [[ -n "$body" ]] && args+=(--data "$body")
  local header
  for header in "$@"; do args+=(-H "$header"); done
  local out
  out=$(curl "${args[@]}" "$BASE$path" 2>/dev/null) || out=$'\n000'
  STATUS="${out##*$'\n'}"
  RESP="${out%$'\n'*}"
}

ok()   { PASS=$((PASS + 1)); echo "  ok   $1"; }
bad()  { FAIL=$((FAIL + 1)); echo "  FAIL $1 (status=$STATUS resp=${RESP:0:160})"; }
step() { STEP=$((STEP + 1)); echo "[$STEP] $1"; }

assert_status() { # 期望状态码 [标签]
  local label="${2:-http $1}"
  [[ "$STATUS" == "$1" ]] && ok "$label" || bad "$label (want HTTP $1)"
}
assert_body() { # 固定字符串 标签
  grep -qF "$1" <<<"$RESP" && ok "$2" || bad "$2 (missing '$1')"
}
assert_eq() { # 期望值 实际值 标签
  [[ "$1" == "$2" ]] && ok "$3" || bad "$3 (want '$1', got '$2')"
}
assert_unmoved() { # skuId 种子值 标签：预占视图"没被动过"= 键不存在（清过 key 后只有真正预扣才会写）
  local value
  value=$(redis_get "$1")
  [[ -z "$value" || "$value" == "$2" ]] && ok "$3" || bad "$3 (stock=$value want empty-or-$2)"
}

db_scalar() { # sql -> 首个单元格
  docker exec aurora-mysql mysql -uroot -p"$MYSQL_PASSWORD" -N -e "$1" 2>/dev/null | tr -d '\r'
}
redis_get() { # skuId -> 库存预占快照
  docker exec aurora-redis redis-cli GET "aurora:stock:$1" | tr -d '\r'
}
data_id() { # 从 RESP 取 '"data":N'
  grep -oE '"data":[0-9]+' <<<"$RESP" | head -1 | cut -d: -f2
}
login_token() { # username -> accessToken
  req POST /api/user/login "" "{\"username\":\"$1\",\"password\":\"$PASSWORD\"}"
  grep -oE '"accessToken":"[^"]+"' <<<"$RESP" | head -1 | cut -d'"' -f4
}
coupon_state() { # couponId -> "STATUS/orderId 或 -"
  db_scalar "SELECT CONCAT(status, '/', IFNULL(order_id, '-')) FROM aurora_order.user_coupon WHERE id=$1"
}
template_remaining() { # templateId -> 余量
  db_scalar "SELECT total - claimed FROM aurora_order.coupon_template WHERE id=$1"
}
seed_stock() { # skuId quantity：重置预占视图（与 smoke-flows 同法）
  docker exec aurora-mysql mysql -uroot -p"$MYSQL_PASSWORD" -e \
    "INSERT INTO aurora_inventory.product_stock (sku_id, available, reserved) VALUES ($1, $2, 0)
     ON DUPLICATE KEY UPDATE available=$2, reserved=0;" 2>/dev/null
  docker exec aurora-redis redis-cli DEL "aurora:stock:$1" > /dev/null
}
nacos_publish_order() { # 关单延迟级别 超时秒数
  local content="aurora:
  order:
    close-delay-level: $1
    close-timeout-seconds: $2
  tx:
    mode: mq"
  curl -fs -X POST "http://$NACOS_ADDR/nacos/v1/cs/configs" \
    --data-urlencode "dataId=aurora-order.yml" \
    --data-urlencode "group=DEFAULT_GROUP" \
    --data-urlencode "tenant=dev" \
    --data-urlencode "type=yml" \
    --data-urlencode "content=$content" > /dev/null
}
restore_close_config() { nacos_publish_order 16 1800 > /dev/null 2>&1 || true; }
trap restore_close_config EXIT

step "前置：注册三个账号（运营 / 两位用户）并取 token"
ADMIN="admin$STAMP"
U1="coupon1$STAMP"
U2="coupon2$STAMP"
for name in "$ADMIN" "$U1" "$U2"; do
  req POST /api/user/register "" "{\"username\":\"$name\",\"password\":\"$PASSWORD\",\"nickname\":\"coupon\"}"
  assert_body '"code":0' "register $name"
done
db_scalar "UPDATE aurora_user.users SET role='ADMIN' WHERE username='$ADMIN'" > /dev/null
ADMIN_TOKEN=$(login_token "$ADMIN")
TOKEN1=$(login_token "$U1")
TOKEN2=$(login_token "$U2")
USER1_ID=$(db_scalar "SELECT id FROM aurora_user.users WHERE username='$U1'")
assert_eq "3" "$(grep -c . <<<"$(printf '%s\n%s\n%s' "$ADMIN_TOKEN" "$TOKEN1" "$TOKEN2")")" "三个账号都拿到 token"

step "运营建券：满 100 减 20（总量 2）+ 满 100 减 10（总量 1）"
WINDOW="\"claimStartAt\":\"$(date -d '1 minute ago' +%Y-%m-%dT%H:%M:%S)\",\"claimEndAt\":\"$(date -d '2 days' +%Y-%m-%dT%H:%M:%S)\",\"validDays\":1"
req POST /api/order/admin/coupons "$ADMIN_TOKEN" "{\"title\":\"smoke-100-20-$STAMP\",\"thresholdAmount\":100.00,\"discountAmount\":20.00,\"total\":2,$WINDOW}"
assert_body '"code":0' "建券 T1（满 100 减 20，总量 2）"
T1=$(data_id)
req POST /api/order/admin/coupons "$ADMIN_TOKEN" "{\"title\":\"smoke-100-10-$STAMP\",\"thresholdAmount\":100.00,\"discountAmount\":10.00,\"total\":1,$WINDOW}"
assert_body '"code":0' "建券 T2（总量 1，用于验证领完）"
T2=$(data_id)

step "$U1 领 T1：券到手且状态为 UNUSED"
req POST "/api/order/coupons/$T1/claim" "$TOKEN1"
assert_status 200 "领券 http 200"
assert_body '"code":0' "领券成功"
CID1=$(data_id)
assert_eq "UNUSED/-" "$(coupon_state "$CID1")" "券状态 UNUSED 且未绑定订单"
req GET /api/order/coupons/mine "$TOKEN1"
assert_body '"status":"UNUSED"' "我的券列表包含它"
assert_eq "1" "$(template_remaining "$T1")" "模板余量 2 → 1"

step "边界①：重复领同一张券 → 50004，且不再消耗名额"
req POST "/api/order/coupons/$T1/claim" "$TOKEN1"
assert_body '"code":50004' "重复领被拒（50004）"
assert_eq "1" "$(template_remaining "$T1")" "失败不占名额（余量仍为 1）"

step "边界②：券已领完 → 50003（换一个用户来领总量 1 的 T2）"
req POST "/api/order/coupons/$T2/claim" "$TOKEN2"
assert_body '"code":0' "$U2 领到 T2"
req POST "/api/order/coupons/$T2/claim" "$TOKEN1"
assert_body '"code":50003' "$U1 领 T2 被拒（50003 已领完）"
assert_eq "0" "$(template_remaining "$T2")" "T2 余量为 0"

step "造数据：价 100 与价 10 的两个 SKU，各就绪 100 件库存"
req POST /api/product/admin/products "$ADMIN_TOKEN" "{\"title\":\"coupon-sku-100-$STAMP\",\"price\":100.00,\"stock\":100000}"
assert_body '"code":0' "建 SKU_100（价 100）"
SKU100=$(data_id)
req POST /api/product/admin/products "$ADMIN_TOKEN" "{\"title\":\"coupon-sku-10-$STAMP\",\"price\":10.00,\"stock\":100000}"
assert_body '"code":0' "建 SKU_10（价 10）"
SKU10=$(data_id)
seed_stock "$SKU100" 100
seed_stock "$SKU10" 100
assert_unmoved "$SKU10" 100 "SKU_10 尚无预占（seed 清过 key）"

step "边界③：金额未达门槛 → 50007，且不预扣、不落单、券仍可用"
req POST /api/order/orders "$TOKEN1" "{\"skuId\":$SKU10,\"quantity\":1}" \
  "Idempotency-Key: thr-$STAMP" "Coupon-Id: $CID1"
assert_status 200 "门槛不足 http 200"
assert_body '"code":50007' "金额未达门槛被拒（50007）"
assert_eq "UNUSED/-" "$(coupon_state "$CID1")" "券没有被扣住（仍 UNUSED、未绑定）"
assert_unmoved "$SKU10" 100 "门槛判定在预扣之前：库存未动"
assert_eq "0" "$(db_scalar "SELECT COUNT(*) FROM aurora_order.orders WHERE user_id=$USER1_ID AND sku_id=$SKU10")" "没有落下订单"

step "用券下单：满 100 减 20 落到订单金额上"
# 关单延迟必须在这一步之前压短：延迟级别是下单那一刻随消息带走的，
# 下完单再改配置只影响之后的新订单（smoke-flows 链 B 也是这个次序）
nacos_publish_order 3 10 && ok "nacos: close-delay-level=3, timeout=10s"
sleep 2
req POST /api/order/orders "$TOKEN1" "{\"skuId\":$SKU100,\"quantity\":1}" \
  "Idempotency-Key: use-$STAMP" "Coupon-Id: $CID1"
assert_status 200 "下单 http 200"
assert_body '"code":0' "用券下单成功"
OID=$(data_id)
assert_eq "80.00" "$(db_scalar "SELECT total_amount FROM aurora_order.orders WHERE id=$OID")" "订单金额 = 100 - 20 = 80.00"
assert_eq "LOCKED/$OID" "$(coupon_state "$CID1")" "券被锁定并绑定到该订单"
assert_eq "99" "$(redis_get "$SKU100")" "库存预占 1（100 → 99）"
req GET "/api/order/orders/$OID" "$TOKEN1"
assert_body '"totalAmount":80.00' "订单详情同样显示抵扣后金额"

step "边界④：过期券 → 50006（券表直接放一张已过期的券做夹具）"
# 夹具用 T2：$U1 没领到过它（第 5 步被 50003 拒了），不会撞 uk_user_coupon_once 唯一键
docker exec aurora-mysql mysql -uroot -p"$MYSQL_PASSWORD" -e "INSERT INTO aurora_order.user_coupon
  (template_id, user_id, status, title, threshold_amount, discount_amount, claimed_at, expire_at)
  VALUES ($T2, $USER1_ID, 'UNUSED', 'expired-fixture-$STAMP', 100.00, 20.00,
          '$(date -d '2 days ago' +%Y-%m-%dT%H:%M:%S)', '$(date -d '1 day ago' +%Y-%m-%dT%H:%M:%S)');" 2>/dev/null
EXPIRED_CID=$(db_scalar "SELECT id FROM aurora_order.user_coupon
  WHERE user_id=$USER1_ID AND title='expired-fixture-$STAMP' ORDER BY id DESC LIMIT 1")
[[ -n "$EXPIRED_CID" ]] && ok "夹具券已插入（id=$EXPIRED_CID）" || bad "夹具券插入失败（后续断言会因此失去意义）"
req POST /api/order/orders "$TOKEN1" "{\"skuId\":$SKU100,\"quantity\":1}" \
  "Idempotency-Key: exp-$STAMP" "Coupon-Id: $EXPIRED_CID"
assert_body '"code":50006' "过期券被拒（50006）"
assert_eq "UNUSED/-" "$(coupon_state "$EXPIRED_CID")" "过期券不会被扣成 LOCKED"

step "关单回券：订单被延迟消息关闭后，券回到 UNUSED"
echo "  ... waiting 16s for the delayed close ..."
sleep 16
req GET "/api/order/orders/$OID" "$TOKEN1"
assert_body '"status":2' "订单已被延迟消息关闭"
assert_eq "UNUSED/-" "$(coupon_state "$CID1")" "券回到 UNUSED 并解绑"
assert_eq "100" "$(redis_get "$SKU100")" "库存预占也已释放（99 → 100）"
nacos_publish_order 16 1800 && ok "nacos: 关单配置已还原（level 16）"

echo
if [[ $FAIL -eq 0 ]]; then
  echo "smoke-coupon OK: $PASS assertions green"
  exit 0
fi
echo "smoke-coupon FAILED: $PASS green, $FAIL failed"
exit 1
