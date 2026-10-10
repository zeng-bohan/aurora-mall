import { http, unwrap } from './http'
import type { Page, Result } from './types'

export interface Sku {
  id: number
  title: string
  /** 后端用 BigDecimal 序列化，前端按 number 处理；金额比较一律走整数分。 */
  price: number
  stock: number
  /** 1=在售 0=下架 */
  status: number
  createdAt: string
  updatedAt: string
}

export const SKU_ON_SALE = 1

export interface SaveSkuRequest {
  title: string
  price: number
  stock: number
}

// ---- 公开读（网关白名单：GET /api/product/** 且路径不含 /admin）----

export function page(current = 1, size = 12): Promise<Page<Sku>> {
  return unwrap(http.get<Result<Page<Sku>>>('/product/products', { params: { current, size } }))
}

export function detail(id: number): Promise<Sku> {
  return unwrap(http.get<Result<Sku>>(`/product/products/${id}`))
}

/**
 * 批量取。ids 手工拼成逗号串：axios 默认把数组序列化成 `ids[]=1&ids[]=2`，
 * 而 Spring 的 @RequestParam List<Long> 只认 `ids=1,2` 或重复的 ids 键。
 */
export function batch(ids: number[]): Promise<Sku[]> {
  if (ids.length === 0) {
    return Promise.resolve([])
  }
  return unwrap(
    http.get<Result<Sku[]>>('/product/products/batch', { params: { ids: ids.join(',') } })
  )
}

// ---- 管理端（路径含 /admin，需 role=ADMIN）----

/** 与公开列表的差别：含已下架商品。 */
export function adminPage(current = 1, size = 10): Promise<Page<Sku>> {
  return unwrap(http.get<Result<Page<Sku>>>('/product/admin/products', { params: { current, size } }))
}

export function adminCreate(request: SaveSkuRequest): Promise<number> {
  return unwrap(http.post<Result<number>>('/product/admin/products', request))
}

/**
 * 更新。后端显式忽略 stock（库存归 aurora-inventory 管，改这里会造成
 * DB 与 Redis 双份库存不一致），所以调用方不必也不能靠这个接口改库存。
 */
export function adminUpdate(id: number, request: SaveSkuRequest): Promise<void> {
  return unwrap(http.put<Result<void>>(`/product/admin/products/${id}`, request))
}

/** 下架，非物理删除：历史订单要能查到商品快照。 */
export function adminOffShelf(id: number): Promise<void> {
  return unwrap(http.delete<Result<void>>(`/product/admin/products/${id}`))
}
