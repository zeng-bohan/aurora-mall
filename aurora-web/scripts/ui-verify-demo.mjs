import { chromium } from 'playwright'

const browser = await chromium.launch({ channel: 'chrome', headless: true })
const context = await browser.newContext({ viewport: { width: 1440, height: 900 } })
const page = await context.newPage()
const errors = []
page.on('console', (message) => {
  if (message.type() === 'error') errors.push(message.text())
})
page.on('pageerror', (error) => errors.push(error.message))

async function login(user, password) {
  await page.goto('http://127.0.0.1:5173/login', { waitUntil: 'networkidle' })
  await page.fill('input[autocomplete="username"]', user)
  await page.fill('input[autocomplete="current-password"]', password)
  await page.click('button[type="submit"]')
  await page.waitForTimeout(1800)
}

await login('admin', 'Aurora123!')
await page.goto('http://127.0.0.1:5173/admin/dashboard', { waitUntil: 'networkidle' })
await page.waitForTimeout(900)
console.log('stats:', (await page.locator('.stat-card__value').allInnerTexts()).join(' | '))
await page.screenshot({ path: '../docs/screenshots/dtc-admin-dashboard-1440.png' })

await page.goto('http://127.0.0.1:5173/admin/orders', { waitUntil: 'networkidle' })
await page.waitForTimeout(700)
const orders = page.locator('.data-table tbody tr')
console.log('order rows:', await orders.count())
console.log('first order:', (await orders.first().innerText()).replace(/\s+/g, ' ').slice(0, 160))
console.log('pager pages:', await page.locator('.el-pager li').count())
await page.screenshot({ path: '../docs/screenshots/dtc-admin-orders-1440.png' })

await page.goto('http://127.0.0.1:5173/admin/orders?status=PAID', { waitUntil: 'networkidle' })
await page.waitForTimeout(700)
console.log('paid rows:', await page.locator('.data-table tbody tr').count())
console.log('paid pager pages:', await page.locator('.el-pager li').count())

await page.goto('http://127.0.0.1:5173/admin/refunds', { waitUntil: 'networkidle' })
await page.waitForTimeout(700)
const refunds = page.locator('.data-table tbody tr')
console.log('refund rows:', await refunds.count())
for (let i = 0; i < Math.min(5, await refunds.count()); i++) {
  console.log('refund', i, (await refunds.nth(i).innerText()).replace(/\s+/g, ' ').slice(0, 140))
}
await page.screenshot({ path: '../docs/screenshots/dtc-admin-refunds-1440.png' })

await login('linyi', 'Aurora123!')
await page.goto('http://127.0.0.1:5173/orders', { waitUntil: 'networkidle' })
await page.waitForTimeout(800)
const userOrders = page.locator('.order-card')
console.log('user order cards:', await userOrders.count())
console.log('user first:', (await userOrders.first().innerText()).replace(/\s+/g, ' ').slice(0, 140))
await page.screenshot({ path: '../docs/screenshots/dtc-orders-1440.png' })

console.log('errors:', errors)
await browser.close()
