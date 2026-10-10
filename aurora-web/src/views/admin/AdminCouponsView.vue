<script setup lang="ts">
import { ElMessage } from 'element-plus'
import { onMounted, reactive, ref } from 'vue'

import { couponApi } from '@/api'
import type { CouponTemplateView } from '@/api/coupon'
import { formatDateTime } from '@/utils/datetime'
import { describeError } from '@/utils/errors'
import { formatYuan, toCents } from '@/utils/money'

const templates = ref<CouponTemplateView[]>([])
const loading = ref(false)
const saving = ref(false)
const dialogVisible = ref(false)

// 时间用 el-date-picker 的 value-format 直接产出后端要的 LocalDateTime 字面量
// （"YYYY-MM-DDTHH:mm:ss"），省掉一次手工格式化。
const form = reactive({
  title: '',
  thresholdAmount: 100,
  discountAmount: 10,
  total: 100,
  claimStartAt: '',
  claimEndAt: '',
  validDays: 7
})

async function load(): Promise<void> {
  loading.value = true
  try {
    templates.value = await couponApi.adminList()
  } catch (error) {
    ElMessage.error(describeError(error))
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
  // 这个端点没有 Bean Validation，非法输入只会回一句笼统的 10001，
  // 所以能本地判的先本地判，用户至少知道错在哪一项。
  if (!form.title.trim()) {
    ElMessage.warning('请填写券名')
    return
  }
  if (form.discountAmount <= 0) {
    ElMessage.warning('优惠金额必须大于 0')
    return
  }
  if (toCents(form.discountAmount) >= toCents(form.thresholdAmount)) {
    ElMessage.warning('优惠金额必须小于使用门槛，否则等于白送')
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
  <div class="aurora-page">
    <div class="aurora-head">
      <h2 class="aurora-title">券模板管理</h2>
      <el-button type="primary" @click="openCreate">新建券模板</el-button>
    </div>

    <el-table v-loading="loading" :data="templates" border empty-text="还没有券模板">
      <el-table-column prop="id" label="编号" width="90" />
      <el-table-column prop="title" label="券名" min-width="180" />
      <el-table-column label="门槛" width="130" align="right">
        <template #default="{ row }">
          <span class="aurora-amount">满 {{ formatYuan(row.thresholdAmount) }}</span>
        </template>
      </el-table-column>
      <el-table-column label="优惠" width="120" align="right">
        <template #default="{ row }">
          <span class="aurora-amount">{{ formatYuan(row.discountAmount) }}</span>
        </template>
      </el-table-column>
      <el-table-column prop="remaining" label="剩余" width="100" align="center" />
      <el-table-column label="领取窗口" min-width="280">
        <template #default="{ row }">
          {{ formatDateTime(row.claimStartAt) }} ~ {{ formatDateTime(row.claimEndAt) }}
        </template>
      </el-table-column>
    </el-table>

    <el-alert
      type="info"
      :closable="false"
      show-icon
      title="这里列出全部模板"
      description="用户端只看得到「在领取窗口内且还有剩余」的模板，运营端不过滤，方便排查为什么用户领不到。"
      class="aurora-note"
    />

    <el-dialog v-model="dialogVisible" title="新建券模板" width="520px">
      <el-form label-position="top">
        <el-form-item label="券名">
          <el-input v-model="form.title" placeholder="例如：满 100 减 10" />
        </el-form-item>
        <el-form-item label="使用门槛（订单金额下限）">
          <el-input-number v-model="form.thresholdAmount" :min="0.01" :precision="2" />
        </el-form-item>
        <el-form-item label="优惠金额">
          <el-input-number v-model="form.discountAmount" :min="0.01" :precision="2" />
        </el-form-item>
        <el-form-item label="发放总量">
          <el-input-number v-model="form.total" :min="1" />
        </el-form-item>
        <el-form-item label="领取开始">
          <el-date-picker
            v-model="form.claimStartAt"
            type="datetime"
            value-format="YYYY-MM-DDTHH:mm:ss"
            placeholder="选择开始时间"
          />
        </el-form-item>
        <el-form-item label="领取结束">
          <el-date-picker
            v-model="form.claimEndAt"
            type="datetime"
            value-format="YYYY-MM-DDTHH:mm:ss"
            placeholder="选择结束时间"
          />
        </el-form-item>
        <el-form-item label="领取后有效天数">
          <el-input-number v-model="form.validDays" :min="1" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="save">创建</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.aurora-head {
  display: flex;
  justify-content: space-between;
  align-items: center;
}

.aurora-title {
  font-size: 20px;
  margin: 8px 0 16px;
}

.aurora-note {
  margin-top: 16px;
}
</style>
