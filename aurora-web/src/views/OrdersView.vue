<script setup lang="ts">
import { ElMessage } from 'element-plus'
import { onMounted, ref } from 'vue'

import { orderApi } from '@/api'
import type { OrderView } from '@/api/order'
import { ORDER_STATUS_LABEL, OrderStatus } from '@/api/order'
import { describeError } from '@/utils/errors'
import { formatYuan } from '@/utils/money'

const orders = ref<OrderView[]>([])
const total = ref(0)
const current = ref(1)
const size = ref(10)
const loading = ref(false)

async function load(): Promise<void> {
  loading.value = true
  try {
    const page = await orderApi.list(current.value, size.value)
    orders.value = page.records
    total.value = page.total
  } catch (error) {
    ElMessage.error(describeError(error))
  } finally {
    loading.value = false
  }
}

function onChangePage(next: number): void {
  current.value = next
  void load()
}

function statusTagType(status: number): 'success' | 'warning' | 'info' {
  if (status === OrderStatus.PAID) {
    return 'success'
  }
  return status === OrderStatus.CREATED ? 'warning' : 'info'
}

onMounted(load)
</script>

<template>
  <div class="aurora-page">
    <div class="aurora-orders__head">
      <h2 class="aurora-title">我的订单</h2>
      <el-button :loading="loading" @click="load">刷新</el-button>
    </div>

    <el-table v-loading="loading" :data="orders" empty-text="还没有订单" border>
      <el-table-column prop="orderId" label="订单号" width="180" />
      <el-table-column prop="skuId" label="商品编号" width="140" />
      <el-table-column prop="quantity" label="数量" width="90" align="center" />
      <el-table-column label="金额" width="130" align="right">
        <template #default="{ row }">
          <span class="aurora-amount">{{ formatYuan(row.totalAmount) }}</span>
        </template>
      </el-table-column>
      <el-table-column label="状态" width="120">
        <template #default="{ row }">
          <el-tag :type="statusTagType(row.status)">{{ ORDER_STATUS_LABEL[row.status] }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="操作" width="120">
        <template #default="{ row }">
          <el-button
            text
            type="primary"
            @click="$router.push({ name: 'order-detail', params: { id: row.orderId } })"
          >
            查看
          </el-button>
        </template>
      </el-table-column>
    </el-table>

    <div v-if="total > size" class="aurora-pager">
      <el-pagination
        layout="prev, pager, next, total"
        :total="total"
        :page-size="size"
        :current-page="current"
        @current-change="onChangePage"
      />
    </div>
  </div>
</template>

<style scoped>
.aurora-orders__head {
  display: flex;
  justify-content: space-between;
  align-items: center;
}

.aurora-title {
  font-size: 20px;
  margin: 8px 0 16px;
}

.aurora-pager {
  display: flex;
  justify-content: center;
  margin-top: 20px;
}
</style>
