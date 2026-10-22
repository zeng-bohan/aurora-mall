<script setup lang="ts">
import { ElMessage, ElMessageBox } from 'element-plus'
import { onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'

import { paymentApi, tradeApi, type Trade, type TradeRow } from '@/api/dtc'
import EmptyState from '@/components/EmptyState.vue'
import RefundDialog from '@/components/RefundDialog.vue'
import StatusBadge from '@/components/StatusBadge.vue'
import { formatDateTime } from '@/utils/datetime'
import { describeError } from '@/utils/errors'
import { formatYuan } from '@/utils/money'

const router = useRouter()

const tabs = [
  { key: '', label: '全部' },
  { key: 'CREATED', label: '待支付' },
  { key: 'PAID', label: '待发货' },
  { key: 'SHIPPED', label: '已发货' },
  { key: 'COMPLETED', label: '已完成' },
  { key: 'CLOSED', label: '已关闭' },
  { key: 'REFUNDED', label: '已退款' },
]
const activeTab = ref('')
const trades = ref<TradeRow[]>([])
const total = ref(0)
const pages = ref(0)
const current = ref(1)
const size = 8
const loading = ref(true)
const error = ref('')
const refundOpen = ref(false)
const refundTrade = ref<Trade | null>(null)

async function load(): Promise<void> {
  loading.value = true
  error.value = ''
  try {
    const page = await tradeApi.list({
      status: activeTab.value || undefined,
      current: current.value,
      size,
    })
    trades.value = page.records
    total.value = page.total
    pages.value = page.pages
  } catch (e) {
    error.value = describeError(e)
    trades.value = []
  } finally {
    loading.value = false
  }
}

function selectTab(key: string): void {
  activeTab.value = key
  current.value = 1
  void load()
}

async function pay(row: TradeRow): Promise<void> {
  try {
    await paymentApi.initiate(row.id)
    await paymentApi.mockPay(row.id)
    ElMessage.success('支付完成')
    await load()
  } catch (e) {
    ElMessage.error(describeError(e))
  }
}

async function cancel(row: TradeRow): Promise<void> {
  try {
    await ElMessageBox.confirm('取消后订单会关闭，库存和优惠券会释放。确认取消？', '取消订单', {
      confirmButtonText: '取消订单',
      cancelButtonText: '保留订单',
      type: 'warning',
    })
    await tradeApi.cancel(row.id)
    ElMessage.success('订单已取消')
    await load()
  } catch (e) {
    if (e instanceof Error && e.message === 'cancel') {
      return
    }
    if (e !== 'cancel') {
      ElMessage.error(describeError(e))
    }
  }
}

async function confirmReceipt(row: TradeRow): Promise<void> {
  try {
    await ElMessageBox.confirm('确认已经收到商品？', '确认收货', {
      confirmButtonText: '确认收货',
      cancelButtonText: '再等等',
    })
    await tradeApi.confirm(row.id)
    ElMessage.success('订单已完成')
    await load()
  } catch (e) {
    if (e !== 'cancel') {
      ElMessage.error(describeError(e))
    }
  }
}

async function openRefund(row: TradeRow): Promise<void> {
  try {
    refundTrade.value = await tradeApi.detail(row.id)
    refundOpen.value = true
  } catch (e) {
    ElMessage.error(describeError(e))
  }
}

function goDetail(row: TradeRow): void {
  void router.push({ name: 'order-detail', params: { id: row.id } })
}

onMounted(() => {
  void load()
})
</script>

<template>
  <div>
    <div class="container page-head">
      <span class="eyebrow">Orders</span>
      <h1 class="display">我的订单。</h1>
      <p>一个订单对应一次支付和一次发货。退款进度与订单状态同步展示。</p>
    </div>

    <div class="container">
      <div class="order-tabs">
        <button
          v-for="tab in tabs"
          :key="tab.key"
          class="order-tab"
          :class="{ 'is-active': activeTab === tab.key }"
          type="button"
          @click="selectTab(tab.key)"
        >
          {{ tab.label }}
        </button>
      </div>

      <div v-if="loading" class="page-loading">正在读取订单…</div>
      <div v-else-if="error" class="error-banner">{{ error }}</div>
      <EmptyState
        v-else-if="!trades.length"
        title="这里还没有订单"
        description="完成一次结算后，订单会出现在这里。"
      >
        <RouterLink class="btn btn--primary" to="/products">去逛逛</RouterLink>
      </EmptyState>

      <div v-else>
        <article v-for="row in trades" :key="row.id" class="order-card">
          <div class="order-card__head">
            <div class="order-card__meta">
              <span class="mono">{{ row.tradeNo }}</span>
              <span>{{ formatDateTime(row.createdAt) }}</span>
              <span>{{ row.source === 'SECKILL' ? '秒杀' : '商城' }}</span>
            </div>
            <StatusBadge :status="row.status" />
          </div>

          <div class="order-items">
            <div v-for="item in row.items.slice(0, 3)" :key="item.id" class="order-item">
              <RouterLink class="order-item__media" :to="`/orders/${row.id}`">
                <img :src="item.imageUrl" :alt="item.title" />
              </RouterLink>
              <div>
                <div class="order-item__title">{{ item.title }}</div>
                <div class="order-item__meta">{{ item.attributesText }} · ×{{ item.quantity }}</div>
              </div>
              <div class="order-item__meta">{{ formatYuan(item.unitPrice) }}</div>
            </div>
            <div v-if="row.items.length > 3" class="tiny">另有 {{ row.items.length - 3 }} 件商品</div>
          </div>

          <div class="order-card__foot">
            <div>
              <span class="order-card__amount">{{ formatYuan(row.payableAmount) }}</span>
              <span v-if="row.refundedAmount > 0" class="tiny" style="margin-left: 10px">
                已退 {{ formatYuan(row.refundedAmount) }}
              </span>
              <span v-if="row.refundStatus !== 'NONE'" class="tag" :class="row.refundStatus === 'FULL' ? 'tag--danger' : 'tag--warning'">{{ row.refundStatus === 'FULL' ? '整单退款' : '部分退款' }}</span>
            </div>
            <div class="order-actions">
              <button class="btn btn--ghost btn--sm" type="button" @click="goDetail(row)">查看详情</button>
              <button v-if="row.status === 'CREATED'" class="btn btn--primary btn--sm" type="button" @click="pay(row)">
                去支付
              </button>
              <button v-if="row.status === 'CREATED'" class="btn btn--line btn--sm" type="button" @click="cancel(row)">
                取消订单
              </button>
              <button v-if="row.status === 'PAID'" class="btn btn--line btn--sm" type="button" @click="openRefund(row)">
                申请退款
              </button>
              <button v-if="row.status === 'SHIPPED'" class="btn btn--primary btn--sm" type="button" @click="confirmReceipt(row)">
                确认收货
              </button>
            </div>
          </div>
        </article>

        <div v-if="pages > 1" class="pagination-row">
          <el-pagination
            background
            layout="prev, pager, next"
            :total="total"
            :page-size="size"
            :current-page="current"
            @current-change="(page: number) => { current = page; load() }"
          />
        </div>
      </div>
    </div>

    <RefundDialog v-model="refundOpen" :trade="refundTrade" @applied="load" />
  </div>
</template>
