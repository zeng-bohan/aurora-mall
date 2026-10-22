import { chromium } from 'playwright'

const browser = await chromium.launch({ channel: 'chrome', headless: true })
const context = await browser.newContext({ viewport: { width: 1440, height: 900 } })
const page = await context.newPage()
const errors = []
const failed = []
page.on('console', (message) => {
  if (message.type() === 'error') {
    errors.push(message.text())
  }
})
page.on('pageerror', (error) => errors.push(error.message))
page.on('response', (response) => {
  if (response.status() >= 400) {
    failed.push(`${response.status()} ${response.url()}`)
  }
})
await page.goto('http://127.0.0.1:5173/', { waitUntil: 'networkidle' })
await page.waitForTimeout(1500)
await page.screenshot({ path: '../docs/screenshots/dtc-home-1440.png', fullPage: true })
console.log('h1:', await page.locator('h1').first().innerText())
console.log('cards:', await page.locator('.product-card').count())
console.log('errors:', errors)
console.log('failed:', failed)
await browser.close()
