<script setup lang="ts">
defineProps<{
  tone: 'pending' | 'paid' | 'closed' | 'danger' | 'info'
  label: string
}>()
</script>

<template>
  <span class="status" :class="`status--${tone}`">{{ label }}</span>
</template>
