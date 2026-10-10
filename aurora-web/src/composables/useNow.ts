import { onUnmounted, ref } from 'vue'
import type { Ref } from 'vue'

/**
 * 每秒跳一次的时钟，用来驱动倒计时文案重算。
 * 组件卸载时自动停——忘了清 interval 会在切页后继续烧 CPU。
 */
export function useNow(intervalMs = 1000): Ref<number> {
  const now = ref(Date.now())
  const timer = window.setInterval(() => {
    now.value = Date.now()
  }, intervalMs)
  onUnmounted(() => window.clearInterval(timer))
  return now
}
