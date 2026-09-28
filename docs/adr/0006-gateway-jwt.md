# 0006 — 鉴权：网关统一 JWT，全手写

日期：2026-09-28 · 状态：Accepted

## Context

备选：网关统一 JWT 手写、Sa-Token、Spring Security。

## Decision

- **网关层统一 JWT**：登录后签发 JWT；网关过滤器完成校验 + 解析用户上下文，透传给下游服务（下游无状态）
- 签发、刷新、过滤器、黑名单全部手写，原理透明可讲
- 服务间 OpenFeign 调用走内网 + 内部签名头防伪装
- 不引入 Sa-Token（核心逻辑黑盒）、不引入 Spring Security（过重，收益不匹配）

## Consequences

- 网关层与手写限流器（ADR-0008）共同构成"基础设施能力"的展示橱窗
- JWT 注销/改密场景需黑名单或短过期 + 刷新机制配合，实现时注意
