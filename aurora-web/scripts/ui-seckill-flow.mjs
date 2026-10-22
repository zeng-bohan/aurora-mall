import { chromium } from 'playwright'

const browser = await chromium.launch({ channel: 'chrome', headless: true })
const adminContext = await browser.newContext({ viewport: { width: 1440, height: 900 } })
const admin = await adminContext.newPage()
const errors = []
for (const page of [admin]) {
  page.on('console', (message) => {
    if (message.type() === 'error') errors.push(message.text())
  })
  page.on('pageerror', (error) => errors.push(error.message))
}

async function login(page, user, password) {
  await page.goto('http://127.0.0.1:5173/login', { waitUntil: 'networkidle' })
  await page.fill('input[autocomplete="username"]', user)
  await page.fill('input[autocomplete="current-password"]', password)
  await page.click('button[type="submit"]')
  await page.waitForTimeout(1600)
}

await login(admin, 'admin', 'Aurora123!')
await admin.goto('http://127.0.0.1:5173/admin/seckill', { waitUntil: 'networkidle' })
await admin.waitForTimeout(800)
await admin.screenshot({ path: '../docs/screenshots/dtc-admin-seckill-before-1440.png' })

await admin.getByRole('button', { name: '创建活动' }).first().click()
const dialog = admin.locator('.el-dialog')
await dialog.waitFor({ state: 'visible' })
await dialog.locator('input').first().fill('Quiet 降噪耳机 · 秋日限定')
await dialog.locator('select').selectOption('28')
await dialog.locator('input[type="number"]').nth(0).fill('699')
await dialog.locator('input[type="number"]').nth(1).fill('5')
await dialog.getByRole('button', { name: '创建活动' }).click()
await admin.waitForTimeout(1500)

const activityRow = admin.locator('.data-table tbody tr', { hasText: 'Quiet 降噪耳机 · 秋日限定' }).first()
console.log('activity row:', (await activityRow.innerText()).replace(/\s+/g, ' ').slice(0, 160))
await activityRow.getByRole('button', { name: '预热' }).click()
await admin.waitForTimeout(1500)
console.log('after preheat:', (await activityRow.innerText()).replace(/\s+/g, ' ').slice(0, 160))
await admin.screenshot({ path: '../docs/screenshots/dtc-admin-seckill-1440.png' })

const userContext = await browser.newContext({ viewport: { width: 1440, height: 1000 } })
const user = await userContext.newPage()
user.on('console', (message) => {
  if (message.type() === 'error') errors.push(message.text())
})
user.on('pageerror', (error) => errors.push(error.message))

await login(user, 'linyi', 'Aurora123!')
await user.goto('http://127.0.0.1:5173/seckill', { waitUntil: 'networkidle' })
await user.waitForTimeout(700)
console.log('seckill cards:', await user.locator('.seckill-card').count())
await user.screenshot({ path: '../docs/screenshots/dtc-seckill-1440.png' })

await user.locator('.seckill-card').first().click()
await user.waitForTimeout(800)
await user.screenshot({ path: '../docs/screenshots/dtc-seckill-detail-1440.png' })
await user.getByRole('button', { name: '立即抢购' }).click()
await user.waitForTimeout(1500)
await user.locator('.link-arrow').waitFor({ timeout: 30000 })
console.log('buy result:', (await user.locator('.seckill-result').innerText()).replace(/\s+/g, ' '))
await user.screenshot({ path: '../docs/screenshots/dtc-seckill-result-1440.png' })

await user.locator('.link-arrow').click()
await user.waitForTimeout(1000)
await user.screenshot({ path: '../docs/screenshots/dtc-seckill-order-1440.png' })
await user.getByRole('button', { name: '模拟支付' }).click()
await user.waitForTimeout(1800)
console.log('after pay:', (await user.locator('.status-hero h1').innerText()).replace(/\s+/g, ' '))
await user.screenshot({ path: '../docs/screenshots/dtc-seckill-order-paid-1440.png' })

await user.getByRole('button', { name: '申请退款' }).waitFor({ timeout: 15000 })
await user.getByRole('button', { name: '申请退款' }).click()
const refundDialog = user.locator('.el-dialog')
await refundDialog.waitFor({ state: 'visible' })
await refundDialog.locator('textarea').fill('秒杀订单整单退款，测试库存归还')
await user.waitForTimeout(600)
await refundDialog.getByRole('button', { name: '提交申请' }).click()
await user.waitForTimeout(1600)
console.log('refund applied:', (await user.locator('body').innerText()).includes('退款申请已提交'))
await user.screenshot({ path: '../docs/screenshots/dtc-seckill-refund-1440.png' })

await admin.goto('http://127.0.0.1:5173/admin/refunds', { waitUntil: 'networkidle' })
await admin.waitForTimeout(800)
const row = admin.locator('.data-table tbody tr', { hasText: '待审批' }).first()
console.log('pending refund row:', (await row.innerText()).replace(/\s+/g, ' ').slice(0, 160))
await row.getByRole('button', { name: '同意' }).click()
await admin.locator('.el-message-box__btns button.el-button--primary').click()
await admin.waitForTimeout(2500)
await admin.screenshot({ path: '../docs/screenshots/dtc-admin-refund-approved-1440.png' })

console.log('errors:', errors)
await browser.close()
