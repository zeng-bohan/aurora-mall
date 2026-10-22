<script setup lang="ts">
import { ElMessage } from 'element-plus'
import { onMounted, ref } from 'vue'

import {
  adminCatalogApi,
  adminTradeApi,
  type AdminStats,
  type CatalogStats,
  type Refund,
  type TradeRow,
} from '@/api/dtc'
import StatusBadge from '@/components/StatusBadge.vue'
import { formatDateTime } from '@/utils/datetime'
import { describeError } from '@/utils/errors'
import { formatYuan } from '@/utils/money'

const stats = ref<AdminStats | null>(null)
const catalog = ref<CatalogStats | null>(null)
const pendingShip = ref<TradeRow[]>([])
const pendingRefund = ref<Refund[]>([])
const loading = ref(true)
const error = ref('')

async function load(): Promise<void> {
  loading.value = true
  error.value = ''
  try {
    const [orderStats, catalogStats, shipPage, refundPage] = await Promise.all([
      adminTradeApi.stats(),
      adminCatalogApi.stats(),
      adminTradeApi.trades({ status: 'PAID', current: 1, size: 5 }),
      adminTradeApi.refunds({ status: 'PENDING', current: 1, size: 5 }),
    ])
    stats.value = orderStats
    catalog.value = catalogStats
    pendingShip.value = shipPage.records
    pendingRefund.value = refundPage.records
  } catch (e) {
    error.value = describeError(e)
    ElMessage.error(error.value)
  } finally {
    loading.value = false
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
        <h1>运营概览</h1>
        <p>统计来自当前数据库的真实汇总，不是静态占位数字。</p>
      </div>
      <div class="admin-actions">
        <button class="btn btn--line btn--sm" type="button" @click="load">刷新数据</button>
        <RouterLink class="btn btn--primary btn--sm" to="/admin/refunds">处理退款</RouterLink>
      </div>
    </div>

    <div v-if="loading" class="admin-empty">正在汇总数据…</div>
    <div v-else-if="error" class="error-banner">{{ error }}</div>
    <template v-else>
      <div class="stat-grid">
        <div class="stat-card">
          <div class="stat-card__label">待发货订单</div>
          <div class="stat-card__value">{{ stats?.pendingShip ?? 0 }}</div>
          <div class="stat-card__foot">已支付，等待运营发货</div>
        </div>
        <div class="stat-card">
          <div class="stat-card__label">待审批退款</div>
          <div class="stat-card__value">{{ stats?.pendingRefund ?? 0 }}</div>
          <div class="stat-card__foot">发货前退款申请</div>
        </div>
        <div class="stat-card">
          <div class="stat-card__label">近 30 天订单</div>
          <div class="stat-card__value">{{ stats?.orders30d ?? 0 }}</div>
          <div class="stat-card__foot">按下单时间统计</div>
        </div>
        <div class="stat-card">
          <div class="stat-card__label">近 30 天销售额</div>
          <div class="stat-card__value">{{ formatYuan(stats?.revenue30d ?? 0) }}</div>
          <div class="stat-card__foot">已支付订单实付减已退款</div>
        </div>
      </div>

      <div class="admin-grid">
        <div class="admin-stack">
          <section class="admin-card">
            <div class="admin-card__head">
              <h2>待发货</h2>
              <RouterLink class="btn btn--ghost btn--sm" to="/admin/orders?status=PAID">全部订单</RouterLink>
            </div>
            <div class="table-wrap">
              <table class="data-table">
                <thead>
                  <tr>
                    <th>订单</th>
                    <th>收货人</th>
                    <th>金额</th>
                    <th>下单时间</th>
                  </tr>
                </thead>
                <tbody>
                  <tr v-for="trade in pendingShip" :key="trade.id">
                    <td>
                      <RouterLink class="mono" :to="`/admin/orders/${trade.id}`">{{ trade.tradeNo }}</RouterLink>
                      <div class="tiny">{{ trade.source === 'SECKILL' ? '秒杀' : '商城' }}</div>
                    </td>
                    <td>{{ trade.receiverName }}</td>
                    <td class="money">{{ formatYuan(trade.payableAmount) }}</td>
                    <td class="tiny">{{ formatDateTime(trade.createdAt) }}</td>
                  </tr>
                  <tr v-if="!pendingShip.length">
                    <td colspan="4" class="admin-empty">暂无待发货订单</td>
                  </tr>
                </tbody>
              </table>
            </div>
          </section>

          <section class="admin-card">
            <div class="admin-card__head">
              <h2>待审批退款</h2>
              <RouterLink class="btn btn--ghost btn--sm" to="/admin/refunds">全部退款</RouterLink>
            </div>
            <div class="table-wrap">
              <table class="data-table">
                <thead>
                  <tr>
                    <th>退款单</th>
                    <th>订单</th>
                    <th>金额</th>
                    <th>状态</th>
                  </tr>
                </thead>
                <tbody>
                  <tr v-for="refund in pendingRefund" :key="refund.id">
                    <td class="mono">{{ refund.refundNo }}</td>
                    <td>{{ refund.tradeNo }}</td>
                    <td class="money">{{ formatYuan(refund.amount) }}</td>
                    <td><StatusBadge :status="refund.status" /></td>
                  </tr>
                  <tr v-if="!pendingRefund.length">
                    <td colspan="4" class="admin-empty">暂无待审批退款</td>
                  </tr>
                </tbody>
              </table>
            </div>
          </section>
        </div>

        <div class="admin-stack">
          <section class="admin-card">
            <div class="admin-card__head"><h2>在售商品</h2></div>
            <div class="admin-card__body">
              <div class="drawer-row">
                <span>在售 SPU</span>
                <strong>{{ catalog?.spuCount ?? 0 }}</strong>
              </div>
              <div class="drawer-row">
                <span>在售 SKU</span>
                <strong>{{ catalog?.skuCount ?? 0 }}</strong>
              </div>
              <div class="drawer-row">
                <span>目录图片</span>
                <strong>45 张概念渲染</strong>
              </div>
            </div>
          </section>

          <section class="admin-card">
            <div class="admin-card__head"><h2>快捷入口</h2></div>
            <div class="admin-card__body" style="display: grid; gap: 8px">
              <RouterLink class="btn btn--line btn--sm" to="/admin/products/new">新建商品</RouterLink>
              <RouterLink class="btn btn--line btn--sm" to="/admin/inventory">调整库存</RouterLink>
              <RouterLink class="btn btn--line btn--sm" to="/admin/seckill">创建秒杀</RouterLink>
              <RouterLink class="btn btn--line btn--sm" to="/admin/coupons">创建优惠券</RouterLink>
            </div>
          </section>
        </div>
      </div>
    </template>
  </div>
</template>
