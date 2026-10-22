<script setup lang="ts">
defineProps<{
  title: string
  meta?: string
  backTo?: string
  backLabel?: string
}>()
</script>

<template>
  <header class="page-head">
    <div class="page-head__copy">
      <RouterLink v-if="backTo" :to="backTo" class="back-link">← {{ backLabel ?? '返回' }}</RouterLink>
      <h1>{{ title }}</h1>
      <p v-if="meta" class="page-head__meta">{{ meta }}</p>
    </div>
    <div v-if="$slots.actions" class="page-head__actions">
      <slot name="actions" />
    </div>
  </header>
</template>
