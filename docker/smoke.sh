#!/usr/bin/env bash
# M0 smoke: bring the infra stack up and assert every service is healthy.
# Services without a healthcheck count as healthy while their status is "Up".
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
echo "  nacos console      http://localhost:8848/nacos"
echo "  rocketmq dashboard http://localhost:8180"
echo "  skywalking ui      http://localhost:8090"
echo "  grafana            http://localhost:3000 (admin/aurora123)"
echo "  prometheus         http://localhost:9090"

if [[ $FAILED -eq 0 ]]; then
  echo "smoke OK: all aurora-mall infra services are up"
else
  echo "smoke FAILED"
  exit 1
fi
