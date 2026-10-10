import { http, unwrap } from './http'
import type { Result } from './types'

/** 券模板（可领列表）。remaining 是剩余可领数量，领完为 0。 */
export interface CouponTemplateView {
  id: number
  title: string
  thresholdAmount: number
  discountAmount: number
  remaining: number
  claimStartAt: string
  claimEndAt: string
}

/** 我的券。EXPIRED 不是库里的状态，是后端读取时按有效期派生的。 */
export const CouponStatus = {
  UNUSED: 'UNUSED',
  LOCKED: 'LOCKED',
  USED: 'USED',
  EXPIRED: 'EXPIRED'
} as const

export const COUPON_STATUS_LABEL: Record<string, string> = {
  [CouponStatus.UNUSED]: '未使用',
  [CouponStatus.LOCKED]: '已锁定（下单中）',
  [CouponStatus.USED]: '已使用',
  [CouponStatus.EXPIRED]: '已过期'
}

export interface CouponView {
  id: number
  title: string
  thresholdAmount: number
  discountAmount: number
  status: string
  expireAt: string
  /** 已用于某订单时才有值 */
  orderId: number | null
}

export interface CreateCouponTemplateRequest {
  title: string
  thresholdAmount: number
  discountAmount: number
  total: number
  claimStartAt: string
  claimEndAt: string
  /** 领取后多少天内有效 */
  validDays: number
}

// ---- 用户端 ----

export function templates(): Promise<CouponTemplateView[]> {
  return unwrap(http.get<Result<CouponTemplateView[]>>('/order/coupons/templates'))
}

export function claim(templateId: number): Promise<number> {
  return unwrap(http.post<Result<number>>(`/order/coupons/${templateId}/claim`))
}

export function mine(): Promise<CouponView[]> {
  return unwrap(http.get<Result<CouponView[]>>('/order/coupons/mine'))
}

// ---- 运营端 ----

/**
 * 建券模板。这个端点**没有 Bean Validation**（字段校验在 Service 层），
 * 非法输入返回 10001 而不是按字段报错，所以前端要自己先做基本校验。
 */
export function adminCreate(request: CreateCouponTemplateRequest): Promise<number> {
  return unwrap(http.post<Result<number>>('/order/admin/coupons', request))
}

/** 运营端列表：不过滤领取窗口，已结束的也在。 */
export function adminList(): Promise<CouponTemplateView[]> {
  return unwrap(http.get<Result<CouponTemplateView[]>>('/order/admin/coupons'))
}
