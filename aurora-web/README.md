# aurora-web

aurora-mall 的前端：Vue 3 + TypeScript + Vite + Element Plus + Pinia。一份代码里两套界面——用户端（浏览、购物车、下单、支付、领券、秒杀）与管理台（商品、券模板、秒杀活动）。

## 起来

```bash
npm install
npm run dev        # http://localhost:5173，端口可用 PORT 覆盖
```

前端只请求**同源**的 `/api`：开发期由 Vite 反代到网关（默认 `http://localhost:8000`，用 `VITE_GATEWAY` 覆盖），生产交给 nginx 做同样的事。所以代码里不出现网关地址，两种环境的凭据与同源策略表现一致。

要真正跑通得先起后端：`docker compose up -d` → `bash nacos/import.sh` → 启服务（见根 README）。前端本身不依赖后端也能启动，只是接口会失败。管理台要 `role=ADMIN`，而注册出来的账号一律是普通用户，得手工改库（命令在根 README）。

```bash
npm run build      # vue-tsc 类型检查 + vite 打包到 dist/
npm run typecheck  # 只做类型检查
```

## 目录

```
src/
├── api/            # 后端契约层：每个服务一个模块，加一个 http 拦截器
├── stores/         # Pinia：登录态、购物车
├── router/         # 路由与守卫（requiresAuth / requiresAdmin）
├── views/          # 页面；admin/ 下是管理台
├── components/     # AppHeader 等
├── composables/    # useNow（驱动倒计时）
└── utils/          # 金额、时间、错误文案
```

## 几处值得说明的取舍

**鉴权集中在 `api/http.ts`。** 响应拦截器统一解后端信封 `{code,message,data}`：业务失败（HTTP 200 + 非零 code）抛 `ApiError` 并把后端文案原样给用户；连接不上或 5xx 抛 `NetworkError`，用前端自己写的通用文案——这时候后端根本没回话，它的 message 是给运维看的。两者不混。

**刷新令牌做了单飞。** 后端的 refresh 是一次一换（旧 refreshToken 用过即失效），如果一个页面并发发了 N 个请求同时被 401，各刷一次只有第一个能成功，其余会把会话打成死局。所以 `http.ts` 里用同一个 in-flight Promise 收敛并发刷新，刷新成功再重放原请求。

**金额一律换算成整数分再比较。** 后端用 BigDecimal，序列化成 JSON 数字后 `19.90` 会变成 `19.9`；`0.1 + 0.2 !== 0.3` 这种浮点坑落在优惠券门槛判断上就是算错。展示时才 `toFixed(2)` 补回两位。

**下单的幂等键按「提交意图」复用。** `Idempotency-Key` 事实上必填，且必须在超时重试时复用同一个键——每次重试都换新键等于每次都是新订单。

**支付回调故意不在前端实现。** `POST /api/payment/payments/mock-callback` 要带渠道密钥的 HMAC 签名，密钥只存在于服务端与 Nacos。下发到浏览器就等于任何人都能伪造「已支付」。所以支付页在发起支付后给出 `docker/mock-pay.sh <orderId> <amount>`，由那个脚本扮演渠道——它从 Nacos 取密钥、按同样的算法签名，与 `smoke-flows.sh` 同法。

## 接口约定速查（会被踩的那几条）

| 约定 | 说明 |
| --- | --- |
| `Idempotency-Key` | 下单事实上必填，缺失回 10001 |
| `Coupon-Id` | 用券下单时券 id 走**请求头**，不在请求体里 |
| 秒杀活动列表 | 也要 token，不在网关白名单里 |
| `/admin` 路径 | 商品读接口公开，但路径含 `/admin` 就需要 ADMIN 角色 |
| 管理员判定 | 登录响应里**没有** role，只能另问 `GET /user/me` |
| 业务错误 | 大多是 HTTP 200 + 非零 code，必须判 code 而不是只看 HTTP 状态 |
| 浏览器不可达 | `/api/inventory/stocks/**` 与 `/api/order/internal/**` 一律拒绝，不要实现 |

## 做这层时暴露并补掉的两处后端缺口

- **没有「我的订单」列表接口** → 补了 `GET /api/order/orders`（按 userId 分页，user_id 写进 SQL 条件而不是查出来再过滤）。在此之前前端只能把下过的订单号记在浏览器本地，换浏览器就没了；那个本地 store 已经删掉。
- **管理台建的商品不能下单** → 补了 `GET/PUT /api/inventory/admin/stocks/{skuId}`（要求 ADMIN）。库存原本只有 order 经 Feign 能写，浏览器没有任何路径能把可售库存开出来，于是商品表里的 `stock` 只是展示字段、新建的商品永远下不了单。管理台因此有两处库存概念：列表里的「库存」是商品表的展示字段，行内「库存」按钮管的是真正决定能不能卖的可售库存。

## 仍然是设计本身的边界，没改

- **一个订单只能有一个商品**（`PlaceOrderRequest` 是 `{skuId, quantity}`）。所以购物车多件结算会生成多个订单，页面如实呈现；优惠券只对单个订单生效，多件时不可选。
- **关单回券，不是退款回券。** 项目里没有用户发起的退款入口，唯一的退款是「迟到支付落到已关订单 → 自动退钱」；而那种订单在关单时券已经退回成 `UNUSED` 了，不存在「已核销的券要回退」这条路。

## 打包体积

Element Plus 目前是整包引入，主 chunk 约 1.1 MB（gzip 373 KB）。要压下来就上 `unplugin-vue-components` + `unplugin-auto-import` 做按需引入——代价是 `ElMessage` 这类 API 变成隐式全局，且生成的 `.d.ts` 需要在类型检查之前就存在（`npm run build` 是 `vue-tsc` 在先），所以得把生成文件一并提交。这里选了简单可控的那条路。
