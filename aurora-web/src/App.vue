<script setup lang="ts">
import { computed, onMounted, watch } from 'vue'
import { useRoute } from 'vue-router'

import SiteFooter from '@/components/SiteFooter.vue'
import SiteHeader from '@/components/SiteHeader.vue'
import { useAuthStore } from '@/stores/auth'
import { useCartStore } from '@/stores/cart'

const route = useRoute()
const auth = useAuthStore()
const cart = useCartStore()

const isAdmin = computed(() => route.path.startsWith('/admin'))
const isBare = computed(() => Boolean(route.meta.bare))

watch(
  () => auth.isLoggedIn,
  (loggedIn) => {
    if (!loggedIn) {
      cart.reset()
    }
  },
)

onMounted(async () => {
  if (!auth.isLoggedIn) {
    return
  }
  await auth.loadIdentity()
  if (auth.isLoggedIn) {
    void cart.load().catch(() => undefined)
  }
})
</script>

<template>
  <RouterView v-if="isAdmin" />
  <RouterView v-else-if="isBare" />
  <div v-else class="app-shell">
    <SiteHeader />
    <main class="app-main">
      <RouterView />
    </main>
    <SiteFooter />
  </div>
</template>
