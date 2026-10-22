<script setup lang="ts">
import { ElMessage } from 'element-plus'
import { onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'

import { adminTradeApi, type TradeRow } from '@/api/dtc'
import StatusBadge from '@/components/StatusBadge.vue'
import { formatDateTime } from '@/utils/datetime'
import { describeError } from '@/utils/errors'
import { formatYuan } from '@/utils/money'

const route = useRoute()
const router = useRouter()

const rows = ref<TradeRow[]>([])
const total = ref(0)
const pages = ref(0)
const current = ref(1)
const size = 15
const statusFilter = ref(typeof route.query.status === 'string' ? route.query.status : '')
const sourceFilter = ref('')
const keyword = ref('')
const loading = ref(true)
const error = ref('')

const shipOpen = ref(false)
const shipTrade = ref<TradeRow | null>(null)
const carrier = ref('AURORA Express')
const trackingNo = ref('')
const shipping = ref(false)

const statuses = [
  { value: '', label: '全部状态' },
  { value: 'CREATED', label: '待支付' },
  { value: 'PAID', label: '待发货' },
  { value: 'SHIPPED', label: '已发货' },
  { value: 'COMPLETED', label: '已完成' },
  { value: 'CLOSED', label: '已关闭' },
  { value: 'REFUNDED', label: '已退款' },
]

async function load(): Promise<void> {
  loading.value = true
  error.value = ''
  try {
    const page = await adminTradeApi.trades({
      status: statusFilter.value || undefined,
      source: sourceFilter.value || undefined,
      keyword: keyword.value.trim() || undefined,
      current: current.value,
      size,
    })
    rows.value = page.records
    total.value = page.total
    pages.value = page.pages
  } catch (e) {
    error.value = describeError(e)
  } finally {
    loading.value = false
  }
}

function search(): void {
  current.value = 1
  void load()
}

function goDetail(row: TradeRow): void {
  void router.push({ name: 'admin-order-detail', params: { id: row.id } })
}

function openShip(row: TradeRow): void {
  shipTrade.value = row
  carrier.value = 'AURORA Express'
  trackingNo.value = ''
  shipOpen.value = true
}

async function ship(): Promise<void> {
  if (!shipTrade.value) {
    return
  }
  if (!carrier.value.trim() || !trackingNo.value.trim()) {
    ElMessage.warning('请填写承运商和运单号')
    return
  }
  shipping.value = true
  try {
    await adminTradeApi.ship(shipTrade.value.id, {
      carrier: carrier.value.trim(),
      trackingNo: trackingNo.value.trim(),
    })
    ElMessage.success('已发货')
    shipOpen.value = false
    await load()
  } catch (e) {
    ElMessage.error(describeError(e))
  } finally {
    shipping.value = false
  }
}

onMounted(() => {
  void load()
})
</script>

<template>
  <div>
    <div class="admin-page-head">
      <div>
        <h1>订单管理</h1>
        <p>父订单维度查看支付、发货、退款和履约状态。</p>
      </div>
      <div class="admin-actions">
        <button class="btn btn--line btn--sm" type="button" @click="load">刷新</button>
      </div>
    </div>

    <section class="admin-card">
      <div class="admin-toolbar">
        <select v-model="statusFilter" class="select" style="width: 130px" @change="search">
          <option v-for="item in statuses" :key="item.value" :value="item.value">{{ item.label }}</option>
        </select>
        <select v-model="sourceFilter" class="select" style="width: 120px" @change="search">
          <option value="">全部来源</option>
          <option value="MALL">商城</option>
          <option value="SECKILL">秒杀</option>
        </select>
        <input v-model="keyword" class="input" style="width: 230px" placeholder="订单号 / 收货人 / 手机号" @keyup.enter="search" />
        <button class="btn btn--primary btn--sm" type="button" @click="search">查询</button>
        <span class="admin-toolbar__spacer" />
        <span class="tiny">共 {{ total }} 笔</span>
      </div>

      <div v-if="loading" class="admin-empty">正在读取订单…</div>
      <div v-else-if="error" class="error-banner" style="margin: 16px">{{ error }}</div>
      <div v-else class="table-wrap">
        <table class="data-table">
          <thead>
            <tr>
              <th>订单</th>
              <th>收货人</th>
              <th>商品</th>
              <th>金额</th>
              <th>来源</th>
              <th>状态</th>
              <th>下单时间</th>
              <th></th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="row in rows" :key="row.id">
              <td>
                <div class="mono">{{ row.tradeNo }}</div>
                <div class="tiny">#{{ row.id }}</div>
              </td>
              <td>
                <div>{{ row.receiverName }}</div>
                <div class="tiny">{{ row.receiverPhone }}</div>
              </td>
              <td>
                <div class="table-product">
                  <img v-if="row.items[0]" :src="row.items[0].imageUrl" :alt="row.items[0].title" />
                  <div>
                    <div class="table-product__title">
                      {{ row.items[0]?.title ?? '—' }}
                      <span v-if="row.items.length > 1" class="tiny">等 {{ row.items.length }} 件</span>
                    </div>
                    <div class="table-product__meta">{{ row.items[0]?.attributesText }}</div>
                  </div>
                </div>
              </td>
              <td class="money">{{ formatYuan(row.payableAmount) }}</td>
              <td>{{ row.source === 'SECKILL' ? '秒杀' : '商城' }}</td>
              <td><StatusBadge :status="row.status" /></td>
              <td class="tiny">{{ formatDateTime(row.createdAt) }}</td>
              <td>
                <div class="table-actions">
                  <button class="btn btn--ghost btn--sm" type="button" @click="goDetail(row)">详情</button>
                  <button v-if="row.status === 'PAID'" class="btn btn--primary btn--sm" type="button" @click="openShip(row)">
                    发货
                  </button>
                </div>
              </td>
            </tr>
            <tr v-if="!rows.length">
              <td colspan="8" class="admin-empty">没有匹配的订单</td>
            </tr>
          </tbody>
        </table>
      </div>

      <div v-if="pages > 1" class="admin-pagination">
        <el-pagination
          background
          layout="prev, pager, next"
          :total="total"
          :page-size="size"
          :current-page="current"
          @current-change="(page: number) => { current = page; load() }"
        />
      </div>
    </section>

    <el-dialog v-model="shipOpen" title="填写发货信息" width="440px">
      <div class="form-section">
        <label class="field">
          <span class="field__label">承运商</span>
          <input v-model="carrier" class="input" maxlength="32" placeholder="如 AURORA Express" />
        </label>
        <label class="field">
          <span class="field__label">运单号</span>
          <input v-model="trackingNo" class="input" maxlength="64" placeholder="模拟运单号" />
        </label>
        <p class="hint">整单发货，不拆包。运单号为模拟数据，用户可在订单详情查看。</p>
      </div>
      <div class="dialog-actions">
        <button class="btn btn--ghost" type="button" @click="shipOpen = false">取消</button>
        <button class="btn btn--primary" type="button" :disabled="shipping" @click="ship">
          {{ shipping ? '提交中…' : '确认发货' }}
        </button>
      </div>
    </el-dialog>
  </div>
</template>
