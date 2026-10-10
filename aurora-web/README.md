# aurora-web

aurora-mall 的前端：Vue 3 + TypeScript + Vite + Element Plus + Pinia。一份代码里两套界面——用户端（浏览、购物车、下单、支付、领券、秒杀）与管理台（商品、券模板、秒杀活动）。

## 起来

```bash
npm install
npm run dev        # http://localhost:5173，端口可用 PORT 覆盖
```

前端只请求**同源**的 `/api`：开发期由 Vite 反代到网关（默认 `http://localhost:8000`，用 `VITE_GATEWAY` 覆盖），生产交给 nginx 做同样的事。所以代码里不出现网关地址，两种环境的凭据与同源策略表现一致。

要真正跑通得先起后端：`docker compose up -d` → `bash nacos/import.sh` → 启服务（见根 README）。前端本身不依赖后端也能启动，只是接口会失败。

```bash
npm run build      # vue-tsc 类型检查 + vite 打包到 dist/
npm run typecheck  # 只做类型检查
```

## 目录

```
src/
├── api/            # 后端契约层：每个服务一个模块，加一个 http 拦截器
├── stores/         # Pinia：登录态、购物车、本地订单索引
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

**下单的幂等键按「提交意图」复用。** `Idempotency-Key` 事实上必填，且必须在超时重试时复用同一个键——每次重试都换新键等于每次都是新订单。券 id 走 `Coupon-Id` 请求头而不是请求体，前端照此实现。

**支付回调故意不在前端实现。** `POST /api/payment/payments/mock-callback` 要带渠道密钥的 HMAC 签名，密钥只存在于服务端与 Nacos。下发到浏览器就等于任何人都能伪造「已支付」。所以支付页在发起支付后给出 `docker/mock-pay.sh <orderId> <amount>`，由那个脚本扮演渠道——它从 Nacos 取密钥、按同样的算法签名，与 `smoke-flows.sh` 同法。

## 已知缺口（这些不是前端能自己解决的）

- **没有「我的订单」列表接口。** 后端只有 `GET /orders/{id}`，所以 `stores/myOrders.ts` 把下过的订单 id 记在浏览器本地，换浏览器或清缓存列表就空了。正解是在 order 服务加一个按 userId 分页的端点。
- **一个订单只能有一个商品。** `PlaceOrderRequest` 是 `{skuId, quantity}`，所以购物车多件结算会生成多个订单，页面如实呈现；优惠券也因此只在单件时可选用。
- **管理台建的商品还不能下单。** 商品表里的 `stock` 只是展示字段，能不能卖由 `aurora-inventory` 决定；而库存服务的写端点不对外开放（网关注入的用户身份会被它的身份守卫拒绝），前端没有任何路径能初始化可售库存。要么库存侧补一个管理入口，要么建商品时由商品服务经 Feign 一并开库存。

## 打包体积

Element Plus 目前是整包引入，主 chunk 约 1.1 MB（gzip 373 KB）。要压下来就上 `unplugin-vue-components` + `unplugin-auto-import` 做按需引入——代价是 `ElMessage` 这类 API 变成隐式全局，且生成的 `.d.ts` 需要在类型检查之前就存在（`npm run build` 是 `vue-tsc` 在先），所以得把生成文件一并提交。这里选了简单可控的那条路。
