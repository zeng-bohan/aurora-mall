# M5 压测报告：秒杀抢购在「活动限流关闭 / 打开」下的对照

日期：2026-10-09 · 状态：已完成（M5 S3 交付物） · 关联：[m4-load-test.md](m4-load-test.md)（压测方法与机器基线同源）、[smoke-seckill.sh](../docker/smoke-seckill.sh)（功能语义的小规模验收缝）

本报告的每个数字都可用 `docker/jmeter/aurora-seckill.jmx` 原样重放（命令见文末「复现」节）。

## 1. 场景定义

压的是**同一条秒杀抢购链路**：Lua 原子预扣（时间窗 / 一人一单 / 库存三合一）→ MQ 异步落单 → 消费者写订单并扣 DB 库存。两轮只有一项差异：活动维度限流（`aurora.seckill.rate-limit`）关闭 / 打开，且该开关是**配置中心下发、服务不重启**（`@RefreshScope`）。

| 项 | 值 |
| --- | --- |
| 入口 | 秒杀服务**直连** `http://localhost:8087`（理由见下） |
| 端点 | `POST /activities/{id}/orders` |
| 活动 | 每轮新建一个活动：限量 3000，窗口覆盖整轮 |
| 阶梯 | 10 → 50 → 100 线程顺序执行（`serialize_threadgroups`），每档 20s；ramp 10s / 20s / 30s |
| 买家身份 | `X-User-Id` 用 JMeter 全局计数器 `${__counter(TRUE,)}`——每个请求都是**不同买家**（真实抢购形态） |
| 内部密钥 | `X-Internal-Secret` 由 `-JSECRET` 传入（等价于网关注入，见下） |
| 思考时间 | 无（测量上限吞吐，不模拟用户节奏） |
| 限流 A 轮 | `enabled: false` |
| 限流 B 轮 | `enabled: true, per-activity-limit: 300, window-seconds: 1` |

**为什么直连服务而不是走网关**：本场景需要 N 个互不相同的买家身份，而网关会从 JWT 注入 `X-User-Id`，携带 N 个身份意味着注册 N 个账号——压测主体会变成注册流程。因此直连带上网关注入的等价头部；网关路径另有 `smoke-seckill.sh` 第 7 步的真实用户用例覆盖，网关**路由级**限流由 `smoke-flows.sh` 链 C 覆盖。

**断言口径**：计划里的断言是「响应含 `"code":0`」（中签），因此 jtl 的 `success` 列天然把请求分成**中签**与**未中签**两类；JMeter 报告里的"失败率"在本次压测读作**未中签率**（未中签包含正常业务拒绝：售罄 30003、被限流 42900）。HTTP 非 2xx 仍由 JMeter 自动计为失败——两轮都没有出现。

## 2. 方法与环境

| 项 | 值 |
| --- | --- |
| 机器 | 16 逻辑核 / 31.8 GB，Windows；中间件栈 + 秒杀服务 + JMeter 全部同机（与 M4 同一台） |
| JVM | JDK 21，秒杀服务默认堆参数（未为压测调参） |
| 压测工具 | Apache JMeter 5.6.3（非交互模式，工具不入仓） |
| MQ / DB | RocketMQ（本地单 broker）+ MySQL（本地容器），消费者按自身吞吐落单 |
| 限流窗口采样 | 压测期间每秒 `ZCARD seckill:rl:{activityId}`——这是"配额封顶"的**直接证据**（窗口 1s，值即当前窗口内的放行数） |
| 两轮切换 | 仅一条 nacos 配置（`aurora-seckill.yml`），服务不重启；其余参数、脚本、数据、栈完全一致 |

## 3. 结果

两轮各 3 档共 69.6s（含 ramp），样本合并统计。

### 3.1 总览

| 指标 | A 轮（限流关闭） | B 轮（限流 300 req/s） |
| --- | --- | --- |
| 样本数 | 201,735 | 215,855 |
| 吞吐 | **2,897 /s** | **3,100 /s** |
| 中签（`code:0`） | 3,000 | 3,000 |
| 限流窗口峰值 ZCARD | **0**（54 次采样全 0） | **300**（54 次采样 53 次非零） |
| 入口被拒（推导） | 0 | ≈ 199,955（≈ **92.6%** 的尝试） |

### 3.2 时延（按中签 / 未中签拆分）

| 类别 | A 轮 | B 轮 |
| --- | --- | --- |
| 中签 avg / p50 / p95 / max | 5 / 5 / 8 / 53 ms | 6 / 5 / 8 / 40 ms |
| 未中签 avg / p50 / p95 / max | 7 / 6 / 13 / 114 ms | 6 / 6 / 12 / 74 ms |

### 3.3 一致性（压完后核对，两轮相同）

