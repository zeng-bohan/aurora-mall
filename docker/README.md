# docker/ — 本地基础设施

本地中间件栈。服务通过 Nacos 发现，MySQL 按服务分库，Redis 保存库存与秒杀预扣，RocketMQ 承载关单与支付事件。

## 启动

```bash
cd docker
docker compose up -d
bash smoke.sh               # 等待并检查中间件健康
bash nacos/import.sh        # 命名空间 dev + 配置/密钥导入 Nacos
```

`import.sh` 要在首次启动服务之前执行。服务从配置中心读取内部密钥，缺配置会直接启动失败。首次运行会在 `docker/nacos/.secrets.env` 生成开发密钥，已加入 `.gitignore`；重复运行复用同一文件。

## 端点

| 服务 | 地址 |
| --- | --- |
| MySQL | `localhost:13306`（root / aurora123） |
| Redis | `localhost:16379` |
| Nacos | `http://localhost:8848/nacos`（命名空间 `dev`） |
| RocketMQ | `localhost:9876`，控制台 `http://localhost:8180` |
| SkyWalking UI | `http://localhost:8090` |
| Prometheus | `http://localhost:9090` |
| Grafana | `http://localhost:3000`（admin / aurora123） |
| Loki | `http://localhost:3100` |

## 建表与种子数据

MySQL 镜像首次创建数据卷时，会按文件名顺序执行 `mysql/init/*.sql`：

| 文件 | 内容 |
| --- | --- |
| `01`–`08` | 用户、商品、ID、库存、订单、支付、秒杀、分库试点的表结构 |
| `12-dtc-catalog.sql` | 7 分类、15 属性、53 属性值、18 SPU、45 SKU、库存 |
| `13-dtc-demo-users.sql` | 运营管理员、6 个顾客、地址、券模板与用户券 |
| `14-dtc-demo-orders.sql` | 37 笔历史父订单、明细、退款、支付单 |
| `15-dtc-demo-seckill.sql` | 1 场进行中、1 场次日的秒杀活动 |

已有数据卷不会自动重跑初始化脚本。需要补种子或重置演示数据时执行：

```bash
bash seed-demo.sh            # 补种子并预热秒杀
bash seed-demo.sh --reset    # 先重置 3001–3037 演示订单，再重铺
```

脚本依次执行 12–15 号 SQL，并在服务已启动时登录运营账号，按 DB 台账重建库存 Redis、为三场秒杀活动预热。SQL 使用固定主键与 `INSERT IGNORE` / `ON DUPLICATE KEY UPDATE`，重复执行不会产生第二份数据。

演示账号：

- 运营：`admin / Aurora123!`
- 顾客：`linyi`、`chenmo`、`zhouran`、`wutong`、`xuzhi`、`heyu`，密码同为 `Aurora123!`

## 冒烟脚本

- `bash smoke.sh`：检查中间件容器与端口。
- `bash smoke-services.sh`：检查网关与服务健康端点，需要先启动服务。
- `bash smoke-flows.sh`、`smoke-coupon.sh`、`smoke-seckill.sh`、`mock-pay.sh` 写于 DTC 重构前，接口已经变化，不再作为当前验收依据。当前业务流程用 `aurora-web/scripts/ui-*.mjs` 或 README 中的浏览器流程验证。

## 注意

- 开发密钥与 Nacos 配置只用于本地，不能直接用于公网。
- 商品图为概念渲染，支付与物流为模拟流程。
- MySQL 端口使用 13306、Redis 使用 16379，避免与本机常见的 3306/6379 冲突。
