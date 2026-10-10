import type { Tokens } from './types'

/**
 * 会话令牌的单一存放点：拦截器与 auth store 都只经过这里，
 * 避免"两处各存一份、刷新后其中一份没更新"的经典 bug。
 *
 * 放在 localStorage 而非内存：多标签页共享登录态、刷新页面不掉登录。
 * 代价是 XSS 可读——本项目后端令牌有黑名单与短 TTL（access 30 分钟），
 * 真要抗 XSS 需换 httpOnly cookie + CSRF 方案，属于上线前的独立决策。
 */
const ACCESS_KEY = 'aurora.accessToken'
const REFRESH_KEY = 'aurora.refreshToken'

export function loadTokens(): Tokens | null {
  const accessToken = localStorage.getItem(ACCESS_KEY)
  const refreshToken = localStorage.getItem(REFRESH_KEY)
  if (!accessToken || !refreshToken) {
    return null
  }
  return { accessToken, refreshToken }
}

export function saveTokens(tokens: Tokens): void {
  localStorage.setItem(ACCESS_KEY, tokens.accessToken)
  localStorage.setItem(REFRESH_KEY, tokens.refreshToken)
}

export function clearTokens(): void {
  localStorage.removeItem(ACCESS_KEY)
  localStorage.removeItem(REFRESH_KEY)
}
