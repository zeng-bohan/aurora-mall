# M3 对照实验：OpenFeign vs 手写 RPC（aurora-rpc）

日期：2026-09-30 · 状态：已完成（M3 交付物） · 关联：ADR-0008、[Spec #22](https://github.com/zeng-bohan/aurora-mall/issues/22)

## 对照的问题

ADR-0008 承诺服务间调用有两条可切换的传输路径：Spring Cloud 成熟的 OpenFeign（HTTP/JSON + Ribbon 式负载均衡），与手写的 aurora-rpc（自定义二进制协议 + Netty + 自研注册发现/负载均衡/熔断）。切换只改一个配置项（`aurora.rpc.enabled`），业务代码零改动。本报告回答三个问题：**功能是否等价？代价是什么？各自边界在哪？**

## 机制对比

| 维度 | OpenFeign（HTTP） | aurora-rpc（Netty TCP） |
| --- | --- | --- |
| 协议 | HTTP/1.1 文本 + JSON | 定长 18 字节头 + body 二进制帧 |
| 序列化 | Jackson（HTTP 信封 Result<T>） | Jackson 载荷 + 自定义帧头（serializer code 可协商） |
| 服务发现 | Spring Cloud LoadBalancer（Nacos） | 自研 RegistryService SPI（Nacos/内存双实现）+ 本地写时复制快照 |
| 负载均衡 | SC LoadBalancer 策略 | 自研 SPI：随机 / 轮询（严格轮转） |
| 容错 | 无内建（依赖外部） | 熔断器逐接口包裹（CLOSED/OPEN/HALF_OPEN，并发试探有界） |
| 连接 | 连接池（HTTP keep-alive） | 单连接多路复用（requestId 挂起表），池化留 M4+ |
| 鉴权 | X-Internal-Secret 头（每请求） | 连接级握手（一次），同源密钥 |
| 失败语义 | HTTP 状态码 + 信封 code | 帧内 status + 四层异常（业务/不可用/超时/熔断） |
| 可排障性 | curl 可复现、任何网关可观测 | 需专门工具抓帧（默认 JSON 载荷部分缓解） |
| 依赖体积 | spring-cloud-openfeign 全家 | netty + jackson（核心零 Spring） |

## 功能等价性证据

同一套业务断言在两种传输下全部通过：

| 验证 | Feign 模式 | RPC 模式 |
| --- | --- | --- |
| smoke-flows 全链路（含限流链 C） | 68 断言绿 | 68 断言绿（cart→product 走 RPC） |
| smoke-rpc 链 D（专测等价） | —（基线） | 11 断言绿：加购 / 标题·价格快照拼装 / 数量持久化 / 未知商品 → 10001（null 契约翻译）/ 未知商品不落购物车 |

等价性的关键在**语义翻译层**：HTTP 侧"不存在 = 40400 信封"、RPC 侧"不存在 = null"，由导出器（NOT_FOUND 异常 → null）与适配器（信封 40400 → null）各自翻译成 `ProductPort` 的统一端口语义——业务代码两种模式下逐字节相同。

复现：`bash docker/smoke-flows.sh`（默认 Feign）→ `bash docker/smoke-rpc.sh`（切 RPC 实测后自动还原）。链 D 独立成脚本而非塞进 smoke-flows：它要重启两个服务，混跑会拖慢主验收回路；等价性断言本身即脚本主体。

## 实测数据

手写 RPC 全链路代理往返微基准（aurora-rpc 测试内 harness，非 JMH；发现缓存命中 → 均衡 → 熔断 → JSON 编解码 → 本机 Netty 真实往返，8 线程 × 5000 次）：

```
[bench] rpc proxy round-trip: 40000 calls in 1580ms -> 25301 ops/s (8 threads) | p50=0.25ms p99=1.36ms
```

（p50/p99 为单次调用端到端延迟分位。Feign 侧本里程碑未单独取数：HTTP 栈是业界已知量，正式压测统一放 M4 JMeter，届时两条路径同链路对比。）

## 边界与已知代价

- **XID 透传 gap**：Seata AT 的全局事务 XID 经 HTTP header 自动传播；自研 RPC 未实现 XID 携带。因此 order→inventory 的调用**保持 Feign**（AT 对照实验依赖它）。若未来 AT 链路要换 RPC，需在协议 attachment 里补 XID 并在服务端接入 Seata 传播器——工程量已评估，暂不投入。
- **单连接 HOL**：RPC 客户端每实例一条 TCP，所有调用多路复用；大响应会头部阻塞小响应。池化留 M4+，届时按实测决定是否共享线程组。
- **Sentinel 不集成**：熔断/限流对照（LeapArray、槽链、规则中心）只做设计文档对照（见 aurora-ratelimit README），不引入依赖——手写组件的存在意义就是读过源码级理解；生产选型仍是 Sentinel。
- **注册中心复用**：RPC 的 RegistryService 未复用 Spring Cloud Discovery 抽象（绑定 Spring 生态与 LoadBalancer Client，无法承载"纯 Java 核心 + 可选胶水"分层）；Nacos 侧只用了注册/订阅/健康过滤，实例元数据/权重/集群路由是已知边界。

## 结论

两条路径功能等价、可随时切换，切换成本为一个配置项 + 一次重启。RPC 的代价是排障工具链与生态集成（XID、池化），收益是协议开销、连接级握手、逐接口熔断与对分布式原理的源码级掌握。M4 压测将给出两条路径在同一业务链路上的正式吞吐对比。
