<script setup lang="ts">
import { ElMessage } from 'element-plus'
import { onMounted, reactive, ref } from 'vue'

import { couponApi, type CouponTemplate, type SaveCouponTemplate } from '@/api/dtc'
import { formatDateTime } from '@/utils/datetime'
import { describeError } from '@/utils/errors'
import { formatYuan } from '@/utils/money'

const rows = ref<CouponTemplate[]>([])
const loading = ref(true)
const error = ref('')
const createOpen = ref(false)
const saving = ref(false)

function localInput(date: Date): string {
  const pad = (n: number) => String(n).padStart(2, '0')
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}T${pad(date.getHours())}:${pad(date.getMinutes())}`
}

function defaults(): SaveCouponTemplate {
  const now = new Date()
  return {
    title: '',
    thresholdAmount: 299,
    discountAmount: 30,
    total: 100,
    claimStartAt: localInput(now),
    claimEndAt: localInput(new Date(now.getTime() + 30 * 86400000)),
    validDays: 30,
  }
}

const form = reactive<SaveCouponTemplate>(defaults())

async function load(): Promise<void> {
  loading.value = true
  error.value = ''
  try {
    rows.value = await couponApi.adminList()
  } catch (e) {
    error.value = describeError(e)
  } finally {
    loading.value = false
  }
}

function openCreate(): void {
  Object.assign(form, defaults())
  createOpen.value = true
}

async function save(): Promise<void> {
  if (!form.title.trim()) {
    ElMessage.warning('请填写券标题')
    return
  }
  if (form.discountAmount >= form.thresholdAmount) {
    ElMessage.warning('抵扣金额必须小于门槛金额')
    return
  }
  saving.value = true
  try {
    await couponApi.adminCreate({ ...form, title: form.title.trim() })
    ElMessage.success('优惠券模板已创建')
    createOpen.value = false
    await load()
  } catch (e) {
    ElMessage.error(describeError(e))
  } finally {
    saving.value = false
  }
}

onMounted(() => {
  void load()
})
</script>

<template>
  <div>
    <div class="admin-page-head">
      <div>
        <h1>优惠券</h1>
        <p>满减券作用于父订单商品金额。用户领取后可在结算时选择。</p>
      </div>
      <div class="admin-actions">
        <button class="btn btn--primary btn--sm" type="button" @click="openCreate">新建券模板</button>
        <button class="btn btn--line btn--sm" type="button" @click="load">刷新</button>
      </div>
    </div>

    <section class="admin-card">
      <div v-if="loading" class="admin-empty">正在读取券模板…</div>
      <div v-else-if="error" class="error-banner" style="margin: 16px">{{ error }}</div>
      <div v-else class="table-wrap">
        <table class="data-table">
          <thead>
            <tr>
              <th>券标题</th>
              <th>门槛</th>
              <th>抵扣</th>
              <th>剩余 / 总量</th>
              <th>领取窗口</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="row in rows" :key="row.id">
              <td><strong>{{ row.title }}</strong></td>
              <td class="money">{{ formatYuan(row.thresholdAmount) }}</td>
              <td class="money">{{ formatYuan(row.discountAmount) }}</td>
              <td>{{ row.remaining }} / {{ row.total }}</td>
              <td class="tiny">{{ formatDateTime(row.claimStartAt) }} – {{ formatDateTime(row.claimEndAt) }}</td>
            </tr>
            <tr v-if="!rows.length">
              <td colspan="5" class="admin-empty">还没有券模板</td>
            </tr>
          </tbody>
        </table>
      </div>
    </section>

    <el-dialog v-model="createOpen" title="新建优惠券" width="520px">
      <div class="form-grid">
        <label class="field field--full">
          <span class="field__label">券标题</span>
          <input v-model="form.title" class="input" maxlength="64" placeholder="如 满 500 减 50" />
        </label>
        <label class="field">
          <span class="field__label">门槛金额</span>
          <input v-model.number="form.thresholdAmount" type="number" min="1" class="input" />
        </label>
        <label class="field">
          <span class="field__label">抵扣金额</span>
          <input v-model.number="form.discountAmount" type="number" min="1" class="input" />
        </label>
        <label class="field">
          <span class="field__label">发放总量</span>
          <input v-model.number="form.total" type="number" min="1" class="input" />
        </label>
        <label class="field">
          <span class="field__label">领取后有效天数</span>
          <input v-model.number="form.validDays" type="number" min="1" class="input" />
        </label>
        <label class="field">
          <span class="field__label">领取开始</span>
          <input v-model="form.claimStartAt" type="datetime-local" class="input" />
        </label>
        <label class="field">
          <span class="field__label">领取结束</span>
          <input v-model="form.claimEndAt" type="datetime-local" class="input" />
        </label>
      </div>
      <p class="hint" style="margin-top: 12px">券模板创建后，用户可在优惠券页领取。抵扣金额必须小于门槛。</p>
      <div class="dialog-actions">
        <button class="btn btn--ghost" type="button" @click="createOpen = false">取消</button>
        <button class="btn btn--primary" type="button" :disabled="saving" @click="save">
          {{ saving ? '创建中…' : '创建' }}
        </button>
      </div>
    </el-dialog>
  </div>
</template>
