<script setup lang="ts">
import { ElMessage } from 'element-plus'
import { onMounted, ref } from 'vue'

import { seckillApi } from '@/api'
import type { SeckillActivityView } from '@/api/seckill'
import { SECKILL_PHASE_LABEL, SeckillPhase } from '@/api/seckill'
import { useNow } from '@/composables/useNow'
import { formatCountdown, formatDateTime } from '@/utils/datetime'
import { describeError } from '@/utils/errors'
import { formatYuan } from '@/utils/money'

const activities = ref<SeckillActivityView[]>([])
const loading = ref(false)
const now = useNow()

function phaseTagType(phase: string): 'success' | 'info' | 'warning' {
  if (phase === SeckillPhase.RUNNING) {
    return 'success'
  }
  return phase === SeckillPhase.NOT_STARTED ? 'warning' : 'info'
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
  <div class="aurora-page">
    <div class="aurora-seckill__head">
      <h2 class="aurora-title">秒杀</h2>
      <el-button :loading="loading" @click="load">刷新</el-button>
    </div>

    <div v-loading="loading" class="aurora-seckill__grid">
      <el-card v-for="activity in activities" :key="activity.id" shadow="hover" class="aurora-activity">
        <div class="aurora-activity__head">
          <span class="aurora-activity__title">{{ activity.title }}</span>
          <el-tag :type="phaseTagType(activity.phase)" size="small">
            {{ SECKILL_PHASE_LABEL[activity.phase] ?? activity.phase }}
          </el-tag>
        </div>

        <div class="aurora-activity__price aurora-amount">{{ formatYuan(activity.seckillPrice) }}</div>

        <div class="aurora-activity__meta">
          剩余 {{ activity.availableStock }} / {{ activity.totalStock }} 件 ·
          每人限 {{ activity.perUserLimit }} 件
        </div>

        <div class="aurora-activity__window">
          {{ formatDateTime(activity.startAt) }} ~ {{ formatDateTime(activity.endAt) }}
        </div>

        <div class="aurora-activity__countdown">
          <template v-if="activity.phase === SeckillPhase.NOT_STARTED">
            距开抢 {{ formatCountdown(activity.startAt, now) ?? '即将开始' }}
          </template>
          <template v-else-if="activity.phase === SeckillPhase.RUNNING">
            距结束 {{ formatCountdown(activity.endAt, now) ?? '即将结束' }}
          </template>
          <template v-else>已结束</template>
        </div>

        <el-button
          type="danger"
          class="aurora-activity__action"
          @click="$router.push({ name: 'seckill-detail', params: { id: activity.id } })"
        >
          {{ activity.phase === SeckillPhase.RUNNING ? '去抢购' : '查看' }}
        </el-button>
      </el-card>
    </div>

    <el-empty v-if="!loading && activities.length === 0" description="当前没有秒杀活动" />
  </div>
</template>

<style scoped>
.aurora-seckill__head {
  display: flex;
  justify-content: space-between;
  align-items: center;
}

.aurora-title {
  font-size: 20px;
  margin: 8px 0 16px;
}

.aurora-seckill__grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(260px, 1fr));
  gap: 16px;
  min-height: 100px;
}

.aurora-activity__head {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 8px;
}

.aurora-activity__title {
  font-weight: 600;
}

.aurora-activity__price {
  font-size: 22px;
  font-weight: 700;
  color: #f56c6c;
  margin: 10px 0;
}

.aurora-activity__meta,
.aurora-activity__window,
.aurora-activity__countdown {
  font-size: 12px;
  color: #909399;
  margin-bottom: 6px;
}

.aurora-activity__countdown {
  color: #e6a23c;
  font-variant-numeric: tabular-nums;
  margin-bottom: 12px;
}

.aurora-activity__action {
  width: 100%;
}
</style>
