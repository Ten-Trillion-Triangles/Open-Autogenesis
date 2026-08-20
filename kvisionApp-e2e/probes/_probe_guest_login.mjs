import { chromium } from 'playwright'
import { setTimeout as sleep } from 'node:timers/promises'
import { mkdirSync, writeFileSync } from 'node:fs'

const ART = '/tmp/ag-softlock-probe'
mkdirSync(ART, { recursive: true })

const browser = await chromium.launchPersistentContext('/tmp/ag-profile-guest', {
    headless: true,
    viewport: { width: 1920, height: 1080 },
    args: ['--no-sandbox']
})
const page = await browser.newPage()

const consoleMsgs = []
const pageErrors = []
page.on('console', (m) => consoleMsgs.push({ t: Date.now(), type: m.type(), text: m.text() }))
page.on('pageerror', (e) => pageErrors.push({ t: Date.now(), msg: e.message }))

console.log('[t=0] Navigate to localhost:8080')
await page.goto('http://localhost:8080/', { waitUntil: 'load' })

// LoadingScreen
console.log('[t=0] Wait for loading screen CTA')
await page.waitForSelector('[data-testid="loading-screen-cta"]', { timeout: 30000 })
await page.locator('[data-testid="loading-screen-cta"]').click()
console.log('[t=0] LoadingScreen clicked')

// Login page
console.log('[t=0] Wait for Login As Guest button')
await page.waitForSelector('[data-testid="login-as-guest"]', { timeout: 30000 })
console.log('[t=0] Login page visible')
await page.locator('[data-testid="login-as-guest"]').click()
console.log('[t=0] Login As Guest clicked')

// Patiently wait for MainMenu
console.log('[t=0] Wait for MainMenu (could take 30-60s due to OAuth)')
try {
    await page.waitForSelector('[data-testid="main-menu"]', { timeout: 90000 })
    console.log('[t=0] MainMenu visible')
} catch (e) {
    console.log('[t=0] FAILED to reach MainMenu')
    const state = await page.evaluate(() => ({
        visibleWindows: Array.from(document.querySelectorAll('[class*="dialog"], [class*="window"], [class*="overlay"]')).filter(e => e.offsetParent !== null).slice(0, 5).map(el => ({ cls: el.className.substring(0, 60), text: el.textContent.trim().substring(0, 80) })),
        buttons: Array.from(document.querySelectorAll('button')).filter(b => b.offsetParent !== null).map(b => b.textContent.trim().substring(0, 30)).filter(t => t.length > 0).slice(0, 10)
    }))
    console.log('State:', JSON.stringify(state, null, 2))
    await page.screenshot({ path: `${ART}/no-mainmenu.png`, fullPage: false })
    await browser.close()
    process.exit(1)
}

await sleep(2000)
await page.screenshot({ path: `${ART}/mainmenu.png`, fullPage: false })

// PLAY
console.log('[t=0] Click PLAY')
await page.evaluate(() => {
    for (const b of document.querySelectorAll('[data-testid="main-menu"] button')) {
        if (b.textContent.trim() === 'PLAY') { b.click(); return }
    }
})

// Commander selection
console.log('[t=0] Wait for commander selection dialog')
try {
    await page.waitForSelector('.commander-selection-card', { timeout: 15000 })
    console.log('[t=0] Commander dialog visible')
} catch (e) {
    console.log('[t=0] FAILED to reach commander selection')
    const state = await page.evaluate(() => ({
        windows: Array.from(document.querySelectorAll('[class*="dialog"], [class*="window"], [class*="overlay"]')).filter(e => e.offsetParent !== null).slice(0, 5).map(el => ({ cls: el.className.substring(0, 60), text: el.textContent.trim().substring(0, 80) })),
        messageBoxes: Array.from(document.querySelectorAll('.message-box, [class*="MessageBox"]')).map(m => m.textContent.trim().substring(0, 100))
    }))
    console.log('State:', JSON.stringify(state, null, 2))
    await page.screenshot({ path: `${ART}/no-commander.png`, fullPage: false })
    await browser.close()
    process.exit(1)
}

// Click first commander card
await page.locator('.commander-selection-card').first().click()
console.log('[t=0] Commander clicked')
await sleep(2500)

// Single player
await page.evaluate(() => {
    for (const c of document.querySelectorAll('.commander-selection-card')) {
        const txt = c.textContent.toUpperCase()
        if (txt.includes('SINGLE PLAYER') || txt.includes('1 PLAYER')) { c.click(); return }
    }
})
console.log('[t=0] Single player clicked')
await sleep(2500)

