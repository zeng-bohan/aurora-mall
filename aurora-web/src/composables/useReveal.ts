import { onMounted, onUnmounted } from 'vue'

/**
 * 滚动进入视口时给 [data-reveal] 加一次动画类。
 * 元素默认可见，脚本失败或禁用动效时只是没有入场动画，不会丢内容。
 */
export function useReveal(): void {
  let observer: IntersectionObserver | null = null

  onMounted(() => {
    if (window.matchMedia('(prefers-reduced-motion: reduce)').matches) {
      return
    }
    if (!('IntersectionObserver' in window)) {
      return
    }
    observer = new IntersectionObserver(
      (entries) => {
        for (const entry of entries) {
          if (!entry.isIntersecting) {
            continue
          }
          const el = entry.target as HTMLElement
          el.classList.add('is-revealed')
          observer?.unobserve(el)
        }
      },
      { rootMargin: '0px 0px -8% 0px', threshold: 0.12 }
    )
    document.querySelectorAll('[data-reveal]').forEach((el) => observer?.observe(el))
  })

  onUnmounted(() => observer?.disconnect())
}
