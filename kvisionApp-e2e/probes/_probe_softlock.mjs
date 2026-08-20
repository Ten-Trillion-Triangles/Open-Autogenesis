import { chromium } from 'playwright'
import { setTimeout as sleep } from 'node:timers/promises'

const ART = '/tmp/ag-softlock-probe'
import { mkdirSync } from 'node:fs'
mkdirSync(ART, { recursive: true })

const browser = await chromium.launch({ headless: true })
const ctx = await browser.newContext({ viewport: { width: 1920, height: 1080 } })
const page = await ctx.newPage()

const consoleMsgs = []
const pageErrors = []
page.on('console', (m) => consoleMsgs.push({ t: Date.now(), type: m.type(), text: m.text() }))
page.on('pageerror', (e) => pageErrors.push({ t: Date.now(), msg: e.message, stack: e.stack }))

await page.goto('http://localhost:8080/?skipLogin=true', { waitUntil: 'load' })
await sleep(4000)

// Skip loading screen
const cta = await page.getByTestId('loading-screen-cta').count()
if (cta > 0) {
    await page.getByTestId('loading-screen-cta').click()
    await sleep(2000)
}

await page.locator('[data-testid="main-menu"]').waitFor({ state: 'visible' })
console.log('MainMenu visible')

// Open Collection -> Maps tab to find an existing map card
await page.evaluate(() => {
    for (const b of document.querySelectorAll('[data-testid="main-menu"] button')) {
        if (b.textContent.trim() === 'Collection') { b.click(); return }
    }
})
await sleep(2500)

await page.evaluate(() => {
    for (const t of document.querySelectorAll('.collection-tab-button')) {
        if (t.title === 'Maps') { t.click(); return }
    }
})
await sleep(3500)

// Find any map card and click PLAY on it
const mapCount = await page.locator('.co-thumb, [data-testid="map-card"]').count()
console.log(`Map cards visible: ${mapCount}`)

let playClicked = false
if (mapCount > 0) {
    // Click the first card to open detail
    await page.locator('.co-thumb, [data-testid="map-card"]').first().click()
    await sleep(3000)
    // Look for PLAY button
    playClicked = await page.evaluate(() => {
        for (const b of document.querySelectorAll('button')) {
            const txt = b.textContent.trim().toUpperCase()
            if (txt === 'PLAY' || txt === 'LOAD' || txt.includes('PLAY MAP')) {
                b.click(); return true
            }
        }
        return false
    })
    console.log(`PLAY clicked: ${playClicked}`)
}

await sleep(5000)

// Now we should be on the gameplay screen with the map. Wait for territory icons.
try {
    await page.waitForSelector('[data-testid="territory-icon"]', { timeout: 15000 })
    console.log('Territory icons visible')
} catch (e) {
    console.log('NO territory icons found after 15s')
    await page.screenshot({ path: `${ART}/no-icons.png`, fullPage: true })
}

const tileCount = await page.locator('[data-testid="territory-icon"]').count()
console.log(`Territory icons rendered: ${tileCount}`)

// Is the UI responsive? Check by looking at the page responsiveness signal.
// We will measure: try to scroll/hover, then check if any UI event handler fires.
await page.screenshot({ path: `${ART}/before-tile-click.png`, fullPage: false })

// Pick the first tile and click it
if (tileCount > 0) {
    const t0 = Date.now()
    const tile = page.locator('[data-testid="territory-icon"]').first()

    // Hover the tile (should trigger hover handler)
    await tile.hover({ force: true }).catch((e) => console.log('Hover error: ' + e.message))
    await sleep(500)

    // Now click the tile (should open TerritoryDescriptionWindow)
    const clickStart = Date.now()
    await tile.click({ force: true, timeout: 5000 }).catch((e) => console.log('Click error: ' + e.message))
    const clickDuration = Date.now() - clickStart
    console.log(`Click took ${clickDuration}ms`)

    await sleep(2000)
    await page.screenshot({ path: `${ART}/after-tile-click.png`, fullPage: false })

    // Did the territory description window show?
    const tdwVisible = await page.evaluate(() => {
        const all = document.querySelectorAll('.login-widget-window, [class*="territory-desc"]')
        for (const el of all) {
            const r = el.getBoundingClientRect()
            if (r.width > 100 && r.height > 100) return { w: r.width, h: r.height, display: getComputedStyle(el).display, vis: getComputedStyle(el).visibility }
        }
        return null
    })
    console.log(`TerritoryDescriptionWindow visible: ${JSON.stringify(tdwVisible)}`)

    // Test mouse responsiveness: try to click somewhere else (center of map)
    const respStart = Date.now()
    const mapCenter = await page.evaluate(() => {
        const mv = document.querySelector('.map-viewer-container')
        if (!mv) return null
        const r = mv.getBoundingClientRect()
        return { x: r.left + r.width / 2, y: r.top + r.height / 2 }
    })

    if (mapCenter) {
        // Try to do a small mouse move and check if the page is responsive
        await page.mouse.move(mapCenter.x, mapCenter.y)
        await page.mouse.move(mapCenter.x + 5, mapCenter.y + 5)
        const respDuration = Date.now() - respStart
        console.log(`Mouse move latency: ${respDuration}ms`)

        // Try clicking the map (should be covered by TDW)
        const clickAfterResp = Date.now()
        await page.mouse.click(mapCenter.x, mapCenter.y).catch((e) => console.log('Map click error: ' + e.message))
        const clickAfterDur = Date.now() - clickAfterResp
        console.log(`Subsequent click took: ${clickAfterDur}ms`)
    }

    // Check if centerOnTerritory was called - look for an animated pan
    const panState = await page.evaluate(() => {
        const mc = document.querySelector('.map-canvas')
        if (!mc) return null
        const t = mc.style.transform
        return t
    })
    console.log(`Map canvas transform after click: ${panState}`)

    // Wait 3s and see if UI is responsive (any new console events)
    const beforeCount = consoleMsgs.length
    await sleep(3000)
    const afterCount = consoleMsgs.length
    console.log(`Console messages before/after 3s wait: ${beforeCount} -> ${afterCount}`)

    await page.screenshot({ path: `${ART}/after-3s.png`, fullPage: false })

    // Try clicking again and see if it works
    if (tileCount > 1) {
        const t1 = Date.now()
        await page.locator('[data-testid="territory-icon"]').nth(1).click({ force: true, timeout: 3000 }).catch((e) => console.log('Second click error: ' + e.message))
        const t2 = Date.now() - t1
        console.log(`Second tile click took: ${t2}ms (>3000 = softlocked)`)
        await page.screenshot({ path: `${ART}/after-second-click.png`, fullPage: false })
    }
}

// Dump console + page errors
const consoleSummary = consoleMsgs.map(m => `[${new Date(m.t).toISOString()}] [${m.type}] ${m.text}`).join('\n')
import { writeFileSync } from 'node:fs'
writeFileSync(`${ART}/console.log`, consoleSummary)
writeFileSync(`${ART}/page-errors.log`, pageErrors.map(e => `[${new Date(e.t).toISOString()}] ${e.msg}\n${e.stack}`).join('\n\n'))

console.log(`\n=== Console messages: ${consoleMsgs.length} ===`)
console.log(`=== Page errors: ${pageErrors.length} ===`)
// Print last 30 console messages for context
for (const m of consoleMsgs.slice(-30)) {
    console.log(`  [${m.type}] ${m.text.substring(0, 200)}`)
}
if (pageErrors.length > 0) {
    console.log('\n=== PAGE ERRORS ===')
    for (const e of pageErrors) {
        console.log(`  ${e.msg}`)
    }
}

await browser.close()
