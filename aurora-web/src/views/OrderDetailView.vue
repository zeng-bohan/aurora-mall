<script setup lang="ts">
import { ElMessage, ElMessageBox } from 'element-plus'
import { computed, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'

import { paymentApi, tradeApi, type PaymentSummary, type Trade } from '@/api/dtc'
import RefundDialog from '@/components/RefundDialog.vue'
import StatusBadge from '@/components/StatusBadge.vue'
import { formatDateTime } from '@/utils/datetime'
import { describeError } from '@/utils/errors'
import { formatYuan } from '@/utils/money'

const route = useRoute()
const router = useRouter()

const trade = ref<Trade | null>(null)
const payment = ref<PaymentSummary | null>(null)
const loading = ref(true)
const error = ref('')
const refundOpen = ref(false)
const actionLoading = ref(false)
const initialNew = route.query.new === '1'

const statusTitle: Record<string, string> = {
  CREATED: '等待支付',
  PAID: '已支付，等待发货',
  SHIPPED: '已发货，路上',
  COMPLETED: '订单已完成',
  CLOSED: '订单已关闭',
  REFUNDED: '订单已退款',
}

const statusCopy: Record<string, string> = {
  CREATED: '请在 30 分钟内完成支付，超时订单会自动关闭并释放库存。',
  PAID: '运营会在 1–2 个工作日内发货。发货前可以申请部分或整单退款。',
  SHIPPED: '商品已交给模拟承运商。收到后请确认收货，7 天后会自动完成。',
  COMPLETED: '订单已完成。感谢你把 AURORA 放进日常。',
  CLOSED: '未支付超时或已取消。库存与优惠券已经释放。',
  REFUNDED: '整单退款已经完成，支付金额与优惠券已按规则退回。',
}

const normalSteps = ['CREATED', 'PAID', 'SHIPPED', 'COMPLETED']
const currentStep = computed(() => {
  if (!trade.value) {
    return -1
  }
  return normalSteps.indexOf(trade.value.status)
})

async function load(): Promise<void> {
  loading.value = true
  error.value = ''
  try {
    trade.value = await tradeApi.detail(Number(route.params.id))
    payment.value = null
    try {
      payment.value = await paymentApi.detail(trade.value.id)
    } catch {
      payment.value = null
    }
  } catch (e) {
    error.value = describeError(e)
    trade.value = null
  } finally {
    loading.value = false
  }
}

async function pay(): Promise<void> {
  if (!trade.value) {
    return
  }
  actionLoading.value = true
  try {
    await paymentApi.initiate(trade.value.id)
    await paymentApi.mockPay(trade.value.id)
    ElMessage.success('支付完成')
    await load()
  } catch (e) {
    ElMessage.error(describeError(e))
  } finally {
    actionLoading.value = false
  }
}

async function cancel(): Promise<void> {
  if (!trade.value) {
    return
  }
  try {
    await ElMessageBox.confirm('取消后订单关闭，库存与优惠券会释放。确认取消？', '取消订单', {
      confirmButtonText: '取消订单',
      cancelButtonText: '保留订单',
      type: 'warning',
    })
    await tradeApi.cancel(trade.value.id)
    ElMessage.success('订单已取消')
    await load()
  } catch (e) {
    if (e !== 'cancel') {
      ElMessage.error(describeError(e))
    }
  }
}

async function confirmReceipt(): Promise<void> {
  if (!trade.value) {
    return
  }
  try {
    await ElMessageBox.confirm('确认已经收到商品？', '确认收货', {
      confirmButtonText: '确认收货',
      cancelButtonText: '再等等',
    })
    await tradeApi.confirm(trade.value.id)
    ElMessage.success('订单已完成')
    await load()
  } catch (e) {
    if (e !== 'cancel') {
      ElMessage.error(describeError(e))
    }
  }
}

function goList(): void {
  void router.push({ name: 'orders' })
}

watch(
  () => route.params.id,
  () => {
    initialNew && void router.replace({ name: 'order-detail', params: { id: route.params.id } })
    void load()
  },
  { immediate: true },
)
</script>

<template>
  <div>
    <div class="container breadcrumb">
      <RouterLink to="/">首页</RouterLink>
      <span>/</span>
      <RouterLink to="/orders">我的订单</RouterLink>
      <span>/</span>
      <span>{{ trade?.tradeNo ?? '订单详情' }}</span>
    </div>

    <div v-if="loading" class="container page-loading">正在读取订单…</div>
    <div v-else-if="error || !trade" class="container">
      <div class="error-banner">{{ error || '订单不存在' }}</div>
      <button class="btn btn--line" type="button" style="margin-top: 16px" @click="goList">返回订单列表</button>
    </div>

    <template v-else>
      <div v-if="initialNew && trade.status === 'CREATED'" class="container">
        <div class="seckill-result" style="background: var(--success-soft); color: var(--success)">
          订单已创建。完成支付后，运营会开始处理发货。
        </div>
      </div>

      <div class="container" style="margin-top: 16px">
        <div class="status-hero">
          <div>
            <span class="eyebrow">{{ trade.source === 'SECKILL' ? 'Seckill Order' : 'Order' }}</span>
            <h1 class="display">{{ statusTitle[trade.status] }}</h1>
            <p>{{ statusCopy[trade.status] }}</p>
          </div>
          <div style="text-align: right">
            <StatusBadge :status="trade.status" />
            <div class="tiny" style="margin-top: 10px">{{ trade.tradeNo }}</div>
          </div>
        </div>

        <div v-if="['CREATED', 'PAID', 'SHIPPED', 'COMPLETED'].includes(trade.status)" class="status-steps">
          <div
            v-for="(step, index) in ['提交订单', '完成支付', '运营发货', '确认收货']"
            :key="step"
            class="status-step"
            :class="{ 'is-done': index <= currentStep }"
          >
            {{ step }}
          </div>
        </div>

        <div class="detail-grid" style="margin-top: 20px">
          <div>
            <section class="detail-panel">
              <h3>商品</h3>
              <div class="checkout-items">
                <div v-for="item in trade.items" :key="item.id" class="checkout-item">
                  <div class="checkout-item__media">
                    <img :src="item.imageUrl" :alt="item.title" />
                  </div>
                  <div>
                    <div class="checkout-item__title">{{ item.title }}</div>
                    <div class="checkout-item__meta">
                      {{ item.attributesText }} · ×{{ item.quantity }} · {{ formatYuan(item.unitPrice) }}
                    </div>
                    <div v-if="item.discountAmount > 0" class="tiny">
                      分摊优惠 {{ formatYuan(item.discountAmount) }}
                    </div>
                    <div v-if="item.refundedAmount > 0" class="error-text">
                      已退 {{ formatYuan(item.refundedAmount) }}
                    </div>
                  </div>
                  <div style="text-align: right">
                    <div class="checkout-item__price">{{ formatYuan(item.itemAmount) }}</div>
                    <StatusBadge v-if="item.status !== 'NORMAL'" :status="item.status" />
                  </div>
                </div>
              </div>
            </section>

            <section class="detail-panel">
              <h3>收货信息</h3>
              <div class="address-option__name">{{ trade.address.receiverName }} · {{ trade.address.phone }}</div>
              <div class="address-option__line">
                {{ trade.address.province }} {{ trade.address.city }} {{ trade.address.district }}
                {{ trade.address.detail }}
                <span v-if="trade.address.postalCode"> · {{ trade.address.postalCode }}</span>
              </div>
              <p class="hint" style="margin-top: 10px">地址为下单时快照，修改地址簿不会影响这笔订单。</p>
            </section>

            <section v-if="trade.carrier || trade.trackingNo" class="detail-panel">
              <h3>模拟物流</h3>
              <div class="detail-panel">
                <div class="summary-row">
                  <span>承运商</span>
                  <strong>{{ trade.carrier || '待补充' }}</strong>
                </div>
                <div class="summary-row" style="margin-top: 8px">
                  <span>运单号</span>
                  <span class="mono">{{ trade.trackingNo || '待补充' }}</span>
                </div>
                <p class="hint" style="margin-top: 10px">
                  运单号为模拟数据，不连接真实承运商。发货后 7 天未确认将自动完成。
                </p>
              </div>
            </section>

            <section v-if="trade.refunds.length" class="detail-panel">
              <h3>退款进度</h3>
              <div v-for="refund in trade.refunds" :key="refund.id" class="refund-line">
                <div>
                  <div style="display: flex; align-items: center; gap: 8px">
                    <strong>{{ formatYuan(refund.amount) }}</strong>
                    <StatusBadge :status="refund.status" />
                  </div>
                  <div class="tiny" style="margin-top: 4px">{{ refund.reason }}</div>
                  <div v-if="refund.rejectReason" class="error-text" style="margin-top: 4px">
                    拒绝原因：{{ refund.rejectReason }}
                  </div>
                </div>
                <div class="tiny" style="text-align: right">
                  <div>{{ formatDateTime(refund.createdAt) }}</div>
                  <div v-if="refund.reviewedAt">审批 {{ formatDateTime(refund.reviewedAt) }}</div>
                </div>
              </div>
            </section>

            <section class="detail-panel">
              <h3>订单信息</h3>
              <div class="summary-row">
                <span>订单号</span>
                <span class="mono">{{ trade.tradeNo }}</span>
              </div>
              <div class="summary-row" style="margin-top: 8px">
                <span>来源</span>
                <span>{{ trade.source === 'SECKILL' ? '秒杀' : '商城' }}</span>
              </div>
              <div class="summary-row" style="margin-top: 8px">
                <span>下单时间</span>
                <span>{{ formatDateTime(trade.createdAt) }}</span>
              </div>
              <div v-if="trade.paidAt" class="summary-row" style="margin-top: 8px">
                <span>支付时间</span>
                <span>{{ formatDateTime(trade.paidAt) }}</span>
              </div>
              <div v-if="trade.shippedAt" class="summary-row" style="margin-top: 8px">
                <span>发货时间</span>
                <span>{{ formatDateTime(trade.shippedAt) }}</span>
              </div>
              <div v-if="trade.completedAt" class="summary-row" style="margin-top: 8px">
                <span>完成时间</span>
                <span>{{ formatDateTime(trade.completedAt) }}</span>
              </div>
              <div v-if="trade.closedAt" class="summary-row" style="margin-top: 8px">
                <span>关闭时间</span>
                <span>{{ formatDateTime(trade.closedAt) }}</span>
              </div>
            </section>
          </div>

          <aside>
            <div class="summary-card" style="position: static">
              <h3>金额</h3>
              <div class="summary-list">
                <div class="summary-row">
                  <span>商品金额</span>
                  <strong>{{ formatYuan(trade.merchandiseAmount) }}</strong>
                </div>
                <div class="summary-row">
                  <span>优惠</span>
                  <span>{{ trade.discountAmount > 0 ? `− ${formatYuan(trade.discountAmount)}` : '—' }}</span>
                </div>
                <div class="summary-row">
                  <span>运费</span>
                  <span>{{ trade.shippingFee === 0 ? '免运费' : formatYuan(trade.shippingFee) }}</span>
                </div>
                <div v-if="trade.refundedAmount > 0" class="summary-row">
                  <span>已退款</span>
                  <span>− {{ formatYuan(trade.refundedAmount) }}</span>
                </div>
              </div>
              <div class="summary-total">
                <span>{{ trade.status === 'REFUNDED' ? '已退款' : '应付 / 实付' }}</span>
                <strong>{{ formatYuan(trade.payableAmount) }}</strong>
              </div>
              <button
                v-if="trade.status === 'CREATED'"
                class="btn btn--primary btn--block"
                type="button"
                :disabled="actionLoading"
                @click="pay"
              >
                {{ actionLoading ? '支付中…' : '模拟支付' }}
              </button>
              <button
                v-if="trade.status === 'PAID'"
                class="btn btn--line btn--block"
                type="button"
                style="margin-top: 10px"
                @click="refundOpen = true"
              >
                申请退款
              </button>
              <button
                v-if="trade.status === 'SHIPPED'"
                class="btn btn--primary btn--block"
                type="button"
                style="margin-top: 10px"
                @click="confirmReceipt"
              >
                确认收货
              </button>
              <button
                v-if="trade.status === 'CREATED'"
                class="btn btn--ghost btn--block"
                type="button"
                style="margin-top: 8px"
                @click="cancel"
              >
                取消订单
              </button>
              <div v-if="payment" class="summary-note">
                支付单 {{ payment.status }} · 已退 {{ formatYuan(payment.refundedAmount) }}
              </div>
              <p v-if="trade.couponTitle" class="summary-note">优惠券：{{ trade.couponTitle }}</p>
            </div>
          </aside>
        </div>
      </div>

      <RefundDialog v-model="refundOpen" :trade="trade" @applied="load" />
    </template>
  </div>
</template>
