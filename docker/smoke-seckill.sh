#!/usr/bin/env bash
# M5 S3 验收缝：秒杀主链路（Lua 原子预扣 → 落单 → 结果查询）的并发正确性。
# 断言的是与落单模式无关的不变量：
#   抢到名额的数量 == 限量 / 其余全部以「已售罄」被拒 / 一人一单 /
#   Redis 余量与 DB available 同时收敛到 0 / 订单数与限量一致 / 每个赢家最终都有确定结果。
#
# 前置条件：
#   1) docker compose up -d && bash nacos/import.sh
#   2) 网关(:8000)、user(:8081)、seckill(:8087) 已启动
#   3) 秒杀库已建表（幂等，可重复执行）：
#      docker exec -i aurora-mysql mysql -uroot -p${MYSQL_PASSWORD:-aurora123} < docker/mysql/init/07-aurora_seckill.sql
#
# 身份说明：并发买家需要 N 个互不相同的身份，逐个注册成本过高，因此抢购直连 :8087
# 并带上网关注入的等价头部（X-User-Id / X-Internal-Secret）；网关路径另有一条
# 真实用户用例覆盖（注册 → 登录 → 经 :8000 抢购 → 轮询落单）。
#
# 落单模式对照（人工两步，脚本会打印各自的时延分位与返回分布，可直接比）：
#   AURORA_SECKILL_ORDER_MODE=mq   <启动 seckill> && bash docker/smoke-seckill.sh
#   AURORA_SECKILL_ORDER_MODE=sync <重启 seckill> && bash docker/smoke-seckill.sh
set -uo pipefail

cd "$(dirname "$0")"

BASE="${BASE:-http://localhost:8000}"
SECKILL="${SECKILL:-http://localhost:8087}"
NACOS_ADDR="${NACOS_ADDR:-localhost:8848}"
MYSQL_PASSWORD="${MYSQL_PASSWORD:-aurora123}"
# 限量刻意小于买家数：这样「超额全部被拒」与「成功数正好等于限量」才可同时断言
STOCK="${STOCK:-5}"
BUYERS="${BUYERS:-40}"
STAMP=$(date +%s)

PASS=0
FAIL=0
STEP=0
ok()   { PASS=$((PASS + 1)); echo "  ok   $1"; }
bad()  { FAIL=$((FAIL + 1)); echo "  FAIL $1"; }
step() { STEP=$((STEP + 1)); echo; echo "[$STEP] $1"; }
assert_eq() { # 期望值 实际值 标签
  [[ "$1" == "$2" ]] && ok "$3" || bad "$3 (want '$1', got '$2')"
}

db_scalar() { # sql -> 首个单元格
  docker exec aurora-mysql mysql -uroot -p"$MYSQL_PASSWORD" -N -e "$1" 2>/dev/null | tr -d '\r'
}
redis_hget() { # key field
  docker exec aurora-redis redis-cli HGET "$1" "$2" | tr -d '\r'
}

# 内部密钥：以配置中心已发布的值为准（与运行中的服务同源）；nacos 不可达时退回本地密钥文件
internal_secret() {
  local value
  value=$(curl -s "http://$NACOS_ADDR/nacos/v1/cs/configs?dataId=aurora-common.yml&group=DEFAULT_GROUP&tenant=dev" \
    | awk '/^[[:space:]]*internal:/{f=1;next} f&&/secret:/{print $2; exit}' | tr -d '"')
  if [[ -z "$value" ]]; then
    value=$(grep -E '^AURORA_INTERNAL_SECRET=' nacos/.secrets.env 2>/dev/null | cut -d= -f2)
  fi
  echo "$value"
}

step "preflight：内部密钥与 seckill 健康检查"
SECRET=$(internal_secret)
if [[ -z "$SECRET" ]]; then
  bad "取不到内部密钥：nacos 不可达且 nacos/.secrets.env 不存在（先执行 bash nacos/import.sh）"
  exit 1
