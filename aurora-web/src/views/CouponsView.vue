<script setup lang="ts">
import { ElMessage } from 'element-plus'
import { onMounted, ref } from 'vue'

import { couponApi } from '@/api'
import type { CouponTemplateView, CouponView } from '@/api/coupon'
import { COUPON_STATUS_LABEL, CouponStatus } from '@/api/coupon'
import { describeError } from '@/utils/errors'
import { formatDateTime } from '@/utils/datetime'
import { formatYuan } from '@/utils/money'

const templates = ref<CouponTemplateView[]>([])
const mine = ref<CouponView[]>([])
const loading = ref(false)
const claimingId = ref<number | null>(null)
const tab = ref('templates')

function statusTagType(status: string): 'success' | 'info' | 'warning' {
  if (status === CouponStatus.UNUSED) {
    return 'success'
  }
  return status === CouponStatus.LOCKED ? 'warning' : 'info'
}

async function load(): Promise<void> {
  loading.value = true
  try {
    const [available, owned] = await Promise.all([couponApi.templates(), couponApi.mine()])
    templates.value = available
    mine.value = owned
  } catch (error) {
    ElMessage.error(describeError(error))
  } finally {
    loading.value = false
  }
}

async function claim(templateId: number): Promise<void> {
  claimingId.value = templateId
  try {
    await couponApi.claim(templateId)
    ElMessage.success('领取成功')
    await load()
  } catch (error) {
    ElMessage.error(describeError(error))
  } finally {
    claimingId.value = null
  }
}

onMounted(load)
</script>

<template>
  <div class="aurora-page">
    <h2 class="aurora-title">优惠券</h2>

    <el-tabs v-model="tab">
      <el-tab-pane label="可领取" name="templates">
        <div v-loading="loading" class="aurora-coupons">
          <el-card v-for="template in templates" :key="template.id" class="aurora-coupon" shadow="hover">
            <div class="aurora-coupon__amount aurora-amount">{{ formatYuan(template.discountAmount) }}</div>
            <div class="aurora-coupon__title">{{ template.title }}</div>
            <div class="aurora-coupon__rule">
              满 {{ formatYuan(template.thresholdAmount) }} 可用 · 剩余 {{ template.remaining }} 张
            </div>
            <div class="aurora-coupon__window">
              {{ formatDateTime(template.claimStartAt) }} ~ {{ formatDateTime(template.claimEndAt) }}
            </div>
            <el-button
              type="primary"
              size="small"
              :disabled="template.remaining <= 0"
              :loading="claimingId === template.id"
              @click="claim(template.id)"
            >
              {{ template.remaining > 0 ? '领取' : '已领完' }}
            </el-button>
          </el-card>
        </div>
        <el-empty v-if="!loading && templates.length === 0" description="当前没有可领取的券" />
      </el-tab-pane>

      <el-tab-pane label="我的券" name="mine">
        <el-table v-loading="loading" :data="mine" empty-text="还没有券">
          <el-table-column prop="title" label="券" min-width="160" />
          <el-table-column label="优惠" width="130" align="right">
            <template #default="{ row }">
              <span class="aurora-amount">{{ formatYuan(row.discountAmount) }}</span>
            </template>
          </el-table-column>
          <el-table-column label="门槛" width="130" align="right">
            <template #default="{ row }">
              <span class="aurora-amount">满 {{ formatYuan(row.thresholdAmount) }}</span>
            </template>
          </el-table-column>
          <el-table-column label="状态" width="140">
            <template #default="{ row }">
              <el-tag :type="statusTagType(row.status)">{{ COUPON_STATUS_LABEL[row.status] ?? row.status }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column label="有效期至" width="170">
            <template #default="{ row }">{{ formatDateTime(row.expireAt) }}</template>
          </el-table-column>
          <el-table-column label="关联订单" width="140">
            <template #default="{ row }">
              <RouterLink v-if="row.orderId" :to="{ name: 'order-detail', params: { id: row.orderId } }">
                {{ row.orderId }}
              </RouterLink>
              <span v-else>—</span>
            </template>
          </el-table-column>
        </el-table>
      </el-tab-pane>
    </el-tabs>
  </div>
</template>

<style scoped>
.aurora-title {
  font-size: 20px;
  margin: 8px 0 16px;
}

.aurora-coupons {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(240px, 1fr));
  gap: 16px;
  min-height: 80px;
}

.aurora-coupon__amount {
  font-size: 24px;
  font-weight: 700;
  color: #f56c6c;
  text-align: left;
}

.aurora-coupon__title {
  font-weight: 600;
  margin: 8px 0;
}

.aurora-coupon__rule,
.aurora-coupon__window {
  font-size: 12px;
  color: #909399;
  margin-bottom: 6px;
}

.aurora-coupon__window {
  margin-bottom: 12px;
}
</style>
