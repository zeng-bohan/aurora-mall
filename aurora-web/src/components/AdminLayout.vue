<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'

import { useAuthStore } from '@/stores/auth'

const route = useRoute()
const router = useRouter()
const auth = useAuthStore()
const navOpen = ref(false)

const pageTitle = computed(() => route.meta.title ?? '运营台')
const pageSection = computed(() => {
  if (route.path.startsWith('/admin/coupons')) {
    return '营销'
  }
  if (route.path.startsWith('/admin/seckill')) {
    return '营销'
  }
  return '商品'
})

watch(
  () => route.fullPath,
  () => {
    navOpen.value = false
  }
)

async function logout(): Promise<void> {
  await auth.logout()
  await router.push({ name: 'login' })
}
</script>

<template>
  <div class="admin-shell">
    <div v-if="navOpen" class="admin-scrim" aria-hidden="true" @click="navOpen = false" />

    <aside id="admin-nav-panel" class="admin-side" :class="{ 'admin-side--open': navOpen }">
      <div class="admin-brand">
        <RouterLink to="/admin/products" class="admin-brand__link" aria-label="AURORA 运营台首页">
          <svg class="admin-brand__mark" viewBox="0 0 24 24" aria-hidden="true">
            <path d="M3 20 12 3l2.4 6H10.2l-3.4 8.4Z" fill="var(--signal)" />
            <path d="M12 20 18.4 3H23L16.6 20Z" fill="var(--ink)" />
          </svg>
          <span class="admin-brand__name display">AURORA</span>
        </RouterLink>
        <span class="admin-brand__scope">运营台</span>
      </div>

      <nav class="admin-nav" aria-label="运营台导航">
        <p class="admin-nav__label">商品</p>
        <RouterLink to="/admin/products" class="admin-nav__item">
          <svg class="admin-nav__glyph" viewBox="0 0 20 20" aria-hidden="true">
            <path d="M3.5 6.5 10 3l6.5 3.5v7L10 17l-6.5-3.5zM3.5 6.5 10 10m6.5-3.5L10 10m0 0v7" />
          </svg>
          商品管理
        </RouterLink>

        <p class="admin-nav__label">营销</p>
        <RouterLink to="/admin/coupons" class="admin-nav__item">
          <svg class="admin-nav__glyph" viewBox="0 0 20 20" aria-hidden="true">
            <path d="M3 6.5h14v2a1.8 1.8 0 0 0 0 3.6v1.4H3v-1.4a1.8 1.8 0 0 0 0-3.6zM8 6.5v7" />
          </svg>
          券模板
        </RouterLink>
        <RouterLink to="/admin/seckill" class="admin-nav__item">
          <svg class="admin-nav__glyph" viewBox="0 0 20 20" aria-hidden="true">
            <circle cx="10" cy="10" r="7" />
            <path d="M10 6v4.2l2.8 1.8" />
          </svg>
          秒杀活动
        </RouterLink>
      </nav>

      <div class="admin-side__foot">
        <RouterLink class="admin-back" to="/products">返回店铺前台</RouterLink>
        <div class="admin-user">
          <span class="admin-user__name">{{ auth.isAdmin ? '管理员账号' : '受限账号' }}</span>
          <button type="button" class="admin-user__out" @click="logout">退出</button>
        </div>
      </div>
    </aside>

    <div class="admin-frame">
      <header class="admin-top">
        <button
          type="button"
          class="admin-menu"
          :aria-expanded="navOpen"
          aria-controls="admin-nav-panel"
          @click="navOpen = !navOpen"
        >
          菜单
        </button>
        <nav class="admin-crumbs" aria-label="面包屑">
          <span>{{ pageSection }}</span>
          <span class="admin-crumbs__sep" aria-hidden="true">/</span>
          <span class="admin-crumbs__current">{{ pageTitle }}</span>
        </nav>
        <div class="admin-top__end">
          <RouterLink class="admin-top__link" to="/products">查看店铺</RouterLink>
          <button type="button" class="admin-top__logout" @click="logout">退出</button>
        </div>
      </header>

      <main class="admin-canvas">
        <RouterView />
      </main>
    </div>
  </div>
</template>

<style scoped>
.admin-shell {
  display: grid;
  grid-template-columns: 232px minmax(0, 1fr);
  min-height: 100vh;
  background: var(--stone);
}

.admin-side {
  position: sticky;
  top: 0;
  display: flex;
  flex-direction: column;
  height: 100vh;
  padding: 20px 14px 18px;
  background: var(--stone);
  border-right: 1px solid var(--line);
  z-index: 40;
}