fi
ok "内部密钥已取到（${#SECRET} 字符）"

HEALTH=000
for _ in $(seq 1 30); do
  # curl 连不上时 -w 依然打印 000；不要额外 echo，否则会拼成 000000
  HEALTH=$(curl -s -o /dev/null -w '%{http_code}' "$SECKILL/actuator/health" 2>/dev/null || true)
  [[ "$HEALTH" == "200" ]] && break
  sleep 1
done
if [[ "$HEALTH" != "200" ]]; then
  bad "seckill 不可达（$SECKILL/actuator/health -> HTTP $HEALTH）"
  exit 1
fi
ok "seckill 健康检查 200"

ADMIN=(-H "X-User-Role: ADMIN" -H "X-Internal-Secret: $SECRET")
START=$(date -d '1 minute ago' +%Y-%m-%dT%H:%M:%S)
END=$(date -d '30 minutes' +%Y-%m-%dT%H:%M:%S)

step "运营建活动（限量 $STOCK）并预热"
CREATE=$(curl -s -XPOST "$SECKILL/admin/activities" -H 'Content-Type: application/json' "${ADMIN[@]}" \
  -d "{\"title\":\"smoke-$STAMP\",\"skuId\":1,\"seckillPrice\":9.90,\"totalStock\":$STOCK,\"perUserLimit\":1,\"startAt\":\"$START\",\"endAt\":\"$END\"}")
AID=$(grep -oE '"data":[0-9]+' <<<"$CREATE" | head -1 | cut -d: -f2)
if [[ -z "$AID" ]]; then
  bad "创建活动失败：${CREATE:0:200}"
  exit 1
fi
ok "活动 id=$AID"

PREHEAT=$(curl -s -XPOST "$SECKILL/admin/activities/$AID/preheat" "${ADMIN[@]}")
if ! grep -qF '"code":0' <<<"$PREHEAT"; then
  bad "预热失败：${PREHEAT:0:200}"
  exit 1
fi
ok "预热完成"
assert_eq "$STOCK" "$(redis_hget "seckill:activity:$AID" stock)" "Redis 快照余量 = $STOCK"

step "$BUYERS 个买家并发抢 $STOCK 个名额"
TMP=$(mktemp -d)
trap 'rm -rf "$TMP"' EXIT
RACE_START=$(date +%s%3N)
for i in $(seq 1 "$BUYERS"); do
  # \n 是 curl -w 的转义：不加它 40 个数值会被拼成一行，分位数统计就只剩 1 条记录
  curl -s -o "$TMP/body.$i" -w '%{time_total}\n' -XPOST "$SECKILL/activities/$AID/orders" \
    -H "X-User-Id: $i" -H "X-Internal-Secret: $SECRET" > "$TMP/time.$i" &
done
wait
RACE_MS=$(( $(date +%s%3N) - RACE_START ))

QUEUED=0
PLACED=0
SOLD_OUT=0
OTHER=0
WINNERS=()
for i in $(seq 1 "$BUYERS"); do
  BODY=$(cat "$TMP/body.$i")
  if grep -qF '"status":"QUEUED"' <<<"$BODY"; then
    QUEUED=$((QUEUED + 1)); WINNERS+=("$i")
  elif grep -qF '"status":"PLACED"' <<<"$BODY"; then
    PLACED=$((PLACED + 1)); WINNERS+=("$i")
  elif grep -qF '"code":30003' <<<"$BODY"; then
    SOLD_OUT=$((SOLD_OUT + 1))
  else
    OTHER=$((OTHER + 1))
    [[ $OTHER -le 3 ]] && echo "      意外响应：user $i -> ${BODY:0:140}"
  fi
done

if [[ "$PLACED" -eq "$STOCK" ]]; then
  echo "  落单模式：sync（POST 直接返回 PLACED）"
else
  echo "  落单模式：mq（POST 返回 QUEUED，消费者异步落单）"
