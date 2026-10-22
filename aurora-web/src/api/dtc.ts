import { http, unwrap } from './http'
import type { Result, Tokens } from './types'

export interface UserView {
  id: number
  username: string
  nickname: string | null
  role: 'ADMIN' | 'USER'
  status: number
  createdAt: string
}

export interface TokenResponse extends Tokens {
  expiresIn: number
}

export interface Category {
  id: number
  name: string
  slug: string
  sort: number
  status: number
}

export interface AttributeValue {
  id: number
  value: string
  colorHex: string | null
  sort: number
  status: number
}

export interface Attribute {
  id: number
  name: string
  code: string
  type: string
  unit: string | null
  filterable: boolean
  variant: boolean
  required: boolean
  display: boolean
  sort: number
  status: number
  values: AttributeValue[]
}

export interface SkuAttribute {
  attributeId: number
  name: string
  valueId: number
  value: string
  colorHex: string | null
}

export interface Sku {
  id: number
  skuCode: string
  title: string
  price: number
  status: number
  imageUrl: string
  stock: number | null
  attributes: SkuAttribute[]
}

export interface SpuCard {
  id: number
  categoryId: number
  categoryName: string
  title: string
  subtitle: string
  imageUrl: string
  minPrice: number
  maxPrice: number
  skuCount: number
  inStock: boolean | null
  status: number
}

export interface Spec {
  name: string
  value: string
}

export interface SpuDetail {
  id: number
  categoryId: number
  categoryName: string
  brand: string
  title: string
  subtitle: string
  description: string
  status: number
  attributes: Attribute[]
  skus: Sku[]
  specs: Spec[]
  related: SpuCard[]
}

export interface ProductSnapshot {
  id: number
  spuId: number
  skuCode: string
  title: string
  attributesText: string
  imageUrl: string
  price: number
  stock: number | null
  status: number
}

export interface PageResult<T> {
  records: T[]
  total: number
  current: number
  size: number
  pages: number
}

export interface CartItem {
  skuId: number
  spuId: number
  skuCode: string
  quantity: number
  title: string
  attributesText: string
  imageUrl: string
  price: number
  stock: number | null
  status: number
}

export interface Address {
  id: number
  userId: number
  receiverName: string
  phone: string
  province: string
  city: string
  district: string
  detail: string
  postalCode: string | null
  isDefault: boolean
  createdAt: string
  updatedAt: string
}

export interface AddressInput {
  receiverName: string
  phone: string
  province: string
  city: string
  district: string
  detail: string
  postalCode?: string
  isDefault?: boolean
}

export interface TradeItem {
  id: number
  spuId: number
  skuId: number
  title: string
  skuCode: string
  attributesText: string
  imageUrl: string
  unitPrice: number
  quantity: number
  itemAmount: number
  discountAmount: number
  refundedAmount: number
  status: 'NORMAL' | 'REFUNDING' | 'REFUNDED'
  refundable: boolean
}

export interface RefundBrief {
  id: number
  refundNo: string
  amount: number
  status: 'PENDING' | 'APPROVED' | 'REJECTED'
  reason: string
  rejectReason: string | null
  createdAt: string
  reviewedAt: string | null
  itemIds: number[]
}

export interface Trade {
  id: number
  tradeNo: string
  userId: number
  source: 'MALL' | 'SECKILL'
  status: 'CREATED' | 'PAID' | 'SHIPPED' | 'COMPLETED' | 'CLOSED' | 'REFUNDED'
  refundStatus: 'NONE' | 'PARTIAL' | 'FULL'
  merchandiseAmount: number
  discountAmount: number
  shippingFee: number
  payableAmount: number
  refundedAmount: number
  couponId: number | null
  couponTitle: string | null
  address: {
    receiverName: string
    phone: string
    province: string
    city: string
    district: string
    detail: string
    postalCode: string | null
  }
  carrier: string | null
  trackingNo: string | null
  paidAt: string | null
  shippedAt: string | null
  completedAt: string | null
  closedAt: string | null
  createdAt: string
  items: TradeItem[]
  refunds: RefundBrief[]
}

export interface TradePreviewItem {
  id: number
  skuId: number
  title: string
  attributesText: string
  imageUrl: string
  quantity: number
  unitPrice: number
  status: string
}

export interface TradeRow {
  id: number
  tradeNo: string
  userId: number
  source: 'MALL' | 'SECKILL'
  status: Trade['status']
  refundStatus: Trade['refundStatus']
  payableAmount: number
  refundedAmount: number
  receiverName: string
  receiverPhone: string
  createdAt: string
  paidAt: string | null
  items: TradePreviewItem[]
}

