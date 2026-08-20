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
await page.waitForTimeout(3000)

const inspect = await page.evaluate(() => {
    const grid = document.querySelector('.collection-grid')
    const gridStyle = grid ? getComputedStyle(grid) : null
    const card = document.querySelector('.collection-card')
    const cardStyle = card ? getComputedStyle(card) : null
    const cardRect = card ? card.getBoundingClientRect() : null
    const thumb = document.querySelector('.co-thumb')
    const thumbStyle = thumb ? getComputedStyle(thumb) : null
    const thumbRect = thumb ? thumb.getBoundingClientRect() : null
    const thumbIcon = thumb?.querySelector('i')
    return {
        grid: { exists: !!grid, display: gridStyle?.display, gridTemplateColumns: gridStyle?.gridTemplateColumns, width: gridStyle?.width, height: gridStyle?.height, children: grid?.children?.length },
        card: { exists: !!card, width: cardStyle?.width, height: cardStyle?.height, display: cardStyle?.display, flexDirection: cardStyle?.flexDirection, rect: cardRect ? { w: cardRect.width, h: cardRect.height, x: cardRect.x, y: cardRect.y } : null },
        thumb: { exists: !!thumb, width: thumbStyle?.width, height: thumbStyle?.height, display: thumbStyle?.display, backgroundImage: thumbStyle?.backgroundImage?.slice(0, 80), rect: thumbRect ? { w: thumbRect.width, h: thumbRect.height } : null, hasIcon: !!thumbIcon, iconClass: thumbIcon?.className }
    }
})
console.log('\n=== DOM INSPECT ===')
console.log(JSON.stringify(inspect, null, 2))
await browser.close()
