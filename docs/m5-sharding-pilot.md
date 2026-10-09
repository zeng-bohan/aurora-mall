# M5 S6：ShardingSphere 分库试点对照报告

> **定位**：这是**试点**，不是上线方案。试点模块 `aurora-sharding-pilot` 不含产品代码，产品链路（`aurora-order` 等）
> 没有引入 ShardingSphere。是否落地见文末「落地前提」——那是一个独立里程碑。
>
> **可复跑**：`mvn -B -pl aurora-sharding-pilot -am test` → 13 条断言全绿（MySQL 不可达时自动跳过，与 Redis/Lua 集成测试同法）。
> 配置见 `aurora-sharding-pilot/src/test/resources/sharding-pilot.yaml`；两个物理库由 `docker/mysql/init/08-aurora_sharding_pilot.sql` 建。

## 一、试点要回答的四个问题

| # | 问题 | 为什么问 |
|---|---|---|
| 1 | 按 `user_id` 分库后，跨分片查询变成什么样 | 现有 SQL 写法不变，但背后代价与语义都变了 |
| 2 | 事务边界还在不在 | 订单/库存/支付的正确性都建立在事务上 |
| 3 | 分片之后"谁保证唯一" | 本项目大量依赖主键与唯一键（订单号、幂等去重、一人一单） |
| 4 | 5.5 这套中间件能不能真的接进现有技术栈 | 版本与依赖矩阵的摩擦是上线成本的大头 |

**方法**：把与线上同构的 `orders` 表 DDL 复制到两个真实 MySQL 库（`aurora_order_shard0/1`），经 ShardingSphere-JDBC 建**逻辑连接**执行。
每条断言做两层验证：ShardingSphere 打印的 **Actual SQL**（证明打到哪一片）+ **直连物理库**（证明数据真在哪一片）。

## 二、结论速览（实测）

| 维度 | 实测行为 | 对本项目的含义 |
|---|---|---|
| 分片键等值查询 | 只打到对应那一个库 | "查我的订单"是单库查询，无损 |
| 无分片键查询 | 广播到两片再归并，结果正确 | 后台按状态查订单代价翻倍，需要"分片键优先"的查询规范 |
| 跨分片分页 | 片内被重写为 `LIMIT 4 OFFSET 0`，内存归并后返回全局正确的第 2 页 | 结果对，但**深分页代价随偏移线性增长** |
| 跨分片聚合 | 两片各自 `COUNT(*)`/`SUM(total_amount)`，归并结果与物理库对账一致 | 报表类查询要单独设计 |
| **改分片键** | **被中间件直接拒绝**：`Can not update sharding value for table 'orders'` | "把订单转给另一个用户"这类需求必须重新设计 |
| 同片事务 | 本地事务，提交/回滚都正确 | 单用户维度的事务与现在等价 |
| 跨片事务 | 一条逻辑事务里两个库各自提交；回滚会传播到两片 | **最大落地门槛**：跨库原子必须上 XA 或 Seata |
| 主键 | 两库 `AUTO_INCREMENT` 各自从 1 开始 → 全局撞键；本项目订单号来自 `aurora-id-generator`，天然规避 | 分片内置雪花（`SNOWFLAKE`）也可用，试点已验证 |
| 唯一键（不含分片键） | 同一个 `uk_code` 在两个库各存一份 → **全局唯一性被破坏** | `idempotent_record.biz_key`、券模板等全局唯一键要改造 |

## 三、关键证据

### 3.1 路由：两侧对证

```text
Actual SQL: ds0 ::: SELECT id FROM orders WHERE user_id = ? ::: [2]      # 分片键等值 → 只打 ds0
Actual SQL: ds0 ::: SELECT id FROM orders WHERE status = ? ::: [0]       # 无分片键 → 广播
Actual SQL: ds1 ::: SELECT id FROM orders WHERE status = ? ::: [0]
```

同时直连两个物理库核对：`user_id=2` 的行只在 shard0、`user_id=3` 的行只在 shard1——**数据真的分到了两个库**，不是逻辑幻觉。

### 3.2 分页重写（最值得记住的一条）

```text
逻辑 SQL：SELECT id FROM orders ORDER BY id DESC LIMIT 2 OFFSET 2
Actual SQL: ds0 ::: SELECT id FROM orders ORDER BY id DESC LIMIT 4 OFFSET 0
Actual SQL: ds1 ::: SELECT id FROM orders ORDER BY id DESC LIMIT 4 OFFSET 0
```

每片都要取 `offset+limit` 行回中间件内存重新排序再截取。第 N 页 = 每片拉 N×size 行：
**正确性没问题**（断言验证返回的是全局第 3、4 名），但深分页代价随分片数倍增长。

### 3.3 跨分片聚合

```text
Actual SQL: ds0 ::: SELECT COUNT(*) FROM orders      # 两片各算一次，中间件归并
Actual SQL: ds1 ::: SELECT COUNT(*) FROM orders
Actual SQL: ds0 ::: SELECT SUM(total_amount) FROM orders
Actual SQL: ds1 ::: SELECT SUM(total_amount) FROM orders
```

双向对账：逻辑层 `COUNT=3 / SUM=60.00`；物理层 `ds0: 2 行/30.00` + `ds1: 1 行/30.00`，两侧一致。

