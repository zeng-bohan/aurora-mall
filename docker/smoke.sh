#!/usr/bin/env bash
# M0 冒烟：拉起中间件栈并断言每个服务健康。
# 没有 healthcheck 的服务，只要状态为 "Up" 即视为健康。
set -euo pipefail

cd "$(dirname "$0")"

docker compose up -d

echo "waiting for services to settle..."
sleep 15

FAILED=0
while IFS=$'\t' read -r name status; do
  [[ -z "$name" ]] && continue
  case "$status" in
    *"(healthy)"*) ;;
    *"unhealthy"*)
      echo "UNHEALTHY: $name ($status)"; FAILED=1 ;;
    Up*) ;;
    *)
      echo "NOT UP: $name ($status)"; FAILED=1 ;;
  esac
done < <(docker compose ps --format '{{.Name}}\t{{.Status}}')

echo
echo "endpoints:"

FAILED_PROBE=0
probe() {
  local label="$1" url="$2"
  local code
  code="$(curl -s -o /dev/null -w '%{http_code}' "$url")"
  if [[ "$code" != 200 && "$code" != 302 ]]; then
    echo "PROBE FAIL $label -> HTTP $code"
    FAILED_PROBE=1
  fi
}

# 镜像里没有探测二进制的容器，改从宿主机侧探测
probe "rocketmq dashboard http://localhost:8180" http://localhost:8180
probe "skywalking ui   http://localhost:8090" http://localhost:8090

echo "  nacos console      http://localhost:8848/nacos"
echo "  rocketmq dashboard http://localhost:8180"
echo "  skywalking ui      http://localhost:8090"
echo "  grafana            http://localhost:3000 (admin/aurora123)"
echo "  prometheus         http://localhost:9090"

if [[ $FAILED -eq 0 && $FAILED_PROBE -eq 0 ]]; then
  echo "smoke OK: all aurora-mall infra services are up"
else
  echo "smoke FAILED"
  exit 1
fi
