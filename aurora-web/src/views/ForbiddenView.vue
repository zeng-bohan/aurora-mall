<template>
  <div class="app-page">
    <div class="empty-state">
      <h1>无权访问</h1>
      <p>当前账户没有这个页面的权限。运营台只对 ADMIN 角色开放；接口侧也会再次校验。</p>
      <RouterLink class="btn" to="/products">回到目录</RouterLink>
    </div>
  </div>
</template>
