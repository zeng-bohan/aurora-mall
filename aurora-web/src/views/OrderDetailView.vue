<script setup lang="ts">
import { ElMessage } from 'element-plus'
import { computed, onMounted, ref, watch } from 'vue'
import { useRoute } from 'vue-router'

import { orderApi, paymentApi, productApi } from '@/api'
import type { OrderView } from '@/api/order'
import { ORDER_STATUS_LABEL, OrderStatus } from '@/api/order'
import type { PaymentView } from '@/api/payment'
import { PAYMENT_STATUS_LABEL, PaymentStatus } from '@/api/payment'
import type { Sku } from '@/api/product'
import OrderLifecycle from '@/components/OrderLifecycle.vue'
import PageHead from '@/components/PageHead.vue'
import ProductFigure from '@/components/ProductFigure.vue'
import StatusPill from '@/components/StatusPill.vue'
import { describeError } from '@/utils/errors'
import { formatYuan } from '@/utils/money'

const route = useRoute()

const order = ref<OrderView | null>(null)
const payment = ref<PaymentView | null>(null)
const sku = ref<Sku | null>(null)
const loading = ref(true)
const paying = ref(false)

const orderId = computed(() => Number(route.params.id))

const canPay = computed(
  () =>
    order.value !== null &&
    order.value.status === OrderStatus.CREATED &&
    payment.value?.status !== PaymentStatus.PAID
)

const statusTone = computed<'pending' | 'paid' | 'closed'>(() => {
  if (order.value?.status === OrderStatus.PAID) {
    return 'paid'
  }
  return order.value?.status === OrderStatus.CREATED ? 'pending' : 'closed'
})

const mockPayCommand = computed(() =>
  order.value ? `bash docker/mock-pay.sh ${order.value.orderId} ${order.value.totalAmount.toFixed(2)}` : ''
)

async function load(): Promise<void> {
  loading.value = true
  order.value = null
  payment.value = null
  sku.value = null
  try {
    const current = await orderApi.detail(orderId.value)
    order.value = current
    await Promise.all([loadPayment(), loadSku(current.skuId)])
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
    payment.value = null
  }
}

async function loadSku(skuId: number): Promise<void> {
  try {
    sku.value = await productApi.detail(skuId)
  } catch {
    sku.value = null
  }
}

async function initiatePayment(): Promise<void> {
  paying.value = true
  try {
    payment.value = await paymentApi.initiate(orderId.value)
    ElMessage.success('模拟支付单已创建，等待本机回调')
  } catch (error) {
    ElMessage.error(describeError(error))
  } finally {
    paying.value = false
  }
}

async function refresh(): Promise<void> {
  await load()
}

onMounted(load)
watch(orderId, load)
</script>

