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
- JWT 注销/改密场景需黑名单或短过期 + 刷新机制配合，实现时注意 → **M1 定案**：注销走 Redis 黑名单（key `aurora:jwt:blacklist:{jti}`，TTL=剩余有效期），网关校验时查询
- **401 语义（M1 定案）**：业务错误按信封约定走 HTTP 200；**鉴权失败在网关返回 HTTP 401**（响应体仍为统一信封 `code=40100`）——网关是鉴权的唯一权威点，401 供客户端触发重新登录/刷新流程
- 透传约定：网关校验通过后附加 `X-User-Id` / `X-User-Role` / `X-Internal-Secret`（共享密钥头，服务侧校验防绕网关直连）；**Authorization 原样转发**——logout 需要原始 token 的 jti，下游信任由内部签名头建立

## 后续加固（2026-09-30 评审）

- **登出会话失效**：logout 除拉黑 access jti 外，写入用户级 `session-invalid-before` 时间戳，refresh 按签发时间比对——一次登出作废该用户全部既有 refresh；refresh 改为一次一换（旧票即用即拉黑）。
- **公开路径头清洗**：网关对白名单路径清除客户端自带的 `X-User-Id`/`X-User-Role`，杜绝未来公开端点读身份头时的即插即用伪造。
- **mock-callback 渠道签名**：当前回调端点仅靠内部密钥（免用户 token）。上线前必须替换为真实渠道签名校验（第三方凭签名+订单号回调），列为 M4 验收项。
