#!/usr/bin/env bash
# M3 验收缝（链 D）：同一商品链路在 RPC 模式下的断言必须与 Feign 模式完全一致。
# 以 AURORA_RPC_ENABLED=true 重启 cart+product，走一遍购物车金路径，
# 跑完自动还原默认（Feign）模式——栈保持原状。
# 前置：栈已起（见 smoke-flows.sh），jars 已构建（mvn package -DskipTests）。
set -uo pipefail

BASE="${BASE:-http://localhost:8000}"
# 仓库根 = 本脚本所在目录的上一级（脚本可能从任意 cwd 调用）
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
PASS=0
FAIL=0

# 早退兜底：任何路径离开都要把 cart+product 还原成 Feign 模式，不留 RPC 状态
RESTORED=0
restore_feign() {
  [[ "$RESTORED" == "1" ]] && return
  RESTORED=1
  restart_default_mode product
  restart_default_mode cart
}

req() { # method path token json-body
  local args=(-sS -X "$1" -H 'Content-Type: application/json')
  [[ -n "${3:-}" ]] && args+=(-H "Authorization: Bearer $3")
  [[ -n "${4:-}" ]] && args+=(--data "$4")
  RESP=$(curl "${args[@]}" "$BASE$2" 2>/dev/null) || RESP='{}'
}

ok()   { PASS=$((PASS + 1)); echo "  ok   $1"; }
bad()  { FAIL=$((FAIL + 1)); echo "  FAIL $1 (resp=${RESP:0:160})"; }
assert_body() { grep -qF "$1" <<<"$RESP" && ok "$2" || bad "$2 (missing '$1')"; }

start_service() { # name
  local name="$1"
  java -Xms128m -Xmx256m -jar "$ROOT/aurora-$name/target/aurora-$name-0.1.0-SNAPSHOT.jar" \
    > /tmp/"$name".log 2>&1 &
}

restart_rpc_mode() { # name
  local name="$1"
  local pid
  pid=$(powershell -NoProfile -Command "(Get-CimInstance Win32_Process -Filter \"Name='java.exe'\" | Where-Object { \$_.CommandLine -match 'aurora-$name-' }).ProcessId" 2>/dev/null | tr -d '\r')
  [[ -n "$pid" ]] && taskkill //F //PID "$pid" > /dev/null 2>&1
  AURORA_RPC_ENABLED=true start_service "$name"
}

restart_default_mode() { # name
  local name="$1"
  local pid
  pid=$(powershell -NoProfile -Command "(Get-CimInstance Win32_Process -Filter \"Name='java.exe'\" | Where-Object { \$_.CommandLine -match 'aurora-$name-' }).ProcessId" 2>/dev/null | tr -d '\r')
  [[ -n "$pid" ]] && taskkill //F //PID "$pid" > /dev/null 2>&1
  start_service "$name"
}

# 网关已封 /actuator/** 穿透（M4）：健康探测直连服务端口
await_healthy() { # name attempts
  local name="$1" attempts="${2:-40}"
  declare -A PORTS=( [gateway]=8000 [user]=8081 [product]=8082 [cart]=8083 [order]=8084 [inventory]=8085 [payment]=8086 )
  local port="${PORTS[$name]:-}"
  for _ in $(seq 1 "$attempts"); do
    local code
    code=$(curl -s -o /dev/null -w '%{http_code}' "http://localhost:${port}/actuator/health" 2>/dev/null)
    [[ "$code" == "200" ]] && return 0
    sleep 3
  done
  return 1
}

STAMP=$(date +%s)
USERNAME="rpc$STAMP"
MYSQL_PASSWORD="${MYSQL_PASSWORD:-aurora123}"

# 任何退出路径都还原 Feign 模式（早退/报错/中断都不留 RPC 状态）
trap restore_feign EXIT

echo "[chain D] rpc-mode equivalence: cart -> product over the handwritten rpc"

# 0) 准备：本地 SQL 提权为 ADMIN（与 smoke-flows 相同）+ 管理端创建商品
req POST /api/user/register "" "{\"username\":\"$USERNAME\",\"password\":\"secret123\",\"nickname\":\"rpc\"}"
grep -qF '"code":0' <<<"$RESP" && ok "seed user registered" || bad "seed user registered"
if ! docker exec aurora-mysql mysql -uroot -p"$MYSQL_PASSWORD" -e \
  "UPDATE aurora_user.users SET role='ADMIN' WHERE username='$USERNAME';" 2>/dev/null; then
  bad "admin promotion (local seed)"; exit 1
fi
req POST /api/user/login "" "{\"username\":\"$USERNAME\",\"password\":\"secret123\"}"
TOKEN=$(grep -oE '"accessToken":"[^"]+"' <<<"$RESP" | head -1 | cut -d'"' -f4)
req POST /api/product/admin/products "$TOKEN" "{\"title\":\"RPC Probe $STAMP\",\"price\":12.50,\"stock\":20}"
assert_body '"code":0' "seed product created (admin)"
SKU=$(grep -oE '"data":[0-9]+' <<<"$RESP" | grep -oE '[0-9]+')
[[ -n "$SKU" ]] && ok "seed product id=$SKU" || { bad "seed product id"; exit 1; }

# 1) 把 cart+product 切到 rpc 模式
echo "  ... 以 AURORA_RPC_ENABLED=true 重启 cart+product"
restart_rpc_mode product
restart_rpc_mode cart
await_healthy product && await_healthy cart && ok "rpc-mode services healthy" || { bad "rpc-mode services healthy"; exit 1; }
sleep 3 # nacos subscription first snapshot

# 2) 用同一条黄金路径走手写 rpc
req POST /api/cart/carts/items "$TOKEN" "{\"skuId\":$SKU,\"quantity\":2}"
assert_body '"code":0' "rpc mode: add to cart"
req GET /api/cart/carts "$TOKEN"
assert_body '"title":"RPC Probe '"$STAMP"'"' "rpc mode: title snapshot joined"
assert_body '"price":12.5' "rpc mode: price snapshot joined"
assert_body '"quantity":2' "rpc mode: quantity persisted"
req POST /api/cart/carts/items "$TOKEN" "{\"skuId\":999999999,\"quantity\":1}"
assert_body '"code":10001' "rpc mode: unknown sku -> 10001 (null contract)"
req GET /api/cart/carts "$TOKEN"
grep -qF '"skuId":999999999' <<<"$RESP" && bad "unknown sku not written" || ok "unknown sku not written"

# 3) 恢复默认（feign）模式，让环境保持原样
echo "  ... 还原 Feign 模式"
restore_feign
await_healthy product && await_healthy cart && ok "feign mode restored" || bad "feign mode restored"

if [[ $FAIL -eq 0 ]]; then
  echo "smoke-rpc OK: $PASS assertions green (rpc mode equivalent to feign)"
  exit 0
fi
echo "smoke-rpc FAILED: $PASS green, $FAIL failed"
exit 1
