<script setup lang="ts">
import { ElMessage } from 'element-plus'
import { computed, onMounted, ref } from 'vue'

import { orderApi, productApi } from '@/api'
import type { OrderView } from '@/api/order'
import { ORDER_STATUS_LABEL, OrderStatus } from '@/api/order'
import EmptyState from '@/components/EmptyState.vue'
import PageHead from '@/components/PageHead.vue'
import StatusPill from '@/components/StatusPill.vue'
import { describeError } from '@/utils/errors'
import { formatYuan } from '@/utils/money'

type Filter = 'all' | 'created' | 'paid' | 'closed'

const orders = ref<OrderView[]>([])
const productTitles = ref<Record<number, string>>({})
const total = ref(0)
const current = ref(1)
const size = ref(10)
const loading = ref(true)
const filter = ref<Filter>('all')

const filters: Array<{ id: Filter; label: string }> = [
  { id: 'all', label: '全部' },
  { id: 'created', label: '待支付' },
  { id: 'paid', label: '已支付' },
  { id: 'closed', label: '已关闭' }
]

const filtered = computed(() => {
  if (filter.value === 'all') {
    return orders.value
  }
  const status = filter.value === 'created' ? OrderStatus.CREATED : filter.value === 'paid' ? OrderStatus.PAID : OrderStatus.CLOSED
  return orders.value.filter((order) => order.status === status)
})

const counts = computed(() => ({
  total: orders.value.length,
  created: orders.value.filter((order) => order.status === OrderStatus.CREATED).length,
  paid: orders.value.filter((order) => order.status === OrderStatus.PAID).length
}))

function titleOf(skuId: number): string {
  return productTitles.value[skuId] ?? `商品编号 ${skuId}`
}

function statusTone(status: number): 'pending' | 'paid' | 'closed' {
  if (status === OrderStatus.PAID) {
    return 'paid'
  }
  return status === OrderStatus.CREATED ? 'pending' : 'closed'
}

async function load(): Promise<void> {
  loading.value = true
  try {
    const page = await orderApi.list(current.value, size.value)
    orders.value = page.records
    total.value = page.total

    const ids = [...new Set(page.records.map((order) => order.skuId))]
    if (ids.length > 0) {
      try {
        const skus = await productApi.batch(ids)
        productTitles.value = Object.fromEntries(skus.map((sku) => [sku.id, sku.title]))
      } catch {
        productTitles.value = {}
      }
    }
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

onMounted(load)
</script>

<template>
  <div class="app-page">
    <PageHead title="我的订单" meta="订单按下单时间倒序；金额由订单服务计算，状态直接来自服务端。">
      <template #actions>
        <button type="button" class="btn btn--outline" :disabled="loading" @click="load">
          {{ loading ? '刷新中…' : '刷新订单' }}
        </button>
      </template>
    </PageHead>

    <dl class="summary-strip" aria-label="本页订单概况">
      <div>
        <dt>本页订单</dt>
        <dd>{{ counts.total }} <span>笔</span></dd>
      </div>
      <div>
        <dt>待支付</dt>
        <dd>{{ counts.created }} <span>笔</span></dd>
      </div>
      <div>
        <dt>已支付</dt>
        <dd>{{ counts.paid }} <span>笔</span></dd>
      </div>
    </dl>

    <div class="filter-row" aria-label="按状态筛选本页订单">
      <button
        v-for="item in filters"
        :key="item.id"
        type="button"
        class="filter-chip"
        :aria-pressed="filter === item.id"
        @click="filter = item.id"
      >
        {{ item.label }}
      </button>
    </div>

    <div v-if="loading && orders.length === 0" aria-busy="true" aria-label="正在加载订单">
      <div class="skeleton-block skeleton-row" />
      <div class="skeleton-block skeleton-row" />
      <div class="skeleton-block skeleton-row" />
    </div>

    <EmptyState
      v-else-if="orders.length === 0"
      title="还没有订单"
      hint="结算成功后的订单会出现在这里；下单后 30 分钟内完成支付。"
    >
      <RouterLink class="btn" to="/products">去目录挑选</RouterLink>
    </EmptyState>

    <EmptyState
      v-else-if="filtered.length === 0"
      title="本页没有符合条件的订单"
      hint="切换筛选条件，或者到下一页看看。"
    />

    <section v-else class="panel" aria-label="订单列表">
      <div class="order-row" v-for="order in filtered" :key="order.orderId">
        <div>
          <p class="order-row__id">订单 {{ order.orderId }}</p>
          <p class="order-row__meta">{{ titleOf(order.skuId) }}</p>
        </div>
        <div>
          <p class="order-row__meta">数量</p>
          <p class="num">{{ order.quantity }}</p>
        </div>
        <div class="order-row__amount">{{ formatYuan(order.totalAmount) }}</div>
        <StatusPill :tone="statusTone(order.status)" :label="ORDER_STATUS_LABEL[order.status]" />
        <div class="order-row__end">
          <RouterLink
            class="btn btn--outline btn--sm"
            :to="{ name: 'order-detail', params: { id: order.orderId } }"
          >
            查看
          </RouterLink>
        </div>
      </div>
    </section>

    <div v-if="total > size" class="catalog-pager">
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
