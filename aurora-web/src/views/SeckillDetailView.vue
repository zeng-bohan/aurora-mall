<script setup lang="ts">
import { ElMessage } from 'element-plus'
import { computed, onMounted, onUnmounted, ref, watch } from 'vue'
import { useRoute } from 'vue-router'

import { seckillApi, type SeckillActivity, type SeckillBuy } from '@/api/dtc'
import { ApiError, ErrorCode } from '@/api/types'
import EmptyState from '@/components/EmptyState.vue'
import StatusBadge from '@/components/StatusBadge.vue'
import { formatCountdown, formatDateTime } from '@/utils/datetime'
import { describeError } from '@/utils/errors'
import { formatYuan } from '@/utils/money'

const route = useRoute()
const activity = ref<SeckillActivity | null>(null)
const result = ref<SeckillBuy | null>(null)
const loading = ref(true)
const buying = ref(false)
const polling = ref(false)
const error = ref('')
const now = ref(Date.now())
let tick: number | undefined

const countdown = computed(() => {
  if (!activity.value) {
    return ''
  }
  if (activity.value.phase === 'RUNNING') {
    return formatCountdown(activity.value.endAt, now.value) ?? '即将结束'
  }
  if (activity.value.phase === 'NOT_STARTED') {
    return formatCountdown(activity.value.startAt, now.value) ?? '即将开始'
  }
  return ''
})

const canBuy = computed(
  () =>
    activity.value?.phase === 'RUNNING' &&
    activity.value.availableStock > 0 &&
    !buying.value &&
    result.value?.status !== 'PLACED',
)

async function load(): Promise<void> {
  loading.value = true
  error.value = ''
  result.value = null
  try {
    activity.value = await seckillApi.detail(Number(route.params.id))
    try {
      result.value = await seckillApi.mine(activity.value.id)
      if (result.value.status === 'QUEUED') {
        void pollResult()
      }
    } catch (e) {
      if (!(e instanceof ApiError && e.code === ErrorCode.NOT_FOUND)) {
        throw e
      }
    }
  } catch (e) {
    error.value = describeError(e)
    activity.value = null
  } finally {
    loading.value = false
  }
}

async function pollResult(): Promise<void> {
  if (!activity.value || polling.value) {
    return
  }
  polling.value = true
  try {
    for (let i = 0; i < 20; i += 1) {
      await new Promise((resolve) => window.setTimeout(resolve, 1500))
      try {
        const current = await seckillApi.mine(activity.value.id)
        result.value = current
        if (current.status === 'PLACED') {
          ElMessage.success('已中签，订单已生成')
          return
        }
      } catch (e) {
        if (e instanceof ApiError && e.code === ErrorCode.NOT_FOUND) {
          continue
        }
        error.value = describeError(e)
        return
      }
    }
    error.value = '排队时间较长，请稍后到订单列表查看结果。'
  } finally {
    polling.value = false
  }
}

async function buy(): Promise<void> {
  if (!activity.value || !canBuy.value) {
    return
  }
  buying.value = true
  error.value = ''
  try {
    result.value = await seckillApi.buy(activity.value.id)
    if (result.value.status === 'PLACED' && result.value.tradeId) {
      ElMessage.success('已中签，订单已生成')
    } else {
      ElMessage.info('已受理，正在排队生成订单')
      void pollResult()
    }
    activity.value = await seckillApi.detail(activity.value.id)
  } catch (e) {
    error.value = describeError(e)
  } finally {
    buying.value = false
  }
}

watch(
  () => route.params.id,
  () => {
    void load()
  },
  { immediate: true },
)

onMounted(() => {
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
    <div class="container breadcrumb">
      <RouterLink to="/">首页</RouterLink>
      <span>/</span>
      <RouterLink to="/seckill">限时秒杀</RouterLink>
      <span>/</span>
      <span>{{ activity?.title ?? '活动详情' }}</span>
    </div>

    <div v-if="loading" class="container page-loading">正在读取活动…</div>
    <div v-else-if="error && !activity" class="container">
      <EmptyState title="活动无法打开" :description="error">
        <RouterLink class="btn btn--line" to="/seckill">返回秒杀列表</RouterLink>
      </EmptyState>
    </div>

    <div v-else-if="activity" class="container seckill-detail">
      <div class="seckill-detail__media">
        <img :src="activity.productImage" :alt="activity.productTitle" />
      </div>
      <div>
        <div style="display: flex; align-items: center; gap: 10px">
          <span class="eyebrow">AURORA Limited Drop</span>
          <StatusBadge :status="activity.phase" />
        </div>
        <h1 class="display" style="margin-top: 12px">{{ activity.title }}</h1>
        <p class="product-info__subtitle">{{ activity.productTitle }}</p>

        <div class="price-row">
          <span class="price-row__amount">{{ formatYuan(activity.seckillPrice) }}</span>
          <span v-if="activity.originalPrice > activity.seckillPrice" class="price-row__note">
            日常价 {{ formatYuan(activity.originalPrice) }}
          </span>
        </div>

        <div class="stock-line">
          <span class="stock-dot" :class="{ 'stock-dot--out': activity.availableStock <= 0 }" />
          剩余 {{ activity.availableStock }} / {{ activity.totalStock }} 件
          <span v-if="activity.phase === 'RUNNING'">· 距结束 {{ countdown }}</span>
          <span v-else-if="activity.phase === 'NOT_STARTED'">· 距开始 {{ countdown }}</span>
        </div>

        <p class="hint" style="margin-top: 8px">
          {{ formatDateTime(activity.startAt) }} – {{ formatDateTime(activity.endAt) }}
        </p>

        <div v-if="result" class="seckill-result">
          <div v-if="result.status === 'PLACED'">
            已中签，订单
            <RouterLink class="link-arrow" :to="`/orders/${result.tradeId}`">查看并支付 →</RouterLink>
          </div>
          <div v-else>已受理，正在排队生成订单。结果会在下面的状态中更新。</div>
        </div>
        <p v-if="error" class="error-text" style="margin-top: 12px">{{ error }}</p>

        <div class="buy-row" style="grid-template-columns: 1fr">
          <button class="btn btn--accent" type="button" :disabled="!canBuy" @click="buy">
            {{
              buying
                ? '提交中…'
                : activity.phase === 'RUNNING'
                  ? activity.availableStock > 0
                    ? '立即抢购'
                    : '已抢完'
                  : activity.phase === 'NOT_STARTED'
                    ? '活动未开始'
                    : '活动已结束'
            }}
          </button>
        </div>

        <ul class="assurance-list">
          <li>秒杀订单不使用优惠券，也不收取运费。</li>
          <li>中签后生成统一父订单，请在 30 分钟内完成支付。</li>
          <li>未支付订单超时会关闭，并归还活动库存。</li>
          <li>发货前支持整单退款；退款完成后活动库存会归还。</li>
        </ul>
      </div>
    </div>
  </div>
</template>
