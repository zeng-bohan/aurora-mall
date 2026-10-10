import { defineStore } from 'pinia'
import { computed, ref } from 'vue'

import { userApi } from '@/api'
import { clearTokens, loadTokens, saveTokens } from '@/api/session'
import { ROLE_ADMIN } from '@/api/user'
import type { Tokens } from '@/api/types'

/**
 * 登录态。
 *
 * role 不在登录响应里，只能另外问 `/user/me`（它读的是网关注入的 X-User-Role）。
 * 所以「是否管理员」在会话恢复后是异步补齐的，不能同步判断。
 */
export const useAuthStore = defineStore('auth', () => {
  const tokens = ref<Tokens | null>(loadTokens())
  const role = ref<string | null>(null)
  const userId = ref<string | null>(null)

  const isLoggedIn = computed(() => tokens.value !== null)
  const isAdmin = computed(() => role.value === ROLE_ADMIN)

  async function login(username: string, password: string): Promise<void> {
    const response = await userApi.login({ username, password })
    applyTokens({ accessToken: response.accessToken, refreshToken: response.refreshToken })
    await loadIdentity()
  }

  /** 会话恢复后补齐身份。失败不抛——拿不到 role 只影响管理入口的可见性。 */
  async function loadIdentity(): Promise<void> {
    if (!tokens.value) {
      role.value = null
      userId.value = null
      return
    }
    try {
      const current = await userApi.me()
      role.value = current.role
      userId.value = current.userId
    } catch {
      role.value = null
      userId.value = null
    }
  }

  async function logout(): Promise<void> {
    try {
      await userApi.logout()
    } catch {
      // 令牌可能已经过期或被拉黑：服务端那一步失败不该阻止本地退出
    }
    forget()
  }

  /** 本地清干净。令牌失效（401 且刷新不了）时由 http 层触发。 */
  function forget(): void {
    clearTokens()
    tokens.value = null
    role.value = null
    userId.value = null
  }

  function applyTokens(next: Tokens): void {
    saveTokens(next)
    tokens.value = next
  }

  return {
    tokens,
    role,
    userId,
    isLoggedIn,
    isAdmin,
    login,
    loadIdentity,
    logout,
    forget,
    applyTokens
  }
})