export interface TradePage {
  records: TradeRow[]
  total: number
  current: number
  size: number
  pages: number
}

export interface RefundItem {
  orderItemId: number
  skuId: number
  title: string
  quantity: number
  amount: number
}

export interface Refund {
  id: number
  refundNo: string
  tradeId: number
  userId: number
  amount: number
  reason: string
  status: 'PENDING' | 'APPROVED' | 'REJECTED'
  settled: boolean
  rejectReason: string | null
  reviewer: string | null
  createdAt: string
  reviewedAt: string | null
  tradeNo: string
  receiverName: string
  items: RefundItem[]
}

export interface RefundPage {
  records: Refund[]
  total: number
  current: number
  size: number
  pages: number
}

export interface RefundQuote {
  allowed: boolean
  full: boolean
  amount: number
  remainingMerchandise: number
  message: string
}

export interface RefundApplyResult {
  refundId: number
  amount: number
  status: string
}

export interface PaymentSummary {
  paymentId: number
  tradeId: number
  tradeNo: string
  amount: number
  refundedAmount: number
  status: 'PAYING' | 'PAID' | 'PARTIALLY_REFUNDED' | 'REFUNDED'
}

export interface CouponTemplate {
  id: number
  title: string
  thresholdAmount: number
  discountAmount: number
  remaining: number
  total: number
  claimStartAt: string
  claimEndAt: string
}

export interface UserCoupon {
  id: number
  title: string
  thresholdAmount: number
  discountAmount: number
  status: 'UNUSED' | 'LOCKED' | 'USED' | 'EXPIRED'
  expireAt: string
  tradeId: number | null
}

export interface AdminStats {
  pendingShip: number
  pendingRefund: number
  orders30d: number
  revenue30d: number
}

export interface CatalogStats {
  spuCount: number
  skuCount: number
}

export interface AdminSku {
  id: number
  spuId: number
  spuTitle: string
  skuCode: string
  title: string
  price: number
  status: number
  imageUrl: string
  stock: number | null
}

export interface AdminUser {
  id: number
  username: string
  nickname: string | null
  role: string
  status: number
  createdAt: string
  orderCount: number
  lastOrderAt: string | null
}

export interface StockView {
  skuId: number
  available: number | null
}

export interface SeckillActivity {
  id: number
  title: string
  spuId: number
  skuId: number
  productTitle: string
  productImage: string
  originalPrice: number
  seckillPrice: number
  totalStock: number
  perUserLimit: number
  startAt: string
  endAt: string
  phase: 'NOT_STARTED' | 'RUNNING' | 'ENDED'
  availableStock: number
}

export interface SeckillBuy {
  status: 'QUEUED' | 'PLACED'
  tradeId: number | null
}

export interface SaveSku {
  id?: number
  skuCode: string
  price: number
  imageUrl?: string
  status?: number
  attributes: Record<number, number>
  title?: string
}

export interface SaveSpu {
  categoryId: number
  title: string
  subtitle: string
  description: string
  attributeIds: number[]
  attributeValues?: Record<number, number>
  specs: Spec[]
  skus: SaveSku[]
  status?: number
}

export interface SaveCategory {
  name: string
  slug: string
  sort?: number
  status?: number
}

export interface SaveAttribute {
  name: string
  code: string
  type?: string
  unit?: string
  filterable?: boolean
  variant?: boolean
  required?: boolean
  display?: boolean
  sort?: number
  status?: number
}

export interface SaveAttributeValue {
  value: string
  colorHex?: string
  sort?: number
  status?: number
}

export interface SaveCouponTemplate {
  title: string
  thresholdAmount: number
  discountAmount: number
  total: number
  claimStartAt: string
  claimEndAt: string
  validDays: number
}

export interface SaveSeckill {
  title: string
  skuId: number
  seckillPrice: number
  totalStock: number
  perUserLimit: number
  startAt: string
  endAt: string
}

export const authApi = {
  login: (body: { username: string; password: string }) =>
    unwrap(http.post<Result<TokenResponse>>('/user/login', body)),
  register: (body: { username: string; password: string; nickname?: string }) =>
    unwrap(http.post<Result<number>>('/user/register', body)),
  me: () => unwrap(http.get<Result<UserView>>('/user/me')),
  logout: () => unwrap(http.post<Result<void>>('/user/logout')),
}

