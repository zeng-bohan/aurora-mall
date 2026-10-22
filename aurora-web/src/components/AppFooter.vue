<template>
  <footer class="site-foot">
    <div class="container">
      <div class="site-foot__top">
        <p class="site-foot__word display">AURORA</p>
        <div class="site-foot__cols">
          <section>
            <h2>目录</h2>
            <RouterLink to="/products">全部选品</RouterLink>
            <RouterLink to="/seckill">秒杀排期</RouterLink>
            <RouterLink to="/coupons">优惠券</RouterLink>
            <RouterLink to="/orders">我的订单</RouterLink>
          </section>
          <section>
            <h2>运行方式</h2>
            <p>Java 21 · Spring Boot 3.5</p>
            <p>8 个服务 · 7 个业务域</p>
            <p>支付为模拟回调，不接真实渠道</p>
            <p>订单 30 分钟未支付自动关闭</p>
          </section>
          <section>
            <h2>来源与许可</h2>
            <a href="https://github.com/zeng-bohan/aurora-mall" target="_blank" rel="noreferrer">
              源码仓库
            </a>
            <p>代码以 MIT 许可发布</p>
            <p>场景摄影来自 Unsplash</p>
          </section>
        </div>
      </div>
      <div class="site-foot__bottom">
        <p>
          场景摄影：Kristian Karlsson、Drew Patrick Miller、Karl Fredrickson、Todd Quackenbush、
          Kelley Bozarth。图片经 picsum.photos 获取，遵循 Unsplash License，仅作氛围，不代表具体商品实拍。
        </p>
        <p>© 2026 aurora-mall · 一种可以逐笔核对的购买方式</p>
      </div>
    </div>
  </footer>
</template>

<style scoped>
.site-foot {
  background: var(--ink);
  color: var(--paper);
  padding: clamp(48px, 7vw, 96px) 0 32px;
}

.site-foot__top {
  display: grid;
  grid-template-columns: 1.1fr 1fr;
  gap: clamp(32px, 5vw, 72px);
  align-items: start;
}

.site-foot__word {
  font-size: clamp(72px, 13vw, 180px);
  color: var(--signal);
  text-transform: uppercase;
}

.site-foot__cols {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 24px;
}

.site-foot__cols section {
  display: grid;
  gap: 6px;
  align-content: start;
}

.site-foot__cols h2 {
  font-size: var(--step-3);
  color: var(--paper);
  margin-bottom: 6px;
}

.site-foot__cols a,
.site-foot__cols p {
  font-size: var(--step-3);
  color: #cfc9be;
  line-height: 1.6;
}

.site-foot__cols a:hover {
  color: var(--signal);
}

.site-foot__bottom {
  display: flex;
  justify-content: space-between;
  gap: 24px;
  margin-top: 64px;
  font-size: var(--step-3);
  color: #a9a398;
}

.site-foot__bottom p {
  max-width: 72ch;
}

@media (max-width: 900px) {
  .site-foot__top {
    grid-template-columns: 1fr;
  }

  .site-foot__cols {
    grid-template-columns: 1fr 1fr;
  }

  .site-foot__bottom {
    display: grid;
  }
}

@media (max-width: 560px) {
  .site-foot__cols {
    grid-template-columns: 1fr;
  }
}
</style>