.admin-brand {
  display: flex;
  align-items: baseline;
  gap: 8px;
  padding: 0 10px 22px;
}

.admin-brand__link {
  display: inline-flex;
  align-items: center;
  gap: 7px;
}

.admin-brand__mark {
  width: 19px;
  height: 19px;
}

.admin-brand__name {
  font-size: 21px;
  letter-spacing: 0.01em;
}

.admin-brand__scope {
  padding-left: 8px;
  border-left: 1px solid var(--line-strong);
  font-size: 12px;
  color: var(--muted);
  letter-spacing: 0.04em;
}

.admin-nav {
  display: grid;
  gap: 2px;
}

.admin-nav__label {
  margin: 14px 10px 6px;
  font-size: 12px;
  color: var(--muted);
}

.admin-nav__label:first-child {
  margin-top: 0;
}

.admin-nav__item {
  display: flex;
  align-items: center;
  gap: 9px;
  min-height: 38px;
  padding: 0 10px;
  border-radius: 5px;
  font-size: 14px;
  font-weight: 500;
  color: var(--ink-3);
}

.admin-nav__item:hover {
  background: var(--stone-2);
  color: var(--ink);
}

.admin-nav__item.router-link-active {
  background: var(--stone-2);
  color: var(--ink);
  font-weight: 600;
  box-shadow: inset 2px 0 0 var(--ink);
}

.admin-nav__glyph {
  flex: none;
  width: 17px;
  height: 17px;
  fill: none;
  stroke: currentColor;
  stroke-width: 1.4;
  color: var(--muted);
}

.admin-nav__item:hover .admin-nav__glyph,
.admin-nav__item.router-link-active .admin-nav__glyph {
  color: var(--ink);
}

.admin-side__foot {
  display: grid;
  gap: 12px;
  margin-top: auto;
  padding: 14px 10px 0;
  border-top: 1px solid var(--line);
}

.admin-back {
  font-size: 13px;
  color: var(--muted);
}

.admin-back:hover {
  color: var(--ink);
}

.admin-user {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
}

.admin-user__name {
  overflow: hidden;
  font-size: 13px;
  color: var(--ink-2);
  text-overflow: ellipsis;
  white-space: nowrap;
}

.admin-user__out,
.admin-top__logout {
  border: 0;
  background: transparent;
  padding: 0;
  min-height: 32px;
  font-size: 13px;
  color: var(--muted);
}

.admin-user__out:hover,
.admin-top__logout:hover {
  color: var(--negative);
}

.admin-frame {
  display: flex;
  flex-direction: column;
  min-width: 0;
}

.admin-top {
  position: sticky;
  top: 0;
  z-index: 30;
  display: flex;
  align-items: center;
  gap: 20px;
  min-height: 56px;
  padding: 0 32px;
  background: var(--paper);
  border-bottom: 1px solid var(--line);
}

.admin-menu {
  display: none;
  min-height: 36px;
  padding: 0 10px;
  border: 1px solid var(--line-strong);
  border-radius: 4px;
  background: var(--paper);
  font-size: 13px;
}

.admin-crumbs {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 13px;
  color: var(--muted);
}

.admin-crumbs__sep {
  color: var(--line-strong);
}

.admin-crumbs__current {
  color: var(--ink);
  font-weight: 600;
}

.admin-top__end {
  display: flex;
  align-items: center;
  gap: 18px;
  margin-left: auto;
  font-size: 13px;
}

.admin-top__link {
  color: var(--muted);
}

.admin-top__link:hover {
  color: var(--ink);
}

.admin-canvas {
  flex: 1;
  min-width: 0;
  padding: 28px 32px 64px;
}

.admin-scrim {
  display: none;
}

@media (max-width: 900px) {
  .admin-shell {
    grid-template-columns: 1fr;
  }

  .admin-side {
    position: fixed;
    top: 0;
    bottom: 0;
    left: 0;
    width: 248px;
    height: 100dvh;
    transform: translateX(-100%);
    transition: transform 180ms ease;
  }

  .admin-side--open {
    transform: translateX(0);
  }

  .admin-scrim {
    display: block;
    position: fixed;
    inset: 0;
    z-index: 35;
    background: rgb(7 16 20 / 35%);
  }

  .admin-menu {
    display: inline-flex;
    align-items: center;
  }

  .admin-top {
    padding: 0 16px;
    gap: 12px;
  }

  .admin-top__end .admin-top__link {
    display: none;
  }

  .admin-canvas {
    padding: 20px 16px 48px;
  }
}
</style>
