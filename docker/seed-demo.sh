#!/usr/bin/env bash
# 演示数据：把前端铺成一个像样的店铺，便于截图、演示，或 clone 后直接看效果。
#
#   bash docker/seed-demo.sh            # 铺演示数据（幂等：已存在则跳过）
#   bash docker/seed-demo.sh --reset    # 先清掉冒烟脚本生成的测试数据，再铺
#
# 前置：中间件栈 + 8 个服务已启动（见 README 快速开始）。
# 生成的数据：
#   - 演示账号 admin / admin12345（已提权 ADMIN）
#   - 16 个商品（数码/家居，足以铺满商品列表一页）
#   - 3 张优惠券模板、1 个秒杀活动（已预热）
#   - admin 名下的 3 笔已支付订单（订单列表与详情有内容）
#
# ⚠️ Windows/Git Bash 注意：这个脚本所有带中文的请求体都走「写临时文件 +
#     curl --data-binary @文件」，SQL 走 stdin。原因是 MSYS 会把非 ASCII 的
#     命令行参数按本地代码页转换，中文经 argv 传给原生 curl/mysql 会变乱码，
#     表现为服务端报「请求体格式错误（10001）」。别把它们改回 -d "…" 内联。
set -euo pipefail

GATEWAY="${GATEWAY:-http://localhost:8000}"
MYSQL_CTN="${MYSQL_CTN:-aurora-mysql}"
MYSQL_PW="${MYSQL_PW:-aurora123}"
NACOS="${NACOS:-http://localhost:8848}"

DEMO_USER="admin"
DEMO_PASS="admin12345"
DEMO_NICK="演示账号"

RESET=0
[[ "${1:-}" == "--reset" ]] && RESET=1

TMPD=$(mktemp -d)
trap 'rm -rf "$TMPD"' EXIT

ok()  { printf '  ok   %s\n' "$*"; }
step(){ printf '\n[%s]\n' "$*"; }
die() { printf 'FAIL %s\n' "$*" >&2; exit 1; }

# SQL 走 stdin，中文不进 argv
mysql_q() {
  docker exec -i "$MYSQL_CTN" mysql -uroot -p"$MYSQL_PW" --default-character-set=utf8mb4 -N \
    <<<"$1" 2>/dev/null
}

# JSON 请求体走 stdin→临时文件→--data-binary，中文不进 argv
# 用法：send_json <METHOD> <path> <token|""> [额外的 curl 参数...]，body 从 stdin 读
send_json() {
  local method="$1" path="$2" token="$3" f; shift 3
  f=$(mktemp "$TMPD/body.XXXXXX")
  cat >"$f"
  if [[ -n "$token" ]]; then
    curl -s -X "$method" "$GATEWAY$path" -H "Authorization: Bearer $token" \
      -H 'Content-Type: application/json' --data-binary "@$f" "$@"
  else
    curl -s -X "$method" "$GATEWAY$path" -H 'Content-Type: application/json' --data-binary "@$f" "$@"
  fi
}

post_json() { send_json POST "$@"; }
put_json()  { send_json PUT "$@"; }

# 提取 "data":<数字>；无匹配时返回空串而不是非零退出码
# （脚本开了 set -e + pipefail，grep 找不到会让 `x=$(… | jnum)` 直接终止脚本）
jnum() { grep -oE '"data":[0-9]+' | head -1 | cut -d: -f2 || true; }

# ── 0. 前置检查 ────────────────────────────────────────────
step "0 前置检查"
curl -sf "$GATEWAY/actuator/health" >/dev/null || die "网关未就绪：$GATEWAY（先跑 docker compose up -d 与 8 个服务）"
ok "网关可达 $GATEWAY"

