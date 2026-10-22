import { chromium } from 'playwright'

const browser = await chromium.launch({ channel: 'chrome', headless: true })
const adminContext = await browser.newContext({ viewport: { width: 1440, height: 900 } })
const admin = await adminContext.newPage()
const errors = []
admin.on('console', (message) => {
  if (message.type() === 'error') errors.push(message.text())
})
admin.on('pageerror', (error) => errors.push(error.message))

await admin.goto('http://127.0.0.1:5173/login', { waitUntil: 'networkidle' })
await admin.fill('input[autocomplete="username"]', 'admin')
await admin.fill('input[autocomplete="current-password"]', 'Aurora123!')
await admin.click('button[type="submit"]')
await admin.waitForTimeout(1600)
await admin.goto('http://127.0.0.1:5173/admin/seckill', { waitUntil: 'networkidle' })
await admin.waitForTimeout(800)

for (const title of ['Type 75 键盘 · 周末闪购', 'Arc 65W 闪充套装 · 明日开抢']) {
  const row = admin.locator('.data-table tbody tr', { hasText: title }).first()
  await row.getByRole('button', { name: '预热' }).click()
  await admin.waitForTimeout(1200)
  console.log('preheated:', title)
}
await admin.screenshot({ path: '../docs/screenshots/dtc-admin-seckill-1440.png' })

const userContext = await browser.newContext({ viewport: { width: 1440, height: 1100 } })
const user = await userContext.newPage()
user.on('console', (message) => {
  if (message.type() === 'error') errors.push(message.text())
})
user.on('pageerror', (error) => errors.push(error.message))
await user.goto('http://127.0.0.1:5173/login', { waitUntil: 'networkidle' })
await user.fill('input[autocomplete="username"]', 'linyi')
await user.fill('input[autocomplete="current-password"]', 'Aurora123!')
await user.click('button[type="submit"]')
await user.waitForTimeout(1600)
await user.goto('http://127.0.0.1:5173/seckill', { waitUntil: 'networkidle' })
await user.waitForTimeout(900)
console.log('cards:', await user.locator('.seckill-card').count())
for (const card of await user.locator('.seckill-card').all()) {
  console.log((await card.innerText()).replace(/\s+/g, ' ').slice(0, 120))
}
await user.screenshot({ path: '../docs/screenshots/dtc-seckill-1440.png' })
console.log('errors:', errors)
await browser.close()
