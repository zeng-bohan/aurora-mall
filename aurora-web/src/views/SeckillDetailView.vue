<script setup lang="ts">
import { ElMessage } from 'element-plus'
import { computed, onMounted, onUnmounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'

import { seckillApi } from '@/api'
import type { SeckillActivityView } from '@/api/seckill'
import { SECKILL_PHASE_LABEL, SeckillBuyStatus, SeckillPhase } from '@/api/seckill'
import { ApiError, ErrorCode } from '@/api/types'
import { useNow } from '@/composables/useNow'
import { formatCountdown, formatDateTime } from '@/utils/datetime'
import { describeError } from '@/utils/errors'
import { formatYuan } from '@/utils/money'

/** 轮询上限：后端异步落单通常一两秒内完成，超过这个次数说明消费者卡住了，别无限转圈。 */
const MAX_POLLS = 60

const route = useRoute()
const router = useRouter()
const now = useNow()

const activity = ref<SeckillActivityView | null>(null)
const loading = ref(false)
const buying = ref(false)
const outcome = ref<string | null>(null)
const outcomeType = ref<'success' | 'error' | 'info'>('info')
const placedOrderId = ref<number | null>(null)
const waiting = ref(false)

let pollTimer: number | null = null
let pollCount = 0

const activityId = computed(() => Number(route.params.id))
const running = computed(() => activity.value?.phase === SeckillPhase.RUNNING)
const canBuy = computed(() => running.value && (activity.value?.availableStock ?? 0) > 0)

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
  outcome.value = '抢到了，订单已生成'
  outcomeType.value = 'success'
  void load()
}

async function pollOnce(): Promise<void> {
  pollCount += 1
  if (pollCount > MAX_POLLS) {
    stopPolling()
    outcome.value = '排队超时，请到订单页确认结果（名额已占，一般不会丢）'
    outcomeType.value = 'info'
    return
  }
  try {
    const result = await seckillApi.myBuy(activityId.value)
    if (result.status === SeckillBuyStatus.PLACED) {
      settle(result.orderId)
    }
  } catch (error) {
    // 40400 = 该活动下还没有我的记录：MQ 还没消费到，继续等
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
    // QUEUED：名额已经扣下了，落单是异步的，轮询等结果
    outcome.value = '已占到名额，正在生成订单…'
    outcomeType.value = 'info'
    startPolling()
  } catch (error) {
    // 售罄 / 已参与 / 未开始这些都是业务结论，直接展示后端文案
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
  <div class="aurora-page">
    <el-page-header content="秒杀详情" @back="router.back()" />

    <el-skeleton v-if="loading" :rows="4" animated class="aurora-skeleton" />

    <el-card v-else-if="activity" class="aurora-activity">
      <div class="aurora-activity__head">
        <h2 class="aurora-activity__title">{{ activity.title }}</h2>
        <el-tag :type="running ? 'success' : 'info'">
          {{ SECKILL_PHASE_LABEL[activity.phase] ?? activity.phase }}
        </el-tag>
      </div>

      <div class="aurora-activity__price aurora-amount">{{ formatYuan(activity.seckillPrice) }}</div>

      <el-descriptions :column="1" border class="aurora-activity__info">
        <el-descriptions-item label="商品编号">{{ activity.skuId }}</el-descriptions-item>
        <el-descriptions-item label="库存">
          剩余 {{ activity.availableStock }} / 共 {{ activity.totalStock }}
        </el-descriptions-item>
        <el-descriptions-item label="限购">每人 {{ activity.perUserLimit }} 件</el-descriptions-item>
        <el-descriptions-item label="活动时间">
          {{ formatDateTime(activity.startAt) }} ~ {{ formatDateTime(activity.endAt) }}
        </el-descriptions-item>
        <el-descriptions-item label="倒计时">
          <template v-if="activity.phase === SeckillPhase.NOT_STARTED">
            距开抢 {{ formatCountdown(activity.startAt, now) ?? '即将开始' }}
          </template>
          <template v-else-if="running">
            距结束 {{ formatCountdown(activity.endAt, now) ?? '即将结束' }}
          </template>
          <template v-else>已结束</template>
        </el-descriptions-item>
      </el-descriptions>

      <div class="aurora-activity__actions">
        <el-button type="danger" size="large" :disabled="!canBuy" :loading="buying" @click="buy">
          {{ activity.availableStock > 0 ? '立即抢购' : '已售罄' }}
        </el-button>
        <el-button :loading="waiting" @click="load">刷新</el-button>
      </div>

      <el-alert
        v-if="outcome"
        :type="outcomeType"
        :closable="false"
        show-icon
        :title="outcome"
        class="aurora-activity__outcome"
      >
        <template v-if="placedOrderId" #default>
          <el-button text type="primary" @click="router.push({ name: 'order-detail', params: { id: placedOrderId } })">
            查看订单 {{ placedOrderId }}
          </el-button>
        </template>
      </el-alert>

      <el-alert
        v-if="waiting"
        type="info"
        :closable="false"
        show-icon
        title="正在等待异步落单"
        description="抢购请求已经在 Redis 原子预扣中占到名额，订单由消息队列异步生成，通常一两秒内完成。"
        class="aurora-activity__outcome"
      />
    </el-card>

    <el-empty v-else description="活动不存在" />
  </div>
</template>

<style scoped>
.aurora-skeleton {
  margin-top: 16px;
}

.aurora-activity {
  margin-top: 16px;
  max-width: 720px;
}

.aurora-activity__head {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 12px;
}

.aurora-activity__title {
  margin: 0;
  font-size: 20px;
}

.aurora-activity__price {
  font-size: 26px;
  font-weight: 700;
  color: #f56c6c;
  margin: 12px 0 16px;
}

.aurora-activity__info {
  margin-bottom: 20px;
}

.aurora-activity__actions {
  display: flex;
  gap: 12px;
}

.aurora-activity__outcome {
  margin-top: 16px;
}
</style>
