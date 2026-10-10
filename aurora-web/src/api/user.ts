import { http, unwrap } from './http'
import type { Result, Tokens } from './types'

export interface LoginRequest {
  username: string
  password: string
}

export interface RegisterRequest {
  username: string
  password: string
  nickname?: string
}

/** 登录/刷新响应比 Tokens 多一个 expiresIn（access 令牌有效秒数）。 */
export interface TokenResponse extends Tokens {
  expiresIn: number
}

/**
 * 当前身份。登录响应里**没有** role 字段——是否管理员只能从这里取，
 * 不能靠解 JWT 猜（role 的来源是网关注入的 X-User-Role）。
 */
export interface CurrentUser {
  userId: string
  role: string
}

export const ROLE_ADMIN = 'ADMIN'

/** 注册固定 role=USER：后端没有注册出 ADMIN 的路径。 */
export function register(request: RegisterRequest): Promise<number> {
  return unwrap(http.post<Result<number>>('/user/register', request))
}

export function login(request: LoginRequest): Promise<TokenResponse> {
  return unwrap(http.post<Result<TokenResponse>>('/user/login', request))
}

/**
 * 登出：服务端要拿 access 令牌里的 jti 拉黑，所以必须带着令牌发。
 * 令牌放在 Authorization 头里由拦截器统一加，这里不需要额外参数。
 */
export function logout(): Promise<void> {
  return unwrap(http.post<Result<void>>('/user/logout'))
}

export function me(): Promise<CurrentUser> {
  return unwrap(http.get<Result<CurrentUser>>('/user/me'))
}
