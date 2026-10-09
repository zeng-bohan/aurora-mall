#!/usr/bin/env bash
# 把 aurora 的运行时配置发布到 nacos 配置中心（dev 命名空间）。
# 在 `docker compose up -d` 之后、启动任何服务之前执行一次。
#
# 首次运行时把密钥生成到 .secrets.env（已被 gitignore），真实值
# 永不进入仓库；重复执行复用已有文件。
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
  if command -v openssl > /dev/null 2>&1; then
    CHANNEL=$(openssl rand -hex 24)
  else
    CHANNEL=$(head -c 24 /dev/urandom | od -An -tx1 | tr -d ' 
')
  fi
  {
    echo "AURORA_JWT_SECRET=dev-jwt-$JWT"
    echo "AURORA_INTERNAL_SECRET=dev-internal-$INTERNAL"
    echo "AURORA_CHANNEL_SECRET=dev-channel-$CHANNEL"
  } > "$SECRETS_FILE"
fi
# shellcheck disable=SC1090
source "$SECRETS_FILE"
if [[ -z "${AURORA_CHANNEL_SECRET:-}" ]]; then
  echo "FATAL: AURORA_CHANNEL_SECRET missing in $SECRETS_FILE - payment callback signature would be unprotected" >&2
  exit 1
fi
CHANNEL="$AURORA_CHANNEL_SECRET"

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
            # 位于用户请求路径上；不要沿用 feign 的 10s/60s 默认值
            # （heredoc 内容经控制台管道传输：保持纯 ascii）
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
# 网关限流：路由 id -> 规则；没有规则的路由不受限。
# 大促前发布新阈值；重新绑定立即生效。
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

SECKILL_CFG=$(cat <<'YAML'
# 秒杀活动维度限流（网关路由级限流之外的第二道闸）：
# 每个活动在 window-seconds 内最多 per-activity-limit 次抢购请求。
# 大促前调大或临时关闭；发布后立即生效（服务侧 @RefreshScope）。
aurora:
  seckill:
    rate-limit:
      enabled: true
      per-activity-limit: 200
      window-seconds: 1
YAML
)

echo "publishing configs into namespace '$NS'..."
publish aurora-common.yml "$COMMON"
publish aurora-user.yml "$USER_CFG"
publish aurora-product.yml "$PRODUCT_CFG"
publish aurora-order.yml "$ORDER_CFG"
publish aurora-gateway.yml "$GATEWAY_CFG"
publish aurora-payment.yml "$PAYMENT_CFG"
publish aurora-seckill.yml "$SECKILL_CFG"

echo "done. secrets stay in $SECRETS_FILE and nacos, never in git."
