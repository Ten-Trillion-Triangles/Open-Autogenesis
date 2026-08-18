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

// Get the FULL class chain on the grid container + computed styles
const inspect = await page.evaluate(() => {
    const grid = document.querySelector('.collection-grid')
    if (!grid) return { error: 'no grid' }
    const card = grid.querySelector('.collection-card')
    const thumb = grid.querySelector('.co-thumb')
    const gridStyle = window.getComputedStyle(grid)
    const cardStyle = card ? window.getComputedStyle(card) : null
    const cardRect = card ? card.getBoundingClientRect() : null
    const thumbStyle = thumb ? window.getComputedStyle(thumb) : null
    const thumbRect = thumb ? thumb.getBoundingClientRect() : null
    return {
        grid: {
            tagName: grid.tagName,
            className: grid.className,
            outerHTML: grid.outerHTML.slice(0, 300),
            computedDisplay: gridStyle.display,
            computedGridTemplateColumns: gridStyle.gridTemplateColumns,
            dataAttrs: Array.from(grid.attributes).filter(a => a.name.startsWith('data-')).map(a => `${a.name}=${a.value}`)
        },
        card: {
            tagName: card?.tagName,
            className: card?.className,
            outerHTML: card?.outerHTML.slice(0, 400),
            computedWidth: cardStyle?.width,
            rectWidth: cardRect?.width
        },
        thumb: {
            tagName: thumb?.tagName,
            className: thumb?.className,
            outerHTML: thumb?.outerHTML.slice(0, 200),
            computedWidth: thumbStyle?.width,
            rectWidth: thumbRect?.width,
            rectHeight: thumbRect?.height
        }
    }
})
console.log('\n=== CLASS CHAIN INSPECT ===')
console.log(JSON.stringify(inspect, null, 2))
await browser.close()