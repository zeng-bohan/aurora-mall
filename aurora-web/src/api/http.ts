import axios from 'axios'
import type { AxiosError, AxiosInstance, AxiosResponse, InternalAxiosRequestConfig } from 'axios'

import { clearTokens, loadTokens, saveTokens } from './session'
import { ApiError, ErrorCode, NetworkError } from './types'
import type { Result, Tokens } from './types'

/** 网关地址在开发期由 Vite 反代、生产由 nginx 反代，前端只认同源的 /api。 */
const BASE_URL = '/api'

/** 走网关的登录态端点：它们自己就是"拿/换令牌"，不能再去触发刷新。 */
const AUTH_FREE_PATHS = ['/user/login', '/user/register', '/user/refresh']

/** 标记已重试过，避免刷新成功后再次 401 时无限循环。 */
interface RetriableConfig extends InternalAxiosRequestConfig {
  _retriedAfterRefresh?: boolean
}

export const http: AxiosInstance = axios.create({
  baseURL: BASE_URL,
  timeout: 10_000,
  headers: { 'Content-Type': 'application/json' }
})

// ---- 会话失效的外部通知（router 注册），避免 http 层直接依赖 router ----

let sessionExpiredHandler: (() => void) | null = null

export function setSessionExpiredHandler(handler: () => void): void {
  sessionExpiredHandler = handler
}

function expireSession(): void {
  clearTokens()
  sessionExpiredHandler?.()
}

// ---- 刷新令牌：单飞，避免并发 401 各刷一次 ----
// 后端 refresh 是"一次一换"（旧 refreshToken 用掉即失效），
// 多个请求同时被 401 却各发一次刷新，只有第一个能成功，其余会把会话打成死局。

let refreshInFlight: Promise<Tokens> | null = null

async function refreshTokens(): Promise<Tokens> {
  const current = loadTokens()
  if (!current) {
    throw new ApiError(ErrorCode.UNAUTHORIZED, '登录已过期，请重新登录')
  }
  if (!refreshInFlight) {
    refreshInFlight = requestRefresh(current.refreshToken).finally(() => {
      refreshInFlight = null
    })
  }
  return refreshInFlight
}

// 用裸 axios 而非 http 实例：刷新请求自己不能再被拦截器捕获，否则 401 会递归。
async function requestRefresh(refreshToken: string): Promise<Tokens> {
  const response = await axios.post<Result<Tokens>>(
    `${BASE_URL}/user/refresh`,
    { refreshToken },
    { timeout: 10_000, headers: { 'Content-Type': 'application/json' } }
  )
  const body = response.data
  if (!body || body.code !== ErrorCode.SUCCESS || !body.data?.accessToken) {
    throw new ApiError(body?.code ?? ErrorCode.UNAUTHORIZED, body?.message ?? '登录已过期')
  }
  const tokens: Tokens = {
    accessToken: body.data.accessToken,
    refreshToken: body.data.refreshToken ?? refreshToken
  }
  saveTokens(tokens)
  return tokens
}

/** 刷新后重放原请求；返回 null 表示没法重试（无刷新令牌 / 已重试过 / 本身是登录态端点）。 */
async function retryAfterRefresh(config: RetriableConfig): Promise<AxiosResponse | null> {
  if (config._retriedAfterRefresh) {
    return null
  }
  if (AUTH_FREE_PATHS.some((path) => config.url?.startsWith(path))) {
    return null
  }
  let tokens: Tokens
  try {
    tokens = await refreshTokens()
  } catch {
    return null
  }
  config._retriedAfterRefresh = true
  config.headers.set('Authorization', `Bearer ${tokens.accessToken}`)
  return http.request(config)
}

// ---- 请求：带上访问令牌 ----

http.interceptors.request.use((config) => {
  const tokens = loadTokens()
  if (tokens) {
    config.headers.set('Authorization', `Bearer ${tokens.accessToken}`)
  }
  return config
})

// ---- 响应：解信封、区分业务失败与走不通 ----

function isEnvelope(value: unknown): value is Result<unknown> {
  return typeof value === 'object' && value !== null && typeof (value as Result<unknown>).code === 'number'
}

http.interceptors.response.use(
  async (response) => {
    const body: unknown = response.data
    if (!isEnvelope(body)) {
      return response
    }
    if (body.code === ErrorCode.SUCCESS) {
      return response
    }
    if (body.code === ErrorCode.UNAUTHORIZED) {
      const retried = await retryAfterRefresh(response.config as RetriableConfig)
      if (retried) {
        return retried
      }
      expireSession()
    }
    throw new ApiError(body.code, body.message)
  },
  async (error: AxiosError) => {
    const response = error.response
    if (response?.status === 401) {
      const retried = await retryAfterRefresh(error.config as RetriableConfig)
      if (retried) {
        return retried
      }
      expireSession()
      throw new ApiError(ErrorCode.UNAUTHORIZED, '登录已过期，请重新登录')
    }
    // 网关限流等服务侧直接用 HTTP 4xx/5xx 回信封的情况：仍按业务码处理
    if (isEnvelope(response?.data)) {
      const body = response.data as Result<unknown>
      throw new ApiError(body.code, body.message)
    }
    if (error.code === 'ECONNABORTED') {
      throw new NetworkError('请求超时，请稍后重试', error)
    }
    throw new NetworkError('网络异常，无法连接服务', error)
  }
)

/**
 * 取出信封里的 data。业务失败已由拦截器抛成 ApiError，
 * 走到这里说明 code===0，但 data 仍可能是 null（Result.ok() 无载荷）。
 */
export async function unwrap<T>(promise: Promise<AxiosResponse<Result<T>>>): Promise<T> {
  const response = await promise
  const body = response.data
  if (!isEnvelope(body)) {
    throw new NetworkError('响应格式不符合预期')
  }
  return body.data
}
