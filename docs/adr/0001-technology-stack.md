# 0001 — 技术栈选型：JDK 21 + Spring Boot 3.x + Spring Cloud Alibaba

日期：2026-09-28 · 状态：Accepted

## Context

从 0 构建高级工程师级微服务作品，需在 Dubbo 生态与 Spring Cloud Alibaba（SCA）生态间选择，并确定 JVM / 框架版本基线。

## Decision

- JDK 21（LTS；虚拟线程作为后续亮点素材）
- Spring Boot 3.x 最新稳定版，配套官方兼容矩阵中的 Spring Cloud Alibaba 版本（具体版本号在 M0 首个工单中钉死并回填本 ADR）
- Nacos（注册 + 配置中心）、Sentinel（限流熔断）、Seata（分布式事务对照）、OpenFeign（声明式服务调用）
- RocketMQ 5.x（事务消息 + 延迟消息为订单场景刚需）、MyBatis-Plus、MySQL 8、Redis 7、Spring Cloud Gateway

## Consequences

- 放弃 Dubbo 生态：大厂使用多，但 SCA 求职面试覆盖面更广，且与手写 RPC（ADR-0008）形成对照更自然
- Boot 3.x 与国内存量 2.x 的差异作为面试讲述点，而非风险
- 不引入 Spring Security / Sa-Token，鉴权方案见 ADR-0006
