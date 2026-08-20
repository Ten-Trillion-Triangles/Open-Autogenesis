import { chromium } from 'playwright'
import { setTimeout as sleep } from 'node:timers/promises'
import { mkdirSync, writeFileSync } from 'node:fs'

const ART = '/tmp/ag-softlock-probe'
mkdirSync(ART, { recursive: true })

const browser = await chromium.launchPersistentContext('/tmp/ag-profile-softlock3', {
    headless: true,
    viewport: { width: 1920, height: 1080 },
    args: ['--no-sandbox']
})
const page = await browser.newPage()

const consoleMsgs = []
const pageErrors = []
page.on('console', (m) => consoleMsgs.push({ t: Date.now(), type: m.type(), text: m.text() }))
page.on('pageerror', (e) => pageErrors.push({ t: Date.now(), msg: e.message, stack: e.stack }))

await page.goto('http://localhost:8080/?skipLogin=true', { waitUntil: 'load' })
await sleep(4000)

const cta = await page.getByTestId('loading-screen-cta').count()
if (cta > 0) {
    await page.getByTestId('loading-screen-cta').click()
    await sleep(2000)
}

await page.locator('[data-testid="main-menu"]').waitFor({ state: 'visible' })
console.log('MainMenu visible')

// Click PLAY
await page.evaluate(() => {
    for (const b of document.querySelectorAll('[data-testid="main-menu"] button')) {
        if (b.textContent.trim() === 'PLAY') { b.click(); return }
    }
})
await sleep(2000)

// Wait for CommanderSelectionDialog
await page.waitForSelector('.commander-selection-card', { timeout: 15000 })
console.log('Commander selection dialog visible')

// Click first commander card (Lord Maple Tree should be cached locally)
await page.locator('.commander-selection-card').first().click()
console.log('Clicked first commander card')

// Wait for game-type cards to appear or skip them (single player flow)
await sleep(2500)

// Click Single Player if asked
const singlePlayer = await page.evaluate(() => {
    for (const c of document.querySelectorAll('.commander-selection-card')) {
        const txt = c.textContent.toUpperCase()
        if (txt.includes('SINGLE PLAYER') || txt.includes('1 PLAYER')) {
            c.click(); return true
        }
    }
    return false
})
console.log(`Single player card clicked: ${singlePlayer}`)

await sleep(2500)

// Click Start Game or similar
const startGame = await page.evaluate(() => {
    for (const b of document.querySelectorAll('button')) {
        const txt = b.textContent.trim().toUpperCase()
        if (txt === 'START' || txt === 'START GAME' || txt === 'BEGIN' || txt === 'CONFIRM' || txt.includes('BEGIN')) {
            b.click(); return txt
        }
    }
    return null
})
console.log(`Start game clicked: ${startGame}`)

await sleep(5000)

// Wait for gameplay UI
try {
    await page.waitForSelector('.gameplay-ui', { timeout: 30000 })
    console.log('Gameplay UI mounted')
} catch (e) {
    console.log('No gameplay UI after 30s. Current state:')
    const state = await page.evaluate(() => ({
        windows: Array.from(document.querySelectorAll('[class*="dialog"], [class*="window"], [class*="overlay"]')).slice(0, 10).map(el => ({ cls: el.className.substring(0, 60), text: el.textContent.trim().substring(0, 60) })),
        gameplayUI: !!document.querySelector('.gameplay-ui'),
        territoryIcons: document.querySelectorAll('[data-testid="territory-icon"]').length
    }))
    console.log(JSON.stringify(state, null, 2))
    await page.screenshot({ path: `${ART}/no-gameplay.png`, fullPage: false })
    await browser.close()
    process.exit(1)
}

// Wait for the map to load — give it time to fetch + render
await sleep(8000)
await page.screenshot({ path: `${ART}/gameplay-loaded.png`, fullPage: false })

const tileCount = await page.locator('[data-testid="territory-icon"]').count()
console.log(`Territory icons rendered: ${tileCount}`)

