import { chromium } from 'playwright'

const browser = await chromium.launch({ channel: 'chrome', headless: true })
const context = await browser.newContext({
  viewport: { width: 390, height: 844 },
  deviceScaleFactor: 2,
  storageState: '../.taste-skill/user-state.json',
})
const page = await context.newPage()
const errors = []
page.on('console', (message) => {
  if (message.type() === 'error') errors.push(message.text())
})
page.on('pageerror', (error) => errors.push(error.message))

async function overflow(label) {
  const value = await page.evaluate(() => ({
    scroll: document.documentElement.scrollWidth,
    inner: window.innerWidth,
  }))
  console.log(label, value, value.scroll <= value.inner ? 'ok' : 'OVERFLOW')
}

await page.goto('http://127.0.0.1:5173/', { waitUntil: 'networkidle' })
await page.waitForTimeout(800)
await overflow('home')
await page.screenshot({ path: '../docs/screenshots/dtc-home-390.png', fullPage: true })

await page.goto('http://127.0.0.1:5173/products', { waitUntil: 'networkidle' })
await page.waitForTimeout(600)
await overflow('products')
await page.screenshot({ path: '../docs/screenshots/dtc-products-390.png', fullPage: true })
await page.getByRole('button', { name: '筛选' }).click()
await page.waitForTimeout(400)
await page.screenshot({ path: '../docs/screenshots/dtc-products-filter-390.png' })

await page.goto('http://127.0.0.1:5173/products/5', { waitUntil: 'networkidle' })
await page.waitForTimeout(600)
await overflow('detail')
await page.screenshot({ path: '../docs/screenshots/dtc-product-detail-390.png', fullPage: true })

await page.goto('http://127.0.0.1:5173/cart', { waitUntil: 'networkidle' })
await page.waitForTimeout(500)
await overflow('cart')
await page.screenshot({ path: '../docs/screenshots/dtc-cart-390.png', fullPage: true })

await page.goto('http://127.0.0.1:5173/orders/2001', { waitUntil: 'networkidle' })
await page.waitForTimeout(500)
await overflow('order-detail')
await page.screenshot({ path: '../docs/screenshots/dtc-order-detail-390.png', fullPage: true })

await page.goto('http://127.0.0.1:5173/orders', { waitUntil: 'networkidle' })
await page.waitForTimeout(600)
await overflow('orders')
await page.screenshot({ path: '../docs/screenshots/dtc-orders-390.png', fullPage: true })

await page.goto('http://127.0.0.1:5173/seckill', { waitUntil: 'networkidle' })
await page.waitForTimeout(700)
await overflow('seckill')
await page.screenshot({ path: '../docs/screenshots/dtc-seckill-390.png', fullPage: true })

await page.goto('http://127.0.0.1:5173/seckill/9930002', { waitUntil: 'networkidle' })
await page.waitForTimeout(700)
await overflow('seckill-detail')
await page.screenshot({ path: '../docs/screenshots/dtc-seckill-detail-390.png', fullPage: true })

console.log('errors:', errors)
await browser.close()
