import { http, unwrap } from './http'
import type { Result } from './types'

/**
 * 库存（管理端）。
 *
 * `/stocks/**` 那组是 order 服务经 Feign 用的，服务侧的身份守卫会把任何带
 * 用户身份的请求一律 403——浏览器够不着，前端也不该碰。能读能改的只有
 * `/admin/stocks/**`，它要求 ADMIN 角色。
 */

export interface StockView {
  skuId: number
  /** null = 该 SKU 还没开过库存，与"可售为 0"不是一回事 */
  available: number | null
}

export function getStock(skuId: number): Promise<StockView> {
  return unwrap(http.get<Result<StockView>>(`/inventory/admin/stocks/${skuId}`))
}

export function setStock(skuId: number, quantity: number): Promise<void> {
  return unwrap(http.put<Result<void>>(`/inventory/admin/stocks/${skuId}`, { quantity }))
}
