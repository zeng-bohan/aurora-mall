<script setup lang="ts">
const props = withDefaults(
  defineProps<{ modelValue: number; min?: number; max?: number; disabled?: boolean }>(),
  { min: 1, max: 99, disabled: false },
)
const emit = defineEmits<{ 'update:modelValue': [value: number] }>()

function step(delta: number): void {
  if (props.disabled) {
    return
  }
  const next = Math.min(props.max, Math.max(props.min, props.modelValue + delta))
  emit('update:modelValue', next)
}
</script>

<template>
  <div class="qty-control">
    <button type="button" :disabled="disabled || modelValue <= min" aria-label="减少数量" @click="step(-1)">−</button>
    <span>{{ modelValue }}</span>
    <button type="button" :disabled="disabled || modelValue >= max" aria-label="增加数量" @click="step(1)">+</button>
  </div>
</template>
