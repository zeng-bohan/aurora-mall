<script setup lang="ts">
defineProps<{
  title: string
  hint?: string
}>()
</script>

<template>
  <div class="empty-state">
    <h2>{{ title }}</h2>
    <p v-if="hint">{{ hint }}</p>
    <slot />
  </div>
</template>
