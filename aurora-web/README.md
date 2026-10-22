# aurora-web

AURORA Mall 前端：Vue 3 + TypeScript + Vite + Pinia + Element Plus。一份代码包含商城和独立运营台。

## 启动

```bash
npm ci
npm run dev        # http://localhost:5173，端口可用 PORT 覆盖
```

前端只请求同源 `/api`。开发期由 Vite 反代到网关（默认 `http://localhost:8000`，用 `VITE_GATEWAY` 覆盖）；生产环境由 nginx 做同样的事。代码里不出现网关地址。

```bash
npm run typecheck  # vue-tsc 类型检查
npm run build      # 类型检查 + 生产构建
```

要跑通交易流程，先按根 README 启动中间件和八个服务，再执行 `bash docker/seed-demo.sh`。

## 页面

商城：

- 首页：品牌主视觉、分类入口、精选商品、使用场景。
- 商品目录：分类、关键词、价格、属性筛选与排序。
- 商品详情：画廊、SKU 选择、库存、数量、加购、立即购买、参数与相关商品。
- 购物车、地址簿、多商品结算、模拟支付。
- 订单列表与详情、模拟物流、发货前退款申请与进度。
- 秒杀列表、详情和结果查询。
- 登录、注册、个人中心、优惠券。

运营台（`/admin`）：

- 概览：待发货、待审批退款、近 30 天订单与销售额，全部来自订单库 SQL。
- 订单、退款审批、SPU / SKU、分类与属性、库存、优惠券、秒杀、用户。

## 目录

```
src/
├── api/            # dtc.ts 是唯一接口封装；http.ts 处理信封、刷新令牌和错误
├── stores/         # 登录态、购物车
├── router/         # 路由与 requiresAuth / requiresAdmin 守卫
├── views/          # 商城页面；admin/ 下是运营台
├── components/     # 头部、页脚、商品卡、状态标签、地址与退款弹窗、运营台外壳
├── styles/         # tokens / base / storefront / app / element
└── utils/          # 金额、时间、错误文案
```

设计令牌在 `styles/tokens.css`：暖白、浅灰、深墨色与少量品牌橙；中英文无衬线字体；统一页面宽度、间距、圆角、阴影和状态色。商城与运营台共享令牌，但布局和密度分开。

## 鉴权与幂等

- 登录态由 Pinia 保存，`api/http.ts` 统一处理 `{code,message,data}` 信封；业务错误抛 `ApiError`，网络错误抛 `NetworkError`。
- 刷新令牌做了并发收敛：多个 401 只发起一次 refresh，成功后重放原请求。
- 下单的 `Idempotency-Key` 按一次提交意图生成；超时重试复用同一个键，服务端据此返回原订单。
- 金额展示前换算成整数分比较，避免浮点误差影响优惠门槛。

## 浏览器验收脚本

`scripts/` 下是 Playwright 脚本，使用本机 Chrome：

```bash
node scripts/ui-storefront.mjs      # 商城浏览、加购、结算
node scripts/ui-admin.mjs           # 运营台订单、退款、商品、库存
node scripts/ui-seckill-flow.mjs    # 创建活动、抢购、支付、退款、审批
node scripts/ui-mobile.mjs          # 390px 布局与横向溢出检查
node scripts/ui-verify-demo.mjs     # 演示数据核对与截图
```

脚本会连接 `http://127.0.0.1:5173`，运行前需启动前端与后端。

## 已知边界

- 支付与物流是演示流程；商品图是概念渲染，不是实拍。
- 没有评价、评分和销量。页面只展示数据库能证明的信息。
- Element Plus 目前整包引入，主 chunk 约 1.1 MB；按需引入需要额外插件与生成文件，暂未采用。
