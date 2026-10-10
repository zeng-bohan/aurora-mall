/**
 * 幂等键生成。
 *
 * 语义要点：键代表一次「提交意图」，不是一次网络请求。所以调用方必须在
 * 用户点下按钮时生成一次并**持有到这次提交有结论**（成功、或被业务拒绝），
 * 中途因为超时/断线重试要复用同一个键——复用才有去重效果。
 * 一提交就换新键，等于每次重试都是一个新订单。
 */
export function newIdempotencyKey(): string {
  if (typeof crypto !== 'undefined' && typeof crypto.randomUUID === 'function') {
    return crypto.randomUUID()
  }
  // 非安全上下文（如局域网 http）拿不到 randomUUID，退到自拼：
  // 这里只求"不会撞"，不承担任何安全职责——键值本身不参与鉴权。
  const random = Math.random().toString(36).slice(2)
  return `key-${Date.now().toString(36)}-${random}`
}
