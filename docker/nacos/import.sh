#!/usr/bin/env bash
# Publish aurora's runtime config into the nacos config center (namespace dev).
# Run once after `docker compose up -d`, before starting any service.
#
# Secrets are generated on first run into .secrets.env (gitignored) so real
# values never enter the repository; re-runs reuse the existing file.
set -euo pipefail

cd "$(dirname "$0")"

NACOS="${NACOS_ADDR:-localhost:8848}"
NS=dev
SECRETS_FILE=".secrets.env"

if [[ ! -f "$SECRETS_FILE" ]]; then
  echo "generating local secrets -> $SECRETS_FILE (gitignored)"
  if command -v openssl > /dev/null 2>&1; then
    JWT=$(openssl rand -hex 24)
    INTERNAL=$(openssl rand -hex 16)
  else
    JWT=$(head -c 24 /dev/urandom | od -An -tx1 | tr -d ' \n')
    INTERNAL=$(head -c 16 /dev/urandom | od -An -tx1 | tr -d ' \n')
  fi
  {
    echo "AURORA_JWT_SECRET=dev-jwt-$JWT"
    echo "AURORA_INTERNAL_SECRET=dev-internal-$INTERNAL"
    echo "AURORA_CHANNEL_SECRET=dev-channel-$INTERNAL"
  } > "$SECRETS_FILE"
fi
# shellcheck disable=SC1090
source "$SECRETS_FILE"
CHANNEL="${AURORA_CHANNEL_SECRET:-dev-channel-fallback}"

echo "ensuring namespace '$NS' exists on $NACOS..."
if curl -fs "http://$NACOS/nacos/v1/console/namespaces" | grep -q "\"namespaceId\":\"$NS\""; then
  echo "  namespace already present"
else
  curl -fs -X POST "http://$NACOS/nacos/v1/console/namespaces" \
    --data-urlencode "customNamespaceId=$NS" \
    --data-urlencode "namespaceName=$NS" \
    --data-urlencode "namespaceDesc=aurora-mall dev config" > /dev/null
  echo "  namespace created"
fi

publish() {
  local data_id="$1" content="$2"
  local resp
  resp=$(curl -fs -X POST "http://$NACOS/nacos/v1/cs/configs" \
    --data-urlencode "dataId=$data_id" \
    --data-urlencode "group=DEFAULT_GROUP" \
    --data-urlencode "tenant=$NS" \
    --data-urlencode "type=yml" \
    --data-urlencode "content=$content")
  if [[ "$resp" != "true" ]]; then
    echo "publish failed for $data_id: $resp" >&2
    exit 1
  fi
  echo "  published $data_id"
}

COMMON=$(cat <<YAML
aurora:
  jwt:
    secret: $AURORA_JWT_SECRET
  internal:
    secret: $AURORA_INTERNAL_SECRET
spring:
  cloud:
    openfeign:
      client:
        config:
          default:
            # on the user request path; do not inherit feign 10s/60s defaults
            # (heredoc payloads go through the console pipe: keep ascii-only)
            connect-timeout: 2000
            read-timeout: 3000
YAML
)
USER_CFG=$(cat <<'YAML'
aurora:
  jwt:
    access-ttl-seconds: 1800
    refresh-ttl-seconds: 604800
YAML
)
PRODUCT_CFG=$(cat <<'YAML'
aurora:
  cache:
    physical-ttl-seconds: 86400
    bloom:
      reseed-interval-ms: 300000
YAML
)
ORDER_CFG=$(cat <<'YAML'
aurora:
  order:
    close-delay-level: 16
    close-timeout-seconds: 1800
  tx:
    mode: mq
YAML
)
GATEWAY_CFG=$(cat <<'YAML'
# gateway rate limit: route id -> rule; routes without a rule are unlimited.
# publish new thresholds before peak events; rebinding is immediate.
aurora:
  rate-limit:
    enabled: true
    routes:
      order:
        limit: 100
        window-seconds: 10
      user:
        limit: 200
        window-seconds: 10
YAML
)

PAYMENT_CFG=$(cat <<YAML
aurora:
  payment:
    channel-secret: $CHANNEL
YAML
)

echo "publishing configs into namespace '$NS'..."
publish aurora-common.yml "$COMMON"
publish aurora-user.yml "$USER_CFG"
publish aurora-product.yml "$PRODUCT_CFG"
publish aurora-order.yml "$ORDER_CFG"
publish aurora-gateway.yml "$GATEWAY_CFG"
publish aurora-payment.yml "$PAYMENT_CFG"

echo "done. secrets stay in $SECRETS_FILE and nacos, never in git."
