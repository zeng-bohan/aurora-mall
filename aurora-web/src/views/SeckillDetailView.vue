<script setup lang="ts">
import { ElMessage } from 'element-plus'
import { computed, onMounted, onUnmounted, ref, watch } from 'vue'
import { useRoute } from 'vue-router'

import { seckillApi } from '@/api'
import type { SeckillActivityView } from '@/api/seckill'
import { SECKILL_PHASE_LABEL, SeckillBuyStatus, SeckillPhase } from '@/api/seckill'
import { ApiError, ErrorCode } from '@/api/types'
import { useNow } from '@/composables/useNow'
import { useReveal } from '@/composables/useReveal'
import { formatCountdown, formatDateTime } from '@/utils/datetime'
import { describeError } from '@/utils/errors'
import { formatYuan } from '@/utils/money'

const MAX_POLLS = 90

const route = useRoute()
const now = useNow()

const activity = ref<SeckillActivityView | null>(null)
const loading = ref(true)
const buying = ref(false)
const outcome = ref<string | null>(null)
const outcomeType = ref<'success' | 'error' | 'info'>('info')
const placedOrderId = ref<number | null>(null)
const waiting = ref(false)

let pollTimer: number | null = null
let pollCount = 0

const activityId = computed(() => Number(route.params.id))

const phase = computed(() => {
  if (!activity.value) {
    return SeckillPhase.NOT_STARTED
  }
  const start = new Date(activity.value.startAt).getTime()
  const end = new Date(activity.value.endAt).getTime()
  if (Number.isNaN(start) || Number.isNaN(end)) {
    return activity.value.phase
  }
  if (now.value < start) {
    return SeckillPhase.NOT_STARTED
  }
  if (now.value >= end) {
    return SeckillPhase.ENDED
  }
  return SeckillPhase.RUNNING
})

const running = computed(() => phase.value === SeckillPhase.RUNNING)
const soldOut = computed(() => (activity.value?.availableStock ?? 0) <= 0)
const canBuy = computed(() => running.value && !soldOut.value && !buying.value)

const countdown = computed(() => {
  if (!activity.value) {
    return null
  }
  if (phase.value === SeckillPhase.NOT_STARTED) {
    return formatCountdown(activity.value.startAt, now.value)
  }
  if (phase.value === SeckillPhase.RUNNING) {
    return formatCountdown(activity.value.endAt, now.value)
  }
  return null
})

const countdownLabel = computed(() => {
  if (phase.value === SeckillPhase.NOT_STARTED) {
    return '距开抢'
  }
  if (phase.value === SeckillPhase.RUNNING) {
    return '距结束'
  }
  return '活动已结束'
})

const buyLabel = computed(() => {
  if (phase.value === SeckillPhase.NOT_STARTED) {
    return '尚未开始'
  }
  if (phase.value === SeckillPhase.ENDED) {
    return '已结束'
  }
  if (soldOut.value) {
    return '已售罄'
  }
  return waiting.value ? '排队中…' : '立即抢购'
})

const soldPercent = computed(() => {
  if (!activity.value || activity.value.totalStock <= 0) {
    return 0
  }
  const sold = activity.value.totalStock - activity.value.availableStock
  return Math.min(Math.max((sold / activity.value.totalStock) * 100, 0), 100)
})

useReveal()

function stopPolling(): void {
  if (pollTimer !== null) {
    window.clearInterval(pollTimer)
    pollTimer = null
  }
  waiting.value = false
}

async function load(): Promise<void> {
  loading.value = true
  try {
    activity.value = await seckillApi.activity(activityId.value)
  } catch (error) {
    ElMessage.error(describeError(error))
  } finally {
    loading.value = false
  }
}

function settle(orderId: number | null): void {
  stopPolling()
  placedOrderId.value = orderId
  outcome.value = '抢到了，订单已生成。'
  outcomeType.value = 'success'
  void load()
}

async function pollOnce(): Promise<void> {
  pollCount += 1
  if (pollCount > MAX_POLLS) {
    stopPolling()
    outcome.value = '结果暂时未知：名额已占用，系统会自动确认订单或返还名额。可稍后刷新，或到订单页核对。'
    outcomeType.value = 'info'
    return
  }
  try {
    const result = await seckillApi.myBuy(activityId.value)
    if (result.status === SeckillBuyStatus.PLACED) {
      settle(result.orderId)
    }
  } catch (error) {
    if (error instanceof ApiError && error.code === ErrorCode.NOT_FOUND) {
      return
    }
    stopPolling()
    outcome.value = describeError(error)
    outcomeType.value = 'error'
  }
}

