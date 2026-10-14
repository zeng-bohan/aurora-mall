# aurora-mall

一个用于学习和验证分布式电商架构的 Java 微服务项目，包含用户端与管理端 Web 界面。主要交易流程覆盖下单、库存预留、支付回调和超时关单，并提供可切换的事务方案与自研分布式组件。

## 功能

- 用户注册、登录、令牌刷新、登出；商品浏览与管理、购物车、订单和支付模拟。
- 订单库存预留、RocketMQ 事务消息与延迟关单；支持将订单事务切换为 Seata AT 对照模式。
- 优惠券领取与下单抵扣、秒杀活动和异步下单；当前订单模型为单商品订单，购物车多商品结算会分别生成订单。
- 缓存穿透保护、网关限流与熔断、请求幂等和业务补偿。
- 独立实现分布式 ID 生成器、限流/熔断组件与基于 Netty 的 RPC 组件；购物车到商品服务可在 OpenFeign 与自研 RPC 间切换。
- Vue 用户端与管理台；Prometheus、Grafana、Loki 和 SkyWalking 可观测性配置。

支付使用模拟回调链路，不连接真实支付渠道。

## 技术栈

| 范围 | 技术 |
| --- | --- |
| 后端 | Java 21、Spring Boot 3.5、Spring Cloud 2025、Spring Cloud Alibaba 2025 |
| 数据与消息 | MySQL 8、Redis 7、RocketMQ 5.3、Nacos 2.4 |
| 分布式事务 | RocketMQ 事务消息；可选 Seata AT |
| 可观测性 | Prometheus、Grafana、Loki、SkyWalking |
| 前端 | Vue 3、TypeScript、Vite、Element Plus、Pinia |
| 构建与运行 | Maven 多模块、Docker Compose、GitHub Actions |

## 架构

~~~mermaid
flowchart LR
    Web[Vue 用户端 / 管理台] --> GW[Spring Cloud Gateway]
    GW --> S[用户、商品、购物车、库存、支付、秒杀服务]
    GW --> O[订单服务]
    S --> N[Nacos 注册与配置]
    O --> N
    S --> DB[(MySQL，按服务分库)]
    O --> DB
    S --> R[(Redis)]
    O --> R
    O --> MQ[RocketMQ]
    O -. 可选事务对照 .-> SEATA[Seata AT]
    S --> OBS[Prometheus / Grafana / Loki / SkyWalking]
    O --> OBS
~~~

`gateway` 路由、限流与 JWT 校验；各业务服务通过 Nacos 发现与配置。订单链路以 RocketMQ 事务消息和延迟关单实现最终一致性。

## 模块与目录

仓库采用 Maven 多模块结构（父 POM 列出 14 个模块）：

| 路径 | 职责 |
| --- | --- |
| `aurora-gateway` | API 网关、认证校验与限流 |
| `aurora-user`、`aurora-product` | 用户与商品服务 |
| `aurora-cart`、`aurora-order` | 购物车与订单服务 |
| `aurora-inventory`、`aurora-payment`、`aurora-seckill` | 库存、模拟支付与秒杀服务 |
| `aurora-common`、`aurora-api` | 公共基础设施与服务契约 |
| `aurora-id-generator`、`aurora-ratelimit`、`aurora-rpc` | 分布式 ID、限流/熔断与 RPC 组件 |
| `aurora-sharding-pilot` | 数据分片试点模块 |
| `aurora-web` | Vue 前端 |
| `docker` | 本地中间件栈、数据库初始化 SQL、冒烟和演示脚本 |
| `docs` | 设计对比、压测报告与截图 |

MySQL 的各服务建库与表结构脚本位于 [docker/mysql/init](docker/mysql/init)。新建数据卷时由 MySQL 镜像初始化；已有数据卷不会自动重新执行初始化脚本。项目以这些 SQL 初始化文件管理本地结构，没有 Flyway/Liquibase 自动迁移流程。

## 快速开始

### 环境要求

- JDK 21
- Maven 3.9 或更高版本
- Docker Compose
- Bash（Windows 可使用 Git Bash）

### 启动基础设施

~~~bash
cd docker
docker compose up -d
bash smoke.sh
bash nacos/import.sh
~~~

`smoke.sh` 检查中间件就绪；`nacos/import.sh` 在本地生成并复用被 Git 忽略的开发密钥，并将应用配置写入 Nacos。先完成导入，再启动 Java 服务。MySQL、Redis、Nacos、RocketMQ 及观测组件的地址见 [docker/README.md](docker/README.md)。

