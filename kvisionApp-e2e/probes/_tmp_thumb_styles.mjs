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
await page.waitForTimeout(3000)

const inspect = await page.evaluate(() => {
    const thumb = document.querySelector('.co-thumb')
    if (!thumb) return { error: 'no thumb' }
    const cs = window.getComputedStyle(thumb)
    return {
        inlineStyle: thumb.getAttribute('style'),
        computed: {
            backgroundImage: cs.backgroundImage.slice(0, 100),
            backgroundColor: cs.backgroundColor,
            backgroundSize: cs.backgroundSize,
            backgroundRepeat: cs.backgroundRepeat,
            backgroundPosition: cs.backgroundPosition,
            display: cs.display,
            alignItems: cs.alignItems,
            justifyContent: cs.justifyContent,
            color: cs.color,
            fontSize: cs.fontSize
        }
    }
})
console.log('\n=== INSPECT ===')
console.log(JSON.stringify(inspect, null, 2))
await browser.close()
