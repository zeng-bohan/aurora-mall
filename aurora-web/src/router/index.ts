import { createRouter, createWebHistory } from 'vue-router'
import type { RouteRecordRaw } from 'vue-router'

import { setSessionExpiredHandler } from '@/api/http'
import { useAuthStore } from '@/stores/auth'

declare module 'vue-router' {
  interface RouteMeta {
    /** 需要登录；未登录时跳登录页并带回来路 */
    requiresAuth?: boolean
    /** 需要 ADMIN；隐含 requiresAuth */
    requiresAdmin?: boolean
    title?: string
  }
}

const routes: RouteRecordRaw[] = [
  { path: '/', redirect: '/products' },

  {
    path: '/login',
    name: 'login',
    component: () => import('@/views/LoginView.vue'),
    meta: { title: '登录' }
  },
  {
    path: '/register',
    name: 'register',
    component: () => import('@/views/RegisterView.vue'),
    meta: { title: '注册' }
  },

  // 商品读对游客开放（网关白名单里 GET /api/product/** 免鉴权）
  {
    path: '/products',
    name: 'products',
    component: () => import('@/views/ProductListView.vue'),
    meta: { title: '商品' }
  },
  {
    path: '/products/:id',
    name: 'product-detail',
    component: () => import('@/views/ProductDetailView.vue'),
    meta: { title: '商品详情' }
  },

  {
    path: '/cart',
    name: 'cart',
    component: () => import('@/views/CartView.vue'),
    meta: { requiresAuth: true, title: '购物车' }
  },
  {
    path: '/checkout',
    name: 'checkout',
    component: () => import('@/views/CheckoutView.vue'),
    meta: { requiresAuth: true, title: '结算' }
  },
  {
    path: '/orders',
    name: 'orders',
    component: () => import('@/views/OrdersView.vue'),
    meta: { requiresAuth: true, title: '我的订单' }
  },
  {
    path: '/orders/:id',
    name: 'order-detail',
    component: () => import('@/views/OrderDetailView.vue'),
    meta: { requiresAuth: true, title: '订单详情' }
  },
  {
    path: '/coupons',
    name: 'coupons',
    component: () => import('@/views/CouponsView.vue'),
    meta: { requiresAuth: true, title: '优惠券' }
  },

  // 秒杀活动列表也不在网关白名单里，同样要登录
  {
    path: '/seckill',
    name: 'seckill',
    component: () => import('@/views/SeckillListView.vue'),
    meta: { requiresAuth: true, title: '秒杀' }
  },
  {
    path: '/seckill/:id',
    name: 'seckill-detail',
    component: () => import('@/views/SeckillDetailView.vue'),
    meta: { requiresAuth: true, title: '秒杀详情' }
  },

  {
    path: '/admin/products',
    name: 'admin-products',
    component: () => import('@/views/admin/AdminProductsView.vue'),
    meta: { requiresAuth: true, requiresAdmin: true, title: '商品管理' }
  },
  {
    path: '/admin/coupons',
    name: 'admin-coupons',
    component: () => import('@/views/admin/AdminCouponsView.vue'),
    meta: { requiresAuth: true, requiresAdmin: true, title: '券模板管理' }
  },
  {
    path: '/admin/seckill',
    name: 'admin-seckill',
    component: () => import('@/views/admin/AdminSeckillView.vue'),
    meta: { requiresAuth: true, requiresAdmin: true, title: '秒杀活动管理' }
  },

  {
    path: '/forbidden',
    name: 'forbidden',
    component: () => import('@/views/ForbiddenView.vue'),
    meta: { title: '无权访问' }
  },
  {
    path: '/:pathMatch(.*)*',
    name: 'not-found',
    component: () => import('@/views/NotFoundView.vue'),
    meta: { title: '页面不存在' }
  }
]

export const router = createRouter({
  history: createWebHistory(),
  routes,
  scrollBehavior: () => ({ top: 0 })
})

router.beforeEach(async (to) => {
  if (!to.meta.requiresAuth && !to.meta.requiresAdmin) {
    return true
  }
  const auth = useAuthStore()
  if (!auth.isLoggedIn) {
    return { name: 'login', query: { redirect: to.fullPath } }
  }
  // 刷新页面后令牌还在、role 还没补齐（role 只能异步从 /user/me 取）
  if (auth.role === null) {
    await auth.loadIdentity()
    if (!auth.isLoggedIn) {
      return { name: 'login', query: { redirect: to.fullPath } }
    }
  }
  if (to.meta.requiresAdmin && !auth.isAdmin) {
    return { name: 'forbidden' }
  }
  return true
})

router.afterEach((to) => {
  document.title = to.meta.title ? `${to.meta.title} · aurora-mall` : 'aurora-mall'
})

// 令牌失效（401 且刷新失败）时把用户送回登录页，并记住原来要去哪
setSessionExpiredHandler(() => {
  const auth = useAuthStore()
  auth.forget()
  const current = router.currentRoute.value
  if (current.name === 'login') {
    return
  }
  void router.replace({ name: 'login', query: { redirect: current.fullPath } })
})
