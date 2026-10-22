import { chromium } from 'playwright'

const browser = await chromium.launch({ channel: 'chrome', headless: true })
const adminContext = await browser.newContext({ viewport: { width: 1440, height: 900 } })
const page = await adminContext.newPage()
const errors = []
page.on('console', (message) => {
  if (message.type() === 'error') errors.push(message.text())
})
page.on('pageerror', (error) => errors.push(error.message))

await page.goto('http://127.0.0.1:5173/login', { waitUntil: 'networkidle' })
await page.fill('input[autocomplete="username"]', 'admin')
await page.fill('input[autocomplete="current-password"]', 'Aurora123!')
await page.click('button[type="submit"]')
await page.waitForTimeout(2500)

await page.goto('http://127.0.0.1:5173/admin/dashboard', { waitUntil: 'networkidle' })
await page.waitForTimeout(600)
console.log('stats:', await page.locator('.stat-card__value').allInnerTexts())
await page.screenshot({ path: '../docs/screenshots/dtc-admin-dashboard-1440.png', fullPage: true })

await page.goto('http://127.0.0.1:5173/admin/refunds', { waitUntil: 'networkidle' })
await page.locator('.admin-toolbar select').first().selectOption('APPROVED')
await page.waitForTimeout(800)
console.log('approved refunds:', (await page.locator('.data-table tbody').innerText()).replace(/\s+/g, ' '))
await page.screenshot({ path: '../docs/screenshots/dtc-admin-refunds-1440.png', fullPage: true })

await page.goto('http://127.0.0.1:5173/admin/orders?status=PAID', { waitUntil: 'networkidle' })
await page.waitForTimeout(500)
const orderRow = page.locator('.data-table tbody tr', { hasText: '2001' }).first()
console.log('order row:', (await orderRow.innerText()).replace(/\s+/g, ' '))
await page.screenshot({ path: '../docs/screenshots/dtc-admin-orders-1440.png', fullPage: true })
await orderRow.getByRole('button', { name: '发货' }).click()
await page.waitForTimeout(400)
const dialog = page.locator('.el-dialog')
const inputs = dialog.locator('input')
await inputs.nth(0).fill('AURORA Express')
await inputs.nth(1).fill('AUR202610223001')
await dialog.getByRole('button', { name: '确认发货' }).click()
await page.waitForTimeout(1500)
console.log('ship dialog closed:', await dialog.isHidden())

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
console.log('admin errors:', errors)

const userContext = await browser.newContext({
  viewport: { width: 1440, height: 900 },
  storageState: '../.taste-skill/user-state.json',
})
const userPage = await userContext.newPage()
const userErrors = []
userPage.on('console', (message) => {
  if (message.type() === 'error') userErrors.push(message.text())
})
userPage.on('pageerror', (error) => userErrors.push(error.message))
await userPage.goto('http://127.0.0.1:5173/orders/2001', { waitUntil: 'networkidle' })
await userPage.waitForTimeout(600)
console.log('user sees:', await userPage.locator('.status-hero h1').innerText())
await userPage.screenshot({ path: '../docs/screenshots/dtc-order-shipped-1440.png', fullPage: true })
await userPage.getByRole('button', { name: '确认收货' }).click()
await userPage.locator('.el-message-box__btns button.el-button--primary').click()
await userPage.waitForTimeout(1200)
console.log('after confirm:', await userPage.locator('.status-hero h1').innerText())
await userPage.screenshot({ path: '../docs/screenshots/dtc-order-completed-1440.png', fullPage: true })
console.log('user errors:', userErrors)
await browser.close()