# ── 1. 可选的测试数据清理 ──────────────────────────────────
# 冒烟脚本每跑一次都会造一批以固定前缀命名的数据；不清掉的话商品列表会
# 混着 "Flow Product 1791862449" 这类压测残留，演示效果很差。
if [[ $RESET -eq 1 ]]; then
  step "1 清理冒烟脚本生成的测试数据"
  P='title LIKE "Flow Product %" OR title LIKE "Audit Product %" OR title LIKE "AuditPay %"'
  P="$P OR title LIKE \"coupon-sku-%\" OR title LIKE \"RPC Probe %\""
  P="$P OR title LIKE \"perf probe %\" OR title LIKE \"smoke%\" OR title = \"x\""
  SKUS=$(mysql_q "SELECT GROUP_CONCAT(id) FROM aurora_product.sku WHERE $P;")
  if [[ -n "$SKUS" && "$SKUS" != "NULL" ]]; then
    mysql_q "DELETE FROM aurora_inventory.product_stock WHERE sku_id IN ($SKUS);" >/dev/null
    mysql_q "DELETE FROM aurora_product.sku WHERE id IN ($SKUS);" >/dev/null
    ok "商品与库存：已清 $(tr ',' '\n' <<<"$SKUS" | wc -l | tr -d ' ') 个测试 SKU"
  else
    ok "商品：无测试残留"
  fi
  # 券：冒烟脚本造的券名以 smoke-<门槛>-<优惠>-<时间戳> 命名，别漏掉 smoke%
  # 多表 DELETE 必须先选中库，否则报 1046 No database selected
  mysql_q 'USE aurora_order;
           DELETE uc FROM user_coupon uc
           JOIN coupon_template t ON uc.template_id = t.id
           WHERE t.title LIKE "rxcoupon-%" OR t.title LIKE "audit-%" OR t.title LIKE "bound-%"
              OR t.title LIKE "race%" OR t.title LIKE "future-%" OR t.title LIKE "expired-fixture-%"
              OR t.title LIKE "smoke%" OR t.title = "future-refute-check";' >/dev/null
  mysql_q 'DELETE FROM aurora_order.coupon_template
           WHERE title LIKE "rxcoupon-%" OR title LIKE "audit-%" OR title LIKE "bound-%"
              OR title LIKE "race%" OR title LIKE "future-%" OR title LIKE "expired-fixture-%"
              OR title LIKE "smoke%" OR title = "future-refute-check";' >/dev/null
  ok "券模板：已清测试残留"
  mysql_q 'DELETE FROM aurora_seckill.seckill_activity
           WHERE title LIKE "impact%" OR title LIKE "audit-%" OR title LIKE "repro-%"
              OR title LIKE "calib-%" OR title LIKE "stress%" OR title LIKE "s%";' >/dev/null
  ok "秒杀活动：已清测试残留"
fi

# ── 2. 演示账号 ────────────────────────────────────────────
step "2 演示账号"
post_json /api/user/register "" <<EOF >/dev/null
{"username":"$DEMO_USER","password":"$DEMO_PASS","nickname":"$DEMO_NICK"}
EOF
# 注册出来的账号一律普通用户；运营台需要 ADMIN，这里直接改库提权。
mysql_q "UPDATE aurora_user.users SET role='ADMIN' WHERE username='$DEMO_USER';" >/dev/null
ok "账号 $DEMO_USER 就绪并已提权 ADMIN"

TOKEN=$(post_json /api/user/login "" <<EOF | grep -oE '"accessToken":"[^"]+' | cut -d'"' -f4 || true
{"username":"$DEMO_USER","password":"$DEMO_PASS"}
EOF
)
[[ -n "$TOKEN" ]] || die "登录失败，拿不到 accessToken"
ok "登录成功"

