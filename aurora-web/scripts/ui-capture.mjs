import { chromium } from 'playwright'

const browser = await chromium.launch({ channel: 'chrome', headless: true })
const context = await browser.newContext({ viewport: { width: 1440, height: 900 } })
const page = await context.newPage()
const errors = []
page.on('console', (message) => {
  if (message.type() === 'error') errors.push(message.text())
})
page.on('pageerror', (error) => errors.push(error.message))

await page.setViewportSize({ width: 1440, height: 4600 })
await page.goto('http://127.0.0.1:5173/', { waitUntil: 'networkidle' })
await page.evaluate(async () => {
  await Promise.all([...document.images].map((image) => image.decode().catch(() => undefined)))
})
await page.waitForTimeout(500)
await page.screenshot({ path: '../docs/screenshots/dtc-home-1440.png', fullPage: true })

await page.setViewportSize({ width: 1440, height: 1800 })
await page.goto('http://127.0.0.1:5173/products', { waitUntil: 'networkidle' })
await page.locator('.filter-option', { hasText: '桌面输入' }).click()
await page.waitForTimeout(600)
console.log('category filter cards:', await page.locator('.product-card').count())
await page.locator('.filter-option', { hasText: '暖白' }).click()
await page.waitForTimeout(600)
console.log('color filter cards:', await page.locator('.product-card').count())
await page.locator('.catalog-toolbar__sort').selectOption('price_asc')
await page.waitForTimeout(600)
const prices = await page.locator('.product-card__price').allInnerTexts()
console.log('prices:', prices)
await page.evaluate(async () => {
  await Promise.all([...document.images].map((image) => image.decode().catch(() => undefined)))
})
await page.screenshot({ path: '../docs/screenshots/dtc-products-1440.png', fullPage: true })

await page.setViewportSize({ width: 1440, height: 900 })
await page.goto('http://127.0.0.1:5173/login', { waitUntil: 'networkidle' })
await page.fill('input[autocomplete="username"]', 'admin')
await page.fill('input[autocomplete="current-password"]', 'Aurora123!')
await page.click('button[type="submit"]')
await page.waitForTimeout(2500)
for (const [path, name] of [
  ['/admin/dashboard', 'dashboard'],
  ['/admin/products', 'products'],
  ['/admin/products/5', 'product-edit'],
  ['/admin/catalog', 'catalog'],
  ['/admin/inventory', 'inventory'],
  ['/admin/coupons', 'coupons'],
  ['/admin/seckill', 'seckill'],
  ['/admin/users', 'users'],
  ['/admin/orders', 'orders'],
  ['/admin/refunds', 'refunds'],
]) {
  await page.goto(`http://127.0.0.1:5173${path}`, { waitUntil: 'networkidle' })
  await page.waitForTimeout(500)
  await page.screenshot({ path: `../docs/screenshots/dtc-admin-${name}-1440.png` })
}
console.log('errors:', errors)
await browser.close()
