<script setup lang="ts">
import { ElMessage, ElMessageBox } from 'element-plus'
import { computed, onMounted, reactive, ref } from 'vue'

import { seckillApi } from '@/api'
import type { SeckillActivityView } from '@/api/seckill'
import { SECKILL_PHASE_LABEL, SeckillPhase } from '@/api/seckill'
import EmptyState from '@/components/EmptyState.vue'
import PageHead from '@/components/PageHead.vue'
import StatusPill from '@/components/StatusPill.vue'
import { useNow } from '@/composables/useNow'
import { formatDateTime } from '@/utils/datetime'
import { describeError } from '@/utils/errors'
import { formatYuan } from '@/utils/money'

const activities = ref<SeckillActivityView[]>([])
const loading = ref(true)
const loadError = ref('')
const saving = ref(false)
const preheatingId = ref<number | null>(null)
const dialogVisible = ref(false)
const now = useNow()

const form = reactive({
  title: '',
  skuId: 1,
  seckillPrice: 1.0,
  totalStock: 100,
  perUserLimit: 1,
  startAt: '',
  endAt: ''
})

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

function canPreheat(activity: SeckillActivityView): boolean {
  return phaseOf(activity) === SeckillPhase.NOT_STARTED
}

const runningCount = computed(
  () => activities.value.filter((activity) => phaseOf(activity) === SeckillPhase.RUNNING).length
)
const remainingStock = computed(() =>
  activities.value.reduce((sum, activity) => sum + Math.max(activity.availableStock, 0), 0)
)

async function load(): Promise<void> {
  loading.value = true
  loadError.value = ''
  try {
    activities.value = await seckillApi.adminActivities()
  } catch (error) {
    loadError.value = describeError(error)
    ElMessage.error(loadError.value)
  } finally {
    loading.value = false
  }
}

function openCreate(): void {
  form.title = ''
  form.skuId = 1
  form.seckillPrice = 1.0
  form.totalStock = 100
  form.perUserLimit = 1
  form.startAt = ''
  form.endAt = ''
  dialogVisible.value = true
}

async function save(): Promise<void> {
  if (!form.title.trim()) {
    ElMessage.warning('请填写活动名')
    return
  }
  if (form.skuId < 1) {
    ElMessage.warning('商品编号必须大于 0')
    return
  }
  if (form.seckillPrice <= 0) {
    ElMessage.warning('秒杀价必须大于 0')
    return
  }
  if (form.totalStock < 1) {
    ElMessage.warning('活动库存至少 1 件')
    return
  }
  if (form.perUserLimit < 1) {
    ElMessage.warning('每人限购至少 1 件')
    return
  }
  if (!form.startAt || !form.endAt) {
    ElMessage.warning('请选择活动起止时间')
    return
  }
  if (new Date(form.endAt) <= new Date(form.startAt)) {
    ElMessage.warning('结束时间必须晚于开始时间')
    return
  }

  saving.value = true
  try {
    const activityId = await seckillApi.adminCreate({ ...form })
    ElMessage.success(`活动已创建（编号 ${activityId}），开始前记得预热库存`)
    dialogVisible.value = false
    await load()
  } catch (error) {
    ElMessage.error(describeError(error))
  } finally {
    saving.value = false
  }
}

async function preheat(activity: SeckillActivityView): Promise<void> {
  if (!canPreheat(activity)) {
    return
  }
  try {
    await ElMessageBox.confirm(
      `将「${activity.title}」的活动库存写入 Redis？预热后开始前不能重复执行。`,
      '预热活动',
      { type: 'warning', confirmButtonText: '预热', cancelButtonText: '取消' }
    )
  } catch {
    return
  }
  preheatingId.value = activity.id
  try {
    const result = await seckillApi.adminPreheat(activity.id)
    ElMessage.success(`已预热，Redis 中库存 ${result.stock}`)
    await load()
  } catch (error) {
    ElMessage.error(describeError(error))
  } finally {
    preheatingId.value = null
  }
}

onMounted(load)
</script>

