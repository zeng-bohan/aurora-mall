import { chromium } from 'playwright'

const browser = await chromium.launch({ channel: 'chrome', headless: true })
const context = await browser.newContext({ viewport: { width: 1440, height: 900 } })
const page = await context.newPage()
const errors = []
page.on('console', (message) => {
  if (message.type() === 'error') errors.push(message.text())
})
page.on('pageerror', (error) => errors.push(error.message))

await page.goto('http://127.0.0.1:5173/login', { waitUntil: 'networkidle' })
await page.fill('input[autocomplete="username"]', 'admin')
await page.fill('input[autocomplete="current-password"]', 'Aurora123!')
await page.click('button[type="submit"]')
await page.waitForURL('http://127.0.0.1:5173/admin', { timeout: 15000 }).catch(async () => {
  await page.waitForTimeout(2000)
})

await page.goto('http://127.0.0.1:5173/admin/dashboard', { waitUntil: 'networkidle' })
await page.waitForTimeout(800)
console.log('stats:', await page.locator('.stat-card__value').allInnerTexts())
await page.screenshot({ path: '../docs/screenshots/dtc-admin-dashboard-1440.png', fullPage: true })

await page.goto('http://127.0.0.1:5173/admin/orders', { waitUntil: 'networkidle' })
await page.waitForTimeout(500)
await page.screenshot({ path: '../docs/screenshots/dtc-admin-orders-1440.png', fullPage: true })

await page.goto('http://127.0.0.1:5173/admin/refunds', { waitUntil: 'networkidle' })
await page.waitForTimeout(500)
const refundRow = page.locator('.data-table tbody tr', { hasText: '2001' }).first()
console.log('refund row:', (await refundRow.innerText()).replace(/\s+/g, ' '))
await page.screenshot({ path: '../docs/screenshots/dtc-admin-refunds-1440.png', fullPage: true })
await refundRow.getByRole('button', { name: '同意' }).click()
await page.locator('.el-message-box__btns button.el-button--primary').click()
await page.waitForTimeout(2500)
const refundText = (await page.locator('.data-table tbody tr', { hasText: '2001' }).first().innerText()).replace(/\s+/g, ' ')
console.log('after approve:', refundText)
await page.screenshot({ path: '../docs/screenshots/dtc-admin-refunds-approved-1440.png', fullPage: true })

await page.goto('http://127.0.0.1:5173/admin/orders?status=PAID', { waitUntil: 'networkidle' })
await page.waitForTimeout(500)
const orderRow = page.locator('.data-table tbody tr', { hasText: '2001' }).first()
await orderRow.getByRole('button', { name: '发货' }).click()
await page.waitForTimeout(500)
const dialog = page.locator('.el-dialog')
const inputs = dialog.locator('input')
await inputs.nth(0).fill('AURORA Express')
await inputs.nth(1).fill('AUR202610223001')
await dialog.getByRole('button', { name: '确认发货' }).click()
await page.waitForTimeout(1500)

for (const [path, name] of [
  ['/admin/products', 'products'],
  ['/admin/products/5', 'product-edit'],
  ['/admin/catalog', 'catalog'],
  ['/admin/inventory', 'inventory'],
  ['/admin/coupons', 'coupons'],
  ['/admin/seckill', 'seckill'],
  ['/admin/users', 'users'],
]) {
  await page.goto(`http://127.0.0.1:5173${path}`, { waitUntil: 'networkidle' })
  await page.waitForTimeout(600)
  await page.screenshot({ path: `../docs/screenshots/dtc-admin-${name}-1440.png`, fullPage: true })
}

console.log('errors:', errors)
await browser.close()
