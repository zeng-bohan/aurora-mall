# 0002 — Maven 多模块 monorepo 与模块命名

日期：2026-09-28 · 状态：Accepted

## Context

个人项目、单一开发者，需要在"多仓库"与"单仓库"间选择，并预先固定模块命名避免返工。

## Decision

- **Maven 多模块 monorepo**，根 `groupId`：`com.zengbohan.aurora`
- 模块命名：`aurora-<service>`，微服务八个：`aurora-gateway` / `aurora-user` / `aurora-product` / `aurora-cart` / `aurora-order` / `aurora-inventory` / `aurora-payment` / `aurora-seckill`（seckill 在 M5 拆出）
- 公共模块：`aurora-common`（统一响应、异常体系、日志约定、幂等注解组件等）
- 手写组件模块（M3）：`aurora-id-generator` / `aurora-ratelimit` / `aurora-rpc`，均为可独立发布的库形态
- 父 POM 统一管理依赖版本；基础设施编排文件集中在仓库 `docker/` 目录

## Consequences

- 单仓库内所有模块版本同涨，个人开发下成本最低
- 手写组件保持库形态（不强依赖业务模块），保证"可讲、可迁移、可单独展示"
