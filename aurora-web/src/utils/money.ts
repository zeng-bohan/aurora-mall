/** 金额比较一律换算成整数分：0.1 + 0.2 !== 0.3 这种坑在优惠券门槛判断上会直接算错。 */
export function toCents(amount: number): number {
  return Math.round(amount * 100)
}

/** 展示用。后端 BigDecimal 序列化后会丢掉尾随零（19.90 -> 19.9），所以必须补回两位。 */
export function formatYuan(amount: number | null | undefined): string {
  if (amount === null || amount === undefined) {
    return '—'
  }
  return `¥${amount.toFixed(2)}`
}
