<script setup lang="ts">
import { ElMessage } from 'element-plus'
import { onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'

import { adminTradeApi, type Trade } from '@/api/dtc'
import StatusBadge from '@/components/StatusBadge.vue'
import { formatDateTime } from '@/utils/datetime'
import { describeError } from '@/utils/errors'
import { formatYuan } from '@/utils/money'

const route = useRoute()
const router = useRouter()

const trade = ref<Trade | null>(null)
const loading = ref(true)
const error = ref('')
const shipOpen = ref(false)
const carrier = ref('AURORA Express')
const trackingNo = ref('')
const shipping = ref(false)

async function load(): Promise<void> {
  loading.value = true
  error.value = ''
  try {
    trade.value = await adminTradeApi.trade(Number(route.params.id))
  } catch (e) {
    error.value = describeError(e)
    trade.value = null
  } finally {
    loading.value = false
  }
}

async function ship(): Promise<void> {
  if (!trade.value) {
    return
  }
  if (!carrier.value.trim() || !trackingNo.value.trim()) {
    ElMessage.warning('请填写承运商和运单号')
    return
  }
  shipping.value = true
  try {
    await adminTradeApi.ship(trade.value.id, {
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

function back(): void {
  void router.push({ name: 'admin-orders' })
}

onMounted(() => {
  void load()
})
</script>

<template>
  <div>
    <div class="admin-page-head">
      <div>
        <button class="btn btn--ghost btn--sm" type="button" style="margin-bottom: 8px" @click="back">← 返回订单列表</button>
        <h1>订单详情</h1>
        <p v-if="trade">{{ trade.tradeNo }} · {{ formatDateTime(trade.createdAt) }}</p>
      </div>
      <div class="admin-actions">
        <button v-if="trade?.status === 'PAID'" class="btn btn--primary btn--sm" type="button" @click="shipOpen = true">
          发货
        </button>
        <button class="btn btn--line btn--sm" type="button" @click="load">刷新</button>
      </div>
    </div>

    <div v-if="loading" class="admin-empty">正在读取订单…</div>
    <div v-else-if="error || !trade" class="error-banner">{{ error || '订单不存在' }}</div>
    <template v-else>
      <div class="admin-grid">
        <div class="admin-stack">
          <section class="admin-card">
            <div class="admin-card__head">
              <h2>商品明细</h2>
              <StatusBadge :status="trade.status" />
            </div>
            <div class="table-wrap">
              <table class="data-table">
                <thead>
                  <tr>
                    <th>商品</th>
                    <th>单价</th>
                    <th>数量</th>
                    <th>商品金额</th>
                    <th>分摊优惠</th>
                    <th>退款</th>
                    <th>状态</th>
                  </tr>
                </thead>
                <tbody>
                  <tr v-for="item in trade.items" :key="item.id">
                    <td>
                      <div class="table-product">
                        <img :src="item.imageUrl" :alt="item.title" />
                        <div>
                          <div class="table-product__title">{{ item.title }}</div>
                          <div class="table-product__meta">{{ item.attributesText }}</div>
                        </div>
                      </div>
                    </td>
                    <td class="money">{{ formatYuan(item.unitPrice) }}</td>
                    <td>{{ item.quantity }}</td>
                    <td class="money">{{ formatYuan(item.itemAmount) }}</td>
                    <td class="money">{{ formatYuan(item.discountAmount) }}</td>
                    <td class="money">{{ formatYuan(item.refundedAmount) }}</td>
                    <td><StatusBadge :status="item.status" /></td>
                  </tr>
                </tbody>
              </table>
            </div>
          </section>

          <section class="admin-card">
            <div class="admin-card__head"><h2>收货信息</h2></div>
            <div class="admin-card__body">
              <div class="drawer-row"><span>收货人</span><strong>{{ trade.address.receiverName }}</strong></div>
              <div class="drawer-row"><span>手机号</span><span>{{ trade.address.phone }}</span></div>
              <div class="drawer-row">
                <span>地址</span>
                <span style="text-align: right">
                  {{ trade.address.province }} {{ trade.address.city }} {{ trade.address.district }}<br />
                  {{ trade.address.detail }}
                </span>
              </div>
              <div class="drawer-row"><span>承运商</span><span>{{ trade.carrier || '未发货' }}</span></div>
              <div class="drawer-row"><span>运单号</span><span class="mono">{{ trade.trackingNo || '—' }}</span></div>
            </div>
          </section>

          <section v-if="trade.refunds.length" class="admin-card">
            <div class="admin-card__head"><h2>退款记录</h2></div>
            <div class="admin-card__body">
              <div v-for="refund in trade.refunds" :key="refund.id" class="drawer-row">
                <span>{{ refund.refundNo }} · {{ formatDateTime(refund.createdAt) }}</span>
                <span>
                  {{ formatYuan(refund.amount) }}
                  <StatusBadge :status="refund.status" />
                </span>
              </div>
            </div>
          </section>
        </div>

        <div class="admin-stack">
          <section class="admin-card">
            <div class="admin-card__head"><h2>金额</h2></div>
            <div class="admin-card__body">
              <div class="drawer-row"><span>商品金额</span><span>{{ formatYuan(trade.merchandiseAmount) }}</span></div>
              <div class="drawer-row"><span>优惠</span><span>− {{ formatYuan(trade.discountAmount) }}</span></div>
              <div class="drawer-row"><span>运费</span><span>{{ formatYuan(trade.shippingFee) }}</span></div>
              <div class="drawer-row"><span>应付</span><strong>{{ formatYuan(trade.payableAmount) }}</strong></div>
              <div class="drawer-row"><span>已退款</span><span>{{ formatYuan(trade.refundedAmount) }}</span></div>
              <div class="drawer-row"><span>退款状态</span><StatusBadge :status="trade.refundStatus" /></div>
            </div>
          </section>

          <section class="admin-card">
            <div class="admin-card__head"><h2>时间线</h2></div>
            <div class="admin-card__body">
              <div class="drawer-row"><span>下单</span><span>{{ formatDateTime(trade.createdAt) }}</span></div>
              <div class="drawer-row"><span>支付</span><span>{{ formatDateTime(trade.paidAt) }}</span></div>
              <div class="drawer-row"><span>发货</span><span>{{ formatDateTime(trade.shippedAt) }}</span></div>
              <div class="drawer-row"><span>完成</span><span>{{ formatDateTime(trade.completedAt) }}</span></div>
              <div class="drawer-row"><span>关闭</span><span>{{ formatDateTime(trade.closedAt) }}</span></div>
            </div>
          </section>
        </div>
      </div>
    </template>

    <el-dialog v-model="shipOpen" title="填写发货信息" width="440px">
      <div class="form-section">
        <label class="field">
          <span class="field__label">承运商</span>
          <input v-model="carrier" class="input" maxlength="32" />
        </label>
        <label class="field">
          <span class="field__label">运单号</span>
          <input v-model="trackingNo" class="input" maxlength="64" placeholder="模拟运单号" />
        </label>
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
