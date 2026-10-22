export type ProductKind =
  | 'camera'
  | 'headphones'
  | 'keyboard'
  | 'mouse'
  | 'chair'
  | 'coffee-machine'
  | 'vacuum'
  | 'tablet'
  | 'phone'
  | 'battery'
  | 'dock'
  | 'speaker'
  | 'purifier'
  | 'coffee'
  | 'charger'
  | 'generic'

const KIND_RULES: Array<[RegExp, ProductKind]> = [
  [/相机|Osmo|Pocket/i, 'camera'],
  [/耳机|WH-1000|降噪/i, 'headphones'],
  [/键盘|黑寡妇|keyboard/i, 'keyboard'],
  [/鼠标|MX Master|mouse/i, 'mouse'],
  [/椅|MARKUS|工学椅/i, 'chair'],
  [/咖啡机|全自动/i, 'coffee-machine'],
  [/吸尘器|V12|Detect/i, 'vacuum'],
  [/iPad|平板/i, 'tablet'],
  [/iPhone|手机|小米 14|Ultra/i, 'phone'],
  [/充电宝|移动电源|mAh/i, 'battery'],
  [/扩展坞|dock|雷电/i, 'dock'],
  [/音箱|N300|speaker/i, 'speaker'],
  [/净化器|空气/i, 'purifier'],
  [/咖啡|超即溶/i, 'coffee'],
  [/充电器|氮化镓|65W/i, 'charger']
]

export const KIND_LABEL: Record<ProductKind, string> = {
  camera: '影像',
  headphones: '随身听',
  keyboard: '输入',
  mouse: '输入',
  chair: '家居',
  'coffee-machine': '厨房',
  vacuum: '清洁',
  tablet: '移动设备',
  phone: '移动设备',
  battery: '随身供电',
  dock: '桌面扩展',
  speaker: '桌面聆听',
  purifier: '家居',
  coffee: '食品',
  charger: '随身供电',
  generic: '选品'
}

export function productKind(title: string): ProductKind {
  const rule = KIND_RULES.find(([pattern]) => pattern.test(title))
  return rule ? rule[1] : 'generic'
}

export function productKindLabel(title: string): string {
  return KIND_LABEL[productKind(title)]
}

export function monogram(title: string): string {
  const latin = title.match(/[A-Za-z0-9]+/g)
  if (latin?.length) {
    return latin[0].slice(0, 2).toUpperCase()
  }
  return title.trim().slice(0, 1) || 'A'
}

export interface PriceBand {
  id: string
  label: string
  min: number
  max: number
}

export const PRICE_BANDS: PriceBand[] = [
  { id: 'entry', label: '随身与桌面 · ¥0–499', min: 0, max: 500 },
  { id: 'work', label: '工作与居家 · ¥500–1,999', min: 500, max: 2000 },
  { id: 'studio', label: '工作室与家 · ¥2,000 以上', min: 2000, max: Number.POSITIVE_INFINITY }
]

export function bandOf(price: number): PriceBand {
  return PRICE_BANDS.find((band) => price >= band.min && price < band.max) ?? PRICE_BANDS[0]
}

export function groupByBand<T extends { price: number }>(items: T[]): Array<{ band: PriceBand; items: T[] }> {
  return PRICE_BANDS.map((band) => ({
    band,
    items: items.filter((item) => item.price >= band.min && item.price < band.max)
  })).filter((group) => group.items.length > 0)
}
