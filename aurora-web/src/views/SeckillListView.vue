<script setup lang="ts">
import { ElMessage } from 'element-plus'
import { onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'

import { seckillApi } from '@/api'
import type { SeckillActivityView } from '@/api/seckill'
import { SECKILL_PHASE_LABEL, SeckillPhase } from '@/api/seckill'
import { useNow } from '@/composables/useNow'
import { useReveal } from '@/composables/useReveal'
import { formatCountdown, formatDateTime } from '@/utils/datetime'
import { describeError } from '@/utils/errors'
import { formatYuan } from '@/utils/money'

const router = useRouter()
const activities = ref<SeckillActivityView[]>([])
const loading = ref(true)
const now = useNow()

useReveal()

function phaseOf(activity: SeckillActivityView): string {
  const start = new Date(activity.startAt).getTime()
  const end = new Date(activity.endAt).getTime()
  if (Number.isNaN(start) || Number.isNaN(end)) {
    return activity.phase
  }
  if (now.value < start) {
    return SeckillPhase.NOT_STARTED
  }
  if (now.value >= end) {
    return SeckillPhase.ENDED
  }
  return SeckillPhase.RUNNING
}

function countdownLabel(activity: SeckillActivityView): string {
  const phase = phaseOf(activity)
  if (phase === SeckillPhase.NOT_STARTED) {
    return `距开抢 ${formatCountdown(activity.startAt, now.value) ?? '即将开始'}`
  }
  if (phase === SeckillPhase.RUNNING) {
    return `距结束 ${formatCountdown(activity.endAt, now.value) ?? '即将结束'}`
  }
  return '已结束'
}

function actionLabel(activity: SeckillActivityView): string {
  return phaseOf(activity) === SeckillPhase.RUNNING ? '去抢购' : '查看'
}

async function load(): Promise<void> {
  loading.value = true
  try {
    activities.value = await seckillApi.activities()
  } catch (error) {
    ElMessage.error(describeError(error))
  } finally {
    loading.value = false
  }
}

onMounted(load)
</script>

<template>
  <div class="seckill-page">
    <div class="container">
      <header class="seckill-head">
        <div>
          <h1>秒杀排期</h1>
          <p>
            限量、限时、限购。预扣在 Redis 中原子完成，落单由消息队列异步处理；排队结果会如实显示。
          </p>
        </div>
        <button type="button" class="btn btn--outline" :disabled="loading" @click="load">
          刷新排期
        </button>
      </header>

      <div v-if="loading" class="store-skeleton" aria-busy="true" aria-label="正在加载秒杀活动" />

      <div v-else-if="activities.length === 0" class="release-empty">
        <h2>当前没有进行中的秒杀</h2>
        <p>排期发布后会出现在这里。</p>
        <RouterLink class="btn" to="/products">回到目录</RouterLink>
      </div>

      <div v-else class="release-sheet" data-reveal>
        <article
          v-for="activity in activities"
          :key="activity.id"
          class="release-row"
        >
          <div class="release-row__phase">
            {{ SECKILL_PHASE_LABEL[phaseOf(activity)] ?? phaseOf(activity) }}
            <span class="num">{{ countdownLabel(activity) }}</span>
          </div>

          <div>
            <h2 class="release-row__title">{{ activity.title }}</h2>
            <p class="release-row__meta">
              剩余 {{ activity.availableStock }} / {{ activity.totalStock }} 件 · 每人限
              {{ activity.perUserLimit }} 件 · {{ formatDateTime(activity.startAt) }} 起
            </p>
          </div>

          <div class="release-row__price">{{ formatYuan(activity.seckillPrice) }}</div>

          <div class="release-row__action">
            <button
              type="button"
              class="btn"
              :class="{ 'btn--signal': phaseOf(activity) === SeckillPhase.RUNNING }"
              @click="router.push({ name: 'seckill-detail', params: { id: activity.id } })"
            >
              {{ actionLabel(activity) }}
            </button>
          </div>
        </article>
      </div>
    </div>
  </div>
</template>
