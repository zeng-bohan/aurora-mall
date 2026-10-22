<script setup lang="ts">
import {
  Back,
  Box,
  Collection,
  DataLine,
  Goods,
  RefreshLeft,
  Ticket,
  Timer,
  User,
} from '@element-plus/icons-vue'
import { computed } from 'vue'
import { useRoute, useRouter } from 'vue-router'

import { useAuthStore } from '@/stores/auth'

const route = useRoute()
const router = useRouter()
const auth = useAuthStore()

const groups = [
  {
    label: '运营',
    items: [
      { to: '/admin/dashboard', label: '运营概览', icon: DataLine },
      { to: '/admin/orders', label: '订单管理', icon: Box },
      { to: '/admin/refunds', label: '退款审批', icon: RefreshLeft },
    ],
  },
  {
    label: '商品',
    items: [
      { to: '/admin/products', label: '商品管理', icon: Goods },
      { to: '/admin/catalog', label: '分类与属性', icon: Collection },
      { to: '/admin/inventory', label: '库存', icon: Box },
    ],
  },
  {
    label: '营销',
    items: [
      { to: '/admin/coupons', label: '优惠券', icon: Ticket },
      { to: '/admin/seckill', label: '秒杀活动', icon: Timer },
    ],
  },
  {
    label: '用户',
    items: [{ to: '/admin/users', label: '用户列表', icon: User }],
  },
]

const initial = computed(() => (auth.displayName || 'A').slice(0, 1).toUpperCase())
const title = computed(() => String(route.meta.title ?? '运营后台'))

function backToStore(): void {
  void router.push('/')
}

async function logout(): Promise<void> {
  await auth.logout()
  await router.push({ name: 'login' })
}
</script>

<template>
  <div class="admin-shell">
    <aside class="admin-sidebar">
      <RouterLink class="admin-brand" to="/admin/dashboard">
        <span class="brand__mark" aria-hidden="true" />
        <span class="brand__word">AURORA</span>
        <span class="admin-brand__label">OPS</span>
      </RouterLink>

      <nav class="admin-nav">
        <template v-for="group in groups" :key="group.label">
          <div class="admin-nav__group">{{ group.label }}</div>
          <RouterLink
            v-for="item in group.items"
            :key="item.to"
            class="admin-nav__link"
            :to="item.to"
            :title="item.label"
            :aria-label="item.label"
          >
            <el-icon class="admin-nav__icon"><component :is="item.icon" /></el-icon>
            <span>{{ item.label }}</span>
          </RouterLink>
        </template>
      </nav>

      <div class="admin-sidebar__foot">
        <div class="admin-user">
          <div class="admin-user__avatar">{{ initial }}</div>
          <div class="admin-user__meta">
            <div class="admin-user__name">{{ auth.displayName }}</div>
            <div class="admin-user__role">运营管理员</div>
          </div>
        </div>
        <button class="admin-nav__link" type="button" style="width: 100%" @click="backToStore">
          <el-icon class="admin-nav__icon"><Back /></el-icon>
          <span>返回商城</span>
        </button>
        <button class="admin-nav__link" type="button" style="width: 100%" @click="logout">
          <el-icon class="admin-nav__icon"><User /></el-icon>
          <span>退出登录</span>
        </button>
      </div>
    </aside>

    <main class="admin-main">
      <div class="admin-topbar">
        <div class="admin-topbar__crumb">
          <span>AURORA OPS</span>
          <span>/</span>
          <strong>{{ title }}</strong>
        </div>
        <div class="admin-topbar__actions">
          <RouterLink class="btn btn--ghost btn--sm" to="/">查看商城</RouterLink>
        </div>
      </div>
      <div class="admin-content">
        <RouterView />
      </div>
    </main>
  </div>
</template>