function startPolling(): void {
  stopPolling()
  pollCount = 0
  waiting.value = true
  pollTimer = window.setInterval(() => {
    void pollOnce()
  }, 1000)
}

async function buy(): Promise<void> {
  buying.value = true
  outcome.value = null
  placedOrderId.value = null
  try {
    const result = await seckillApi.buy(activityId.value)
    if (result.status === SeckillBuyStatus.PLACED) {
      settle(result.orderId)
      return
    }
    outcome.value = '已占到名额，正在生成订单。'
    outcomeType.value = 'info'
    startPolling()
  } catch (error) {
    outcome.value = describeError(error)
    outcomeType.value = 'error'
    await load()
  } finally {
    buying.value = false
  }
}

onMounted(load)
onUnmounted(stopPolling)
watch(activityId, () => {
  stopPolling()
  outcome.value = null
  placedOrderId.value = null
  void load()
})
</script>

<template>
  <div class="seckill-page">
    <div class="container">
      <RouterLink class="back-link" to="/seckill">← 返回秒杀排期</RouterLink>

      <div v-if="loading" class="store-skeleton" aria-busy="true" aria-label="正在加载活动" />

      <div v-else-if="!activity" class="store-empty">
        <h1>活动不存在</h1>
        <p>它可能已经结束，或链接已经失效。</p>
        <RouterLink class="btn" to="/seckill">回到排期</RouterLink>
      </div>

      <article v-else class="ticket" data-reveal>
        <div class="ticket__main">
          <div>
            <p class="release-row__meta">{{ SECKILL_PHASE_LABEL[phase] ?? phase }}</p>
            <h1>{{ activity.title }}</h1>
          </div>

          <div class="stock-meter">
            <div class="stock-meter__bar" role="progressbar" :aria-valuenow="Math.round(soldPercent)" aria-valuemin="0" aria-valuemax="100" aria-label="已售比例">
              <div class="stock-meter__fill" :style="{ width: `${soldPercent}%` }" />
            </div>
            <div class="stock-meter__label">
              <span>剩余 {{ activity.availableStock }} 件</span>
              <span>共 {{ activity.totalStock }} 件</span>
            </div>
          </div>

          <dl class="product-specs">
            <div>
              <dt>秒杀价</dt>
              <dd>{{ formatYuan(activity.seckillPrice) }}</dd>
            </div>
            <div>
              <dt>商品编号</dt>
              <dd>{{ activity.skuId }}</dd>
            </div>
            <div>
              <dt>每人限购</dt>
              <dd>{{ activity.perUserLimit }} 件</dd>
            </div>
            <div>
              <dt>活动时间</dt>
              <dd>{{ formatDateTime(activity.startAt) }} 至 {{ formatDateTime(activity.endAt) }}</dd>
            </div>
          </dl>

          <div class="seckill-rules">
            <p>预扣成功只代表占到名额，订单由消息队列异步生成。</p>
            <p>同一活动下重复参与会被服务端拒绝；结果以订单页为准。</p>
          </div>
        </div>

        <div class="ticket__side">
          <div>
            <p class="countdown-label">{{ countdownLabel }}</p>
            <p v-if="countdown" class="countdown" aria-live="polite">{{ countdown }}</p>
            <p v-if="countdown === null" class="countdown countdown--ended">—</p>
          </div>

          <button type="button" class="btn btn--signal btn--block" :disabled="!canBuy" @click="buy">
            {{ buyLabel }}
          </button>
          <button type="button" class="btn btn--outline btn--block" :disabled="waiting" @click="load">
            刷新状态
          </button>

          <p
            v-if="outcome"
            class="seckill-outcome"
            :class="{
              'seckill-outcome--success': outcomeType === 'success',
              'seckill-outcome--error': outcomeType === 'error'
            }"
            aria-live="polite"
          >
            {{ outcome }}
            <RouterLink
              v-if="placedOrderId"
              class="auth-alt"
              :to="{ name: 'order-detail', params: { id: placedOrderId } }"
            >
              查看订单 {{ placedOrderId }}
            </RouterLink>
          </p>

          <p v-if="waiting" class="seckill-outcome" aria-live="polite">
            正在等待异步落单。请求已在 Redis 原子预扣中占到名额，通常一两秒内生成订单。
          </p>
        </div>
      </article>
    </div>
  </div>
</template>