fi
echo "  返回分布：QUEUED=$QUEUED PLACED=$PLACED SOLD_OUT=$SOLD_OUT OTHER=$OTHER"
assert_eq "$STOCK" "$((QUEUED + PLACED))" "抢到名额的数量正好等于限量"
assert_eq "$((BUYERS - STOCK))" "$SOLD_OUT" "其余 $((BUYERS - STOCK)) 个全部以「已售罄 30003」被拒"
assert_eq "0" "$OTHER" "没有出现意外错误"
assert_eq "$STOCK" "${#WINNERS[@]}" "赢家数与限量一致"

cat "$TMP"/time.* | sort -n | awk -v wall="$RACE_MS" -v buyers="$BUYERS" '
  {a[NR]=$1}
  END {p50 = int(NR*0.5) < 1 ? 1 : int(NR*0.5); p95 = int(NR*0.95) < 1 ? 1 : int(NR*0.95);
       printf "  buy POST 时延：p50=%.0fms p95=%.0fms max=%.0fms（n=%d）\n",
              a[p50]*1000, a[p95]*1000, a[NR]*1000, NR;
       printf "  整轮墙钟：%dms（%d 个并发，含 curl/进程开销，分母不是服务自身耗时）\n", wall, buyers}'

# 两种落单模式的可见差异：POST 返回的这一刻，DB 里已经有多少订单、还有多少请求悬在 PENDING。
# mq 模式：落单在消费者侧异步发生，这两个数字反映"削峰"（DB 写入不在请求线程里）；
# sync 模式：POST 返回时订单必然已经在 DB 里，PENDING 必为 0。
ORDERS_AT_RETURN=$(db_scalar "SELECT COUNT(*) FROM aurora_seckill.seckill_order WHERE activity_id=$AID")
PENDING_AT_RETURN=$(docker exec aurora-redis redis-cli --raw HVALS "seckill:result:$AID" 2>/dev/null | grep -c '^PENDING$' || true)
echo "  POST 返回瞬间：DB 订单数=$ORDERS_AT_RETURN，仍 PENDING=$PENDING_AT_RETURN"

step "一人一单：赢家再次抢购被拒"
DUP_USER="${WINNERS[0]:-1}"
DUP=$(curl -s -XPOST "$SECKILL/activities/$AID/orders" -H "X-User-Id: $DUP_USER" -H "X-Internal-Secret: $SECRET")
if grep -qF '"code":30004' <<<"$DUP"; then
  ok "用户 $DUP_USER 二次抢购 -> 已参与过（30004）"
else
  bad "二次抢购未被拒：${DUP:0:160}"
fi

step "结果收敛：每个赢家都拿到确定状态（mq 模式下这一步证明消费者确实落单了）"
SETTLED=0
ORDER_IDS=""
for id in "${WINNERS[@]}"; do
  MINE=""
  for _ in $(seq 1 60); do
    MINE=$(curl -s "$SECKILL/activities/$AID/orders/mine" -H "X-User-Id: $id" -H "X-Internal-Secret: $SECRET")
    grep -qF '"status":"PLACED"' <<<"$MINE" && break
    sleep 0.25
  done
  if grep -qF '"status":"PLACED"' <<<"$MINE"; then
    SETTLED=$((SETTLED + 1))
    ORDER_IDS="$ORDER_IDS $(grep -oE '"orderId":[0-9]+' <<<"$MINE" | head -1 | cut -d: -f2)"
  else
    bad "赢家 $id 未落定：${MINE:0:140}"
  fi
done
assert_eq "$STOCK" "$SETTLED" "全部赢家最终 PLACED"
DISTINCT_ORDERS=$(tr ' ' '\n' <<<"$ORDER_IDS" | sed '/^$/d' | sort -u | wc -l | tr -d ' ')
assert_eq "$STOCK" "$DISTINCT_ORDERS" "每个赢家拿到互不相同的订单号"