# ── 3. 商品 ────────────────────────────────────────────────
step "3 商品"
# 名称刻意选真实商品：截图和演示时一眼能看出是个真店铺，而不是测试数据。
CATALOG=(
  "iPhone 15 Pro 钛金属原色 256G|8999.00|120"
  "小米 14 Ultra 16G+512G|6499.00|80"
  "大疆 Osmo Pocket 3 口袋云台相机|3499.00|60"
  "索尼 WH-1000XM5 头戴式降噪耳机|2399.00|150"
  "iPad Air 11 英寸 M2 128G|4799.00|90"
  "戴森 V12 Detect Slim 无绳吸尘器|3699.00|70"
  "飞利浦 3200 系列全自动咖啡机|2999.00|55"
  "雷蛇 黑寡妇蜘蛛 V4 机械键盘|899.00|200"
  "罗技 MX Master 3S 无线鼠标|699.00|240"
  "宜家 MARKUS 人体工学椅|999.00|110"
  "安克 65W 氮化镓充电器|129.00|500"
  "三顿半 超即溶咖啡 24 颗装|199.00|300"
  "米家空气净化器 4 Pro|1499.00|85"
  "漫步者 N300 有源音箱|769.00|130"
  "绿联 雷电 4 扩展坞 11 合 1|1299.00|95"
  "倍思 20000mAh 自带线充电宝|229.00|260"
)
created=0; skipped=0
for row in "${CATALOG[@]}"; do
  IFS='|' read -r title price stock <<<"$row"
  exists=$(mysql_q "SELECT COUNT(*) FROM aurora_product.sku WHERE title='$title';")
  if [[ "$exists" != "0" ]]; then skipped=$((skipped+1)); continue; fi
  post_json /api/product/admin/products "$TOKEN" <<EOF >/dev/null
{"title":"$title","price":$price,"stock":$stock}
EOF
  created=$((created+1))
done
ok "新建 $created 个，已存在跳过 $skipped 个"

# 库存表是下单时可售库存的唯一入口，商品建完要把库存开出来。
# 注意接口是 PUT 且字段名是 quantity（不是 stock），写错会静默失败、下单时报
# “库存服务不可用”。
for row in "${CATALOG[@]}"; do
  IFS='|' read -r title price stock <<<"$row"
  sku=$(mysql_q "SELECT id FROM aurora_product.sku WHERE title='$title' LIMIT 1;")
  [[ -z "$sku" ]] && continue
  resp=$(put_json "/api/inventory/admin/stocks/$sku" "$TOKEN" <<EOF
{"quantity":$stock}
EOF
)
  [[ "$resp" == *'"code":0'* ]] || die "开库存失败 sku=$sku: $resp"
done
ok "可售库存已开通"

# ── 4. 优惠券模板 ──────────────────────────────────────────
step "4 优惠券模板"
NOW=$(date -u +%Y-%m-%dT%H:%M:%S)
END=$(date -u -d '+30 days' +%Y-%m-%dT%H:%M:%S)
COUPONS=(
  "新人满 100 减 10|100.00|10.00|1000|7"
  "满 500 减 50|500.00|50.00|500|15"
  "满 2000 减 300|2000.00|300.00|200|30"
)
for row in "${COUPONS[@]}"; do
  IFS='|' read -r title th dis total days <<<"$row"
  exists=$(mysql_q "SELECT COUNT(*) FROM aurora_order.coupon_template WHERE title='$title';")
  [[ "$exists" != "0" ]] && { ok "已存在：$title"; continue; }
  post_json /api/order/admin/coupons "$TOKEN" <<EOF >/dev/null
{"title":"$title","thresholdAmount":$th,"discountAmount":$dis,"total":$total,"claimStartAt":"$NOW","claimEndAt":"$END","validDays":$days}
EOF
  ok "新建：$title"
done

