<script setup lang="ts">
import { ElMessage } from 'element-plus'
import { onMounted, ref } from 'vue'

import { couponApi } from '@/api'
import type { CouponTemplateView, CouponView } from '@/api/coupon'
import { COUPON_STATUS_LABEL, CouponStatus } from '@/api/coupon'
import EmptyState from '@/components/EmptyState.vue'
import PageHead from '@/components/PageHead.vue'
import StatusPill from '@/components/StatusPill.vue'
import { formatDateTime } from '@/utils/datetime'
import { describeError } from '@/utils/errors'
import { formatYuan } from '@/utils/money'

type Tab = 'templates' | 'mine'

const templates = ref<CouponTemplateView[]>([])
const mine = ref<CouponView[]>([])
const loading = ref(true)
const claimingId = ref<number | null>(null)
const tab = ref<Tab>('templates')

function statusTone(status: string): 'pending' | 'paid' | 'closed' {
  if (status === CouponStatus.UNUSED) {
    return 'paid'
  }
  return status === CouponStatus.LOCKED ? 'pending' : 'closed'
}

async function load(): Promise<void> {
  loading.value = true
  try {
    const [available, owned] = await Promise.all([couponApi.templates(), couponApi.mine()])
    templates.value = available
    mine.value = owned
  } catch (error) {
    ElMessage.error(describeError(error))
  } finally {
    loading.value = false
  }
}

async function claim(templateId: number): Promise<void> {
  claimingId.value = templateId
  try {
    await couponApi.claim(templateId)
    ElMessage.success('领取成功')
    await load()
  } catch (error) {
    ElMessage.error(describeError(error))
  } finally {
    claimingId.value = null
  }
}

onMounted(load)
</script>

<template>
  <div class="app-page">
    <PageHead title="优惠券" meta="领取后的券会绑定到账户；下单时只用一张，订单关闭会按规则退回。" />

    <div class="tab-row" role="tablist" aria-label="优惠券分类">
      <button
        type="button"
        class="tab-btn"
        role="tab"
        :aria-selected="tab === 'templates'"
        @click="tab = 'templates'"
      >
        可领取
      </button>
      <button
        type="button"
        class="tab-btn"
        role="tab"
        :aria-selected="tab === 'mine'"
        @click="tab = 'mine'"
      >
        我的券
      </button>
    </div>

    <div v-if="loading" aria-busy="true" aria-label="正在加载优惠券">
      <div class="skeleton-block skeleton-row" />
      <div class="skeleton-block skeleton-row" />
      <div class="skeleton-block skeleton-row" />
    </div>

    <template v-else-if="tab === 'templates'">
      <div v-if="templates.length === 0">
        <EmptyState
          title="当前没有可领取的券"
          hint="运营发布券模板后会出现在这里；领取窗口和剩余数量都由服务端判断。"
        />
      </div>
      <div v-else class="coupon-grid">
        <article v-for="template in templates" :key="template.id" class="coupon-ticket">
          <div>
            <p class="coupon-ticket__value">{{ formatYuan(template.discountAmount) }}</p>
            <h2>{{ template.title }}</h2>
            <p class="coupon-ticket__rule">
              满 {{ formatYuan(template.thresholdAmount) }} 可用 · 剩余 {{ template.remaining }} 张
            </p>
            <p class="coupon-ticket__window">
              {{ formatDateTime(template.claimStartAt) }} 至 {{ formatDateTime(template.claimEndAt) }}
            </p>
          </div>
          <div class="coupon-ticket__action">
            <button
              type="button"
              class="btn btn--sm"
              :disabled="template.remaining <= 0 || claimingId === template.id"
              @click="claim(template.id)"
            >
              {{ claimingId === template.id ? '领取中…' : template.remaining > 0 ? '领取' : '已领完' }}
            </button>
          </div>
        </article>
      </div>
    </template>

    <template v-else>
      <div v-if="mine.length === 0">
        <EmptyState title="还没有券" hint="先到“可领取”里领一张，结算时就能看到可用券。">
          <button type="button" class="btn" @click="tab = 'templates'">去领券</button>
        </EmptyState>
      </div>
      <div v-else class="panel">
        <div class="table-wrap">
          <table class="data-table">
            <thead>
              <tr>
                <th>券</th>
                <th class="num">优惠</th>
                <th class="num">门槛</th>
                <th>状态</th>
                <th>有效期至</th>
                <th>关联订单</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="coupon in mine" :key="coupon.id">
                <td class="identity">
                  <span class="row-title">{{ coupon.title }}</span>
                  <span class="row-sub">券编号 {{ coupon.id }}</span>
                </td>
                <td class="num">{{ formatYuan(coupon.discountAmount) }}</td>
                <td class="num">满 {{ formatYuan(coupon.thresholdAmount) }}</td>
                <td>
                  <StatusPill
                    :tone="statusTone(coupon.status)"
                    :label="COUPON_STATUS_LABEL[coupon.status] ?? coupon.status"
                  />
                </td>
                <td>{{ formatDateTime(coupon.expireAt) }}</td>
                <td>
                  <RouterLink
                    v-if="coupon.orderId"
                    :to="{ name: 'order-detail', params: { id: coupon.orderId } }"
                  >
                    订单 {{ coupon.orderId }}
                  </RouterLink>
                  <span v-else class="muted">—</span>
                </td>
              </tr>
            </tbody>
          </table>
        </div>
      </div>
    </template>
  </div>
</template>
