#!/usr/bin/env bash
# M1 acceptance seam: one command walks the whole golden path through the
# gateway and asserts every response. Prereq: `docker compose up -d`,
# `nacos/import.sh`, and all 7 services running (see README runbook).
set -uo pipefail

BASE="${BASE:-http://localhost:8000}"
PASS=0
FAIL=0
STEP=0

req() { # method path [token] [json-body] [extra-header]
  local method="$1" path="$2" token="${3:-}" body="${4:-}" extra="${5:-}"
  local args=(-sS -X "$method" -o- -w $'\n%{http_code}' -H 'Content-Type: application/json')
  [[ -n "$token" ]] && args+=(-H "Authorization: Bearer $token")
  [[ -n "$body" ]] && args+=(--data "$body")
  [[ -n "$extra" ]] && args+=(-H "$extra")
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

step "admin price update converges (delayed double delete) + admin list"
req PUT "/api/product/admin/products/$SKU" "$NEW_TOKEN" "{\"title\":\"$TITLE\",\"price\":24.90,\"stock\":50}"
assert_status 200 && assert_body '"code":0' "price update ok"
req GET "/api/product/products/$SKU"
assert_body '"price":24.90' "first read shows new price (cache evicted)"
sleep 1
req GET "/api/product/products/$SKU"
assert_body '"price":24.90' "still new price after delayed double delete"
req GET "/api/product/admin/products" "$NEW_TOKEN"
assert_status 200 "admin list requires token"
assert_body "\"title\":\"$TITLE\"" "admin list includes the product"

# ---- M2: trade chains ----------------------------------------------------

seed_stock() { # skuId quantity: reset the db row and drop the redis key so the
               # reserve path rebuilds from the db view
  docker exec aurora-mysql mysql -uroot -p"${MYSQL_PASSWORD:-aurora123}" -e \
    "INSERT INTO aurora_inventory.product_stock (sku_id, available, reserved) VALUES ($1, $2, 0) ON DUPLICATE KEY UPDATE available=$2, reserved=0;" 2>/dev/null
  docker exec aurora-redis redis-cli DEL "aurora:stock:$1" > /dev/null
}

assert_eq() { # expected actual label
  [[ "$1" == "$2" ]] && ok "$3" || bad "$3 (want '$1', got '$2')"
}

db_scalar() { # sql -> first cell
  docker exec aurora-mysql mysql -uroot -p"${MYSQL_PASSWORD:-aurora123}" -N -e "$1" 2>/dev/null | tr -d '\r'
}

redis_get() {
  docker exec aurora-redis redis-cli GET "aurora:stock:$1" | tr -d '\r'
}

nacos_publish_order() { # delayLevel timeoutSeconds
  local content="aurora:
  order:
    close-delay-level: $1
    close-timeout-seconds: $2
  tx:
    mode: mq"
  curl -fs -X POST "http://${NACOS_ADDR:-localhost:8848}/nacos/v1/cs/configs" \
    --data-urlencode "dataId=aurora-order.yml" \
    --data-urlencode "group=DEFAULT_GROUP" \
    --data-urlencode "tenant=dev" \
    --data-urlencode "type=yml" \
    --data-urlencode "content=$content" > /dev/null
}

ACTOR_TOKEN="${NEW_TOKEN:-$TOKEN}"

step "chain A: seed, place, duplicate 40900, pay, converge, replay no-op"
seed_stock "$SKU" 100
CA_KEY="chainA-$STAMP"
req POST /api/order/orders "$ACTOR_TOKEN" "{\"skuId\":$SKU,\"quantity\":3}" "Idempotency-Key: $CA_KEY"
assert_status 200 "place http 200"
assert_body '"code":0' "place envelope ok"
OID_A=$(grep -oE '"data":[0-9]+' <<<"$RESP" | head -1 | cut -d: -f2)
[[ -n "$OID_A" ]] && ok "order id=$OID_A" || bad "order id missing"
req POST /api/order/orders "$ACTOR_TOKEN" "{\"skuId\":$SKU,\"quantity\":3}" "Idempotency-Key: $CA_KEY"
assert_body '"code":40900' "duplicate key -> 40900"
req POST /api/payment/payments "$ACTOR_TOKEN" "{\"orderId\":$OID_A}"
assert_body '"code":0' "payment initiated"
req POST /api/payment/payments "$ACTOR_TOKEN" "{\"orderId\":$OID_A}"
assert_body '"code":0' "repeat initiate returns the same payment"
req POST /api/payment/payments/mock-callback "" "{\"orderId\":$OID_A}"
assert_body '"status":1' "callback flips payment to PAID"
sleep 3
req GET "/api/order/orders/$OID_A" "$ACTOR_TOKEN"
assert_body '"status":1' "order advanced to PAID"
assert_eq "97" "$(db_scalar "SELECT available FROM aurora_inventory.product_stock WHERE sku_id=$SKU")" "db available is 97"
assert_eq "0" "$(db_scalar "SELECT reserved FROM aurora_inventory.product_stock WHERE sku_id=$SKU")" "db reserved is 0"
req POST /api/payment/payments/mock-callback "" "{\"orderId\":$OID_A}"
sleep 1
assert_eq "97" "$(db_scalar "SELECT available FROM aurora_inventory.product_stock WHERE sku_id=$SKU")" "replayed callback: stock unchanged"
req GET "/api/order/orders/$OID_A" "$ACTOR_TOKEN"
assert_body '"status":1' "order still PAID after replay"

step "chain B: shrink close delay via nacos, overdue order closes and restores stock"
nacos_publish_order 3 10 && ok "nacos: close-delay-level=3, timeout=10s"
sleep 2
seed_stock "$SKU" 100
CB_KEY="chainB-$STAMP"
req POST /api/order/orders "$ACTOR_TOKEN" "{\"skuId\":$SKU,\"quantity\":2}" "Idempotency-Key: $CB_KEY"
assert_body '"code":0' "place ok"
OID_B=$(grep -oE '"data":[0-9]+' <<<"$RESP" | head -1 | cut -d: -f2)
assert_eq "98" "$(redis_get "$SKU")" "redis reserved 2 (100 -> 98)"
req GET "/api/order/orders/$OID_B" "$ACTOR_TOKEN"
assert_body '"status":0' "order is CREATED"
echo "  ... waiting 16s for the delayed close ..."
sleep 16
req GET "/api/order/orders/$OID_B" "$ACTOR_TOKEN"
assert_body '"status":2' "order CLOSED by delayed message"
assert_eq "100" "$(redis_get "$SKU")" "redis restored to 100"
nacos_publish_order 16 1800 && ok "nacos: close config restored (level 16)"

echo
if [[ $FAIL -eq 0 ]]; then
  echo "smoke-flows OK: $PASS assertions green"
  exit 0
fi
echo "smoke-flows FAILED: $PASS green, $FAIL failed"
exit 1