if (tileCount === 0) {
    console.log('NO tiles — exiting')
    await browser.close()
    process.exit(1)
}

// Now — the critical test. Click a tile and measure what happens.
const beforeClick = consoleMsgs.length
const t0 = Date.now()
await page.locator('[data-testid="territory-icon"]').first().click({ force: true, timeout: 5000 }).catch((e) => console.log('First click error: ' + e.message))
const clickDuration = Date.now() - t0
console.log(`First tile click took: ${clickDuration}ms`)

await sleep(2000)

// Was the TerritoryDescriptionWindow opened?
const tdwInfo = await page.evaluate(() => {
    // Look for elements that match TerritoryDescriptionWindow — its class is "login-widget-window" plus the styling
    const all = Array.from(document.querySelectorAll('.login-widget-window'))
    return all.map(el => ({
        cls: el.className,
        visible: el.offsetParent !== null,
        display: getComputedStyle(el).display,
        visibility: getComputedStyle(el).visibility,
        opacity: getComputedStyle(el).opacity,
        position: getComputedStyle(el).position,
        zIndex: getComputedStyle(el).zIndex,
        rect: el.getBoundingClientRect()
    }))
})
console.log(`TDW info:`, JSON.stringify(tdwInfo, null, 2))

await page.screenshot({ path: `${ART}/after-tile-click-1.png`, fullPage: false })

// Try clicking elsewhere on the map to see if UI is responsive
const mapCenter = await page.evaluate(() => {
    const mv = document.querySelector('.map-viewer-container')
    if (!mv) return null
    const r = mv.getBoundingClientRect()
    return { x: r.left + r.width / 2, y: r.top + r.height / 2 }
})

if (mapCenter) {
    const t1 = Date.now()
    await page.mouse.move(mapCenter.x - 200, mapCenter.y - 100)
    const moveDuration = Date.now() - t1
    console.log(`Mouse move latency: ${moveDuration}ms`)

    // Try a second tile click — that's the real test for softlock
    await sleep(500)
    const t2 = Date.now()
    await page.locator('[data-testid="territory-icon"]').nth(1).click({ force: true, timeout: 5000 }).catch((e) => console.log('Second click error: ' + e.message))
    const secondClickDuration = Date.now() - t2
    console.log(`Second tile click took: ${secondClickDuration}ms (>3000 = softlock)`)

    await sleep(1000)
    await page.screenshot({ path: `${ART}/after-tile-click-2.png`, fullPage: false })

    // Try clicking 5 tiles in rapid succession
    for (let i = 0; i < 5; i++) {
        const idx = i % Math.min(tileCount, 5)
        const tx = Date.now()
        await page.locator('[data-testid="territory-icon"]').nth(idx).click({ force: true, timeout: 3000 }).catch((e) => console.log(`Click ${i} error: ${e.message}`))
        const dur = Date.now() - tx
        console.log(`  Click ${i}: ${dur}ms`)
        await sleep(300)
    }
}

// Save logs
writeFileSync(`${ART}/console.log`, consoleMsgs.map(m => `[${new Date(m.t).toISOString()}] [${m.type}] ${m.text}`).join('\n'))
writeFileSync(`${ART}/page-errors.log`, pageErrors.map(e => `[${new Date(e.t).toISOString()}] ${e.msg}\n${e.stack}`).join('\n\n'))

console.log(`\n=== Total console messages: ${consoleMsgs.length} ===`)
console.log(`=== Page errors: ${pageErrors.length} ===`)
console.log(`=== Console messages during tile clicks: ${consoleMsgs.length - beforeClick} ===`)

// Print any error/warn messages from the click sequence
const clickMsgs = consoleMsgs.slice(beforeClick)
for (const m of clickMsgs) {
    if (m.type === 'error' || m.type === 'warning' || m.text.includes('Territory') || m.text.includes('MapViewer')) {
        console.log(`  [${m.type}] ${m.text.substring(0, 250)}`)
    }
}

await browser.close()
