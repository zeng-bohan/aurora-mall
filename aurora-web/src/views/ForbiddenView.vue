<template>
  <div class="aurora-page aurora-blank">
    <el-result icon="warning" title="无权访问" sub-title="这个页面需要管理员角色。">
      <template #extra>
        <el-button type="primary" @click="$router.replace('/products')">回到商品页</el-button>
      </template>
    </el-result>
  </div>
</template>

<style scoped>
.aurora-blank {
  padding-top: 48px;
}
</style>
