<script setup lang="ts">
import { ElMessage } from 'element-plus'
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'

import { tradeApi, type Refund } from '@/api/dtc'
import StatusBadge from '@/components/StatusBadge.vue'
import { useAuthStore } from '@/stores/auth'
import { useCartStore } from '@/stores/cart'
import { formatDateTime } from '@/utils/datetime'
import { formatYuan } from '@/utils/money'

const auth = useAuthStore()
const cart = useCartStore()
const router = useRouter()

const refunds = ref<Refund[]>([])
const loadingRefunds = ref(true)
const initial = computed(() => (auth.displayName || 'A').slice(0, 1).toUpperCase())

async function loadRefunds(): Promise<void> {
  loadingRefunds.value = true
  try {
    const page = await tradeApi.myRefunds({ current: 1, size: 5 })
    refunds.value = page.records
  } catch {
    refunds.value = []
  } finally {
    loadingRefunds.value = false
  }
}

async function logout(): Promise<void> {
  await auth.logout()
  cart.reset()
  ElMessage.success('已退出登录')
  void router.push({ name: 'home' })
}

onMounted(() => {
  void loadRefunds()
})
</script>

<template>
  <div>
    <div class="container page-head">
      <span class="eyebrow">Account</span>
      <h1 class="display">个人中心。</h1>
      <p>管理地址、优惠券和订单，查看退款进度。</p>
    </div>

    <div class="container" style="padding-block: 32px 72px">
      <div class="profile-grid">
        <aside class="profile-card">
          <div class="avatar">{{ initial }}</div>
          <h3 style="margin-top: 14px">{{ auth.displayName }}</h3>
          <p class="hint">@{{ auth.user?.username }}</p>
          <p class="hint" style="margin-top: 4px">
            {{ auth.isAdmin ? '运营管理员' : '普通顾客' }}
            <span v-if="auth.user?.createdAt"> · {{ formatDateTime(auth.user.createdAt) }} 注册</span>
          </p>
          <div class="profile-links">
            <RouterLink class="profile-link" to="/orders">我的订单 <span>→</span></RouterLink>
            <RouterLink class="profile-link" to="/addresses">地址簿 <span>→</span></RouterLink>
            <RouterLink class="profile-link" to="/coupons">优惠券 <span>→</span></RouterLink>
            <RouterLink v-if="auth.isAdmin" class="profile-link" to="/admin">运营后台 <span>→</span></RouterLink>
          </div>
          <button class="btn btn--line btn--block" type="button" style="margin-top: 20px" @click="logout">
            退出登录
          </button>
        </aside>

        <section>
          <div class="detail-panel">
            <div class="checkout-section__head">
              <h3 style="margin: 0">最近退款</h3>
              <RouterLink class="link-arrow" to="/orders">全部订单 →</RouterLink>
            </div>
            <div v-if="loadingRefunds" class="hint">正在读取退款记录…</div>
            <div v-else-if="!refunds.length" class="admin-empty">还没有退款记录。</div>
            <div v-else>
              <div v-for="refund in refunds" :key="refund.id" class="refund-line">
                <div>
                  <div style="display: flex; align-items: center; gap: 8px">
                    <strong>{{ refund.tradeNo }}</strong>
                    <StatusBadge :status="refund.status" />
                    <span v-if="refund.settled" class="tag tag--success">已结算</span>
                  </div>
                  <div class="tiny" style="margin-top: 4px">{{ refund.reason }}</div>
                </div>
                <div style="text-align: right">
                  <div>{{ formatYuan(refund.amount) }}</div>
                  <div class="tiny">{{ formatDateTime(refund.createdAt) }}</div>
                </div>
              </div>
            </div>
          </div>

          <div class="detail-panel">
            <h3>演示说明</h3>
            <p class="hint" style="line-height: 1.8">
              支付与物流为模拟流程，商品图为概念渲染。演示账号：<span class="mono">linyi / Aurora123!</span>；
              运营账号：<span class="mono">admin / Aurora123!</span>。
            </p>
          </div>
        </section>
      </div>
    </div>
  </div>
</template>