| 检查 | 结果 |
| --- | --- |
| DB 订单数 | 3,000（**正好等于限量**，无超卖） |
| DB `seckill_stock.available` | 0 |
| Redis 活动余量 | 0 |
| 结果 hash 中残留 `PENDING` | 0（每个中签最终都有确定结果） |
| 订单时间跨度 | B 轮：首单到末单 28s（消费者全程跟得上，约 107 单/秒） |

`≈ 199,955` 这个数是推导值而非直接计数：入口放行量 = 300 /s × 53 个非零采样秒 ≈ 15,900，其余尝试都被限流拒绝；它也可以从响应长度分布交叉验证（196,020 条明显长于售罄报文的样本 ≈ 42900 报文）。

## 4. 结论

1. **限流按配置精确生效**：B 轮窗口峰值 ZCARD **正好 300**（不是 299 也不是 301），A 轮全程 0——这是限额封顶的直接证据，比从响应码反推更硬。
2. **两轮都不超卖、都不丢单**：限量 3000 在 20 万级尝试下**恰好**成交 3000，Redis 与 DB 同时收敛到 0，没有残留 PENDING。这是 M5 出口标准（"不超卖 + 并发证据"）在压测规模上的复现。
3. **在这台机器上，限流不是"防止服务被打挂"的关键**：A 轮在没有限流的情况下 2,897 /s 仍保持 5–7 ms 的中位时延、p95 13–14 ms——因为售罄后的拒绝路径很便宜（一次 Redis Lua，不碰 DB）。**限流的真实价值是把"每个中签请求的成本"（MQ 投递 + DB 写入 + 消费者落单）按预算摊平**，并保证一个爆款活动不会吃光服务的处理配额（维度是活动，其他活动不受影响）。这一点在设计里就写明了：闸门本身便宜，限流管的是入场量而不是保护闸门。
4. **B 轮的"吞吐更高"不要误读**：3,100 /s 是被限流的请求更快返回（少一次 Lua）带来的迭代数上升，不是有效吞吐提升；有效吞吐（中签）两轮都是 3,000。
5. **未中签时延的尾部（p95/max）B 轮更小**（12 / 74 ms vs 13 / 114 ms），与"入口先拦掉 92% 的请求"的方向一致；但两轮都在个位数毫秒量级，这个差异不能当作强结论。

## 5. 复现

```bash
# 0) 前置：中间件栈 + 秒杀服务（直连 8087）；秒杀库已建表
docker compose up -d && bash docker/nacos/import.sh
docker exec -i aurora-mysql mysql -uroot -paurora123 < docker/mysql/init/07-aurora_seckill.sql
java -jar aurora-seckill/target/aurora-seckill-0.1.0-SNAPSHOT.jar &     # 默认 profile

# 1) 下发限流配置（配置中心，服务不重启）
#    A 轮 enabled=false；B 轮 enabled=true, per-activity-limit=300, window-seconds=1
curl -s -XPOST http://localhost:8848/nacos/v1/cs/configs \
  --data-urlencode "dataId=aurora-seckill.yml" --data-urlencode "group=DEFAULT_GROUP" \
  --data-urlencode "tenant=dev" --data-urlencode "type=yml" \
  --data-urlencode "content=aurora:
  seckill:
    rate-limit:
      enabled: true
      per-activity-limit: 300
      window-seconds: 1"

# 2) 建活动（限量 3000、当前时间窗内）并预热：POST /admin/activities → /admin/activities/{id}/preheat
#    字段与鉴权见 aurora-seckill 的 SeckillAdminController（X-User-Role: ADMIN + X-Internal-Secret）

# 3) 跑一轮
jmeter -n -t docker/jmeter/aurora-seckill.jmx \
  -JACTIVITY_ID=<活动id> -JSECRET=<aurora.internal.secret> -JDURATION=20 -l arm.jtl

# 4) 核对收敛：DB 订单数 == 中签数、available=0、Redis 余量=0、结果 hash 无 PENDING
docker exec aurora-mysql mysql -uroot -paurora123 -e \
  "SELECT COUNT(*) FROM aurora_seckill.seckill_order WHERE activity_id=<活动id>;
   SELECT available FROM aurora_seckill.seckill_stock WHERE activity_id=<活动id>;"
```

## 6. 后续可做

1. **给抢购结果加指标**：`aurora_seckill_buy_total{outcome=...}`（中签 / 售罄 / 已参与 / 被限流）——本报告的"被限流 92.6%"目前是推导值，有了计数器就是直接读 `/actuator/prometheus`，也才谈得上"峰值下告警有输入"。
2. **再加一轮"中签路径持续热"的对照**：把限量调到远超总请求数，让每个请求都走到 MQ + DB，此时限流对尾延迟与消费者积压的影响才会显出来（本轮售罄后的请求太便宜，掩盖了这部分成本）。
3. **网关路径的阶梯**：若能批量预置 N 个账号的 JWT，可把入口换成网关，顺带把路由级限流也纳入对照表。
