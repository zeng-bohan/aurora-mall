#!/usr/bin/env bash
# 幂等演示数据：商品目录、演示账号、37 笔历史订单、3 场秒杀活动。
# 前置：MySQL 容器已启动，建表脚本已由镜像初始化执行。
#
#   bash docker/seed-demo.sh
#
# 商品与服务启动后，脚本会尝试登录运营账号并为两场演示活动写入 Redis 预热。
# 门店与运营台地址、账号见输出末尾。
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
MYSQL_CTN="${MYSQL_CTN:-aurora-mysql}"
MYSQL_PW="${MYSQL_PW:-aurora123}"
GATEWAY="${GATEWAY:-http://localhost:8000}"

step() { printf '\n[%s]\n' "$*"; }
ok() { printf '  ok   %s\n' "$*"; }
warn() { printf '  skip %s\n' "$*"; }

step "导入商品、账号、订单与秒杀种子"
if [[ "${1:-}" == "--reset" ]]; then
  docker exec -e "MYSQL_PWD=$MYSQL_PW" -i "$MYSQL_CTN" \
    mysql -uroot --default-character-set=utf8mb4 < "$ROOT/docker/mysql/reset-demo-orders.sql"
  ok "已重置演示订单 3001–3037"
fi

for file in 12-dtc-catalog 13-dtc-demo-users 14-dtc-demo-orders 15-dtc-demo-seckill; do
  docker exec -e "MYSQL_PWD=$MYSQL_PW" -i "$MYSQL_CTN" \
    mysql -uroot --default-character-set=utf8mb4 < "$ROOT/docker/mysql/init/$file.sql"
  ok "$file.sql"
done

step "商品与服务状态"
if ! curl -sf "$GATEWAY/actuator/health" >/dev/null; then
  warn "网关未就绪，跳过秒杀预热；启动服务后在运营台点击“预热”即可"
else
  ok "网关可达 $GATEWAY"
  TOKEN=$(curl -s -X POST "$GATEWAY/api/user/login" \
    -H 'Content-Type: application/json' \
    -d '{"username":"admin","password":"Aurora123!"}' \
    | sed -n 's/.*"accessToken":"\([^"]*\)".*/\1/p')
  if [[ -z "$TOKEN" ]]; then
    warn "运营账号登录失败，跳过预热"
  else
    if curl -sf -X POST "$GATEWAY/api/inventory/admin/stocks/reconcile" \
      -H "Authorization: Bearer $TOKEN" >/dev/null; then
      ok "库存 Redis 已按 DB 台账重建"
    else
      warn "库存 Redis 重建失败（服务未就绪时可在运营台库存页点击“重建 Redis”）"
    fi
    for id in 9930001 9930002 9930003; do
      if curl -sf -X POST "$GATEWAY/api/seckill/admin/activities/$id/preheat" \
        -H "Authorization: Bearer $TOKEN" >/dev/null; then
        ok "活动 $id 已预热"
      else
        warn "活动 $id 预热失败（可能已结束）"
      fi
    done
  fi
fi

step "演示入口"
cat <<'EOF'
  商城    http://localhost:5173
  运营台  http://localhost:5173/admin
  管理员  admin / Aurora123!
  顾客    linyi / Aurora123!（chenmo、zhouran、wutong、xuzhi、heyu 同密码）
  商品    18 个 SPU / 45 个 SKU
  订单    37 笔历史订单 + 实际下单产生的新订单
  秒杀    9930002 进行中，9930001 次日开始
EOF
