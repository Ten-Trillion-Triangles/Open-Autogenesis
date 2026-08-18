import { chromium } from 'playwright'
const browser = await chromium.launch({ headless: true })
const ctx = await browser.newContext({ viewport: { width: 1400, height: 900 } })
const page = await ctx.newPage()
page.on('console', m => console.log(`[${m.type()}] ${m.text()}`))
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

// Wait for thumbnail render
await page.waitForTimeout(3000)

const inspect = await page.evaluate(() => {
    const grid = document.querySelector('.collection-grid')
    if (!grid) return { error: 'no grid' }
    const card = grid.querySelector('.collection-card')
    const thumb = grid.querySelector('.co-thumb')
    const thumbStyle = window.getComputedStyle(thumb)
    const thumbRect = thumb.getBoundingClientRect()
    return {
        thumb: {
            outerHTML: thumb.outerHTML.slice(0, 500),
            hasIcon: !!thumb.querySelector('i'),
            iconClass: thumb.querySelector('i')?.className,
            dataUrlPresent: thumbStyle.backgroundImage.includes('data:image/png'),
            dataUrlLength: thumbStyle.backgroundImage.length,
            backgroundColor: thumbStyle.backgroundColor,
            rectWidth: thumbRect.width,
            rectHeight: thumbRect.height
        }
    }
})
console.log('\n=== THUMBNAIL DETAIL ===')
console.log(JSON.stringify(inspect, null, 2))
await page.screenshot({ path: '/tmp/ag-fixes-2026-08-14/_thumb-detail.png', fullPage: false, clip: { x: 100, y: 250, width: 250, height: 250 } })
await browser.close()