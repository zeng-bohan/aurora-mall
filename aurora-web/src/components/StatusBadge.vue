<script setup lang="ts">
import { computed } from 'vue'

const props = defineProps<{ status: string }>()

const labels: Record<string, string> = {
  CREATED: '待支付',
  PAID: '待发货',
  SHIPPED: '已发货',
  COMPLETED: '已完成',
  CLOSED: '已关闭',
  REFUNDED: '已退款',
  PENDING: '待审批',
  APPROVED: '已同意',
  REJECTED: '已拒绝',
  UNUSED: '可使用',
  LOCKED: '已锁定',
  USED: '已使用',
  EXPIRED: '已过期',
  NORMAL: '正常',
  REFUNDING: '退款中',
  PARTIAL: '部分退款',
  FULL: '全额退款',
  NONE: '无退款',
  ON_SALE: '在售',
  OFF_SHELF: '下架',
  NOT_STARTED: '未开始',
  RUNNING: '进行中',
  ENDED: '已结束',
}

const tones: Record<string, string> = {
  CREATED: 'tag--warning',
  PAID: 'tag--info',
  SHIPPED: 'tag--accent',
  COMPLETED: 'tag--success',
  CLOSED: '',
  REFUNDED: 'tag--danger',
  PENDING: 'tag--warning',
  APPROVED: 'tag--success',
  REJECTED: 'tag--danger',
  UNUSED: 'tag--success',
  LOCKED: 'tag--warning',
  USED: '',
  EXPIRED: '',
  REFUNDING: 'tag--warning',
  PARTIAL: 'tag--warning',
  FULL: 'tag--danger',
  NONE: '',
  ON_SALE: 'tag--success',
  OFF_SHELF: '',
  NOT_STARTED: '',
  RUNNING: 'tag--accent',
  ENDED: '',
}

const label = computed(() => labels[props.status] ?? props.status)
const tone = computed(() => tones[props.status] ?? '')
</script>

<template>
  <span class="tag" :class="tone">{{ label }}</span>
</template>
