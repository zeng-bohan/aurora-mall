<script setup lang="ts">
import { ElMessage } from 'element-plus'
import { computed, ref, watch } from 'vue'

import { tradeApi, type RefundQuote, type Trade } from '@/api/dtc'
import { describeError } from '@/utils/errors'
import { formatYuan } from '@/utils/money'

const props = defineProps<{ modelValue: boolean; trade: Trade | null }>()
const emit = defineEmits<{
  'update:modelValue': [value: boolean]
  applied: []
}>()

const visible = computed({
  get: () => props.modelValue,
  set: (value: boolean) => emit('update:modelValue', value),
})
const selected = ref<number[]>([])
const reason = ref('')
const quote = ref<RefundQuote | null>(null)
const loadingQuote = ref(false)
const submitting = ref(false)
const error = ref('')

const refundableItems = computed(() => props.trade?.items.filter((item) => item.refundable) ?? [])
const hasPending = computed(() => props.trade?.refunds.some((refund) => refund.status === 'PENDING') ?? false)

watch(
  () => props.modelValue,
  (open) => {
    if (!open) {
      return
    }
    selected.value = refundableItems.value.map((item) => item.id)
    reason.value = ''
    quote.value = null
    error.value = ''
  },
)

watch(
  () => selected.value,
  async (ids) => {
    if (!props.trade || !ids.length) {
      quote.value = null
      return
    }
    loadingQuote.value = true
    error.value = ''
    try {
      quote.value = await tradeApi.quoteRefund(props.trade.id, ids)
    } catch (e) {
      quote.value = null
      error.value = describeError(e)
    } finally {
      loadingQuote.value = false
    }
  },
  { deep: true },
)

function toggle(id: number): void {
  selected.value = selected.value.includes(id)
    ? selected.value.filter((item) => item !== id)
    : [...selected.value, id]
}

async function submit(): Promise<void> {
  if (!props.trade) {
    return
  }
  if (!selected.value.length) {
    error.value = '请选择要退款的商品'
    return
  }
  if (!quote.value?.allowed) {
    error.value = quote.value?.message || '当前选择不可退款'
    return
  }
  if (!reason.value.trim()) {
    error.value = '请填写退款原因'
    return
  }
  submitting.value = true
  error.value = ''
  try {
    await tradeApi.applyRefund(props.trade.id, selected.value, reason.value.trim())
    ElMessage.success('退款申请已提交，等待运营审批')
    emit('applied')
    visible.value = false
  } catch (e) {
    error.value = describeError(e)
  } finally {
    submitting.value = false
  }
}
</script>

<template>
  <el-dialog v-model="visible" title="申请退款" width="620px" append-to-body>
    <p class="hint" style="margin-bottom: 14px">
      仅支持发货前退款。部分退款只退被选商品的商品金额，不退运费；整单退款退回剩余实付与运费，并返还优惠券。
    </p>
    <div v-if="hasPending" class="error-banner" style="margin-bottom: 12px">
      该订单已有待审批的退款申请，请等待处理完成后再提交。
    </div>
    <div v-else-if="!refundableItems.length" class="admin-empty">没有可退款的子单。</div>
    <div v-else>
      <label v-for="item in refundableItems" :key="item.id" class="address-option" style="margin-bottom: 8px">
        <input
          type="checkbox"
          class="checkbox"
          :checked="selected.includes(item.id)"
          @change="toggle(item.id)"
        />
        <div style="flex: 1">
          <div class="address-option__name">{{ item.title }}</div>
          <div class="address-option__line">
            {{ item.attributesText }} · ×{{ item.quantity }} · {{ formatYuan(item.itemAmount) }}
          </div>
        </div>
      </label>

      <div v-if="loadingQuote" class="hint" style="margin-top: 12px">正在计算退款金额…</div>
      <div v-else-if="quote" class="seckill-result">
        <div style="display: flex; justify-content: space-between; gap: 16px">
          <span>{{ quote.full ? '整单退款' : '部分退款' }}</span>
          <strong>{{ formatYuan(quote.amount) }}</strong>
        </div>
        <div class="tiny" style="margin-top: 6px">
          退款后剩余商品金额 {{ formatYuan(quote.remainingMerchandise) }}
        </div>
        <div v-if="quote.message" class="error-text" style="margin-top: 6px">{{ quote.message }}</div>
      </div>

      <label class="field" style="margin-top: 14px">
        <span class="field__label">退款原因</span>
        <textarea v-model="reason" class="textarea" maxlength="200" placeholder="简要说明退款原因" />
      </label>
      <p v-if="error" class="error-text" style="margin-top: 10px">{{ error }}</p>
    </div>

    <div class="dialog-actions">
      <button class="btn btn--ghost" type="button" @click="visible = false">取消</button>
      <button
        class="btn btn--primary"
        type="button"
        :disabled="submitting || hasPending || !quote?.allowed"
        @click="submit"
      >
        {{ submitting ? '提交中…' : '提交申请' }}
      </button>
    </div>
  </el-dialog>
</template>
