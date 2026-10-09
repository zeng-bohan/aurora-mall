#!/usr/bin/env bash
# M0 验收缝：网关健康检查 + 每个服务各自端口上的健康检查。
# 前置条件：`docker compose up -d`（中间件）以及 8 个 Spring 服务运行在
# 宿主机上（见 README 运行手册），网关监听 :8000。
# 注意：网关已封 /actuator/** 穿透（指标端点不暴露给网关流量），服务健康改为
# 直连各自端口探测——这本身就是"内部端点不对用户流量开放"语义的一部分。
set -euo pipefail

BASE="${BASE:-http://localhost:8000}"

FAILED=0
check() {
  local label="$1" url="$2"
  local code
  code="$(curl -s -o /dev/null -w '%{http_code}' "$url")"
  if [[ "$code" == "200" ]]; then
    echo "OK   $label"
  else
    echo "FAIL $label -> HTTP $code"
    FAILED=1
  fi
}

check "gateway   /actuator/health" "$BASE/actuator/health"
declare -A PORTS=( [user]=8081 [product]=8082 [cart]=8083 [order]=8084 [inventory]=8085 [payment]=8086 [seckill]=8087 )
for svc in user product cart order inventory payment seckill; do
  check "$svc  /actuator/health" "http://localhost:${PORTS[$svc]}/actuator/health"
done

if [[ $FAILED -eq 0 ]]; then
  echo "service smoke OK: gateway + 7 services reachable through routes"
else
  echo "service smoke FAILED"
  exit 1
fi