export const catalogApi = {
  page: (params: {
    categoryId?: number
    keyword?: string
    minPrice?: number
    maxPrice?: number
    attrs?: string
    sort?: string
    current?: number
    size?: number
  }) => unwrap(http.get<Result<PageResult<SpuCard>>>('/product/products', { params })),
  detail: (id: number) => unwrap(http.get<Result<SpuDetail>>(`/product/products/${id}`)),
  related: (id: number) =>
    unwrap(http.get<Result<SpuCard[]>>(`/product/products/${id}/related`)),
  categories: () => unwrap(http.get<Result<Category[]>>('/product/products/categories')),
  attributes: (categoryId?: number) =>
    unwrap(http.get<Result<Attribute[]>>('/product/products/attributes', { params: { categoryId } })),
  batch: (ids: number[]) =>
    unwrap(http.get<Result<ProductSnapshot[]>>('/product/products/batch', {
      params: { ids: ids.join(',') },
    })),
  snapshot: (skuId: number) =>
    unwrap(http.get<Result<ProductSnapshot>>(`/product/products/skus/${skuId}`)),
}

export const adminCatalogApi = {
  page: (params: { categoryId?: number; keyword?: string; current?: number; size?: number }) =>
    unwrap(http.get<Result<PageResult<SpuCard>>>('/product/admin/products', { params })),
  detail: (id: number) => unwrap(http.get<Result<SpuDetail>>(`/product/admin/products/${id}`)),
  create: (body: SaveSpu) => unwrap(http.post<Result<number>>('/product/admin/products', body)),
  update: (id: number, body: SaveSpu) =>
    unwrap(http.put<Result<number>>(`/product/admin/products/${id}`, body)),
  status: (id: number, status: number) =>
    unwrap(http.put<Result<void>>(`/product/admin/products/${id}/status`, null, { params: { status } })),
  stats: () => unwrap(http.get<Result<CatalogStats>>('/product/admin/stats')),
  skus: (keyword?: string) =>
    unwrap(http.get<Result<AdminSku[]>>('/product/admin/skus', { params: { keyword } })),
  categories: () => unwrap(http.get<Result<Category[]>>('/product/admin/categories')),
  createCategory: (body: SaveCategory) =>
    unwrap(http.post<Result<number>>('/product/admin/categories', body)),
  updateCategory: (id: number, body: SaveCategory) =>
    unwrap(http.put<Result<void>>(`/product/admin/categories/${id}`, body)),
  attributes: () => unwrap(http.get<Result<Attribute[]>>('/product/admin/attributes')),
  createAttribute: (body: SaveAttribute) =>
    unwrap(http.post<Result<number>>('/product/admin/attributes', body)),
  updateAttribute: (id: number, body: SaveAttribute) =>
    unwrap(http.put<Result<void>>(`/product/admin/attributes/${id}`, body)),
  addValue: (attributeId: number, body: SaveAttributeValue) =>
    unwrap(http.post<Result<number>>(`/product/admin/attributes/${attributeId}/values`, body)),
  updateValue: (valueId: number, body: SaveAttributeValue) =>
    unwrap(http.put<Result<void>>(`/product/admin/attributes/values/${valueId}`, body)),
}

export const cartApi = {
  list: () => unwrap(http.get<Result<CartItem[]>>('/cart/carts')),
  add: (skuId: number, quantity: number) =>
    unwrap(http.post<Result<void>>('/cart/carts/items', { skuId, quantity })),
  update: (skuId: number, quantity: number) =>
    unwrap(http.put<Result<void>>('/cart/carts/items', { skuId, quantity })),
  remove: (skuId: number) => unwrap(http.delete<Result<void>>(`/cart/carts/items/${skuId}`)),
  clear: () => unwrap(http.delete<Result<void>>('/cart/carts')),
}

export const addressApi = {
  list: () => unwrap(http.get<Result<Address[]>>('/user/addresses')),
  create: (body: AddressInput) => unwrap(http.post<Result<number>>('/user/addresses', body)),
  update: (id: number, body: AddressInput) =>
    unwrap(http.put<Result<void>>(`/user/addresses/${id}`, body)),
  remove: (id: number) => unwrap(http.delete<Result<void>>(`/user/addresses/${id}`)),
  setDefault: (id: number) => unwrap(http.put<Result<void>>(`/user/addresses/${id}/default`)),
}

