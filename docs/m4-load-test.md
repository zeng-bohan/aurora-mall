# M4 压测报告：同一购物车金路径下 Feign 与手写 RPC 的对照

日期：2026-10-08 · 状态：已完成（M4 交付物） · 关联：[m3-rpc-comparison.md](m3-rpc-comparison.md)（传输等价性与微基准）

本报告的每个数字都可用 `docker/jmeter/aurora-load.jmx` 原样重放（命令见文末「复现」节）。

## 1. 场景定义

压测的是**同一段业务代码**：cart 的购物车写/读两个端点，它们分别触发 `ProductPort` 的 `detail` 与 `batch` 调用。`aurora.rpc.enabled` 只切换这两个调用的传输实现，业务代码零改动——这正是 M3 承诺的"换传输不改业务"在压力下的验证。

| 项 | 值 |
| --- | --- |
| 入口 | 网关 `http://localhost:8000`（真实链路，非直连服务端口） |
| 端点 1 | `PUT /api/cart/carts/items`（写，触发 cart→product `detail`） |
| 端点 2 | `GET /api/cart/carts`（读，触发 cart→product `batch`） |
| 阶梯 | 10 → 50 → 100 线程，顺序执行（`serialize_threadgroups`），每档 60s；ramp 10s / 20s / 30s |
| 认证 | 每个线程用 `Once Only Controller` 登录一次（160 次登录/轮），用真实 JWT 走网关鉴权 |
| 幂等写 | 写用 `PUT`（setQuantity）而非 `POST`（累加），保证购物车行数恒定、读数可复现 |
| 思考时间 | 无（测量上限吞吐，不模拟用户节奏） |

## 2. 方法与环境

| 项 | 值 |
| --- | --- |
| 机器 | 16 逻辑核 / 31.8 GB，Windows；7 个服务 + 中间件栈 + JMeter 全部同机 |
| JVM | JDK 21；每个服务 `-Xms128m -Xmx256m`（与应用前几轮验收一致，未为压测调参） |
| 压测工具 | Apache JMeter 5.6.3（非交互模式，工具不入仓） |
| 网关限流 | 压测期间 `aurora-gateway.yml` 为 `routes: {}`（cart 路由不受限流影响），避免 429 污染对照 |
| 数据 | 单用户 + 单 SKU（管理员创建的压测商品），读路径命中缓存 |
| 两轮切换 | round 1 默认（Feign）；round 2 以 `AURORA_RPC_ENABLED=true` 重启 cart+product，两轮参数、脚本、数据、栈完全一致；跑完已还原 Feign |

RPC 轮次生效已用事实校验：product 除 `8082` 外新增监听 `62427`（手写 RPC 的 Netty 端口），cart 与之保持持久连接（`62500 -> 62427`）；还原后 product 只剩 `8082`。

## 3. 结果

登录采样（160/轮）不计入统计；下表为两个业务端点的合并指标。

### 3.1 逐档对照

| 档位 | 模式 | 样本数 | 吞吐 | avg | p90 | p95 | p99 | 错误率 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 10 线程 | Feign | 26,540 | **444 /s** | 20.8 ms | 40 ms | 62 ms | 120 ms | 0.00% |
| 10 线程 | RPC | 67,671 | **1,132 /s** | 8.1 ms | 12 ms | 15 ms | 36 ms | 0.00% |
| 50 线程 | Feign | 36,810 | **614 /s** | 68.3 ms | 119 ms | 166 ms | 285 ms | **8.25%** |
| 50 线程 | RPC | 82,078 | **1,370 /s** | 30.5 ms | 52 ms | 62 ms | 88 ms | 0.00% |
| 100 线程 | Feign | 24,212 | **404 /s** | 185.8 ms | 385 ms | 462 ms | 648 ms | **28.47%** |
| 100 线程 | RPC | 75,320 | **1,257 /s** | 59.8 ms | 89 ms | 102 ms | 148 ms | 0.00% |

### 3.2 整轮对照

| 模式 | 样本数 | 吞吐 | 错误率 | 退化形态 |
| --- | --- | --- | --- | --- |
| Feign（默认） | 87,722 | 486 /s | 11.32% | 50 线程起吞吐见顶、错误出现；100 线程吞吐**回落**（614 → 404 /s）且近三成请求失败 |
| 手写 RPC | 225,229 | 1,250 /s | **0.00%** | 吞吐随并发上升后进入平台（1,132 → 1,370 → 1,257 /s），延迟线性增长、无错误 |

同场景下 RPC 轮吞吐为 Feign 轮的 **2.1–2.6 倍**（10 线程 2.5×、50 线程 2.2×、100 线程 3.1×），p99 只有 Feign 的 1/4–1/5，且**没有出现错误**。

## 4. 关键发现

### 4.1 Feign 轮的错误不是"服务被打挂"，而是客户端本地端口耗尽

Feign 轮 9,928 个失败样本里，9,924 个是 **HTTP 200 + 非零业务码**（`10000 商品服务不可用`），只有 4 个 500。cart 日志给出根因：

```
feign.RetryableException: Address already in use: getsockopt
  executing GET http://aurora-product/products/60        （4,845 次）
feign.RetryableException: Address already in use: getsockopt
  executing GET http://aurora-product/products/batch?...  （5,079 次）
```

