#!/usr/bin/env bash
# M1 acceptance seam: one command walks the whole golden path through the
# gateway and asserts every response. Prereq: `docker compose up -d`,
# `nacos/import.sh`, and all 7 services running (see README runbook).
set -uo pipefail

BASE="${BASE:-http://localhost:8000}"
PASS=0
FAIL=0
STEP=0

req() { # method path [token] [json-body] -> body in RESP, http code in STATUS
  local method="$1" path="$2" token="${3:-}" body="${4:-}"
  local args=(-sS -X "$method" -o- -w $'\n%{http_code}' -H 'Content-Type: application/json')
  [[ -n "$token" ]] && args+=(-H "Authorization: Bearer $token")
  [[ -n "$body" ]] && args+=(--data "$body")
  local out
  out=$(curl "${args[@]}" "$BASE$path" 2>/dev/null) || out=$'\n000'
  STATUS="${out##*$'\n'}"
  RESP="${out%$'\n'*}"
}

ok()   { PASS=$((PASS + 1)); echo "  ok   $1"; }
bad()  { FAIL=$((FAIL + 1)); echo "  FAIL $1 (status=$STATUS resp=${RESP:0:160})"; }

step() { STEP=$((STEP + 1)); echo "[$STEP] $1"; }

assert_status() { # expected, [label]
  local label="${2:-http $1}"
  [[ "$STATUS" == "$1" ]] && ok "$label" || bad "$label (want HTTP $1)"
}
assert_body() { # fixed-string to grep, label
  grep -qF "$1" <<<"$RESP" && ok "$2" || bad "$2 (missing '$1')"
}

STAMP=$(date +%s)
USERNAME="flow$STAMP"
PASSWORD="secret123"
TOKEN=""
REFRESH=""

step "register fresh user $USERNAME"
req POST /api/user/register "" "{\"username\":\"$USERNAME\",\"password\":\"$PASSWORD\",\"nickname\":\"flow\"}"
assert_status 200 "register http 200"
assert_body '"code":0' "register envelope ok"
if [[ "$FAIL" -gt 0 ]]; then echo "aborted: cannot continue without a user"; exit 1; fi

step "promote to ADMIN (local seed, same machine only)"
if PROMOTE_OUT=$(docker exec aurora-mysql mysql -uroot -p"${MYSQL_PASSWORD:-aurora123}" -e \
  "UPDATE aurora_user.users SET role='ADMIN' WHERE username='$USERNAME';" 2>&1); then
  ok "promotion executed"
else
  bad "promotion failed: ${PROMOTE_OUT:0:120}"
  exit 1
fi

step "login (fresh token carries ADMIN role)"
req POST /api/user/login "" "{\"username\":\"$USERNAME\",\"password\":\"$PASSWORD\"}"
assert_status 200 "login http 200"
TOKEN=$(grep -oE '"accessToken":"[^"]+"' <<<"$RESP" | head -1 | cut -d'"' -f4)
REFRESH=$(grep -oE '"refreshToken":"[^"]+"' <<<"$RESP" | head -1 | cut -d'"' -f4)
[[ -n "$TOKEN" && -n "$REFRESH" ]] && ok "both tokens issued" || bad "tokens missing"
assert_body '"expiresIn":1800' "access ttl 1800s from config center"
if [[ -z "$TOKEN" ]]; then echo "aborted: no token"; exit 1; fi

step "admin creates a product"
TITLE="Flow Product $STAMP"
req POST /api/product/admin/products "$TOKEN" "{\"title\":\"$TITLE\",\"price\":19.90,\"stock\":50}"
assert_status 200 "create http 200"
assert_body '"code":0' "create envelope ok"
SKU=$(grep -oE '"data":[0-9]+' <<<"$RESP" | head -1 | cut -d: -f2)
[[ -n "$SKU" ]] && ok "sku id=$SKU" || { bad "sku id missing"; echo "aborted"; exit 1; }

step "guest browses detail (no token, public route)"
req GET "/api/product/products/$SKU"
assert_status 200 "guest detail http 200"
assert_body "\"title\":\"$TITLE\"" "snapshot title matches"
assert_body '"price":19.90' "price matches"

step "guest lists products"
req GET "/api/product/products"
assert_status 200 "guest list http 200"
assert_body '"code":0' "list envelope ok"

step "add to cart: qty 2, then repeat qty 3 (accumulate)"
req POST /api/cart/carts/items "$TOKEN" "{\"skuId\":$SKU,\"quantity\":2}"
assert_status 200 && assert_body '"code":0' "add x2 ok"
req POST /api/cart/carts/items "$TOKEN" "{\"skuId\":$SKU,\"quantity\":3}"
assert_status 200 && assert_body '"code":0' "add x3 ok"

step "view cart: quantity accumulated, product snapshot joined via feign"
req GET /api/cart/carts "$TOKEN"
assert_status 200 "view http 200"
assert_body "\"quantity\":5" "quantity is 5 (2+3)"
assert_body "\"title\":\"$TITLE\"" "title snapshot joined"
assert_body "\"price\":19.90" "price snapshot joined"

step "set quantity to 7"
req PUT /api/cart/carts/items "$TOKEN" "{\"skuId\":$SKU,\"quantity\":7}"
assert_status 200 && assert_body '"code":0' "set ok"
req GET /api/cart/carts "$TOKEN"
assert_body "\"quantity\":7" "quantity now 7"

step "reject unknown sku and invalid quantity"
req POST /api/cart/carts/items "$TOKEN" '{"skuId":999999999,"quantity":1}'
assert_body '"code":10001' "unknown sku -> 10001"
req PUT /api/cart/carts/items "$TOKEN" "{\"skuId\":$SKU,\"quantity\":0}"
assert_body '"code":10001' "qty 0 -> 10001"

step "refresh flow: refresh token issues a new access token"
req POST /api/user/refresh "" "{\"refreshToken\":\"$REFRESH\"}"
assert_status 200 "refresh http 200"
NEW_TOKEN=$(grep -oE '"accessToken":"[^"]+"' <<<"$RESP" | head -1 | cut -d'"' -f4)
[[ -n "$NEW_TOKEN" ]] && ok "new access token issued" || bad "new token missing"
req GET /api/user/me "${NEW_TOKEN:-none}"
assert_body '"code":0' "new token accepted by gateway"

step "logout blacklists the token"
req POST /api/user/logout "$TOKEN"
assert_status 200 && assert_body '"code":0' "logout ok"
req GET /api/user/me "$TOKEN"
assert_status 401 "old token now 401 at gateway"
assert_body '"code":40100' "401 envelope code"

step "authz guard: plain user hits admin endpoint"
USERNAME2="guest$STAMP"
req POST /api/user/register "" "{\"username\":\"$USERNAME2\",\"password\":\"$PASSWORD\"}"
assert_body '"code":0' "second user registered"
req POST /api/user/login "" "{\"username\":\"$USERNAME2\",\"password\":\"$PASSWORD\"}"
TOKEN2=$(grep -oE '"accessToken":"[^"]+"' <<<"$RESP" | head -1 | cut -d'"' -f4)
req POST /api/product/admin/products "${TOKEN2:-none}" '{"title":"x","price":1.00,"stock":1}'
assert_body '"code":40300' "non-admin -> 40300"

echo
if [[ $FAIL -eq 0 ]]; then
  echo "smoke-flows OK: $PASS assertions green"
  exit 0
fi
echo "smoke-flows FAILED: $PASS green, $FAIL failed"
exit 1
