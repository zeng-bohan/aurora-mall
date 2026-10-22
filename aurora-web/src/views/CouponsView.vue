<script setup lang="ts">
import { ElMessage } from 'element-plus'
import { computed, onMounted, ref } from 'vue'

import { couponApi, type CouponTemplate, type UserCoupon } from '@/api/dtc'
import EmptyState from '@/components/EmptyState.vue'
import StatusBadge from '@/components/StatusBadge.vue'
import { formatDateTime } from '@/utils/datetime'
import { describeError } from '@/utils/errors'
import { formatYuan } from '@/utils/money'

const templates = ref<CouponTemplate[]>([])
const mine = ref<UserCoupon[]>([])
const loading = ref(true)
const claiming = ref<number | null>(null)
const claimedTemplateIds = computed(() => new Set(mine.value.map((coupon) => coupon.templateId)))

async function load(): Promise<void> {
  loading.value = true
  try {
    const [available, owned] = await Promise.all([couponApi.templates(), couponApi.mine()])
    templates.value = available
    mine.value = owned
  } catch (e) {
    ElMessage.error(describeError(e))
  } finally {
    loading.value = false
  }
}

async function claim(template: CouponTemplate): Promise<void> {
  claiming.value = template.id
  try {
    await couponApi.claim(template.id)
    ElMessage.success('优惠券已领取')
    await load()
  } catch (e) {
    ElMessage.error(describeError(e))
  } finally {
    claiming.value = null
  }
}

onMounted(() => {
  void load()
})
</script>

<template>
  <div>
    <div class="container page-head">
      <span class="eyebrow">Coupons</span>
      <h1 class="display">优惠券。</h1>
      <p>券只作用于父订单商品金额。退款后剩余金额低于券门槛时，需要整单退款。</p>
    </div>

    <div class="container" style="padding-block: 32px 72px">
      <div v-if="loading" class="page-loading">正在读取优惠券…</div>
      <template v-else>
        <section>
          <div class="section-head">
            <div>
              <span class="eyebrow">可领取</span>
              <h2 class="display" style="font-size: 28px">现在能领的券。</h2>
            </div>
          </div>
          <EmptyState
            v-if="!templates.length"
            title="暂时没有可领取的券"
            description="运营创建新的券模板后会出现在这里。"
          />
          <div v-else class="coupon-grid">
            <article v-for="template in templates" :key="template.id" class="coupon-card">
              <div class="coupon-card__amount">
                <strong>{{ formatYuan(template.discountAmount) }}</strong>
                <span>满 {{ formatYuan(template.thresholdAmount) }} 可用</span>
              </div>
              <div class="coupon-card__body">
                <h3>{{ template.title }}</h3>
                <p>剩余 {{ template.remaining }} 张 · 截止 {{ formatDateTime(template.claimEndAt) }}</p>
                <button
                  class="btn btn--primary btn--sm"
                  type="button"
                  :disabled="claiming === template.id || claimedTemplateIds.has(template.id)"
                  @click="claim(template)"
                >
                  {{ claiming === template.id ? '领取中…' : claimedTemplateIds.has(template.id) ? '已领取' : '领取' }}
                </button>
              </div>
            </article>
          </div>
        </section>

        <section style="margin-top: 56px">
          <div class="section-head">
            <div>
              <span class="eyebrow">我的券</span>
              <h2 class="display" style="font-size: 28px">账户里的券。</h2>
            </div>
          </div>
          <EmptyState
            v-if="!mine.length"
            title="还没有优惠券"
            description="先领取一张，结算时会自动校验门槛。"
          />
          <div v-else class="coupon-grid">
            <article v-for="coupon in mine" :key="coupon.id" class="coupon-card">
              <div class="coupon-card__amount">
                <strong>{{ formatYuan(coupon.discountAmount) }}</strong>
                <span>满 {{ formatYuan(coupon.thresholdAmount) }} 可用</span>
              </div>
              <div class="coupon-card__body">
                <div style="display: flex; align-items: center; gap: 8px">
                  <h3>{{ coupon.title }}</h3>
                  <StatusBadge :status="coupon.status" />
                </div>
                <p>有效期至 {{ formatDateTime(coupon.expireAt) }}</p>
                <RouterLink v-if="coupon.status === 'UNUSED'" class="link-arrow" to="/products">
                  去使用 →
                </RouterLink>
                <RouterLink v-else-if="coupon.tradeId" class="link-arrow" :to="`/orders/${coupon.tradeId}`">
                  查看关联订单 →
                </RouterLink>
              </div>
            </article>
          </div>
        </section>
      </template>
    </div>
  </div>
</template>