step "一致性：Redis 余量 / DB available / 订单数收敛到同一事实"
assert_eq "0" "$(redis_hget "seckill:activity:$AID" stock)" "Redis 余量归零（预扣用尽）"
assert_eq "0" "$(db_scalar "SELECT available FROM aurora_seckill.seckill_stock WHERE activity_id=$AID")" "DB available 归零（落单扣减到位）"
assert_eq "$STOCK" "$(db_scalar "SELECT COUNT(*) FROM aurora_seckill.seckill_order WHERE activity_id=$AID")" "DB 订单数 == 限量（无超卖）"
assert_eq "$STOCK" "$(db_scalar "SELECT COUNT(DISTINCT user_id) FROM aurora_seckill.seckill_order WHERE activity_id=$AID")" "订单归属互不相同（一人一单）"
assert_eq "0" "$(db_scalar "SELECT COUNT(*) FROM aurora_seckill.seckill_order WHERE activity_id=$AID AND status<>'CREATED'")" "订单状态都是 CREATED"

step "网关路径：真实用户经 :8000 抢第二个活动（限量 1）"
BID=$(curl -s -XPOST "$SECKILL/admin/activities" -H 'Content-Type: application/json' "${ADMIN[@]}" \
  -d "{\"title\":\"smoke-gw-$STAMP\",\"skuId\":1,\"seckillPrice\":19.90,\"totalStock\":1,\"perUserLimit\":1,\"startAt\":\"$START\",\"endAt\":\"$END\"}" \
  | grep -oE '"data":[0-9]+' | head -1 | cut -d: -f2)
if [[ -z "$BID" ]]; then
  bad "第二个活动创建失败"
else
  curl -s -XPOST "$SECKILL/admin/activities/$BID/preheat" "${ADMIN[@]}" > /dev/null
  ok "第二个活动 id=$BID 已预热"
fi

if [[ -n "$BID" ]]; then
  UNAME="seckill$STAMP"
  REG=$(curl -s -XPOST "$BASE/api/user/register" -H 'Content-Type: application/json' \
    -d "{\"username\":\"$UNAME\",\"password\":\"secret123\",\"nickname\":\"seckill\"}")
  grep -qF '"code":0' <<<"$REG" && ok "注册用户 $UNAME" || bad "注册失败：${REG:0:160}"

  LOGIN=$(curl -s -XPOST "$BASE/api/user/login" -H 'Content-Type: application/json' \
    -d "{\"username\":\"$UNAME\",\"password\":\"secret123\"}")
  TOKEN=$(grep -oE '"accessToken":"[^"]+"' <<<"$LOGIN" | head -1 | cut -d'"' -f4)
  [[ -n "$TOKEN" ]] && ok "登录拿到 token" || bad "登录失败：${LOGIN:0:160}"

  if [[ -n "$TOKEN" ]]; then
    BUY=$(curl -s -XPOST "$BASE/api/seckill/activities/$BID/orders" -H "Authorization: Bearer $TOKEN")
    grep -qE '"status":"(QUEUED|PLACED)"' <<<"$BUY" && ok "经网关抢购受理：${BUY:0:120}" || bad "经网关抢购失败：${BUY:0:160}"

    MINE=""
    for _ in $(seq 1 60); do
      MINE=$(curl -s "$BASE/api/seckill/activities/$BID/orders/mine" -H "Authorization: Bearer $TOKEN")
      grep -qF '"status":"PLACED"' <<<"$MINE" && break
      sleep 0.25
    done
    grep -qF '"status":"PLACED"' <<<"$MINE" && ok "网关路径最终落单（orderId 就绪）" || bad "网关路径未落单：${MINE:0:160}"
  fi
fi

echo
if [[ $FAIL -eq 0 ]]; then
  echo "smoke-seckill OK: $PASS assertions green"
  exit 0
fi
echo "smoke-seckill FAILED: $PASS green, $FAIL failed"
exit 1
