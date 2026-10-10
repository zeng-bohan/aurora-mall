import { http, unwrap } from './http'
import type { Result } from './types'

/** 活动阶段，由后端按当前时间与起止时间算好。 */
export const SeckillPhase = {
  NOT_STARTED: 'NOT_STARTED',
  RUNNING: 'RUNNING',
  ENDED: 'ENDED'
} as const

export const SECKILL_PHASE_LABEL: Record<string, string> = {
  [SeckillPhase.NOT_STARTED]: '未开始',
  [SeckillPhase.RUNNING]: '抢购中',
  [SeckillPhase.ENDED]: '已结束'
}

/** 抢购结果：PLACED 已落单；QUEUED 已占名额、正在异步落单，要轮询。 */
export const SeckillBuyStatus = {
  PLACED: 'PLACED',
  QUEUED: 'QUEUED'
} as const

export interface SeckillActivityView {
  id: number
  title: string
  skuId: number
  seckillPrice: number
  totalStock: number
  perUserLimit: number
  startAt: string
  endAt: string
  phase: string
  availableStock: number
}

export interface SeckillBuyView {
  status: string
  /** 只有 PLACED 时才有值 */
  orderId: number | null
}

export interface CreateActivityRequest {
  title: string
  skuId: number
  seckillPrice: number
  totalStock: number
  perUserLimit: number
  startAt: string
  endAt: string
}

export interface PreheatResult {
  activityId: number
  stock: number
}

// ---- 用户端（全部需要 token，活动列表也不例外）----

/** 未结束的活动，抢购中的排在前面。 */
export function activities(): Promise<SeckillActivityView[]> {
  return unwrap(http.get<Result<SeckillActivityView[]>>('/seckill/activities'))
}

export function activity(id: number): Promise<SeckillActivityView> {
  return unwrap(http.get<Result<SeckillActivityView>>(`/seckill/activities/${id}`))
}

/** 抢购：无请求体，身份取网关注入的 X-User-Id。 */
export function buy(id: number): Promise<SeckillBuyView> {
  return unwrap(http.post<Result<SeckillBuyView>>(`/seckill/activities/${id}/orders`))
}

/**
 * 轮询抢购结果。注意：该活动下没有你的记录时后端回 40400 而不是空结果，
 * 调用方要么把 40400 当「还没抢到/没参与」，要么先确保已经 buy 过。
 */
export function myBuy(id: number): Promise<SeckillBuyView> {
  return unwrap(http.get<Result<SeckillBuyView>>(`/seckill/activities/${id}/orders/mine`))
}

// ---- 运营端 ----

/**
 * 建活动。与券模板一样**没有 Bean Validation**，字段校验在 Service 层，
 * 非法输入统一回 10001，所以前端要先自己校验。
 */
export function adminCreate(request: CreateActivityRequest): Promise<number> {
  return unwrap(http.post<Result<number>>('/seckill/admin/activities', request))
}

export function adminActivities(): Promise<SeckillActivityView[]> {
  return unwrap(http.get<Result<SeckillActivityView[]>>('/seckill/admin/activities'))
}

/** 预热：把活动库存灌进 Redis。活动创建后不预热则无法抢购（30005）。 */
export function adminPreheat(id: number): Promise<PreheatResult> {
  return unwrap(http.post<Result<PreheatResult>>(`/seckill/admin/activities/${id}/preheat`))
}
