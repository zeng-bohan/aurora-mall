<script setup lang="ts">
import { ArrowDown, Menu, Search, ShoppingBag, User } from '@element-plus/icons-vue'
import { computed, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'

import { useAuthStore } from '@/stores/auth'
import { useCartStore } from '@/stores/cart'

const route = useRoute()
const router = useRouter()
const auth = useAuthStore()
const cart = useCartStore()

const menuOpen = ref(false)
const userOpen = ref(false)
const keyword = ref('')

const navLinks = computed(() => {
  const links = [
    { to: '/products', label: '商品目录' },
    { to: '/seckill', label: '限时秒杀' },
  ]
  if (auth.isLoggedIn) {
    links.push({ to: '/orders', label: '我的订单' }, { to: '/coupons', label: '优惠券' })
  }
  return links
})

const cartCount = computed(() => cart.totalQuantity)

watch(
  () => route.fullPath,
  () => {
    menuOpen.value = false
    userOpen.value = false
  },
)

function submitSearch(): void {
  const value = keyword.value.trim()
  if (!value) {
    return
  }
  menuOpen.value = false
  void router.push({ name: 'products', query: { keyword: value } })
}

async function logout(): Promise<void> {
  userOpen.value = false
  await auth.logout()
  cart.reset()
  void router.push({ name: 'home' })
}
</script>

<template>
  <header class="site-header">
    <div class="container header-inner">
      <RouterLink class="brand" to="/" aria-label="AURORA 首页">
        <span class="brand__mark" aria-hidden="true" />
        <span class="brand__word">AURORA</span>
        <span class="brand__sub">Desk &amp; Pocket</span>
      </RouterLink>

      <nav class="main-nav" aria-label="主导航">
        <RouterLink v-for="link in navLinks" :key="link.to" :to="link.to" class="nav-link">
          {{ link.label }}
        </RouterLink>
      </nav>

      <div class="header-actions">
        <form class="header-search" role="search" @submit.prevent="submitSearch">
          <el-icon :size="15"><Search /></el-icon>
          <input v-model="keyword" type="search" placeholder="搜索产品" aria-label="搜索产品" />
        </form>

        <RouterLink to="/cart" class="icon-btn cart-link" aria-label="购物车">
          <el-icon :size="19"><ShoppingBag /></el-icon>
          <span v-if="cartCount > 0" class="cart-badge">{{ cartCount > 99 ? '99+' : cartCount }}</span>
        </RouterLink>

        <div v-if="auth.isLoggedIn" class="user-menu">
          <button class="user-menu__trigger" type="button" @click="userOpen = !userOpen">
            <el-icon :size="17"><User /></el-icon>
            <span>{{ auth.displayName }}</span>
            <el-icon :size="12"><ArrowDown /></el-icon>
          </button>
          <div v-if="userOpen" class="user-menu__panel">
            <RouterLink class="user-menu__item" to="/profile">个人中心</RouterLink>
            <RouterLink class="user-menu__item" to="/orders">我的订单</RouterLink>
            <RouterLink class="user-menu__item" to="/addresses">地址簿</RouterLink>
            <RouterLink v-if="auth.isAdmin" class="user-menu__item" to="/admin">运营后台</RouterLink>
            <button class="user-menu__item" type="button" @click="logout">退出登录</button>
          </div>
        </div>
        <template v-else>
          <RouterLink class="btn btn--ghost btn--sm" to="/login">登录</RouterLink>
          <RouterLink class="btn btn--primary btn--sm" to="/register">注册</RouterLink>
        </template>

        <button class="icon-btn mobile-toggle" type="button" aria-label="打开菜单" @click="menuOpen = !menuOpen">
          <el-icon :size="20"><Menu /></el-icon>
        </button>
      </div>
    </div>

    <div v-if="menuOpen" class="mobile-panel">
      <form class="header-search" style="display: flex; width: 100%; margin-bottom: 10px" @submit.prevent="submitSearch">
        <el-icon :size="15"><Search /></el-icon>
        <input v-model="keyword" type="search" placeholder="搜索产品" aria-label="搜索产品" />
      </form>
      <RouterLink v-for="link in navLinks" :key="link.to" :to="link.to">{{ link.label }}</RouterLink>
      <RouterLink v-if="auth.isLoggedIn" to="/profile">个人中心</RouterLink>
      <RouterLink v-if="auth.isLoggedIn" to="/addresses">地址簿</RouterLink>
      <RouterLink v-if="auth.isAdmin" to="/admin">运营后台</RouterLink>
      <RouterLink v-if="!auth.isLoggedIn" to="/login">登录</RouterLink>
      <RouterLink v-if="!auth.isLoggedIn" to="/register">注册</RouterLink>
      <button v-if="auth.isLoggedIn" type="button" style="padding: 12px 0; text-align: left" @click="logout">
        退出登录
      </button>
    </div>
  </header>
</template>
