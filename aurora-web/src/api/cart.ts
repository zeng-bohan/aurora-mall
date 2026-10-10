import { http, unwrap } from './http'
import type { Result } from './types'

/**
 * 购物车行项目。除数量外都是下单时的商品快照，
 * 所以商品改价后购物车里可能仍是旧价——结算金额以订单服务算的为准。
 */
export interface CartItem {
  skuId: number
  quantity: number
  title: string
  price: number
  stock: number
  /** 1=在售 0=已下架；已下架的条目不能结算 */
  status: number
}

export interface CartItemRequest {
  skuId: number
  /** 后端限制 1..999，超出报「数量超出上限」 */
  quantity: number
}

export function list(): Promise<CartItem[]> {
  return unwrap(http.get<Result<CartItem[]>>('/cart/carts'))
}

export function addItem(request: CartItemRequest): Promise<void> {
  return unwrap(http.post<Result<void>>('/cart/carts/items', request))
}

/** 覆盖为指定数量（不是增量）。 */
export function updateItem(request: CartItemRequest): Promise<void> {
  return unwrap(http.put<Result<void>>('/cart/carts/items', request))
}

export function removeItem(skuId: number): Promise<void> {
  return unwrap(http.delete<Result<void>>(`/cart/carts/items/${skuId}`))
}

export function clear(): Promise<void> {
  return unwrap(http.delete<Result<void>>('/cart/carts'))
}
