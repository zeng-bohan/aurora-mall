<script setup lang="ts">
import { onMounted, onUnmounted, ref } from 'vue'

import { seckillApi, type SeckillActivity } from '@/api/dtc'
import EmptyState from '@/components/EmptyState.vue'
import StatusBadge from '@/components/StatusBadge.vue'
import { formatCountdown, formatDateTime } from '@/utils/datetime'
import { describeError } from '@/utils/errors'
import { formatYuan } from '@/utils/money'

const activities = ref<SeckillActivity[]>([])
const loading = ref(true)
const error = ref('')
const now = ref(Date.now())
let tick: number | undefined

async function load(): Promise<void> {
  loading.value = true
  error.value = ''
  try {
    activities.value = await seckillApi.list()
  } catch (e) {
    error.value = describeError(e)
  } finally {
    loading.value = false
  }
}

function countdown(activity: SeckillActivity): string {
  if (activity.phase === 'RUNNING') {
    return formatCountdown(activity.endAt, now.value) ?? '即将结束'
  }
  if (activity.phase === 'NOT_STARTED') {
    return formatCountdown(activity.startAt, now.value) ?? '即将开始'
  }
  return '已结束'
}

onMounted(() => {
  void load()
  tick = window.setInterval(() => {
    now.value = Date.now()
  }, 1000)
})

onUnmounted(() => {
  if (tick) {
    window.clearInterval(tick)
  }
})
</script>

<template>
  <div>
    <div class="container page-head">
      <span class="eyebrow">Limited Drop</span>
      <h1 class="display">限时秒杀。</h1>
      <p>活动库存独立分配，不占普通 SKU 库存。中签后生成待支付订单，请在 30 分钟内完成支付。</p>
    </div>

    <div class="container" style="padding-block: 32px 72px">
      <div v-if="loading" class="page-loading">正在读取活动…</div>
      <div v-else-if="error" class="error-banner">{{ error }}</div>
      <EmptyState
        v-else-if="!activities.length"
        title="暂时没有秒杀活动"
        description="运营创建并预热活动后会出现在这里。"
      >
        <RouterLink class="btn btn--line" to="/products">先逛逛商品</RouterLink>
      </EmptyState>
      <div v-else class="seckill-list">
        <RouterLink
          v-for="activity in activities"
          :key="activity.id"
          class="seckill-card"
          :to="`/seckill/${activity.id}`"
        >
          <div class="seckill-card__media">
            <img :src="activity.productImage" :alt="activity.productTitle" />
          </div>
          <div class="seckill-card__body">
            <div style="display: flex; justify-content: space-between; gap: 10px">
              <span class="eyebrow">AURORA Drop</span>
              <StatusBadge :status="activity.phase" />
            </div>
            <h3>{{ activity.title }}</h3>
            <p class="tiny" style="margin-top: 6px">{{ activity.productTitle }}</p>
            <div class="seckill-card__price">
              {{ formatYuan(activity.seckillPrice) }}
              <small v-if="activity.originalPrice > activity.seckillPrice">
                {{ formatYuan(activity.originalPrice) }}
              </small>
            </div>
            <div class="seckill-card__meta">
              <span>{{ activity.phase === 'RUNNING' ? '距结束' : '距开始' }} {{ countdown(activity) }}</span>
              <span>剩余 {{ activity.availableStock }} / {{ activity.totalStock }}</span>
            </div>
            <p class="tiny" style="margin-top: 10px">
              {{ formatDateTime(activity.startAt) }} – {{ formatDateTime(activity.endAt) }}
            </p>
          </div>
        </RouterLink>
      </div>
    </div>
  </div>
</template>
