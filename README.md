# aurora-mall

从 0 构建的 Java 微服务电商系统。

技术基线：**JDK 21 · Spring Boot 3.5.0 · Spring Cloud 2025.0.0 · Spring Cloud Alibaba 2025.0.0.0**（版本对钉死自 SCA 官方矩阵，见 [ADR-0001](docs/adr/0001-technology-stack.md)）。

## 架构总览

```mermaid
flowchart LR
    C[客户端] --> G[aurora-gateway :8000<br/>路由 / 手写JWT(M1) / 限流(M4)]

    G --> U[aurora-user]
    G --> P[aurora-product]
    G --> CA[aurora-cart]
    G --> O[aurora-order]
    G --> I[aurora-inventory]
    G --> PA[aurora-payment]

    subgraph 业务服务均注册至
      N[(Nacos<br/>注册+配置)]
    end

    U & P & CA & O & I & PA --> N

    O -. 事务消息/延迟消息 .-> MQ[(RocketMQ 5.3)]
    U & P & O & I & PA -.-> SQL[(MySQL 8)]
    P & O & I -.-> R[(Redis 7)]

    subgraph 可观测性[M4 起接入]
      SW[SkyWalking OAP+UI] & PR[Prometheus] & LK[Loki] --> GR[Grafana]
    end
```

设计决策（为什么不用 Dubbo / Spring Security、事务与缓存方案、手写组件边界）全部记录在 [docs/adr/](docs/adr/)，共 8 篇。

## 10 分钟跑起来

前置：JDK 21 · Maven 3.9+ · Docker Desktop（≥6GB 可用内存）· Git Bash（Windows）。

```bash
git clone https://github.com/zeng-bohan/aurora-mall.git && cd aurora-mall
```

**1. 起基础设施全家桶**（MySQL/Redis/Nacos/RocketMQ/SkyWalking/Prometheus/Grafana/Loki）：

```bash
cd docker && docker compose up -d && bash smoke.sh && bash nacos/import.sh
```

看到 `smoke OK` 即中间件就绪（首次拉镜像约 10 分钟）。`nacos/import.sh` 把密钥与各服务配置导入 Nacos（命名空间 dev，密钥本地生成不进 git）——**必须在启动服务前执行**，否则服务启动即失败（fail-fast）。详见 [docker/README.md](docker/README.md)。

**2. 构建并启动 7 个服务**（网关 :8000，业务服务 :8081-8086）：

```bash
cd .. && mvn clean package
for svc in gateway user product cart order inventory payment; do
  java -jar "aurora-$svc/target/aurora-$svc-0.1.0-SNAPSHOT.jar" > /tmp/"$svc".log 2>&1 &
done
```

**3. 验收**：经网关全链路打通

```bash
bash docker/smoke-services.sh
```

看到 `service smoke OK: gateway + 6 services reachable through routes` 即验收通过。

### 端点速查

| 服务 | 地址 | 说明 |
| --- | --- | --- |
| 网关 | http://localhost:8000 | 业务入口 `/api/<service>/**` |
| 健康检查 | `/actuator/health`（各服务直连或经网关） | smoke 断言项 |
| Nacos 控制台 | http://localhost:8848/nacos | 服务注册/配置 |
| RocketMQ 控制台 | http://localhost:8180 | Topic/消费组 |
| SkyWalking UI | http://localhost:8090 | 链路追踪 |
| Grafana | http://localhost:3000（admin/aurora123） | 指标/日志大盘 |
| Prometheus | http://localhost:9090 | 指标抓取 |
| MySQL / Redis | 宿主 **13306** / **16379**（root/aurora123） | 网络内仍为标准端口 |

> 端口说明：本机开发机上 8080/3306/6379 被其他常驻项目占用，故网关与 MySQL/Redis 的**宿主端口**做了映射偏移；服务间网络内通信一律走标准端口。

## 里程碑

| 阶段 | 内容 | 状态 |
| --- | --- | --- |
| M0 | 工程骨架：版本矩阵 + 中间件全家桶 + 7 服务注册 + 网关路由 + CI | 🚧 进行中 |
| M1 | 用户 / 商品 / 购物车（JWT 鉴权、Cache Aside 三防） | 未开始 |
| M2 | 订单 / 库存 / 支付（RocketMQ 事务消息、Seata 对照、统一幂等组件） | 未开始 |
| M3 | 手写组件三部曲：ID 生成器 / 限流熔断 / RPC（与 OpenFeign 切换） | 未开始 |
| M4 | 可观测性 + 网关强化 + JMeter 压测报告 | 未开始 |
| M5 | 秒杀 / 优惠券 / ShardingSphere 分库试点 | 未开始 |
| M6 | 完整前端（Vue3 用户端 + 管理后台） | 未开始 |
| M7 | kind 练习 + 买服务器 + ICP 备案 + 上线 | 未开始 |

任务拆解见 [GitHub Issues](https://github.com/zeng-bohan/aurora-mall/issues)（每里程碑一份 spec + 依赖排序的 tracer-bullet 工单）。

## 文档索引

- [CONTEXT.md](CONTEXT.md) — 项目定位、领域词汇表、工程约定
- [docs/adr/](docs/adr/) — 架构决策记录（0001 技术栈 → 0008 手写组件）
- [docs/agents/](docs/agents/) — AI 协作配置（issue tracker / 标签 / 领域文档）
- `docker/smoke.sh` / `docker/smoke-services.sh` — 两级验收接缝脚本

## CI

push / PR 触发 [GitHub Actions](.github/workflows/ci.yml)：`mvn verify`（Temurin 21 + Maven 缓存）→ 逐服务构建层式 Docker 镜像（仅本地构建验证，不推送）。
