import { http, unwrap } from './http'
import type { Result } from './types'

/** 订单状态：0=待支付 1=已支付 2=已关闭 */
export const OrderStatus = {
  CREATED: 0,
  PAID: 1,
  CLOSED: 2
} as const

export const ORDER_STATUS_LABEL: Record<number, string> = {
  [OrderStatus.CREATED]: '待支付',
  [OrderStatus.PAID]: '已支付',
  [OrderStatus.CLOSED]: '已关闭'
}

export interface OrderView {
  orderId: number
  userId: number
  skuId: number
  quantity: number
  /** 服务端按商品快照算出的金额；请求体里不接受金额，防改价。 */
  totalAmount: number
  status: number
}

export interface PlaceOrderRequest {
  skuId: number
  quantity: number
}

export interface PlaceOrderOptions {
  /**
   * 幂等键。事实上必填：缺失时后端直接回 10001「缺少 Idempotency-Key 请求头」。
   * 同一个「提交意图」在网络重试时必须复用同一个键，否则后端的去重形同虚设。
   */
  idempotencyKey: string
  /** 用券下单：券 id 走 Coupon-Id 请求头，不在请求体里。 */
  couponId?: number | null
}

export function place(request: PlaceOrderRequest, options: PlaceOrderOptions): Promise<number> {
  const headers: Record<string, string> = { 'Idempotency-Key': options.idempotencyKey }
  if (options.couponId != null) {
    headers['Coupon-Id'] = String(options.couponId)
  }
  return unwrap(http.post<Result<number>>('/order/orders', request, { headers }))
}

/**
 * 按 id 查单。
 *
 * 注意后端**没有「我的订单列表」接口**——只有这一个按 id 查的端点。
 * 也就是说前端要么自己记住下过的订单 id（见 stores/myOrders.ts），
 * 要么就只能展示刚下的那一单。这是 M6 暴露出来的一处后端缺口。
 */
export function detail(id: number): Promise<OrderView> {
  return unwrap(http.get<Result<OrderView>>(`/order/orders/${id}`))
}
