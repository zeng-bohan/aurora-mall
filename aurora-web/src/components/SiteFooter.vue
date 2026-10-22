<script setup lang="ts">
const year = new Date().getFullYear()
</script>

<template>
  <footer class="site-footer">
    <div class="container footer-main">
      <div class="footer-brand">
        <RouterLink class="brand" to="/">
          <span class="brand__mark" aria-hidden="true" />
          <span class="brand__word">AURORA</span>
        </RouterLink>
        <p>桌面与随身科技。让每天使用的工具安静、顺手、耐用。</p>
      </div>

      <div class="footer-col">
        <h4>选购</h4>
        <RouterLink to="/products">全部商品</RouterLink>
        <RouterLink to="/products?categoryId=1">充电与续航</RouterLink>
        <RouterLink to="/products?categoryId=5">音频</RouterLink>
        <RouterLink to="/seckill">限时秒杀</RouterLink>
      </div>

      <div class="footer-col">
        <h4>账户</h4>
        <RouterLink to="/orders">我的订单</RouterLink>
        <RouterLink to="/addresses">地址簿</RouterLink>
        <RouterLink to="/coupons">优惠券</RouterLink>
        <RouterLink to="/profile">个人中心</RouterLink>
      </div>

      <div class="footer-col">
        <h4>服务</h4>
        <span>满 ¥299 免运费，未满 ¥12</span>
        <span>发货前支持部分或整单退款</span>
        <span>发货后 7 天自动确认收货</span>
        <span>支付与物流为演示模拟流程</span>
      </div>
    </div>

    <div class="container">
      <div class="footer-note">
        演示项目：商品图为概念渲染，支付与物流均为模拟流程，不连接真实渠道。价格、库存与订单数据用于功能演示。
      </div>
      <div class="footer-bottom">
        <span>© {{ year }} AURORA. Fictional brand for portfolio demonstration.</span>
        <span>Built with Vue 3 · Spring Boot · MySQL · Redis</span>
      </div>
    </div>
  </footer>
</template>
