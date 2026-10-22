import { createRouter, createWebHistory } from 'vue-router'
import type { RouteRecordRaw } from 'vue-router'

import { setSessionExpiredHandler } from '@/api/http'
import { useAuthStore } from '@/stores/auth'

declare module 'vue-router' {
  interface RouteMeta {
    requiresAuth?: boolean
    requiresAdmin?: boolean
    bare?: boolean
    title?: string
  }
}

const routes: RouteRecordRaw[] = [
  {
    path: '/',
    name: 'home',
    component: () => import('@/views/HomeView.vue'),
    meta: { title: '桌面与随身科技' },
  },
  {
    path: '/products',
    name: 'products',
    component: () => import('@/views/ProductListView.vue'),
    meta: { title: '商品目录' },
  },
  {
    path: '/products/:id',
    name: 'product-detail',
    component: () => import('@/views/ProductDetailView.vue'),
    meta: { title: '商品详情' },
  },
  {
    path: '/cart',
    name: 'cart',
    component: () => import('@/views/CartView.vue'),
    meta: { requiresAuth: true, title: '购物车' },
  },
  {
    path: '/checkout',
    name: 'checkout',
    component: () => import('@/views/CheckoutView.vue'),
    meta: { requiresAuth: true, title: '结算' },
  },
  {
    path: '/orders',
    name: 'orders',
    component: () => import('@/views/OrdersView.vue'),
    meta: { requiresAuth: true, title: '我的订单' },
  },
  {
    path: '/orders/:id',
    name: 'order-detail',
    component: () => import('@/views/OrderDetailView.vue'),
    meta: { requiresAuth: true, title: '订单详情' },
  },
  {
    path: '/addresses',
    name: 'addresses',
    component: () => import('@/views/AddressView.vue'),
    meta: { requiresAuth: true, title: '地址簿' },
  },
  {
    path: '/coupons',
    name: 'coupons',
    component: () => import('@/views/CouponsView.vue'),
    meta: { requiresAuth: true, title: '优惠券' },
  },
  {
    path: '/profile',
    name: 'profile',
    component: () => import('@/views/ProfileView.vue'),
    meta: { requiresAuth: true, title: '个人中心' },
  },
  {
    path: '/seckill',
    name: 'seckill',
    component: () => import('@/views/SeckillListView.vue'),
    meta: { requiresAuth: true, title: '限时秒杀' },
  },
  {
    path: '/seckill/:id',
    name: 'seckill-detail',
    component: () => import('@/views/SeckillDetailView.vue'),
    meta: { requiresAuth: true, title: '秒杀详情' },
  },
  {
    path: '/login',
    name: 'login',
    component: () => import('@/views/LoginView.vue'),
    meta: { bare: true, title: '登录' },
  },
  {
    path: '/register',
    name: 'register',
    component: () => import('@/views/RegisterView.vue'),
    meta: { bare: true, title: '注册' },
  },
  {
    path: '/admin',
    component: () => import('@/components/AdminShell.vue'),
    meta: { requiresAuth: true, requiresAdmin: true },
    children: [
      { path: '', redirect: { name: 'admin-dashboard' } },
      {
        path: 'dashboard',
        name: 'admin-dashboard',
        component: () => import('@/views/admin/AdminDashboardView.vue'),
        meta: { title: '运营概览' },
      },
      {
        path: 'orders',
        name: 'admin-orders',
        component: () => import('@/views/admin/AdminOrdersView.vue'),
        meta: { title: '订单管理' },
      },
      {
        path: 'orders/:id',
        name: 'admin-order-detail',
        component: () => import('@/views/admin/AdminOrderDetailView.vue'),
        meta: { title: '订单详情' },
      },
      {
        path: 'refunds',
        name: 'admin-refunds',
        component: () => import('@/views/admin/AdminRefundsView.vue'),
        meta: { title: '退款审批' },
      },
      {
        path: 'products',
        name: 'admin-products',
        component: () => import('@/views/admin/AdminProductsView.vue'),
        meta: { title: '商品管理' },
      },
      {
        path: 'products/new',
        name: 'admin-product-new',
        component: () => import('@/views/admin/AdminProductEditView.vue'),
        meta: { title: '新建商品' },
      },
      {
        path: 'products/:id',
        name: 'admin-product-edit',
        component: () => import('@/views/admin/AdminProductEditView.vue'),
        meta: { title: '编辑商品' },
      },
      {
        path: 'catalog',
        name: 'admin-catalog',
        component: () => import('@/views/admin/AdminCatalogView.vue'),
        meta: { title: '分类与属性' },
      },
      {
        path: 'inventory',
        name: 'admin-inventory',
        component: () => import('@/views/admin/AdminInventoryView.vue'),
        meta: { title: '库存' },
      },
      {
        path: 'coupons',
        name: 'admin-coupons',
        component: () => import('@/views/admin/AdminCouponsView.vue'),
        meta: { title: '优惠券' },
      },
      {
        path: 'seckill',
        name: 'admin-seckill',
        component: () => import('@/views/admin/AdminSeckillView.vue'),
        meta: { title: '秒杀活动' },
      },
      {
        path: 'users',
        name: 'admin-users',
        component: () => import('@/views/admin/AdminUsersView.vue'),
        meta: { title: '用户' },
      },
    ],
  },
  {
    path: '/forbidden',
    name: 'forbidden',
    component: () => import('@/views/ForbiddenView.vue'),
    meta: { title: '无权访问' },
  },
  {
    path: '/:pathMatch(.*)*',
    name: 'not-found',
    component: () => import('@/views/NotFoundView.vue'),
    meta: { title: '页面不存在' },
  },
]

export const router = createRouter({
  history: createWebHistory(),
  routes,
  scrollBehavior: () => ({ top: 0 }),
})

router.beforeEach(async (to) => {
  if (!to.meta.requiresAuth && !to.meta.requiresAdmin) {
    return true
  }
  const auth = useAuthStore()
  if (!auth.isLoggedIn) {
    return { name: 'login', query: { redirect: to.fullPath } }
  }
  if (!auth.user) {
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
  document.title = to.meta.title ? `${to.meta.title} · AURORA` : 'AURORA'
})

setSessionExpiredHandler(() => {
  const auth = useAuthStore()
  auth.forget()
  const current = router.currentRoute.value
  if (current.name === 'login') {
    return
  }
  void router.replace({ name: 'login', query: { redirect: current.fullPath } })
})