### 3.4 事务

- **同片**：在 `user_id=2` 上写两行后回滚 → 两个库都没有数据（本地事务，与单库等价）。
- **跨片**：一条逻辑事务里写 `user_id=2` 与 `user_id=3` → Actual SQL 显示 **ds0 与 ds1 都参与**，回滚也会传播到两片。
- 但 ShardingSphere 默认是**本地事务**：两个库各自提交，**库与库之间没有原子提交协议**。ds0 提交成功、ds1 提交失败或进程
  在两次提交之间崩溃，会留下半写状态。要跨库原子必须启用 XA（`shardingsphere-transaction-xa-*`，中央仓库有 atomikos / narayana 实现）
  或接入 Seata（`shardingsphere-transaction-base-seata-at`）。**这是试点无法证明、只能靠配置解决的一条结论**，所以写在这里而不是藏在测试里。

### 3.5 主键与唯一键

```text
# AUTO_INCREMENT：两个库各自计数
ds0: id=1 (user 2)        ds1: id=1 (user 3)        → 跨片看主键重复
# 本项目现状：id 由集中式 aurora-id-generator 供号 → 全局唯一，分片不影响
# 对照：配置 keyGenerateStrategy: SNOWFLAKE 后，不给 id 也能拿到全局唯一主键（实测 1315394731890614272）
# 唯一键：uk_code='DUP' 分别以 user 2/3 插入 → 两个库各存一份；同片内仍然拦得住
```

## 四、依赖矩阵踩坑记录（对上线最有用的一节）

试点在 `shardingsphere-jdbc` 的版本与依赖上花了最多时间。记下「报错 → 真实原因」，避免重新踩：

| 报错现象 | 真实原因 |
|---|---|
| `StorageUnit` 构造里对 `url` 取 `toString()` 抛 NPE | 缺 `shardingsphere-infra-data-source-pool-hikari`（池属性同义映射：标准名 `url` ↔ Hikari 的 `jdbcUrl`） |
| `SPI-00001: No implementation class load from SPI 'ContextManagerBuilder'` | 缺运行模式模块（Standalone） |
| `SPI-00001: ... SPI 'PrivilegeProvider' with type 'ALL_PERMITTED'` | 缺权限特性模块 |
| `NoClassDefFoundError: org/apache/commons/lang3/Strings` | `commons-lang3` 需 ≥ 3.18，而 Spring Boot 父 POM 管的版本更低 |
| `Non-resolvable parent POM ... shardingsphere-distribution` | 官方 BOM（`shardingsphere-bom`）不可解析，不能用 `<scope>import</scope>` |

**版本选择**：`5.5.3` 在中央仓库的发布不完整——聚合包 `shardingsphere-jdbc` 不含分片特性、不含池适配、不含运行模式，
BOM 的 parent 也没发布；逐件补齐后仍卡在 `PrivilegeProvider` SPI，而 `5.5.2` 是经典聚合（特性/池适配/运行模式都在包内），
一次跑通。**试点锁定 5.5.2**，并在根 pom 的依赖矩阵里显式列版本（不用 BOM）。

## 五、落地前提（如果真的要走分片）

试点只回答了"行为是什么"，下面这些是**上线前必须另外解决**的，按优先级排：

1. **版本与依赖矩阵**：锁 `shardingsphere-jdbc` 版本 + 显式列齐（含 `commons-lang3 ≥ 3.18` 这类跨版本冲突）。
2. **跨库事务方案**：XA 或 Seata。本项目 M2 已经做过 Seata AT 的对照，那套结论可以直接复用（不是从零开始）。
3. **分片键与查询规范**：`orders` 用 `user_id` 没问题；但 `idempotent_record`（全局唯一 `biz_key`）、`coupon_template`、
   `tx_message` 这些**不含用户维度**的表要单独决定（广播表 / 单库表 / 改键）。
4. **唯一键改造清单**：所有不含分片键的唯一约束在分片后都会退化成本地约束——逐张表过一遍，缺的补成"分片键 + 业务键"组合。
5. **深分页与报表**：给查询定规矩（分片键优先；跨片聚合走离线或预聚合）。
6. **Spring Boot 集成未验证**：试点是纯 JDBC + YAML 配置（`YamlShardingSphereDataSourceFactory`）。生产走
   `shardingsphere-jdbc-spring-boot-starter` + `spring.shardingsphere.*`，**键名/装配是另一套**，需要单独验证一轮。
7. **迁移方案**：双写、停机窗口、校验工具——都不是试点能回答的，需要独立里程碑。

## 六、复现方式

```bash
# 1) 两个分片库（全部 IF NOT EXISTS，幂等）
bash -c 'docker exec -i aurora-mysql mysql -uroot -paurora123 < docker/mysql/init/08-aurora_sharding_pilot.sql'

# 2) 跑试点（13 条断言；MySQL 不可达时自动跳过）
mvn -B -pl aurora-sharding-pilot -am test
```

产物一览：分片算法 `UserIdModShardingAlgorithm`（取模，`floorMod` 处理负数）、配置 `sharding-pilot.yaml`、
四组断言 `ShardRoutingTest` / `ShardQueryMergeTest` / `ShardTransactionTest` / `ShardIdentityTest`。