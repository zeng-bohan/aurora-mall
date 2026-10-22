<script setup lang="ts">
import { ElMessage } from 'element-plus'
import { computed, onMounted, reactive, ref } from 'vue'

import { couponApi } from '@/api'
import type { CouponTemplateView } from '@/api/coupon'
import EmptyState from '@/components/EmptyState.vue'
import PageHead from '@/components/PageHead.vue'
import { useNow } from '@/composables/useNow'
import { formatDateTime } from '@/utils/datetime'
import { describeError } from '@/utils/errors'
import { formatYuan, toCents } from '@/utils/money'

const templates = ref<CouponTemplateView[]>([])
const loading = ref(true)
const loadError = ref('')
const saving = ref(false)
const dialogVisible = ref(false)
const now = useNow(60_000)

const form = reactive({
  title: '',
  thresholdAmount: 100,
  discountAmount: 10,
  total: 100,
  claimStartAt: '',
  claimEndAt: '',
  validDays: 7
})

const remainingTotal = computed(() => templates.value.reduce((sum, item) => sum + item.remaining, 0))
const activeCount = computed(
  () =>
    templates.value.filter((item) => {
      const start = new Date(item.claimStartAt).getTime()
      const end = new Date(item.claimEndAt).getTime()
      return !Number.isNaN(start) && !Number.isNaN(end) && now.value >= start && now.value < end
    }).length
)

async function load(): Promise<void> {
  loading.value = true
  loadError.value = ''
  try {
    templates.value = await couponApi.adminList()
  } catch (error) {
    loadError.value = describeError(error)
    ElMessage.error(loadError.value)
  } finally {
    loading.value = false
  }
}

function openCreate(): void {
  form.title = ''
  form.thresholdAmount = 100
  form.discountAmount = 10
  form.total = 100
  form.claimStartAt = ''
  form.claimEndAt = ''
  form.validDays = 7
  dialogVisible.value = true
}

async function save(): Promise<void> {
  if (!form.title.trim()) {
    ElMessage.warning('请填写券名')
    return
  }
  if (form.discountAmount <= 0) {
    ElMessage.warning('优惠金额必须大于 0')
    return
  }
  if (toCents(form.discountAmount) >= toCents(form.thresholdAmount)) {
    ElMessage.warning('优惠金额必须小于使用门槛')
    return
  }
  if (!form.claimStartAt || !form.claimEndAt) {
    ElMessage.warning('请选择领取起止时间')
    return
  }
  if (new Date(form.claimEndAt) <= new Date(form.claimStartAt)) {
    ElMessage.warning('领取结束时间必须晚于开始时间')
    return
  }
  if (form.validDays < 1) {
    ElMessage.warning('有效天数至少 1 天')
    return
  }

  saving.value = true
  try {
    await couponApi.adminCreate({ ...form })
    ElMessage.success('券模板已创建')
    dialogVisible.value = false
    await load()
  } catch (error) {
    ElMessage.error(describeError(error))
  } finally {
    saving.value = false
  }
}

onMounted(load)
</script>

<template>
  <div class="app-page">
    <PageHead title="券模板" meta="查看全部模板的领取窗口与剩余数量；当前仅支持新建，已有模板只读。">
      <template #actions>
        <button type="button" class="btn" @click="openCreate">新建券模板</button>
      </template>
    </PageHead>

    <dl class="summary-strip" aria-label="券模板概况">
      <div>
        <dt>模板总数</dt>
        <dd>{{ templates.length }} <span>个</span></dd>
      </div>
      <div>
        <dt>剩余可领</dt>
        <dd>{{ remainingTotal }} <span>张</span></dd>
      </div>
      <div>
        <dt>领取窗口内</dt>
        <dd>{{ activeCount }} <span>个</span></dd>
      </div>
    </dl>

    <p class="admin-hint">
      列表不按领取窗口过滤，便于排查用户为什么领不到。剩余数量由服务端扣减，编辑能力尚未开放。
    </p>

    <div v-if="loading && templates.length === 0" aria-busy="true" aria-label="正在加载券模板">
      <div class="skeleton-block skeleton-row" />
      <div class="skeleton-block skeleton-row" />
      <div class="skeleton-block skeleton-row" />
    </div>

    <EmptyState
      v-else-if="loadError"
      title="券模板加载失败"
      :hint="loadError"
    >
      <button type="button" class="btn" @click="load">重新加载</button>
    </EmptyState>

    <EmptyState
      v-else-if="templates.length === 0"
      title="还没有券模板"
      hint="创建模板后，用户可在「优惠券」页领取；领取窗口与剩余数量由服务端控制。"
    >
      <button type="button" class="btn" @click="openCreate">新建券模板</button>
    </EmptyState>

    <section v-else class="panel">
      <div class="table-wrap">
        <table class="data-table">
          <thead>
            <tr>
              <th>券名</th>
              <th class="num">门槛</th>
              <th class="num">优惠</th>
              <th class="num">剩余</th>
              <th>领取窗口</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="template in templates" :key="template.id">
              <td class="identity">
                <span class="row-title">{{ template.title }}</span>
                <span class="row-sub">编号 {{ template.id }}</span>
              </td>
              <td class="num">满 {{ formatYuan(template.thresholdAmount) }}</td>
              <td class="num">{{ formatYuan(template.discountAmount) }}</td>
              <td class="num">{{ template.remaining }}</td>
              <td>
                <span>{{ formatDateTime(template.claimStartAt) }}</span>
                <span class="row-sub">至 {{ formatDateTime(template.claimEndAt) }}</span>
              </td>
            </tr>
          </tbody>
        </table>
      </div>
    </section>

    <el-dialog v-model="dialogVisible" title="新建券模板" width="540px">
      <el-form label-position="top">
        <el-form-item label="券名">
          <el-input v-model="form.title" placeholder="例如：满 100 减 10" />
        </el-form-item>
        <div class="admin-form__row">
          <el-form-item label="使用门槛（订单金额下限）">
            <el-input-number v-model="form.thresholdAmount" :min="0.01" :precision="2" style="width: 100%" />
          </el-form-item>
          <el-form-item label="优惠金额">
            <el-input-number v-model="form.discountAmount" :min="0.01" :precision="2" style="width: 100%" />
          </el-form-item>
        </div>
        <div class="admin-form__row">
          <el-form-item label="发放总量">
            <el-input-number v-model="form.total" :min="1" style="width: 100%" />
          </el-form-item>
          <el-form-item label="领取后有效天数">
            <el-input-number v-model="form.validDays" :min="1" style="width: 100%" />
          </el-form-item>
        </div>
        <div class="admin-form__row">
          <el-form-item label="领取开始">
            <el-date-picker
              v-model="form.claimStartAt"
              type="datetime"
              value-format="YYYY-MM-DDTHH:mm:ss"
              placeholder="选择开始时间"
              style="width: 100%"
            />
          </el-form-item>
          <el-form-item label="领取结束">
            <el-date-picker
              v-model="form.claimEndAt"
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
