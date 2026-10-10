<template>
  <div class="aurora-page aurora-blank">
    <el-result icon="info" title="页面不存在" sub-title="链接可能已经失效，或者路径写错了。">
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