export const tradeApi = {
  place: (
    body: { items: { skuId: number; quantity: number }[]; addressId: number; couponId?: number },
    idempotencyKey: string,
  ) =>
    unwrap(http.post<Result<number>>('/order/trades', body, {
      headers: { 'Idempotency-Key': idempotencyKey },
    })),
  list: (params: { current?: number; size?: number; status?: string }) =>
    unwrap(http.get<Result<TradePage>>('/order/trades', { params })),
  detail: (id: number) => unwrap(http.get<Result<Trade>>(`/order/trades/${id}`)),
  cancel: (id: number) => unwrap(http.post<Result<void>>(`/order/trades/${id}/cancel`)),
  confirm: (id: number) => unwrap(http.post<Result<void>>(`/order/trades/${id}/confirm`)),
  quoteRefund: (id: number, itemIds: number[]) =>
    unwrap(http.get<Result<RefundQuote>>(`/order/trades/${id}/refunds/quote`, {
      params: { itemIds: itemIds.join(',') },
    })),
  applyRefund: (id: number, itemIds: number[], reason: string) =>
    unwrap(http.post<Result<RefundApplyResult>>(`/order/trades/${id}/refunds`, { itemIds, reason })),
  myRefunds: (params: { current?: number; size?: number }) =>
    unwrap(http.get<Result<RefundPage>>('/order/trades/refunds/mine', { params })),
}

export const adminTradeApi = {
  stats: () => unwrap(http.get<Result<AdminStats>>('/order/admin/stats')),
  trades: (params: {
    status?: string
    source?: string
    keyword?: string
    current?: number
    size?: number
  }) => unwrap(http.get<Result<TradePage>>('/order/admin/trades', { params })),
  trade: (id: number) => unwrap(http.get<Result<Trade>>(`/order/admin/trades/${id}`)),
  ship: (id: number, body: { carrier: string; trackingNo: string }) =>
    unwrap(http.post<Result<void>>(`/order/admin/trades/${id}/ship`, body)),
  refunds: (params: { status?: string; current?: number; size?: number }) =>
    unwrap(http.get<Result<RefundPage>>('/order/admin/refunds', { params })),
  refund: (id: number) => unwrap(http.get<Result<Refund>>(`/order/admin/refunds/${id}`)),
  approve: (id: number) => unwrap(http.post<Result<Refund>>(`/order/admin/refunds/${id}/approve`)),
  reject: (id: number, reason: string) =>
    unwrap(http.post<Result<Refund>>(`/order/admin/refunds/${id}/reject`, { reason })),
  users: () => unwrap(http.get<Result<AdminUser[]>>('/order/admin/users')),
}

export const couponApi = {
  templates: () => unwrap(http.get<Result<CouponTemplate[]>>('/order/coupons/templates')),
  claim: (id: number) => unwrap(http.post<Result<number>>(`/order/coupons/${id}/claim`)),
  mine: () => unwrap(http.get<Result<UserCoupon[]>>('/order/coupons/mine')),
  adminList: () => unwrap(http.get<Result<CouponTemplate[]>>('/order/admin/coupons')),
  adminCreate: (body: SaveCouponTemplate) =>
    unwrap(http.post<Result<number>>('/order/admin/coupons', body)),
}

export const paymentApi = {
  initiate: (tradeId: number) =>
    unwrap(http.post<Result<PaymentSummary>>('/payment/payments', { tradeId })),
  detail: (tradeId: number) =>
    unwrap(http.get<Result<PaymentSummary>>(`/payment/payments/${tradeId}`)),
  mockPay: (tradeId: number) =>
    unwrap(http.post<Result<PaymentSummary>>(`/payment/payments/${tradeId}/mock-pay`)),
}

export const inventoryApi = {
  stocks: (skuIds?: number[]) =>
    unwrap(http.get<Result<StockView[]>>('/inventory/admin/stocks', {
      params: skuIds && skuIds.length ? { skuIds: skuIds.join(',') } : undefined,
    })),
  setStock: (skuId: number, quantity: number) =>
    unwrap(http.put<Result<void>>(`/inventory/admin/stocks/${skuId}`, { quantity })),
}

export const seckillApi = {
  list: () => unwrap(http.get<Result<SeckillActivity[]>>('/seckill/activities')),
  detail: (id: number) =>
    unwrap(http.get<Result<SeckillActivity>>(`/seckill/activities/${id}`)),
  buy: (id: number) => unwrap(http.post<Result<SeckillBuy>>(`/seckill/activities/${id}/orders`)),
  mine: (id: number) =>
    unwrap(http.get<Result<SeckillBuy>>(`/seckill/activities/${id}/orders/mine`)),
  adminList: () => unwrap(http.get<Result<SeckillActivity[]>>('/seckill/admin/activities')),
  adminCreate: (body: SaveSeckill) =>
    unwrap(http.post<Result<number>>('/seckill/admin/activities', body)),
  preheat: (id: number) =>
    unwrap(http.post<Result<{ activityId: number; stock: number }>>(
      `/seckill/admin/activities/${id}/preheat`,
    )),
}
