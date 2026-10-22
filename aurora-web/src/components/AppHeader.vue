<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'

import { useAuthStore } from '@/stores/auth'
import { useCartStore } from '@/stores/cart'

const route = useRoute()
const router = useRouter()
const auth = useAuthStore()
const cart = useCartStore()
const menuOpen = ref(false)

const cartCount = computed(() => cart.totalQuantity)

const mainLinks = computed(() => {
  const links = [
    { to: '/products', label: '目录' },
    { to: '/seckill', label: '秒杀', auth: true },
    { to: '/coupons', label: '优惠券', auth: true },
    { to: '/orders', label: '订单', auth: true }
  ]
  return links.filter((link) => !link.auth || auth.isLoggedIn)
})

watch(
  () => route.fullPath,
  () => {
    menuOpen.value = false
  }
)

async function onLogout(): Promise<void> {
  await auth.logout()
  cart.reset()
  await router.push({ name: 'login' })
}
</script>

<template>
  <header class="site-head">
    <div class="site-head__inner container">
      <RouterLink class="brand" to="/products" aria-label="AURORA 极光选品首页">
        <svg class="brand__mark" viewBox="0 0 24 24" aria-hidden="true">
          <path d="M3 20 12 3l2.4 6H10.2l-3.4 8.4Z" fill="var(--signal)" />
          <path d="M12 20 18.4 3H23L16.6 20Z" fill="var(--ink)" />
        </svg>
        <span class="brand__word display">AURORA</span>
      </RouterLink>

      <nav class="desk-nav" aria-label="主导航">
        <RouterLink
          v-for="link in mainLinks"
          :key="link.to"
          :to="link.to"
          class="desk-nav__link"
        >
          {{ link.label }}
        </RouterLink>
        <RouterLink v-if="auth.isAdmin" to="/admin/products" class="desk-nav__link">
          运营台
        </RouterLink>
      </nav>

      <div class="head-actions">
        <RouterLink v-if="auth.isLoggedIn" to="/cart" class="cart-link">
          购物车
          <span class="cart-link__count num">{{ cartCount }}</span>
        </RouterLink>

        <template v-if="auth.isLoggedIn">
          <button type="button" class="text-action" @click="onLogout">退出</button>
        </template>
        <template v-else>
          <RouterLink to="/login" class="text-action">登录</RouterLink>
          <RouterLink to="/register" class="btn btn--signal btn--sm">注册</RouterLink>
        </template>

        <button
          type="button"
          class="menu-toggle"
          :aria-expanded="menuOpen"
          aria-controls="mobile-nav"
          @click="menuOpen = !menuOpen"
        >
          菜单
        </button>
      </div>
    </div>

    <div v-if="menuOpen" id="mobile-nav" class="mobile-nav">
      <RouterLink v-for="link in mainLinks" :key="link.to" :to="link.to">
        {{ link.label }}
      </RouterLink>
      <RouterLink v-if="auth.isAdmin" to="/admin/products">运营台</RouterLink>
      <RouterLink v-if="auth.isLoggedIn" to="/cart">购物车（{{ cartCount }}）</RouterLink>
    </div>
  </header>
</template>

<style scoped>
.site-head {
  position: sticky;
  top: 0;
  z-index: 30;
  background: var(--stone);
}

.site-head__inner {
  display: flex;
  align-items: center;
  gap: 28px;
  min-height: 64px;
}

.brand {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  flex: none;
}

.brand__mark {
  width: 22px;
  height: 22px;
}

.brand__word {
  font-size: 24px;
  letter-spacing: 0.01em;
}

.desk-nav {
  display: flex;
  align-items: center;
  gap: 20px;
  flex: 1;
}

.desk-nav__link {
  position: relative;
  display: inline-flex;
  align-items: center;
  min-height: 44px;
  font-size: var(--step-2);
  font-weight: 600;
  color: var(--ink-2);
}

.desk-nav__link:hover {
  color: var(--signal-deep);
}

.desk-nav__link.router-link-active {
  color: var(--ink);
}

.desk-nav__link.router-link-active::after {
  content: '';
  position: absolute;
  right: 0;
  bottom: 10px;
  left: 0;
  height: 2px;
  background: var(--signal-deep);
}

.head-actions {
  display: flex;
  align-items: center;
  gap: 16px;
  flex: none;
}

.cart-link {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  min-height: 44px;
  font-size: var(--step-2);
  font-weight: 600;
}

.cart-link:hover {
  color: var(--signal-deep);
}

.cart-link__count {
  display: inline-grid;
  place-items: center;
  min-width: 22px;
  height: 22px;
  padding: 0 5px;
  background: var(--ink);
  color: var(--paper);
  font-size: 12px;
  border-radius: 11px;
}

.text-action {
  border: 0;
  background: transparent;
  padding: 0;
  min-height: 44px;
  font-size: var(--step-2);
  font-weight: 600;
  color: var(--ink-2);
}

.text-action:hover {
  color: var(--signal-deep);
}

.menu-toggle {
  display: none;
  min-height: 44px;
  padding: 0 12px;
  border: 1px solid var(--line-strong);
  border-radius: 0;
  background: transparent;
  font-size: var(--step-2);
  font-weight: 600;
}

.mobile-nav {
  display: none;
}

@media (max-width: 900px) {
  .site-head__inner {
    gap: 16px;
  }

  .desk-nav {
    display: none;
  }

  .head-actions {
    margin-left: auto;
  }

  .head-actions .text-action {
    display: none;
  }

  .menu-toggle {
    display: inline-flex;
    align-items: center;
  }

  .mobile-nav {
    display: grid;
    gap: 0;
    padding: 8px var(--gutter) 16px;
    background: var(--stone);
  }

  .mobile-nav a {
    display: flex;
    align-items: center;
    min-height: 48px;
    border-top: 1px solid var(--line);
    font-size: var(--step-3);
    font-weight: 600;
  }
}

@media (max-width: 480px) {
  .brand__word {
    font-size: 22px;
  }

  .cart-link {
    font-size: var(--step-1);
  }

  .cart-link__count {
    min-width: 20px;
    height: 20px;
  }
}
</style>
