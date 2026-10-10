<script setup lang="ts">
import { onMounted, ref } from 'vue'

import { orderApi } from '@/api'
import type { OrderView } from '@/api/order'
import { ORDER_STATUS_LABEL, OrderStatus } from '@/api/order'
import { useMyOrdersStore } from '@/stores/myOrders'
import { formatYuan } from '@/utils/money'

const myOrders = useMyOrdersStore()

const orders = ref<OrderView[]>([])
const missingIds = ref<number[]>([])
const loading = ref(false)

/**
 * 后端没有「我的订单列表」接口，只能拿本地记下的 id 逐个查详情。
 * 所以这里是 N 次请求——订单多了会明显变慢，正解是后端补一个按 userId 分页的端点。
 */
async function load(): Promise<void> {
  loading.value = true
  myOrders.load()
  const loaded: OrderView[] = []
  const missing: number[] = []
  for (const orderId of myOrders.orderIds) {
    try {
      loaded.push(await orderApi.detail(orderId))
    } catch {
      // 查不到的多半是本地记录与账号对不上（换过库/清过数据）：记下来，不打断整页
      missing.push(orderId)
    }
  }
  orders.value = loaded
  missingIds.value = missing
  loading.value = false
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

    <el-alert
      type="info"
      :closable="false"
      show-icon
      title="订单列表保存在本机"
      description="后端目前没有「我的订单列表」接口，这份列表来自你在这台浏览器上下过的单。换浏览器或清缓存后列表会变空，正解是后端补一个按用户查询的端点。"
      class="aurora-orders__note"
    />

    <el-table v-loading="loading" :data="orders" empty-text="还没有订单" class="aurora-orders__table">
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
          <el-button text type="primary" @click="$router.push({ name: 'order-detail', params: { id: row.orderId } })">
            查看
          </el-button>
        </template>
      </el-table-column>
    </el-table>

    <p v-if="missingIds.length > 0" class="aurora-orders__missing">
      有 {{ missingIds.length }} 条本地记录查不到对应订单（可能不属于当前账号）：{{
        missingIds.join('、')
      }}
      <el-button text type="primary" @click="myOrders.forgetAll()">清掉这些本地记录</el-button>
    </p>
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

.aurora-orders__note {
  margin-bottom: 16px;
}

.aurora-orders__table {
  margin-bottom: 12px;
}

.aurora-orders__missing {
  font-size: 13px;
  color: #e6a23c;
}
</style>
