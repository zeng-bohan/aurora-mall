<script setup lang="ts">
import { ElMessage } from 'element-plus'
import { onMounted, reactive, ref } from 'vue'

import { seckillApi } from '@/api'
import type { SeckillActivityView } from '@/api/seckill'
import { SECKILL_PHASE_LABEL } from '@/api/seckill'
import { formatDateTime } from '@/utils/datetime'
import { describeError } from '@/utils/errors'
import { formatYuan } from '@/utils/money'

const activities = ref<SeckillActivityView[]>([])
const loading = ref(false)
const saving = ref(false)
const preheatingId = ref<number | null>(null)
const dialogVisible = ref(false)

const form = reactive({
  title: '',
  skuId: 1,
  seckillPrice: 1.0,
  totalStock: 100,
  perUserLimit: 1,
  startAt: '',
  endAt: ''
})

async function load(): Promise<void> {
  loading.value = true
  try {
    activities.value = await seckillApi.adminActivities()
  } catch (error) {
    ElMessage.error(describeError(error))
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
  // 与券模板同理：这个端点没有 Bean Validation，非法输入统一回 10001
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
    ElMessage.success(`活动已创建（编号 ${activityId}），记得预热库存`)
    dialogVisible.value = false
    await load()
  } catch (error) {
    ElMessage.error(describeError(error))
  } finally {
    saving.value = false
  }
}

async function preheat(activity: SeckillActivityView): Promise<void> {
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
  <div class="aurora-page">
    <div class="aurora-head">
      <h2 class="aurora-title">秒杀活动管理</h2>
      <el-button type="primary" @click="openCreate">新建活动</el-button>
    </div>

    <el-alert
      type="warning"
      :closable="false"
      show-icon
      title="建完必须预热"
      description="活动库存要先灌进 Redis，抢购才走得到 Lua 预扣这一步；没预热的活动抢购会返回「秒杀活动尚未就绪」。"
      class="aurora-note"
    />

    <el-table v-loading="loading" :data="activities" border empty-text="还没有活动">
      <el-table-column prop="id" label="编号" width="90" />
      <el-table-column prop="title" label="活动名" min-width="160" />
      <el-table-column prop="skuId" label="商品" width="100" />
      <el-table-column label="秒杀价" width="120" align="right">
        <template #default="{ row }">
          <span class="aurora-amount">{{ formatYuan(row.seckillPrice) }}</span>
        </template>
      </el-table-column>
      <el-table-column label="库存" width="130" align="center">
        <template #default="{ row }">{{ row.availableStock }} / {{ row.totalStock }}</template>
      </el-table-column>
      <el-table-column label="阶段" width="110">
        <template #default="{ row }">
          <el-tag :type="row.phase === 'RUNNING' ? 'success' : 'info'">
            {{ SECKILL_PHASE_LABEL[row.phase] ?? row.phase }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="时间" min-width="260">
        <template #default="{ row }">
          {{ formatDateTime(row.startAt) }} ~ {{ formatDateTime(row.endAt) }}
        </template>
      </el-table-column>
      <el-table-column label="操作" width="110">
        <template #default="{ row }">
          <el-button
            text
            type="primary"
            size="small"
            :loading="preheatingId === row.id"
            @click="preheat(row)"
          >
            预热
          </el-button>
        </template>
      </el-table-column>
    </el-table>

    <el-dialog v-model="dialogVisible" title="新建秒杀活动" width="520px">
      <el-form label-position="top">
        <el-form-item label="活动名">
          <el-input v-model="form.title" placeholder="例如：双十一整点秒杀" />
        </el-form-item>
        <el-form-item label="商品编号">
          <el-input-number v-model="form.skuId" :min="1" />
        </el-form-item>
        <el-form-item label="秒杀价">
          <el-input-number v-model="form.seckillPrice" :min="0.01" :precision="2" />
        </el-form-item>
        <el-form-item label="活动库存">
          <el-input-number v-model="form.totalStock" :min="1" />
        </el-form-item>
        <el-form-item label="每人限购">
          <el-input-number v-model="form.perUserLimit" :min="1" />
        </el-form-item>
        <el-form-item label="开始时间">
          <el-date-picker
            v-model="form.startAt"
            type="datetime"
            value-format="YYYY-MM-DDTHH:mm:ss"
            placeholder="选择开始时间"
          />
        </el-form-item>
        <el-form-item label="结束时间">
          <el-date-picker
            v-model="form.endAt"
            type="datetime"
            value-format="YYYY-MM-DDTHH:mm:ss"
            placeholder="选择结束时间"
          />
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
  margin-bottom: 16px;
}
</style>
