import { chromium } from 'playwright'
const browser = await chromium.launch({ headless: true })
const ctx = await browser.newContext({ viewport: { width: 1400, height: 900 } })
const page = await ctx.newPage()
await page.goto('http://localhost:8080/?skipLogin=true', { waitUntil: 'load' })
await page.waitForTimeout(4000)
const cta = await page.getByTestId('loading-screen-cta').count()
if (cta > 0) { await page.getByTestId('loading-screen-cta').click(); await page.waitForTimeout(2000) }
await page.locator('[data-testid="main-menu"]').waitFor({ state: 'visible' })
await page.evaluate(() => {
    for (const b of document.querySelectorAll('[data-testid="main-menu"] button')) {
        if (b.textContent.trim() === 'Collection') { b.click(); return }
    }
})
await page.waitForTimeout(2500)
await page.evaluate(() => {
    for (const t of document.querySelectorAll('.collection-tab-button')) {
        if (t.title === 'Maps') { t.click(); return }
    }
})
await page.waitForTimeout(5000)
await page.waitForTimeout(5000)

// Extract the data URL and save it
const dataUrl = await page.evaluate(() => {
    const cs = window.getComputedStyle(document.querySelector('.co-thumb'))
    return cs.backgroundImage
})
console.log('Data URL length:', dataUrl.length)
console.log('Data URL prefix:', dataUrl.slice(0, 150))
console.log('Data URL suffix:', dataUrl.slice(-150))

// Save the data URL as a PNG file
import { writeFileSync } from 'node:fs'
const base64Data = dataUrl.replace(/^url\("data:image\/png;base64,/, '').replace(/"$/, '').replace(/%3B/g, ';')
const buffer = Buffer.from(base64Data, 'base64')
writeFileSync('/tmp/ag-fixes-2026-08-14/_decoded.png', buffer)
console.log('Decoded PNG saved, size:', buffer.length, 'bytes')

// Now check if the data URL string is what we EXPECT
// Pull the actual stored image from MapCardThumbnailRenderer.lastDataUrlForTest
const stored = await page.evaluate(() => {
    return { renderersExist: typeof window.MapCardThumbnailRenderer !== 'undefined' }
})
console.log('Stored:', JSON.stringify(stored))

await browser.close()