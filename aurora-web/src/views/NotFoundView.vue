<template>
  <div class="container section">
    <div class="empty">
      <div class="empty__title">页面不存在</div>
      <p>链接可能已经失效，或者地址输入有误。</p>
      <div style="display: flex; gap: 10px">
        <RouterLink class="btn btn--primary" to="/">返回首页</RouterLink>
        <RouterLink class="btn btn--line" to="/products">浏览商品</RouterLink>
      </div>
    </div>
  </div>
</template>
