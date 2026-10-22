<script setup lang="ts">
import { computed } from 'vue'

import { OrderStatus } from '@/api/order'

const props = defineProps<{
  status: number
}>()

const paid = computed(() => props.status === OrderStatus.PAID)
const closed = computed(() => props.status === OrderStatus.CLOSED)
</script>

<template>
  <ol class="timeline" aria-label="订单进度">
    <li class="timeline-step timeline-step--done">
      <span class="timeline-step__dot" aria-hidden="true" />
      <div>
        <strong>订单已创建</strong>
        <p>订单与事务消息在同一笔本地事务里写入。</p>
      </div>
    </li>
    <li class="timeline-step" :class="{ 'timeline-step--done': paid, 'timeline-step--current': !paid && !closed }">
      <span class="timeline-step__dot" aria-hidden="true" />
      <div>
        <strong>库存已预留</strong>
        <p v-if="closed">订单关闭后，预留库存已回滚。</p>
        <p v-else>Redis 原子预扣完成，支付前不会释放。</p>
      </div>
    </li>
    <li class="timeline-step" :class="{ 'timeline-step--done': paid, 'timeline-step--current': !paid && !closed }">
      <span class="timeline-step__dot" aria-hidden="true" />
      <div>
        <strong>支付完成</strong>
        <p v-if="paid">渠道回调已通过 HMAC 验签，金额与订单一致。</p>
        <p v-else-if="closed">订单关闭前未收到有效支付回调。</p>
        <p v-else>等待模拟渠道回调；本页面不会伪造支付结果。</p>
      </div>
    </li>
    <li class="timeline-step" :class="{ 'timeline-step--done': closed, 'timeline-step--current': !paid && !closed }">
      <span class="timeline-step__dot" aria-hidden="true" />
      <div>
        <strong>{{ closed ? '订单已关闭' : '自动关单' }}</strong>
        <p v-if="closed">未支付订单已关闭，库存与优惠券按规则回退。</p>
        <p v-else-if="paid">已支付订单不会进入自动关单。</p>
        <p v-else>30 分钟内未支付将自动关闭，并回滚库存与优惠券。</p>
      </div>
    </li>
  </ol>
</template>
