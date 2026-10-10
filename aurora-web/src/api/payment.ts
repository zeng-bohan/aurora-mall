import { http, unwrap } from './http'
import type { Result } from './types'

/** 支付单状态：0=支付中 1=已支付 2=已退款 */
export const PaymentStatus = {
  PAYING: 0,
  PAID: 1,
  REFUNDED: 2
} as const

export const PAYMENT_STATUS_LABEL: Record<number, string> = {
  [PaymentStatus.PAYING]: '支付中',
  [PaymentStatus.PAID]: '已支付',
  [PaymentStatus.REFUNDED]: '已退款'
}

export interface PaymentView {
  paymentId: number
  orderId: number
  amount: number
  status: number
}

/** 发起支付：只传订单号，金额由服务端按订单算（请求里带金额会越权定价）。 */
export function initiate(orderId: number): Promise<PaymentView> {
  return unwrap(http.post<Result<PaymentView>>('/payment/payments', { orderId }))
}

export function byOrder(orderId: number): Promise<PaymentView> {
  return unwrap(http.get<Result<PaymentView>>(`/payment/payments/${orderId}`))
}

/**
 * 渠道回调 `/payment/payments/mock-callback` 故意**不在前端实现**。
 *
 * 它代表「渠道」这一侧，必须带 `X-Channel-Signature`（channel-secret 的
 * HMAC-SHA256，密钥在服务端与 Nacos，绝不下发浏览器）。前端若去调它，
 * 就得把渠道密钥打进前端包，等于把支付回调签名模型作废。
 * 本地联调用 `docker/mock-pay.sh`（读 .secrets.env 签名）来模拟渠道。
 */
