<script setup lang="ts">
defineProps<{ title: string; description?: string }>()
</script>

<template>
  <div class="empty">
    <div class="empty__title">{{ title }}</div>
    <p v-if="description">{{ description }}</p>
    <slot />
  </div>
</template>