- 默认 Feign 客户端（JDK `HttpURLConnection`）**每次调用新建连接、不复用**，高并发下本地临时端口被 TIME_WAIT 占满（本机动态端口范围 `49152 + 16384`），于是 `getsockopt` 直接失败；
- 同一轮次 **product 服务端零错误**（日志 22 KB，`商品服务不可用` 计数为 0），说明 pressure 完全集中在 cart 的连接管理侧，而非 product 处理不过来；
- 手写 RPC 轮同位置 **0 次** `RetryableException`、**0 次** `商品服务不可用`（cart 日志 21 KB 全绿）——持久连接 + 连接池天然没有这个问题。

### 4.2 结论的正确表述（边界，务必一起读）

这条对照测的是**默认开箱配置**与**池化持久连接**的差异，**不是**"手写 RPC 协议比 HTTP/JSON 更快"：

- 差异的主因是**连接生命周期管理**（每请求新建连接 vs 长连接池），而非序列化格式；换用带连接池的 Feign 客户端（Apache HttpClient / OkHttp）后，端口耗尽预期会消失，届时的对比才更接近"协议与序列化"本身；
- 换句话说，本报告的可用结论是：**默认 Spring Cloud Feign 配置在本场景下 50 线程即开始失败，而手写 RPC 在 100 线程仍零错误**——这正是 M3 报告里"两条可切换路径各自边界"的实测补充；
- 单机运行（7 服务 + 中间件 + 压测器同机、每服务 256 MB 堆）意味着**绝对数字不代表生产**；两轮同环境同参数，故**相对差异与失败机制**是可信结论。

### 4.3 与 M3 微基准的关系

M3 的双轴对照（协议/传输微基准 + 等价性）测的是单次调用开销；本轮测的是**并发下的稳定性**。两者互补：微基准说"单次调用 RPC 更省"，本轮说"并发下 Feign 默认连接模型先崩"。RPC 侧未观测到单连接 HOL 阻塞导致的退化（100 线程延迟仍线性），池化决策可维持现状。

### 4.4 期间观测到的两处环境噪声（不影响对照）

1. **Loki 直推刷屏**：大流量下 loki4j 批量上报触发 Loki 的 `429 Maximum active stream limit exceeded`（标签含 `traceId`，高基数导致流数爆炸），cart 日志一轮长到 90 MB 并持续重试。两轮同噪声，且它消耗的是宿主 CPU/IO，属于共同成本；**但这本身是一个值得后续处理的运维问题**（见 §6）。
2. **smoke-flows 链路 C 偶发抖动**：两次运行中一次 `third request is rate limited` 失败（返回 200），一次 68 断言全绿；手动复现（发布限流规则后等待 6s）稳定得到 `200/200/429/429`，说明是脚本发布规则后立即断言的时序竞争，而非限流功能失效。

## 5. 复现

```bash
# 0) 前置：栈已起、7 服务在线、jars 已构建（见 README 快速开始）
cd docker && docker compose up -d && bash smoke.sh && bash nacos/import.sh

# 1) 准备压测数据（注册用户 → 本地提权 ADMIN → 建商品取得 skuId）
#    与 smoke-rpc.sh 的 seed 相同，示例：
curl -s -X POST http://localhost:8000/api/user/register -H 'Content-Type: application/json' \
  -d '{"username":"perf1","password":"secret123","nickname":"perf"}'
docker exec aurora-mysql mysql -uroot -paurora123 \
  -e "UPDATE aurora_user.users SET role='ADMIN' WHERE username='perf1';"
TOKEN=$(curl -s -X POST http://localhost:8000/api/user/login -H 'Content-Type: application/json' \
  -d '{"username":"perf1","password":"secret123"}' | sed -E 's/.*"accessToken":"([^"]+)".*/\1/')
curl -s -X POST http://localhost:8000/api/product/admin/products \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"title":"perf probe","price":19.9,"stock":100000}'

# 2) 第一轮：默认 Feign
jmeter -n -t docker/jmeter/aurora-load.jmx \
  -JUSER=perf1 -JPASS=secret123 -JSKU_ID=<skuId> -JDURATION=60 -l r1-feign.jtl

# 3) 第二轮：手写 RPC（重启 cart+product，其余不动）
AURORA_RPC_ENABLED=true java -Xms128m -Xmx256m -jar aurora-product/target/aurora-product-0.1.0-SNAPSHOT.jar &
AURORA_RPC_ENABLED=true java -Xms128m -Xmx256m -jar aurora-cart/target/aurora-cart-0.1.0-SNAPSHOT.jar &
jmeter -n -t docker/jmeter/aurora-load.jmx \
  -JUSER=perf1 -JPASS=secret123 -JSKU_ID=<skuId> -JDURATION=60 -l r2-rpc.jtl

# 4) 还原 Feign 模式（同 3，去掉 AURORA_RPC_ENABLED）
# 5) 回归缝：压测后业务行为不变
bash docker/smoke-flows.sh      # 68 assertions green
```

两轮之间除 `AURORA_RPC_ENABLED` 外无任何差异；`jmx` 的 token 由测试内部登录取得（避免把长 JWT 经命令行传入）。

## 6. 后续可做

1. **给 Feign 换池化客户端再跑一轮**（Apache HttpClient/OkHttp + `max-connections`）：把"连接管理"与"协议/序列化"两个因素分离，得到更纯粹的协议对照；
2. **Loki 标签降基数**：`traceId` 不作为标签（它是高基数），改为结构化元数据或用 `| json` 提取，可在高流量下避免 `429 Maximum active stream limit exceeded`；
3. **smoke-flows 链路 C 断言加轮询重试**：消除发布规则后的时序竞争，让验收缝稳定反映功能而非抖动。
