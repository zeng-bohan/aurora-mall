# docker/ — 本地基础设施

全项目共用的中间件栈，与未来服务器部署同构。

## 启动

```bash
cd docker
docker compose up -d        # 首次拉取镜像约 2.5GB
bash smoke.sh               # 等待并断言全部服务健康
bash nacos/import.sh        # 命名空间 dev + 配置/密钥导入 Nacos
```

`import.sh` 必须在**首次启动任何服务之前**执行：服务从配置中心导入 `aurora-common.yml`（密钥），缺配置即启动失败（fail-fast）。脚本首次运行会在 `docker/nacos/.secrets.env` 生成密钥（已 gitignore，不进仓库），重复运行复用同一文件并覆盖发布配置。

## 端点速查

| 服务 | 地址 |
| --- | --- |
| MySQL | localhost:13306（root/aurora123）——宿主 3306 被其他项目占用 |
| Redis | localhost:16379——宿主 6379 同样被占用 |
| Nacos 控制台 | http://localhost:8848/nacos（命名空间 `dev` 存放 aurora 配置） |
| RocketMQ namesrv / broker | localhost:9876 / localhost:10911，控制台 http://localhost:8180 |
| SkyWalking | OAP 11800/12800，UI http://localhost:8090（9.7 线：H2 存储） |
| Prometheus | http://localhost:9090 |
| Grafana | http://localhost:3000（admin/aurora123） |
| Loki | http://localhost:3100 |

## 冒烟脚本

- `bash smoke.sh` — 中间接缝：逐容器断言 healthy/running，并对镜像内无探测工具的 dashboard/UI 从宿主侧主动探测
- `bash smoke-services.sh` — 服务接缝：网关 + 6 服务经 `/api/<service>/actuator/health` 全 200（需先启动服务）
- `bash smoke-flows.sh` — 业务金路径接缝：注册→登录→浏览→加购→改价收敛→登出，逐步断言（需全部服务在跑）

## 备注

- RocketMQ 容器以 root 运行：镜像不含 store/logs 目录，卷挂载点属主为 root
- 上表端口是本开发机的偏离值；容器网络内通信一律走标准端口
