/**
 * 后端统一响应信封：业务错误走 HTTP 200 + 非零 code，
 * 基础设施故障由 GlobalExceptionHandler 返回 HTTP 5xx。
 */
export interface Result<T> {
  code: number
  message: string
  data: T
}

/**
 * 双令牌。refresh 一次一换：每次刷新都会返回新的 refreshToken，
 * 旧的立即失效——所以并发刷新必须单飞，否则只有第一个能成功。
 */
export interface Tokens {
  accessToken: string
  refreshToken: string
}

/**
 * MyBatis-Plus 的分页对象直接序列化给前端，带一堆内部字段
 * （optimizeCountSql / countId / maxLimit 等）。前端只该依赖这五个，
 * 多取的字段不进类型，免得后端内部结构一改就牵连页面。
 */
export interface Page<T> {
  records: T[]
  total: number
  size: number
  current: number
  pages: number
}

/** 与后端 ErrorCode 枚举一一对应（只列前端要分支处理的）。 */export const ErrorCode = {
  SUCCESS: 0,
  SYSTEM_ERROR: 10000,
  PARAM_ERROR: 10001,
  UNAUTHORIZED: 40100,
  FORBIDDEN: 40300,
  NOT_FOUND: 40400,
  DUPLICATE_REQUEST: 40900,
  RATE_LIMITED: 42900,
  INVENTORY_INSUFFICIENT: 20001,
  SECKILL_NOT_STARTED: 30001,
  SECKILL_ENDED: 30002,
  SECKILL_SOLD_OUT: 30003,
  SECKILL_ALREADY_BOUGHT: 30004,
  SECKILL_NOT_READY: 30005,
  COUPON_NOT_STARTED: 50001,
  COUPON_CLAIM_ENDED: 50002,
  COUPON_SOLD_OUT: 50003,
  COUPON_ALREADY_CLAIMED: 50004,
  COUPON_NOT_USABLE: 50005,
  COUPON_EXPIRED: 50006,
  COUPON_THRESHOLD_NOT_MET: 50007
} as const

/**
 * 业务失败：后端明确回了非零 code。
 * 与"请求根本没走通"（网络/5xx）区分开——前者该把 message 直接给用户看，
 * 后者只能给一句通用提示，因为 message 是给运维看的。
 */
export class ApiError extends Error {
  readonly code: number

  constructor(code: number, message: string) {
    super(message)
    this.name = 'ApiError'
    this.code = code
  }

  /** 未登录/登录过期：调用方通常只需提示并跳登录，不必自己判断码。 */
  get isUnauthorized(): boolean {
    return this.code === ErrorCode.UNAUTHORIZED
  }
}

/** 网络层失败（超时、连接被拒、5xx）：不是业务结论，不可当作"操作被拒绝"。 */
export class NetworkError extends Error {
  constructor(message: string, readonly cause?: unknown) {
    super(message)
    this.name = 'NetworkError'
  }
}