# ── 5. 秒杀活动 ────────────────────────────────────────────
step "5 秒杀活动"
SKU_TITLE="iPhone 15 Pro 钛金属原色 256G"
SKU_ID=$(mysql_q "SELECT id FROM aurora_product.sku WHERE title='$SKU_TITLE' LIMIT 1;")
ACT_TITLE="iPhone 15 Pro 限时秒杀"
ACT_ID=$(mysql_q "SELECT id FROM aurora_seckill.seckill_activity WHERE title='$ACT_TITLE' LIMIT 1;")
if [[ -z "$ACT_ID" ]]; then
  S=$(date -u -d '-1 hour' +%Y-%m-%dT%H:%M:%S)
  E=$(date -u -d '+30 days' +%Y-%m-%dT%H:%M:%S)
  ACT_ID=$(post_json /api/seckill/admin/activities "$TOKEN" <<EOF | jnum || true
{"title":"$ACT_TITLE","skuId":$SKU_ID,"seckillPrice":6999.00,"totalStock":200,"perUserLimit":1,"startAt":"$S","endAt":"$E"}
EOF
)
  ok "新建活动 id=$ACT_ID"
else
  ok "已存在活动 id=$ACT_ID"
fi
[[ -n "$ACT_ID" ]] && curl -s -X POST "$GATEWAY/api/seckill/admin/activities/$ACT_ID/preheat" \
  -H "Authorization: Bearer $TOKEN" >/dev/null && ok "已预热（库存刷进 Redis）"

# ── 6. 历史订单 ────────────────────────────────────────────
step "6 历史订单（订单列表/详情有内容可看）"
CHANNEL_SECRET=$(curl -s "$NACOS/nacos/v1/cs/configs?dataId=aurora-payment.yml&group=DEFAULT_GROUP&tenant=dev" \
  | grep -oE 'channel-secret: [A-Za-z0-9-]+' | cut -d' ' -f2 || true)

buy() { # skuTitle quantity -> 下单并支付
  local t="$1" q="$2" sid oid amt sig resp
  sid=$(mysql_q "SELECT id FROM aurora_product.sku WHERE title='$t' LIMIT 1;")
  [[ -z "$sid" ]] && { echo "  skip 找不到商品：$t" >&2; return 0; }
  # 幂等键按「商品+数量」生成，脚本重复跑不会重复下单
  resp=$(post_json /api/order/orders "$TOKEN" -H "Idempotency-Key: demo-$sid-$q" <<EOF
{"skuId":$sid,"quantity":$q}
EOF
)
  oid=$(jnum <<<"$resp")
  if [[ -z "$oid" ]]; then
    # 重复跑时幂等键已用过会返回 40900，这属于预期，不当失败
    if [[ "$resp" == *40900* ]]; then echo "  ok   订单已存在（幂等键命中）：$t"; return 0; fi
    die "下单失败：$t -> $resp"
  fi
  amt=$(post_json /api/payment/payments "$TOKEN" <<EOF | grep -oE '"amount":[0-9]+(\.[0-9]+)?' | head -1 | cut -d: -f2 || true
{"orderId":$oid}
EOF
)
  [[ -z "$amt" ]] && die "发起支付失败：订单 #$oid"
  # 回调要带渠道签名：HMAC-SHA256("orderId:amount", channel-secret)，服务端常量时间校验
  sig=$(printf '%s:%s' "$oid" "$amt" | openssl dgst -sha256 -hmac "$CHANNEL_SECRET" | awk '{print $NF}')
  resp=$(curl -s -X POST "$GATEWAY/api/payment/payments/mock-callback" -H 'Content-Type: application/json' \
    -H "X-Channel-Signature: $sig" -d "{\"orderId\":$oid,\"amount\":$amt}")
  [[ "$resp" == *'"code":0'* ]] || die "支付回调失败：订单 #$oid -> $resp"
  ok "订单 #$oid 已支付（¥$amt）"
}

buy "安克 65W 氮化镓充电器" 2
buy "三顿半 超即溶咖啡 24 颗装" 1
buy "罗技 MX Master 3S 无线鼠标" 1

# ── 完成 ───────────────────────────────────────────────────
step "完成"
cat <<EOF
  演示账号    $DEMO_USER / $DEMO_PASS（ADMIN）
  前端        http://localhost:5173
  运营台      登录后右上角切到「运营台」

  提示：$DEMO_USER 名下已有订单，可直接看订单列表与详情。
EOF
