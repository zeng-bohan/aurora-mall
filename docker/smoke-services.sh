#!/usr/bin/env bash
# M0 acceptance seam: every skeleton service must answer 200 through the gateway.
# Prereq: `docker compose up -d` (infra) and the 7 Spring services running on
# the host (see README runbook), gateway on :8000.
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

check "gateway      /actuator/health" "$BASE/actuator/health"
for svc in user product cart order inventory payment; do
  check "$svc  /api/$svc/actuator/health" "$BASE/api/$svc/actuator/health"
done

if [[ $FAILED -eq 0 ]]; then
  echo "service smoke OK: gateway + 6 services reachable through routes"
else
  echo "service smoke FAILED"
  exit 1
fi
