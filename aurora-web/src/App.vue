<script setup lang="ts">
import { onMounted } from 'vue'

import AppHeader from '@/components/AppHeader.vue'
import { useAuthStore } from '@/stores/auth'
import { useCartStore } from '@/stores/cart'
import { useMyOrdersStore } from '@/stores/myOrders'

const auth = useAuthStore()
const cart = useCartStore()
const myOrders = useMyOrdersStore()

// 刷新页面后令牌还在，但角色、购物车、本地订单索引都得重新装载一遍。
// 这里不 await 到阻塞渲染：加载失败不该让整页白屏，各页面自己也会兜。
onMounted(() => {
  if (!auth.isLoggedIn) {
    return
  }
  void auth.loadIdentity()
  myOrders.load()
  void cart.load().catch(() => undefined)
})
</script>

<template>
  <div class="aurora-shell">
    <AppHeader />
    <main class="aurora-main">
      <RouterView />
    </main>
  </div>
</template>

<style scoped>
.aurora-shell {
  display: flex;
  flex-direction: column;
  min-height: 100%;
}

.aurora-main {
  flex: 1;
}
</style>
