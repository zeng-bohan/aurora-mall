import { chromium } from 'playwright'

const base = process.env.BASE_URL ?? 'http://127.0.0.1:5173'
const browser = await chromium.launch({ channel: process.env.CHANNEL ?? 'chrome', headless: true })
const failures = []
const checked = []

async function login(page, username, password) {
  await page.goto(base + '/login', { waitUntil: 'networkidle' })
  await page.waitForSelector('input[autocomplete="username"]', { timeout: 15000 })
  await page.fill('input[autocomplete="username"]', username)
  await page.fill('input[autocomplete="current-password"]', password)
  await page.click('button[type="submit"]')
  await page.waitForTimeout(1200)
}

async function newPage(viewport, who) {
  const context = await browser.newContext({ viewport })
  const page = await context.newPage()
  page.on('console', (message) => {
    if (message.type() === 'error') failures.push(`${who} console: ${message.text()}`)
  })
  page.on('pageerror', (error) => failures.push(`${who} pageerror: ${error.message}`))
  page.on('requestfailed', (request) => {
    failures.push(`${who} requestfailed: ${request.url()} ${request.failure()?.errorText ?? ''}`)
  })
  page.on('response', (response) => {
    if (response.status() >= 500) failures.push(`${who} http ${response.status()}: ${response.url()}`)
  })
  return { context, page }
}

async function checkPage(page, path, viewport) {
  await page.setViewportSize(viewport)
  await page.goto(base + path, { waitUntil: 'networkidle' })
  await page.waitForTimeout(350)
  const size = await page.evaluate(() => ({
    scroll: document.documentElement.scrollWidth,
    client: document.documentElement.clientWidth,
  }))
  if (size.scroll > size.client + 1) {
    failures.push(`overflow ${viewport.width}px ${path}: ${size.scroll} > ${size.client}`)
  }
  const brokenImages = await page.locator('img').evaluateAll((images) =>
    images.filter((image) => image.complete && image.naturalWidth === 0).map((image) => image.src),
  )
  for (const image of brokenImages) {
    failures.push(`broken image ${viewport.width}px ${path}: ${image}`)
  }
  checked.push(`${viewport.width}px ${path}`)
}

const viewports = [
  { width: 1440, height: 900 },
  { width: 1280, height: 800 },
  { width: 390, height: 844 },
  { width: 320, height: 700 },
]
const storePages = [
  '/', '/products', '/products/1', '/cart', '/orders', '/orders/3019',
  '/orders/3037', '/coupons', '/addresses', '/profile', '/seckill', '/seckill/9930002',
]
const adminPages = [
  '/admin/dashboard', '/admin/orders', '/admin/orders/3037', '/admin/refunds',
  '/admin/products', '/admin/products/1', '/admin/catalog', '/admin/inventory',
  '/admin/coupons', '/admin/seckill', '/admin/users',
]

const store = await newPage(viewports[0], 'store')
await login(store.page, 'linyi', 'Aurora123!')
for (const viewport of viewports) {
  for (const path of storePages) {
    await checkPage(store.page, path, viewport)
  }
}

const admin = await newPage(viewports[0], 'admin')
await login(admin.page, 'admin', 'Aurora123!')
for (const viewport of viewports) {
  for (const path of adminPages) {
    await checkPage(admin.page, path, viewport)
  }
}

const pending = await newPage(viewports[0], 'pending-refund')
await login(pending.page, 'wutong', 'Aurora123!')
await checkPage(pending.page, '/orders/3010', viewports[0])

// Interactions that do not write business data.
await store.page.setViewportSize(viewports[0])
await store.page.goto(base + '/products', { waitUntil: 'networkidle' })
const search = store.page.getByPlaceholder('搜索名称或描述')
await search.fill('键盘')
await search.press('Enter')
await store.page.waitForTimeout(600)
const keyboardCards = await store.page.locator('.product-card').count()
if (keyboardCards < 1) failures.push('search 键盘 returned no product')

await store.page.goto(base + '/products/5', { waitUntil: 'networkidle' })
await store.page.locator('.option').first().click()
if ((await store.page.locator('.option.is-selected').count()) < 1) {
  failures.push('SKU option did not become active')
}

await store.page.goto(base + '/orders/3037', { waitUntil: 'networkidle' })
await store.page.getByRole('button', { name: '申请退款' }).click()
const refundDialog = store.page.locator('.el-dialog')
await refundDialog.waitFor({ state: 'visible' })
if (!(await refundDialog.getByRole('button', { name: '提交申请' }).isVisible())) {
  failures.push('refund dialog did not render submit button')
}
await refundDialog.getByRole('button', { name: '取消' }).click()

await admin.page.setViewportSize(viewports[0])
await admin.page.goto(base + '/admin/refunds?status=APPROVED', { waitUntil: 'networkidle' })
await admin.page.waitForTimeout(600)
await admin.page.locator('.data-table tbody tr').first().getByRole('button', { name: '详情' }).click()
try {
  await admin.page.locator('.el-dialog', { hasText: '退款详情' }).waitFor({ state: 'visible', timeout: 5000 })
} catch {
  failures.push('admin refund drawer did not open')
}

const guest = await newPage(viewports[2], 'guest')
await guest.page.goto(base + '/admin/dashboard', { waitUntil: 'networkidle' })
await guest.page.waitForTimeout(500)
if (!guest.page.url().includes('/login')) failures.push('guest was not redirected to login for /admin')
await guest.page.goto(base + '/definitely-not-a-page', { waitUntil: 'networkidle' })
if (!(await guest.page.getByText('页面不存在').count())) failures.push('404 page not rendered')

await browser.close()
if (failures.length) {
  console.error(`UI smoke failed (${failures.length}):`)
  for (const failure of failures) console.error('  - ' + failure)
  process.exit(1)
}
console.log(`UI smoke passed: ${checked.length} page/viewport combinations, no console errors, failed requests, overflow or broken images.`)