<template>
  <div class="app-page">
    <PageHead title="秒杀活动" meta="配置活动时间、价格与库存；开始前把库存预热到 Redis，抢购才会被受理。">
      <template #actions>
        <button type="button" class="btn" @click="openCreate">新建活动</button>
      </template>
    </PageHead>

    <dl class="summary-strip" aria-label="秒杀活动概况">
      <div>
        <dt>活动总数</dt>
        <dd>{{ activities.length }} <span>个</span></dd>
      </div>
      <div>
        <dt>进行中</dt>
        <dd>{{ runningCount }} <span>个</span></dd>
      </div>
      <div>
        <dt>剩余名额</dt>
        <dd>{{ remainingStock }} <span>件</span></dd>
      </div>
    </dl>

    <p class="admin-hint">
      新建活动后需在开始前预热。预热会把活动库存写入 Redis；已开始的活动不能再次预热，避免覆盖在途预扣。
    </p>

    <div v-if="loading && activities.length === 0" aria-busy="true" aria-label="正在加载秒杀活动">
      <div class="skeleton-block skeleton-row" />
      <div class="skeleton-block skeleton-row" />
      <div class="skeleton-block skeleton-row" />
    </div>

    <EmptyState
      v-else-if="loadError"
      title="秒杀活动加载失败"
      :hint="loadError"
    >
      <button type="button" class="btn" @click="load">重新加载</button>
    </EmptyState>

    <EmptyState
      v-else-if="activities.length === 0"
      title="还没有秒杀活动"
      hint="新建活动后，在开始前执行预热；活动开始后用户才能在秒杀排期看到可抢购状态。"
    >
      <button type="button" class="btn" @click="openCreate">新建活动</button>
    </EmptyState>

    <section v-else class="panel">
      <div class="table-wrap">
        <table class="data-table">
          <thead>
            <tr>
              <th>活动</th>
              <th class="num">秒杀价</th>
              <th class="num">库存</th>
              <th>阶段</th>
              <th>时间</th>
              <th aria-label="操作" />
            </tr>
          </thead>
          <tbody>
            <tr v-for="activity in activities" :key="activity.id">
              <td class="identity">
                <span class="row-title">{{ activity.title }}</span>
                <span class="row-sub">编号 {{ activity.id }} · 商品 {{ activity.skuId }}</span>
              </td>
              <td class="num">{{ formatYuan(activity.seckillPrice) }}</td>
              <td class="num">{{ activity.availableStock }} / {{ activity.totalStock }}</td>
              <td>
                <StatusPill
                  :tone="phaseOf(activity) === SeckillPhase.RUNNING ? 'paid' : phaseOf(activity) === SeckillPhase.NOT_STARTED ? 'pending' : 'closed'"
                  :label="SECKILL_PHASE_LABEL[phaseOf(activity)] ?? phaseOf(activity)"
                />
              </td>
              <td>
                <span>{{ formatDateTime(activity.startAt) }}</span>
                <span class="row-sub">至 {{ formatDateTime(activity.endAt) }}</span>
              </td>
              <td>
                <div class="row-actions">
                  <button
                    type="button"
                    class="btn btn--outline btn--sm"
                    :disabled="!canPreheat(activity) || preheatingId === activity.id"
                    :title="canPreheat(activity) ? '把活动库存写入 Redis' : '活动已开始或已结束，不应再次预热'"
                    @click="preheat(activity)"
                  >
                    {{ preheatingId === activity.id ? '预热中…' : '预热' }}
                  </button>
                </div>
              </td>
            </tr>
          </tbody>
        </table>
      </div>
    </section>

    <el-dialog v-model="dialogVisible" title="新建秒杀活动" width="540px">
      <el-form label-position="top">
        <el-form-item label="活动名">
          <el-input v-model="form.title" placeholder="例如：双十一整点秒杀" />
        </el-form-item>
        <div class="admin-form__row">
          <el-form-item label="商品编号">
            <el-input-number v-model="form.skuId" :min="1" style="width: 100%" />
          </el-form-item>
          <el-form-item label="秒杀价">
            <el-input-number v-model="form.seckillPrice" :min="0.01" :precision="2" style="width: 100%" />
          </el-form-item>
        </div>
        <div class="admin-form__row">
          <el-form-item label="活动库存">
            <el-input-number v-model="form.totalStock" :min="1" style="width: 100%" />
          </el-form-item>
          <el-form-item label="每人限购">
            <el-input-number v-model="form.perUserLimit" :min="1" style="width: 100%" />
          </el-form-item>
        </div>
        <div class="admin-form__row">
          <el-form-item label="开始时间">
            <el-date-picker
              v-model="form.startAt"
              type="datetime"
              value-format="YYYY-MM-DDTHH:mm:ss"
              placeholder="选择开始时间"
              style="width: 100%"
            />
          </el-form-item>
          <el-form-item label="结束时间">
            <el-date-picker
              v-model="form.endAt"
              type="datetime"
              value-format="YYYY-MM-DDTHH:mm:ss"
              placeholder="选择结束时间"
              style="width: 100%"
            />
          </el-form-item>
        </div>
      </el-form>
      <template #footer>
        <button type="button" class="btn btn--outline" @click="dialogVisible = false">取消</button>
        <button type="button" class="btn" :disabled="saving" @click="save">
          {{ saving ? '创建中…' : '创建' }}
        </button>
      </template>
    </el-dialog>
  </div>
</template>
