<template>
  <div class="container section">
    <div class="empty">
      <div class="empty__title">这里需要运营权限</div>
      <p>当前账号不是管理员。请使用运营账号登录后再进入后台。</p>
      <div style="display: flex; gap: 10px">
        <RouterLink class="btn btn--primary" to="/login">切换账号</RouterLink>
        <RouterLink class="btn btn--line" to="/">返回商城</RouterLink>
      </div>
    </div>
  </div>
</template>