// Start
await page.evaluate(() => {
    for (const b of document.querySelectorAll('button')) {
        const txt = b.textContent.trim().toUpperCase()
        if (txt === 'START' || txt === 'START GAME' || txt === 'CONFIRM' || txt === 'BEGIN') { b.click(); return }
    }
})
console.log('[t=0] Start clicked')

// Wait for gameplay
try {
    await page.waitForSelector('.gameplay-ui', { timeout: 60000 })
    console.log('[t=0] Gameplay UI mounted')
} catch (e) {
    console.log('[t=0] FAILED to reach gameplay')
    await page.screenshot({ path: `${ART}/no-gameplay.png`, fullPage: false })
    await browser.close()
    process.exit(1)
}

await sleep(8000) // Let map load
await page.screenshot({ path: `${ART}/gameplay.png`, fullPage: false })

const tileCount = await page.locator('[data-testid="territory-icon"]').count()
console.log(`[t=0] Territory icons: ${tileCount}`)

if (tileCount === 0) {
    console.log('No tiles found')
    await browser.close()
    process.exit(1)
}

// Now THE TEST
const beforeClicks = consoleMsgs.length
console.log('[t=0] === Click test: first tile ===')
const t0 = Date.now()
await page.locator('[data-testid="territory-icon"]').first().click({ force: true, timeout: 5000 }).catch((e) => console.log('Click 1 error: ' + e.message))
const click1Dur = Date.now() - t0
console.log(`[t=${click1Dur}ms] Click 1 took: ${click1Dur}ms`)
await sleep(1500)

const tdwInfo = await page.evaluate(() => {
    const els = Array.from(document.querySelectorAll('.login-widget-window'))
    return els.filter(el => {
        const r = el.getBoundingClientRect()
        return r.width > 100 && r.height > 100
    }).map(el => {
        const r = el.getBoundingClientRect()
        return { text: el.textContent.trim().substring(0, 200), display: getComputedStyle(el).display, w: r.width, h: r.height }
    })
})
console.log('TDW after click 1:', JSON.stringify(tdwInfo, null, 2))

// Click a second tile — that's the real test
console.log('[t=0] === Click test: second tile ===')
const t1 = Date.now()
await page.locator('[data-testid="territory-icon"]').nth(1).click({ force: true, timeout: 5000 }).catch((e) => console.log('Click 2 error: ' + e.message))
const click2Dur = Date.now() - t1
console.log(`[t=${click2Dur}ms] Click 2 took: ${click2Dur}ms (>3000 = softlock)`)

await sleep(1500)
await page.screenshot({ path: `${ART}/after-clicks.png`, fullPage: false })

// Click a third tile
console.log('[t=0] === Click test: third tile ===')
const t2 = Date.now()
await page.locator('[data-testid="territory-icon"]').nth(2).click({ force: true, timeout: 5000 }).catch((e) => console.log('Click 3 error: ' + e.message))
const click3Dur = Date.now() - t2
console.log(`[t=${click3Dur}ms] Click 3 took: ${click3Dur}ms`)

await sleep(1500)

// Test mouse responsiveness
const mapCenter = await page.evaluate(() => {
    const mv = document.querySelector('.map-viewer-container')
    if (!mv) return null
    const r = mv.getBoundingClientRect()
    return { x: r.left + r.width / 2, y: r.top + r.height / 2 }
})
if (mapCenter) {
    const t3 = Date.now()
    await page.mouse.move(mapCenter.x - 200, mapCenter.y - 100)
    const mouseDur = Date.now() - t3
    console.log(`[t=${mouseDur}ms] Mouse move latency: ${mouseDur}ms`)
}

// Print relevant console messages from clicks
const clickMsgs = consoleMsgs.slice(beforeClicks)
console.log(`\n=== Console messages during clicks: ${clickMsgs.length} ===`)
for (const m of clickMsgs.filter(m => m.type === 'error' || m.text.includes('Territory') || m.text.includes('MapViewer') || m.text.includes('softlock') || m.text.includes('ERROR'))) {
    console.log(`  [${m.type}] ${m.text.substring(0, 250)}`)
}

writeFileSync(`${ART}/guest-console.log`, consoleMsgs.map(m => `[${new Date(m.t).toISOString()}] [${m.type}] ${m.text}`).join('\n'))
writeFileSync(`${ART}/guest-page-errors.log`, pageErrors.map(e => `[${new Date(e.t).toISOString()}] ${e.msg}`).join('\n'))

console.log(`\n=== Final: ${consoleMsgs.length} console msgs, ${pageErrors.length} page errors ===`)

await browser.close()
