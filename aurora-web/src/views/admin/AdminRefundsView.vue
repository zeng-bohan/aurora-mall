<script setup lang="ts">
import { ElMessage, ElMessageBox } from 'element-plus'
import { onMounted, ref } from 'vue'

import { adminTradeApi, type Refund } from '@/api/dtc'
import StatusBadge from '@/components/StatusBadge.vue'
import { formatDateTime } from '@/utils/datetime'
import { describeError } from '@/utils/errors'
import { formatYuan } from '@/utils/money'

const rows = ref<Refund[]>([])
const total = ref(0)
const pages = ref(0)
const current = ref(1)
const size = 15
const statusFilter = ref('PENDING')
const loading = ref(true)
const error = ref('')
const detail = ref<Refund | null>(null)
const detailOpen = ref(false)
const rejectOpen = ref(false)
const rejectReason = ref('')
const rejectTarget = ref<Refund | null>(null)
const acting = ref(false)

const statuses = [
  { value: '', label: '全部状态' },
  { value: 'PENDING', label: '待审批' },
  { value: 'APPROVED', label: '已同意' },
  { value: 'REJECTED', label: '已拒绝' },
]

async function load(): Promise<void> {
  loading.value = true
  error.value = ''
  try {
    const page = await adminTradeApi.refunds({
      status: statusFilter.value || undefined,
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

async function openDetail(refund: Refund): Promise<void> {
  try {
    detail.value = await adminTradeApi.refund(refund.id)
    detailOpen.value = true
  } catch (e) {
    ElMessage.error(describeError(e))
  }
}

async function approve(refund: Refund): Promise<void> {
  try {
    await ElMessageBox.confirm(
      `同意退款 ${formatYuan(refund.amount)}？同意后会发起支付退款并回补库存。`,
      '同意退款',
      { confirmButtonText: '同意', cancelButtonText: '取消', type: 'warning' },
    )
    acting.value = true
    const updated = await adminTradeApi.approve(refund.id)
    ElMessage.success(updated.settled ? '退款已完成' : '已同意，退款处理中')
    detail.value = updated
    await load()
  } catch (e) {
    if (e !== 'cancel') {
      ElMessage.error(describeError(e))
    }
  } finally {
    acting.value = false
  }
}

function openReject(refund: Refund): void {
  rejectTarget.value = refund
  rejectReason.value = ''
  rejectOpen.value = true
}

async function reject(): Promise<void> {
  if (!rejectTarget.value) {
    return
  }
  if (!rejectReason.value.trim()) {
    ElMessage.warning('请填写拒绝原因')
    return
  }
  acting.value = true
  try {
    await adminTradeApi.reject(rejectTarget.value.id, rejectReason.value.trim())
    ElMessage.success('已拒绝退款申请')
    rejectOpen.value = false
    await load()
  } catch (e) {
    ElMessage.error(describeError(e))
  } finally {
    acting.value = false
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
        <h1>退款审批</h1>
        <p>审批结果会同步到订单、支付单和库存台账。</p>
      </div>
      <div class="admin-actions">
        <button class="btn btn--line btn--sm" type="button" @click="load">刷新</button>
      </div>
    </div>

    <section class="admin-card">
      <div class="admin-toolbar">
        <select v-model="statusFilter" class="select" style="width: 140px" @change="search">
          <option v-for="item in statuses" :key="item.value" :value="item.value">{{ item.label }}</option>
        </select>
        <span class="admin-toolbar__spacer" />
        <span class="tiny">共 {{ total }} 条</span>
      </div>

      <div v-if="loading" class="admin-empty">正在读取退款申请…</div>
      <div v-else-if="error" class="error-banner" style="margin: 16px">{{ error }}</div>
      <div v-else class="table-wrap">
        <table class="data-table">
          <thead>
            <tr>
              <th>退款单</th>
              <th>订单</th>
              <th>收货人</th>
              <th>金额</th>
              <th>状态</th>
              <th>申请时间</th>
              <th></th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="refund in rows" :key="refund.id">
              <td class="mono">{{ refund.refundNo }}</td>
              <td>{{ refund.tradeNo }}</td>
              <td>{{ refund.receiverName }}</td>
              <td class="money">{{ formatYuan(refund.amount) }}</td>
              <td>
                <StatusBadge :status="refund.status" />
                <span v-if="refund.settled" class="tag tag--success" style="margin-left: 4px">已结算</span>
              </td>
              <td class="tiny">{{ formatDateTime(refund.createdAt) }}</td>
              <td>
                <div class="table-actions">
                  <button class="btn btn--ghost btn--sm" type="button" @click="openDetail(refund)">详情</button>
                  <template v-if="refund.status === 'PENDING'">
                    <button class="btn btn--primary btn--sm" type="button" :disabled="acting" @click="approve(refund)">
                      同意
                    </button>
                    <button class="btn btn--line btn--sm" type="button" @click="openReject(refund)">拒绝</button>
                  </template>
                </div>
              </td>
            </tr>
            <tr v-if="!rows.length">
              <td colspan="7" class="admin-empty">没有匹配的退款申请</td>
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

    <el-dialog v-model="detailOpen" title="退款详情" width="560px">
      <template v-if="detail">
        <div class="drawer-detail">
          <div class="drawer-row"><span>退款单</span><span class="mono">{{ detail.refundNo }}</span></div>
          <div class="drawer-row"><span>订单</span><span>{{ detail.tradeNo }}</span></div>
          <div class="drawer-row"><span>金额</span><strong>{{ formatYuan(detail.amount) }}</strong></div>
          <div class="drawer-row"><span>状态</span><StatusBadge :status="detail.status" /></div>
          <div class="drawer-row"><span>结算</span><span>{{ detail.settled ? '支付退款与库存回补已完成' : '尚未完成' }}</span></div>
          <div class="drawer-row"><span>原因</span><span>{{ detail.reason }}</span></div>
          <div v-if="detail.rejectReason" class="drawer-row"><span>拒绝原因</span><span>{{ detail.rejectReason }}</span></div>
          <div>
            <h3>退款商品</h3>
            <div v-for="item in detail.items" :key="item.orderItemId" class="drawer-row">
              <span>{{ item.title }} · ×{{ item.quantity }}</span>
              <span>{{ formatYuan(item.amount) }}</span>
            </div>
          </div>
        </div>
      </template>
    </el-dialog>

    <el-dialog v-model="rejectOpen" title="拒绝退款" width="440px">
      <label class="field">
        <span class="field__label">拒绝原因</span>
        <textarea v-model="rejectReason" class="textarea" maxlength="200" placeholder="说明拒绝原因，用户可在订单详情看到" />
      </label>
      <div class="dialog-actions">
        <button class="btn btn--ghost" type="button" @click="rejectOpen = false">取消</button>
        <button class="btn btn--primary" type="button" :disabled="acting" @click="reject">
          {{ acting ? '提交中…' : '确认拒绝' }}
        </button>
      </div>
    </el-dialog>
  </div>
</template>
