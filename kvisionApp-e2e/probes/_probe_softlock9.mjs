import { chromium } from 'playwright'
import { setTimeout as sleep } from 'node:timers/promises'
import { mkdirSync, writeFileSync } from 'node:fs'

const ART = '/tmp/ag-softlock-probe'
mkdirSync(ART, { recursive: true })

const browser = await chromium.launchPersistentContext('/tmp/ag-profile-softlock9', {
    headless: true,
    viewport: { width: 1920, height: 1080 },
    args: ['--no-sandbox']
})
const page = await browser.newPage()

const consoleMsgs = []
const pageErrors = []
page.on('console', (m) => consoleMsgs.push({ t: Date.now(), type: m.type(), text: m.text() }))
page.on('pageerror', (e) => pageErrors.push({ t: Date.now(), msg: e.message }))

// NO skipLogin — let the LoginPage show, click "Login As Guest" button
await page.goto('http://localhost:8080/', { waitUntil: 'load' })
await sleep(4000)

const cta = await page.getByTestId('loading-screen-cta').count()
if (cta > 0) {
    await page.getByTestId('loading-screen-cta').click()
    await sleep(2000)
}

// Wait for login page
await page.waitForSelector('[data-testid="login-as-guest"]', { timeout: 15000 })
console.log('Login page visible, clicking Login As Guest')
await page.locator('[data-testid="login-as-guest"]').click()

// Wait for real OAuth flow + MainMenu
await page.waitForSelector('[data-testid="main-menu"]', { timeout: 30000 })
console.log('MainMenu visible after guest login')

await sleep(2000)
await page.screenshot({ path: `${ART}/after-guest-login.png`, fullPage: false })

// Click PLAY
await page.evaluate(() => {
    for (const b of document.querySelectorAll('[data-testid="main-menu"] button')) {
        if (b.textContent.trim() === 'PLAY') { b.click(); return }
    }
})
await sleep(2500)

// Commander selection dialog should appear
try {
    await page.waitForSelector('.commander-selection-card', { timeout: 10000 })
    console.log('Commander selection dialog visible')
} catch (e) {
    console.log('No commander selection dialog, current state:')
    const state = await page.evaluate(() => ({
        cards: document.querySelectorAll('.commander-selection-card').length,
        visibleTexts: Array.from(document.querySelectorAll('button')).filter(b => b.offsetParent !== null).map(b => b.textContent.trim().substring(0, 30)).filter(t => t.length > 0).slice(0, 20)
    }))
    console.log(JSON.stringify(state, null, 2))
    await page.screenshot({ path: `${ART}/no-commander-dialog.png`, fullPage: false })
    await browser.close()
    process.exit(1)
}

await page.locator('.commander-selection-card').first().click()
await sleep(2500)

// Click single player card if present
await page.evaluate(() => {
    for (const c of document.querySelectorAll('.commander-selection-card')) {
        const txt = c.textContent.toUpperCase()
        if (txt.includes('SINGLE PLAYER') || txt.includes('1 PLAYER')) { c.click(); return }
    }
})
await sleep(2500)

// Click Start/Confirm button
await page.evaluate(() => {
    for (const b of document.querySelectorAll('button')) {
        const txt = b.textContent.trim().toUpperCase()
        if (txt === 'START' || txt === 'START GAME' || txt === 'CONFIRM' || txt === 'BEGIN') { b.click(); return }
    }
})
await sleep(8000)

// Wait for gameplay
try {
    await page.waitForSelector('.gameplay-ui', { timeout: 30000 })
    console.log('Gameplay UI mounted')
} catch (e) {
    console.log('No gameplay UI after 30s')
    const state = await page.evaluate(() => ({
        gameplayUI: !!document.querySelector('.gameplay-ui'),
        mapViewer: !!document.querySelector('.map-viewer-container'),
        territoryIcons: document.querySelectorAll('[data-testid="territory-icon"]').length,
        visibleWindows: Array.from(document.querySelectorAll('[class*="dialog"], [class*="window"], [class*="overlay"]')).slice(0, 10).map(el => ({ cls: el.className.substring(0, 60), text: el.textContent.trim().substring(0, 60) }))
    }))
    console.log(JSON.stringify(state, null, 2))
    await page.screenshot({ path: `${ART}/no-gameplay.png`, fullPage: false })
    await browser.close()
    process.exit(1)
}

