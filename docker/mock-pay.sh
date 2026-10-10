#!/usr/bin/env bash
# 模拟支付渠道的异步回调 —— 前端支付页联调用。
#
# 为什么单独一个脚本而不是前端直接调：回调要带 X-Channel-Signature，签名用的
# channel-secret 只存在于 Nacos 与服务进程里。把它下发给浏览器就等于作废整条
# 回调签名模型（任何人都能伪造"已支付"），所以这一跳必须留在服务端一侧。
# 本脚本扮演"渠道"，与 docker/smoke-flows.sh 里的 channel_sign 同法取密钥、同法签名。
#
# 用法：bash docker/mock-pay.sh <orderId> <amount>
#   amount 必须与订单金额逐位一致（后端会比对），例如 59.70
set -euo pipefail

ORDER_ID="${1:-}"
AMOUNT="${2:-}"
if [[ -z "$ORDER_ID" || -z "$AMOUNT" ]]; then
  echo "用法：bash docker/mock-pay.sh <orderId> <amount>" >&2
  exit 2
fi

BASE="${BASE:-http://localhost:8000}"
NACOS="${NACOS_ADDR:-localhost:8848}"

# 渠道密钥从配置中心取：import.sh 生成后只发布到 nacos 与本地 .secrets.env，
# 从不进 git。取不到就明确失败，不要退化成"无签名调用"。
SECRET="$(curl -fsS "http://$NACOS/nacos/v1/cs/configs?dataId=aurora-payment.yml&group=DEFAULT_GROUP&tenant=dev" \
  | grep -oE 'channel-secret: [A-Za-z0-9-]+' | cut -d' ' -f2 || true)"
if [[ -z "$SECRET" ]]; then
  echo "FATAL: 无法从 $NACOS 取到 channel-secret；先跑 docker/nacos/import.sh" >&2
  exit 1
fi

# 与后端 ChannelSignatureVerifier 对偶：HMAC-SHA256(secret, "<orderId>:<amount>") 的小写 hex
SIGNATURE="$(printf '%s:%s' "$ORDER_ID" "$AMOUNT" | openssl dgst -sha256 -hmac "$SECRET" | awk '{print $NF}')"

echo "==> 模拟渠道回调 order=$ORDER_ID amount=$AMOUNT"
curl -sS -X POST "$BASE/api/payment/payments/mock-callback" \
  -H "Content-Type: application/json" \
  -H "X-Channel-Signature: $SIGNATURE" \
  -d "{\"orderId\":$ORDER_ID,\"amount\":$AMOUNT}"
echo