Compose 在宿主机映射 MySQL `13306`、Redis `16379`、Nacos `8848`，网关默认使用 `8000`。上述配置面向本地开发；不要把仓库中的开发凭证或关闭认证的 Nacos 配置直接用于公网部署。

### 构建并启动后端

在仓库根目录：

~~~bash
cd ..
mvn -B clean verify
~~~

构建后，在一个 Bash 终端启动 8 个服务进程：

~~~bash
for service in gateway user product cart order inventory payment seckill; do
  java -jar "aurora-$service/target/aurora-$service-0.1.0-SNAPSHOT.jar" &
done
wait
~~~

服务从 Nacos 获取运行配置。若只需调试单个服务，可在 IDE 中启动对应模块的 Spring Boot 应用。

### 启动前端

前端代理 `/api` 到本地网关。启动后端后，在另一终端运行：

~~~bash
cd aurora-web
npm ci
npm run dev
~~~

前端开发地址为 `http://localhost:5173`。管理界面要求 ADMIN 角色；注册流程创建的是普通用户，演示管理员账号由本地演示数据脚本提供。不要将演示账号用于生产环境。

## API 与权限

客户端通过网关 `http://localhost:8000` 访问业务接口。常用入口：

| 功能 | 示例路径 |
| --- | --- |
| 注册、登录、续期、登出、当前用户 | `/api/user/register`、`/api/user/login`、`/api/user/refresh`、`/api/user/logout`、`/api/user/me` |
| 商品浏览与管理 | `/api/product/products`、`/api/product/admin/products` |
| 购物车 | `/api/cart/carts` |
| 订单与优惠券 | `/api/order/orders`、`/api/order/coupons/templates` |
| 秒杀 | `/api/seckill/activities`、`/api/seckill/activities/{id}/orders` |

网关使用 JWT access/refresh token；登出会撤销 token。管理接口执行角色校验，内部服务接口使用共享密钥和身份头守卫。支付回调由服务端校验 HMAC 签名。详细路由与请求模型以各模块 Controller 和 [aurora-web/README.md](aurora-web/README.md) 为准。

## 配置与数据

仓库没有统一的 `.env.example`。本地基础地址与覆盖项见各服务的 `application.yml`；主要变量包括：

| 变量 | 用途 |
| --- | --- |
| `NACOS_ADDR` | Nacos 地址 |
| `MYSQL_ADDR`、`MYSQL_PASSWORD` | MySQL 地址与密码 |
| `REDIS_HOST`、`REDIS_PORT` | Redis 连接 |
| `ROCKETMQ_ADDR` | RocketMQ NameServer |
| `AURORA_TX_MODE`、`SEATA_ENABLED`、`SEATA_ADDR` | 订单事务方案及可选 Seata 连接 |
| `AURORA_RPC_ENABLED`、`RPC_HOST`、`RPC_PORT` | 购物车到商品服务的 RPC 开关与监听配置 |
| `AURORA_SECKILL_ORDER_MODE` | 秒杀订单处理模式 |
| `VITE_GATEWAY` | 前端 Vite 开发代理目标 |

`docker/nacos/import.sh` 生成 JWT、内部调用和支付回调密钥，并发布到 Nacos 的 `dev` 命名空间；本地密钥保存在 `docker/nacos/.secrets.env`（已忽略）。不要提交该文件或在 README、日志中粘贴密钥。

## 开发与部署

后端构建并运行测试：

~~~bash
mvn -B clean verify
~~~

前端：

~~~bash
cd aurora-web
npm ci
npm run typecheck
npm run build
~~~

基础设施健康检查使用 `bash docker/smoke.sh`；服务健康检查使用 `bash docker/smoke-services.sh`。`smoke-flows.sh`、`smoke-seckill.sh`、`smoke-coupon.sh` 和 `seed-demo.sh` 会创建或调整本地业务数据，只应在开发数据库中运行，脚本说明见 [docker/README.md](docker/README.md)。

GitHub Actions 执行 Maven 验证、前端构建，并构建服务 Docker 镜像；工作流不会将镜像推送到镜像仓库。当前 Compose 文件编排中间件，应用服务仍需单独启动；`docker/Dockerfile.service` 可用于构建单服务镜像。

## 许可证

[MIT](LICENSE)