await sleep(8000) // let map load
await page.screenshot({ path: `${ART}/gameplay-loaded.png`, fullPage: false })

const tileCount = await page.locator('[data-testid="territory-icon"]').count()
console.log(`Territory icons rendered: ${tileCount}`)

if (tileCount === 0) {
    console.log('NO tiles found')
    await browser.close()
    process.exit(1)
}

// THE CRITICAL TEST — click tiles and measure response
const beforeClicks = consoleMsgs.length

console.log('=== Click test 1: first tile ===')
const t0 = Date.now()
await page.locator('[data-testid="territory-icon"]').first().click({ force: true, timeout: 5000 }).catch((e) => console.log('Click 1 error: ' + e.message))
const click1Dur = Date.now() - t0
console.log(`Click 1 took: ${click1Dur}ms`)
await sleep(1500)

const tdwOpen = await page.evaluate(() => {
    const all = Array.from(document.querySelectorAll('.login-widget-window'))
    return all.filter(el => {
        const r = el.getBoundingClientRect()
        return r.width > 100 && r.height > 100
    }).map(el => ({
        text: el.textContent.trim().substring(0, 100),
        display: getComputedStyle(el).display,
        visibility: getComputedStyle(el).visibility,
        rect: el.getBoundingClientRect()
    }))
})
console.log('TerritoryDescriptionWindow after click 1:', JSON.stringify(tdwOpen, null, 2))

// Try clicking another tile to test if the UI is still responsive
console.log('\n=== Click test 2: second tile ===')
const t1 = Date.now()
await page.locator('[data-testid="territory-icon"]').nth(1).click({ force: true, timeout: 5000 }).catch((e) => console.log('Click 2 error: ' + e.message))
const click2Dur = Date.now() - t1
console.log(`Click 2 took: ${click2Dur}ms (>3000 = softlock confirmed)`)

await sleep(1500)
await page.screenshot({ path: `${ART}/after-clicks.png`, fullPage: false })

// Look at map canvas transform
const transform = await page.evaluate(() => {
    const mc = document.querySelector('.map-canvas')
    return mc ? mc.style.transform : null
})
console.log(`Map canvas transform: ${transform}`)

// Check if UI is responsive by trying a third click
console.log('\n=== Click test 3: third tile ===')
const t2 = Date.now()
await page.locator('[data-testid="territory-icon"]').nth(2).click({ force: true, timeout: 5000 }).catch((e) => console.log('Click 3 error: ' + e.message))
const click3Dur = Date.now() - t2
console.log(`Click 3 took: ${click3Dur}ms`)

await sleep(1500)

// Check if we can scroll/hover
const mapCenter = await page.evaluate(() => {
    const mv = document.querySelector('.map-viewer-container')
    if (!mv) return null
    const r = mv.getBoundingClientRect()
    return { x: r.left + r.width / 2, y: r.top + r.height / 2 }
})
if (mapCenter) {
    const t3 = Date.now()
    await page.mouse.move(mapCenter.x - 200, mapCenter.y - 100)
    await page.mouse.move(mapCenter.x - 100, mapCenter.y - 50)
    const mouseDur = Date.now() - t3
    console.log(`Mouse move latency: ${mouseDur}ms`)
}

// Print relevant console messages
const clickMsgs = consoleMsgs.slice(beforeClicks)
console.log(`\n=== Console messages during clicks: ${clickMsgs.length} ===`)
for (const m of clickMsgs.filter(m => m.type === 'error' || m.text.includes('Territory') || m.text.includes('MapViewer') || m.text.includes('softlock') || m.text.includes('ERROR'))) {
    console.log(`  [${m.type}] ${m.text.substring(0, 250)}`)
}

writeFileSync(`${ART}/probe9-console.log`, consoleMsgs.map(m => `[${new Date(m.t).toISOString()}] [${m.type}] ${m.text}`).join('\n'))
writeFileSync(`${ART}/probe9-page-errors.log`, pageErrors.map(e => `[${new Date(e.t).toISOString()}] ${e.msg}\n${e.stack || ''}`).join('\n\n'))

console.log(`\n=== Total: ${consoleMsgs.length} console msgs, ${pageErrors.length} page errors ===`)

await browser.close()
