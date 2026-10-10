import { ApiError, NetworkError } from '@/api/types'

/**
 * 把各类失败翻译成能直接给用户看的一句话。
 *
 * ApiError 的 message 是后端写的业务文案（"库存不足"、"您已领取过该券"），
 * 本来就是给用户看的，原样透出。NetworkError 的文案由前端写，
 * 因为这时候后端根本没回话，它的 message 是给运维看的。
 */
export function describeError(error: unknown): string {
  if (error instanceof ApiError || error instanceof NetworkError) {
    return error.message
  }
  if (error instanceof Error) {
    return error.message
  }
  return '未知错误'
}

/** 幂等重放、重复提交一类的冲突码：调用方通常只需提示，不需要跳转或重试。 */
export function isDuplicate(error: unknown): boolean {
  return error instanceof ApiError && error.code === 40900
}
