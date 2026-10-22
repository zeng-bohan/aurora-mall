<script setup lang="ts">
import { computed, onMounted, watch } from 'vue'
import { useRoute } from 'vue-router'

import AppFooter from '@/components/AppFooter.vue'
import AppHeader from '@/components/AppHeader.vue'
import { useAuthStore } from '@/stores/auth'
import { useCartStore } from '@/stores/cart'

const route = useRoute()
const auth = useAuthStore()
const cart = useCartStore()

const storefrontRoutes = new Set([
  'products',
  'product-detail',
  'seckill',
  'seckill-detail',
  'login',
  'register'
])

const noFooterRoutes = new Set(['login', 'register', 'forbidden', 'not-found'])

const isAdmin = computed(() => route.path.startsWith('/admin'))
const isStorefront = computed(() => storefrontRoutes.has(String(route.name)))
const showFooter = computed(
  () => !isAdmin.value && !noFooterRoutes.has(String(route.name))
)

watch(
  () => auth.isLoggedIn,
  (loggedIn) => {
    // 会话过期与主动登出走同一条路径，本地购物车都必须清掉
    if (!loggedIn) {
      cart.reset()
    }
  }
)

onMounted(() => {
  if (!auth.isLoggedIn) {
    return
  }
  void auth.loadIdentity()
  void cart.load().catch(() => undefined)
})
</script>

<template>
  <RouterView v-if="isAdmin" />
  <div v-else class="app-shell" :class="isStorefront ? 'storefront' : 'app-chrome'">
    <AppHeader />
    <main :class="isStorefront ? 'store-main' : 'app-main'">
      <RouterView />
    </main>
    <AppFooter v-if="showFooter" />
  </div>
</template>
