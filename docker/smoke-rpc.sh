#!/usr/bin/env bash
# M3 acceptance seam (chain D): the SAME product flows asserted in rpc mode
# must behave exactly like feign mode. Restarts cart+product with
# AURORA_RPC_ENABLED=true, walks the cart golden path, then restores the
# default (feign) mode so the stack is left as it was.
# Prereq: stack up (see smoke-flows.sh), jars built (mvn package -DskipTests).
set -uo pipefail

BASE="${BASE:-http://localhost:8000}"
PASS=0
FAIL=0

req() { # method path token json-body
  local args=(-sS -X "$1" -H 'Content-Type: application/json')
  [[ -n "${3:-}" ]] && args+=(-H "Authorization: Bearer $3")
  [[ -n "${4:-}" ]] && args+=(--data "$4")
  RESP=$(curl "${args[@]}" "$BASE$2" 2>/dev/null) || RESP='{}'
}

ok()   { PASS=$((PASS + 1)); echo "  ok   $1"; }
bad()  { FAIL=$((FAIL + 1)); echo "  FAIL $1 (resp=${RESP:0:160})"; }
assert_body() { grep -qF "$1" <<<"$RESP" && ok "$2" || bad "$2 (missing '$1')"; }

start_service() { # name [extra-env]
  local name="$1"
  java -Xms128m -Xmx256m -jar "aurora-$name/target/aurora-$name-0.1.0-SNAPSHOT.jar" \
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

await_healthy() { # name attempts
  local name="$1" attempts="${2:-40}"
  for _ in $(seq 1 "$attempts"); do
    local code
    code=$(curl -s -o /dev/null -w '%{http_code}' "$BASE/api/$name/actuator/health" 2>/dev/null)
    [[ "$code" == "200" ]] && return 0
    sleep 3
  done
  return 1
}

STAMP=$(date +%s)
USERNAME="rpc$STAMP"

echo "[chain D] rpc-mode equivalence: cart -> product over the handwritten rpc"

# 0) seed: local-sql promotion to ADMIN (same as smoke-flows) + admin-created product
req POST /api/user/register "" "{\"username\":\"$USERNAME\",\"password\":\"secret123\",\"nickname\":\"rpc\"}"
grep -qF '"code":0' <<<"$RESP" && ok "seed user registered" || bad "seed user registered"
if ! docker exec aurora-mysql mysql -uroot -paurora123 -e   "UPDATE aurora_user.users SET role='ADMIN' WHERE username='$USERNAME';" 2>/dev/null; then
  bad "admin promotion (local seed)"; exit 1
fi
req POST /api/user/login "" "{\"username\":\"$USERNAME\",\"password\":\"secret123\"}"
TOKEN=$(grep -oE '"accessToken":"[^"]+"' <<<"$RESP" | head -1 | cut -d'"' -f4)
req POST /api/product/admin/products "$TOKEN" "{\"title\":\"RPC Probe $STAMP\",\"price\":12.50,\"stock\":20}"
assert_body '"code":0' "seed product created (admin)"
SKU=$(grep -oE '"data":[0-9]+' <<<"$RESP" | grep -oE '[0-9]+')
[[ -n "$SKU" ]] && ok "seed product id=$SKU" || { bad "seed product id"; exit 1; }

# 1) switch cart+product to rpc mode
echo "  ... restarting cart+product with AURORA_RPC_ENABLED=true"
restart_rpc_mode product
restart_rpc_mode cart
await_healthy product && await_healthy cart && ok "rpc-mode services healthy" || { bad "rpc-mode services healthy"; exit 1; }
sleep 3 # nacos subscription first snapshot

# 2) same golden path over the handwritten rpc
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

# 3) restore default (feign) mode so the stack is left as found
echo "  ... restoring feign mode"
restart_default_mode product
restart_default_mode cart
await_healthy cart && ok "feign mode restored" || bad "feign mode restored"

if [[ $FAIL -eq 0 ]]; then
  echo "smoke-rpc OK: $PASS assertions green (rpc mode equivalent to feign)"
  exit 0
fi
echo "smoke-rpc FAILED: $PASS green, $FAIL failed"
exit 1
