<script setup lang="ts">
import { ElMessage } from 'element-plus'
import { computed, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'

import { orderApi, paymentApi } from '@/api'
import type { OrderView } from '@/api/order'
import { ORDER_STATUS_LABEL, OrderStatus } from '@/api/order'
import type { PaymentView } from '@/api/payment'
import { PAYMENT_STATUS_LABEL, PaymentStatus } from '@/api/payment'
import { describeError } from '@/utils/errors'
import { formatYuan } from '@/utils/money'

const route = useRoute()
const router = useRouter()

const order = ref<OrderView | null>(null)
const payment = ref<PaymentView | null>(null)
const loading = ref(false)
const paying = ref(false)

const orderId = computed(() => Number(route.params.id))

const canPay = computed(
  () => order.value !== null && order.value.status === OrderStatus.CREATED && payment.value?.status !== PaymentStatus.PAID
)

/** 本地联调时用来模拟渠道回调的命令；金额必须与订单一致，后端会比对。 */
const mockPayCommand = computed(() =>
  order.value ? `bash docker/mock-pay.sh ${order.value.orderId} ${order.value.totalAmount.toFixed(2)}` : ''
)

async function load(): Promise<void> {
  loading.value = true
  order.value = null
  payment.value = null
  try {
    order.value = await orderApi.detail(orderId.value)
    await loadPayment()
  } catch (error) {
    ElMessage.error(describeError(error))
  } finally {
    loading.value = false
  }
}

async function loadPayment(): Promise<void> {
  try {
    payment.value = await paymentApi.byOrder(orderId.value)
  } catch {
    // 还没发起支付过：支付单不存在是正常情况，不是错误
    payment.value = null
  }
}

async function initiatePayment(): Promise<void> {
  paying.value = true
  try {
    payment.value = await paymentApi.initiate(orderId.value)
    ElMessage.success('支付单已创建，等待渠道回调')
  } catch (error) {
    ElMessage.error(describeError(error))
  } finally {
    paying.value = false
  }
}

/** 渠道回调是异步的，刷新一次看看状态有没有变。 */
async function refresh(): Promise<void> {
  await load()
}

onMounted(load)
watch(orderId, load)
</script>

<template>
  <div class="aurora-page">
    <el-page-header content="订单详情" @back="router.back()" />

    <el-skeleton v-if="loading" :rows="4" animated class="aurora-skeleton" />

    <template v-else-if="order">
      <el-descriptions :column="1" border class="aurora-order__info">
        <el-descriptions-item label="订单号">{{ order.orderId }}</el-descriptions-item>
        <el-descriptions-item label="商品编号">{{ order.skuId }}</el-descriptions-item>
        <el-descriptions-item label="数量">{{ order.quantity }}</el-descriptions-item>
        <el-descriptions-item label="金额">
          <span class="aurora-amount">{{ formatYuan(order.totalAmount) }}</span>
        </el-descriptions-item>
        <el-descriptions-item label="订单状态">{{ ORDER_STATUS_LABEL[order.status] }}</el-descriptions-item>
        <el-descriptions-item label="支付状态">
          {{ payment ? PAYMENT_STATUS_LABEL[payment.status] : '尚未发起支付' }}
        </el-descriptions-item>
      </el-descriptions>

      <div class="aurora-order__actions">
        <el-button v-if="canPay" type="primary" :loading="paying" @click="initiatePayment">
          发起支付
        </el-button>
        <el-button @click="refresh">刷新状态</el-button>
        <el-button @click="router.push({ name: 'orders' })">返回列表</el-button>
      </div>

      <el-alert
        v-if="order.status === OrderStatus.CREATED"
        type="warning"
        :closable="false"
        show-icon
        title="30 分钟内未支付会自动关单"
        description="关单时库存会自动回滚，券也会退回。"
        class="aurora-order__note"
      />

      <el-card v-if="payment && payment.status === PaymentStatus.PAYING" class="aurora-order__pay">
        <template #header>支付进行中</template>
        <p>
          支付单已创建（编号 {{ payment.paymentId }}，金额
          <span class="aurora-amount">{{ formatYuan(payment.amount) }}</span>）。
          真实支付会由渠道异步回调通知，本项目的渠道是模拟的，用下面这条命令扮演渠道发回调：
        </p>
        <el-input :model-value="mockPayCommand" readonly>
          <template #append>
            <el-button @click="refresh">回调后刷新</el-button>
          </template>
        </el-input>
        <p class="aurora-order__pay-hint">
          为什么不在页面上点一下就支付：回调必须带渠道密钥的 HMAC 签名，而密钥只存在于服务端，
          下发到浏览器就等于任何人都能伪造「已支付」。
        </p>
      </el-card>
    </template>

    <el-empty v-else description="订单不存在或不属于当前账号" />
  </div>
</template>

<style scoped>
.aurora-skeleton {
  margin-top: 16px;
}

.aurora-order__info {
  margin-top: 16px;
  max-width: 620px;
}

.aurora-order__actions {
  display: flex;
  gap: 12px;
  margin-top: 20px;
}

.aurora-order__note {
  margin-top: 20px;
  max-width: 620px;
}

.aurora-order__pay {
  margin-top: 20px;
  max-width: 720px;
}

.aurora-order__pay-hint {
  margin: 12px 0 0;
  font-size: 13px;
  color: #909399;
}
</style>
