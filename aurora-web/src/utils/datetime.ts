/**
 * 后端用 LocalDateTime 序列化，格式是 "2026-10-10T14:30:00"（不带时区），
 * 按本地时间解析即可——服务端与浏览器都在同一时区时语义一致。
 */
export function formatDateTime(value: string | null | undefined): string {
  if (!value) {
    return '—'
  }
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) {
    return value
  }
  const pad = (n: number) => String(n).padStart(2, '0')
  return (
    `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())} ` +
    `${pad(date.getHours())}:${pad(date.getMinutes())}`
  )
}

/**
 * 距目标时间还有多久；已过则返回 null。用于"距开抢 / 距结束"倒计时。
 *
 * now 显式传入而不是内部读 Date.now()：模板里只有依赖到一个响应式的 tick，
 * 倒计时才会每秒重算。内部自己取时间的话渲染一次就冻住了。
 */
export function formatCountdown(target: string, now: number): string | null {
  const remaining = new Date(target).getTime() - now
  if (Number.isNaN(remaining) || remaining <= 0) {
    return null
  }
  const totalSeconds = Math.floor(remaining / 1000)
  const days = Math.floor(totalSeconds / 86400)
  const hours = Math.floor((totalSeconds % 86400) / 3600)
  const minutes = Math.floor((totalSeconds % 3600) / 60)
  const seconds = totalSeconds % 60
  const pad = (n: number) => String(n).padStart(2, '0')
  return days > 0
    ? `${days}天 ${pad(hours)}:${pad(minutes)}:${pad(seconds)}`
    : `${pad(hours)}:${pad(minutes)}:${pad(seconds)}`
}
