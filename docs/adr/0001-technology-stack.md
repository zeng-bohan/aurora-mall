# 0001 — 技术栈选型：JDK 21 + Spring Boot 3.x + Spring Cloud Alibaba

日期：2026-09-28 · 状态：Accepted

## Context

从 0 构建微服务系统，需在 Dubbo 生态与 Spring Cloud Alibaba（SCA）生态间选择，并确定 JVM / 框架版本基线。

## Decision

- JDK 21（LTS；虚拟线程作为后续亮点素材）
- **Spring Boot 3.5.0 + Spring Cloud 2025.0.0 + Spring Cloud Alibaba 2025.0.0.0**（T1 已钉死并经 `mvn clean verify` 在 JDK 21 上验证；依据 SCA 官方 2025.0.x 分支 POM 的版本属性，见 <https://github.com/alibaba/spring-cloud-alibaba/tree/2025.0.x>）
- Nacos（注册 + 配置中心）、Sentinel（限流熔断）、Seata（分布式事务对照）、OpenFeign（声明式服务调用）
- RocketMQ 5.x（事务消息 + 延迟消息为订单场景刚需）、MyBatis-Plus、MySQL 8、Redis 7、Spring Cloud Gateway
- **M2 实现钉死**：rocketmq-spring-boot-starter **2.3.1**（父 POM 显式管理，SCA BOM 未含）；Seata **2.5.0**（`org.apache.seata` 命名空间，来自 SCA BOM）——对照实验见 [docs/m2-tx-comparison.md](../m2-tx-comparison.md)

## Consequences

- 放弃 Dubbo 生态：SCA 生态在国内使用更广泛，且与手写 RPC（ADR-0008）形成对照更自然
- Boot 3.x 与国内存量 2.x 的差异作为技术演进对照点，而非风险
- 不引入 Spring Security / Sa-Token，鉴权方案见 ADR-0006