<template>
  <div class="app-page">
    <PageHead
      back-to="/orders"
      back-label="返回订单列表"
      :title="order ? `订单 ${order.orderId}` : '订单详情'"
      :meta="order ? '金额与状态来自订单服务；支付结果只认渠道回调。' : '正在读取订单状态。'"
    >
      <template #actions>
        <button type="button" class="btn btn--outline" :disabled="loading" @click="refresh">
          {{ loading ? '刷新中…' : '刷新状态' }}
        </button>
      </template>
    </PageHead>

    <div v-if="loading" aria-busy="true" aria-label="正在加载订单">
      <div class="skeleton-block skeleton-row" />
      <div class="skeleton-block skeleton-row" />
      <div class="skeleton-block skeleton-row" />
    </div>

    <div v-else-if="!order" class="empty-state">
      <h2>订单不存在或不属于当前账户</h2>
      <p>为了避免泄露他人订单是否存在，服务端对不属于你的订单统一返回不存在。</p>
      <RouterLink class="btn" to="/orders">回到订单列表</RouterLink>
    </div>

    <div v-else class="detail-grid">
      <div class="detail-stack">
        <section class="panel" aria-label="订单内容">
          <div class="detail-header">
            <div>
              <h2 class="row-title">{{ sku?.title ?? `商品编号 ${order.skuId}` }}</h2>
              <p class="detail-header__meta">
                数量 {{ order.quantity }} · 单价按订单快照计算
              </p>
            </div>
            <StatusPill :tone="statusTone" :label="ORDER_STATUS_LABEL[order.status]" />
          </div>

          <div class="order-product">
            <div class="order-product__figure">
              <ProductFigure
                :title="sku?.title ?? `商品 ${order.skuId}`"
                :label="`${sku?.title ?? '商品'} 的目录图形`"
              />
            </div>
            <dl class="detail-facts">
              <div>
                <dt>订单号</dt>
                <dd>{{ order.orderId }}</dd>
              </div>
              <div>
                <dt>商品编号</dt>
                <dd>{{ order.skuId }}</dd>
              </div>
              <div>
                <dt>数量</dt>
                <dd>{{ order.quantity }}</dd>
              </div>
              <div>
                <dt>订单金额</dt>
                <dd>{{ formatYuan(order.totalAmount) }}</dd>
              </div>
            </dl>
          </div>
        </section>

        <section class="panel" aria-label="订单进度">
          <h2 class="panel-head">订单进度</h2>
          <OrderLifecycle :status="order.status" />
        </section>
      </div>

      <aside class="detail-side">
        <section class="panel" aria-label="支付状态">
          <h2 class="panel-head">
            支付
            <span class="channel-tag">模拟渠道</span>
          </h2>
          <p class="channel-note">不连接真实支付机构；回调由本机脚本签名触发。</p>
          <div class="summary-line">
            <span>支付状态</span>
            <strong>{{ payment ? PAYMENT_STATUS_LABEL[payment.status] : '尚未发起支付' }}</strong>
          </div>
          <div v-if="payment" class="summary-line" style="margin-top: 8px">
            <span>支付单号</span>
            <strong class="num">{{ payment.paymentId }}</strong>
          </div>
          <div v-if="payment" class="summary-line" style="margin-top: 8px">
            <span>支付金额</span>
            <strong class="num">{{ formatYuan(payment.amount) }}</strong>
          </div>

          <div class="summary-actions" style="margin-top: 16px">
            <button
              v-if="canPay"
              type="button"
              class="btn btn--block"
              :disabled="paying"
              @click="initiatePayment"
            >
              {{ paying ? '创建支付单…' : '发起模拟支付' }}
            </button>
            <RouterLink v-if="payment" class="btn btn--outline btn--block" to="/orders">
              返回订单列表
            </RouterLink>
          </div>
        </section>

        <div v-if="order.status === OrderStatus.CREATED" class="notice notice--warning">
          <strong>30 分钟内未支付会自动关单</strong>
          <p>关单时库存会回滚，优惠券也会按订单状态退回。</p>
        </div>

        <details v-if="payment && payment.status === PaymentStatus.PAYING" class="pay-details">
          <summary>本机演示：模拟渠道回调</summary>
          <p>
            真实支付由渠道异步回调。回调必须带服务端密钥的 HMAC 签名，密钥不会下发浏览器；这里用本机脚本扮演渠道。
          </p>
          <code class="code-block">{{ mockPayCommand }}</code>
          <button type="button" class="btn btn--outline btn--sm" @click="refresh">
            回调后刷新状态
          </button>
        </details>

        <details class="pay-details">
          <summary>为什么页面上不能直接“一键支付”</summary>
          <p>
            渠道回调必须通过 HMAC 验签，密钥只存在于服务端与 Nacos。把回调密钥打进前端包，等于允许任何人伪造“已支付”。
          </p>
        </details>
      </aside>
    </div>
  </div>
</template>

<style scoped>
.detail-stack {
  display: grid;
  gap: 16px;
}

.detail-side {
  display: grid;
  gap: 16px;
  align-content: start;
}

.order-product {
  display: grid;
  grid-template-columns: 140px minmax(0, 1fr);
  gap: 20px;
  align-items: center;
}

.order-product__figure {
  background: var(--stone);
  padding: 10px;
}

.pay-details {
  background: var(--paper);
  border: 1px solid var(--line);
  border-radius: var(--radius-app);
  padding: 16px;
}

.pay-details summary {
  cursor: pointer;
  font-size: var(--step-2);
  font-weight: 600;
}

.pay-details p {
  margin: 10px 0;
  font-size: var(--step-2);
  color: var(--muted);
  line-height: 1.55;
}

.pay-details .btn {
  margin-top: 10px;
}

@media (max-width: 560px) {
  .order-product {
    grid-template-columns: 1fr;
  }
}
</style>
