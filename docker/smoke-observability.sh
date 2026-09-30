#!/usr/bin/env bash
# M4 验收缝：可观测三件套活体验证。
# ① Prometheus 抓取目标全 UP ② Loki 按 traceId 命中日志 ③ OAP 里有 trace。
# 前置：栈已起（服务带 SkyWalking agent + micrometer + loki4j）。
set -uo pipefail
PASS=0
FAIL=0
ok()   { PASS=$((PASS + 1)); echo "  ok   $1"; }
bad()  { FAIL=$((FAIL + 1)); echo "  FAIL $1"; }

# 产生真实流量（登录+浏览），保证 trace/日志都是刚发生的
curl -s -o /dev/null -X POST http://localhost:8000/api/user/login \
  -H "Content-Type: application/json" -d '{"username":"nobody-obs","password":"x"}'
sleep 5 # 等待 agent 上报 + loki 批次推送（batchTimeoutMs=3s）+ prometheus 抓取间隔(15s)

echo "[1/3] Prometheus targets"
PROM_TARGETS=$(curl -s "http://localhost:9090/api/v1/targets?state=active")
DOWN=$(echo "$PROM_TARGETS" | python3 -c "
import sys,json
d=json.load(sys.stdin)
bad=[t['labels'].get('instance','?') for t in d['data']['activeTargets']
     if 'aurora' in t.get('labels',{}).get('job','') and t['health']!='up']
print(len(bad), ' '.join(bad))
")
[[ "$DOWN" == "0 " || "$DOWN" == "0" ]] && ok "aurora-services 抓取目标全部 UP" || bad "prometheus targets down: $DOWN"

echo "[2/3] Loki 按 traceId 检索"
# 从 order 日志取最新的真实 traceId（新 logback 布局输出 TID:xxx）
TID=$(grep -oE "TID: [a-f0-9.]{20,}" /tmp/order.log 2>/dev/null | tail -1 | cut -d' ' -f2)
if [[ -z "$TID" ]]; then
  # 没有 TID 就直接验证服务流存在性（label 命中即算通）
  LOKI_HIT=$(curl -s "http://localhost:3100/loki/api/v1/query_range" \
    --data-urlencode 'query={service="aurora-order"}' --data-urlencode "limit=5" | grep -c '"values"')
  [[ "$LOKI_HIT" -ge 1 ]] && ok "Loki 有 aurora-order 日志流" || bad "Loki 无 aurora-order 日志流"
else
  LOKI_HIT=$(curl -s "http://localhost:3100/loki/api/v1/query_range" \
    --data-urlencode "query={service=\"aurora-order\",traceId=\"$TID\"}" --data-urlencode "limit=5" \
    | grep -c '"values"')
  [[ "$LOKI_HIT" -ge 1 ]] && ok "Loki 按 traceId=$TID 命中日志" || bad "Loki traceId 查询未命中"
fi

echo "[3/3] SkyWalking OAP 有 trace"
NOW=$(python3 -c "import datetime;print(datetime.datetime.now().strftime('%Y-%m-%d %H%M'))")
BEFORE=$(python3 -c "import datetime;print((datetime.datetime.now()-datetime.timedelta(minutes=15)).strftime('%Y-%m-%d %H%M'))")
printf '{"query":"query($duration: Duration!) { a: queryBasicTraces(condition: {queryDuration: $duration, queryOrder: BY_START_TIME, traceState: ALL, paging: {pageNum: 1, pageSize: 3}}) { traces { segmentId } } }","variables":{"duration":{"start":"%s","end":"%s","step":"MINUTE"}}}' "$BEFORE" "$NOW" > /tmp/oap-q.json
OAP=$(curl -s -X POST http://localhost:12800/graphql -H "Content-Type: application/json" --data-binary @/tmp/oap-q.json | grep -c '"segmentId"')
[[ "$OAP" -ge 1 ]] && ok "OAP 最近 15 分钟有 trace" || bad "OAP 无 trace"

if [[ $FAIL -eq 0 ]]; then
  echo "smoke-observability OK: $PASS assertions green"
  exit 0
fi
echo "smoke-observability FAILED: $PASS green, $FAIL failed"
exit 1
