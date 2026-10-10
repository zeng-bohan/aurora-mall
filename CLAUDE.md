# aurora-mall

从 0 构建的 Java 微服务电商系统（Maven 多模块 monorepo，JDK 21 + Spring Boot 3.5）。里程碑与当前进度见 README。

## 常用命令

- 全模块测试：`mvn test`（外部依赖类集成测试无环境时自动跳过）
- 单模块测试：`mvn -pl aurora-rpc test`
- 打包并本地起服务：`mvn clean package` 后 `java -jar aurora-<svc>/target/aurora-<svc>-0.1.0-SNAPSHOT.jar`
- 中间件与可观测栈、smoke 脚本在 `docker/` 下

## 本机端口（有意偏离标准值，勿改回）

- 网关 8000、MySQL 宿主 13306、Redis 宿主 16379；容器网络内仍是标准端口
- 新增 JDBC/Redis 配置一律用上述宿主端口

## 工程约定

- 代码标识符用英文、提交信息用中文；注释跟随文档用中文
- 提交只保留 zeng-bohan 为作者/提交者，不带任何 AI 归属
- 架构与设计取舍见各组件 README 与 `docs/` 下的对照报告
- 开发流程由 zengbohan-skill 驱动（显式调用 `/zengbohan-skill <task>`；单一 plan.md，工件在 `.scratch/`）
