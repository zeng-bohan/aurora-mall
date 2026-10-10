<script setup lang="ts">
import { useRouter } from 'vue-router'

import { useAuthStore } from '@/stores/auth'
import { useCartStore } from '@/stores/cart'
import { useMyOrdersStore } from '@/stores/myOrders'

const router = useRouter()
const auth = useAuthStore()
const cart = useCartStore()
const myOrders = useMyOrdersStore()

async function onLogout(): Promise<void> {
  await auth.logout()
  // 本地视图一并丢掉：下一个登录的人不该看到上个人的购物车与订单索引
  cart.reset()
  myOrders.forgetAll()
  await router.push({ name: 'login' })
}
</script>

<template>
  <header class="aurora-header">
    <div class="aurora-header__inner">
      <RouterLink class="aurora-brand" to="/products">aurora-mall</RouterLink>

      <nav class="aurora-nav">
        <RouterLink to="/products">商品</RouterLink>
        <RouterLink v-if="auth.isLoggedIn" to="/seckill">秒杀</RouterLink>
        <RouterLink v-if="auth.isLoggedIn" to="/coupons">优惠券</RouterLink>
        <RouterLink v-if="auth.isLoggedIn" to="/orders">我的订单</RouterLink>

        <el-dropdown v-if="auth.isAdmin" trigger="hover">
          <span class="aurora-nav__admin">管理台</span>
          <template #dropdown>
            <el-dropdown-menu>
              <el-dropdown-item @click="router.push({ name: 'admin-products' })">
                商品管理
              </el-dropdown-item>
              <el-dropdown-item @click="router.push({ name: 'admin-coupons' })">
                券模板管理
              </el-dropdown-item>
              <el-dropdown-item @click="router.push({ name: 'admin-seckill' })">
                秒杀活动管理
              </el-dropdown-item>
            </el-dropdown-menu>
          </template>
        </el-dropdown>
      </nav>

      <div class="aurora-actions">
        <RouterLink v-if="auth.isLoggedIn" class="aurora-actions__cart" to="/cart">
          <el-badge :value="cart.totalQuantity" :hidden="cart.totalQuantity === 0">
            <el-button text>购物车</el-button>
          </el-badge>
        </RouterLink>

        <template v-if="auth.isLoggedIn">
          <el-button text @click="onLogout">退出</el-button>
        </template>
        <template v-else>
          <el-button text @click="router.push({ name: 'login' })">登录</el-button>
          <el-button type="primary" @click="router.push({ name: 'register' })">注册</el-button>
        </template>
      </div>
    </div>
  </header>
</template>

<style scoped>
.aurora-header {
  background: #fff;
  border-bottom: 1px solid #e4e7ed;
  position: sticky;
  top: 0;
  z-index: 10;
}

.aurora-header__inner {
  max-width: var(--aurora-page-max);
  margin: 0 auto;
  padding: 0 16px;
  height: 56px;
  display: flex;
  align-items: center;
  gap: 24px;
}

.aurora-brand {
  font-weight: 700;
  font-size: 18px;
  letter-spacing: 0.02em;
}

.aurora-nav {
  display: flex;
  align-items: center;
  gap: 18px;
  flex: 1;
  font-size: 14px;
}

.aurora-nav a {
  color: #606266;
}

.aurora-nav a.router-link-active {
  color: #409eff;
  font-weight: 600;
}

.aurora-nav__admin {
  color: #606266;
  cursor: pointer;
  outline: none;
}

.aurora-actions {
  display: flex;
  align-items: center;
  gap: 8px;
}

.aurora-actions__cart {
  display: inline-flex;
}
</style>
