# 截图索引

截图来自本机 Chrome，使用真实后端与演示数据库。商品图为概念渲染，支付与物流为模拟流程。

除特别标注外，商城与运营台页面均保存 1440 × 900、1280 × 800、390 × 844、320 × 700 四个视口。文件名中的数字是视口宽度。

## 商城

| 文件 | 页面 |
| --- | --- |
| `dtc-home-*.png` | 首页 |
| `dtc-products-*.png` | 商品目录 |
| `dtc-products-filter-390.png` | 移动端筛选面板 |
| `dtc-product-detail-*.png` | 商品详情与 SKU 选择 |
| `dtc-cart-*.png` | 购物车 |
| `dtc-checkout-1440.png` | 多商品结算与优惠券 |
| `dtc-orders-*.png` | 订单列表 |
| `dtc-order-detail-*.png` | 已发货订单与模拟物流 |
| `dtc-order-detail-paid-1440.png` | 已支付待发货 |
| `dtc-order-shipped-1440.png` | 已发货 |
| `dtc-order-completed-1440.png` | 已完成 |
| `dtc-order-refund-pending-1440.png` | 待审批退款 |
| `dtc-refund-dialog.png` | 部分退款申请弹窗 |
| `dtc-coupons-*.png` | 优惠券领取与账户券 |
| `dtc-addresses-*.png` | 地址簿 |
| `dtc-address-dialog-320.png` / `dtc-address-dialog-390.png` | 移动端新增地址弹窗 |
| `dtc-profile-*.png` | 个人中心 |
| `dtc-seckill-*.png` | 秒杀列表 |
| `dtc-seckill-detail-*.png` | 秒杀详情 |
| `dtc-seckill-result-1440.png` | 中签结果 |
| `dtc-seckill-order-1440.png` / `dtc-seckill-order-paid-1440.png` | 秒杀订单与支付 |
| `dtc-seckill-refund-1440.png` | 秒杀整单退款 |

## 运营台

| 文件 | 页面 |
| --- | --- |
| `dtc-admin-dashboard-*.png` | 概览，指标来自真实 SQL |
| `dtc-admin-orders-*.png` | 订单管理 |
| `dtc-admin-refunds-*.png` | 退款审批 |
| `dtc-admin-refund-approved-1440.png` | 已同意退款的详情 |
| `dtc-admin-products-*.png` | 商品管理 |
| `dtc-admin-product-edit-*.png` | SPU / SKU 编辑 |
| `dtc-admin-catalog-*.png` | 分类与属性 |
| `dtc-admin-inventory-*.png` | 库存与 Redis 重建 |
| `dtc-admin-coupons-*.png` | 优惠券 |
| `dtc-admin-seckill-*.png` | 秒杀活动与预热 |
| `dtc-admin-users-*.png` | 用户 |

## 可观测性

`obs-*.png` / `obs-*.jpg` 是 Prometheus、Grafana、SkyWalking 的本地观测截图。

## 历史截图

`legacy/` 存放 DTC 重构前的旧界面截图。它们与当前 UI 和订单模型不一致，只保留作对照。
