import { chromium } from 'playwright'
import { setTimeout as sleep } from 'node:timers/promises'
import { mkdirSync, writeFileSync } from 'node:fs'

const ART = '/tmp/ag-softlock-probe'
mkdirSync(ART, { recursive: true })

const browser = await chromium.launchPersistentContext('/tmp/ag-profile-softlock6', {
    headless: true,
    viewport: { width: 1920, height: 1080 },
    args: ['--no-sandbox']
})
const page = await browser.newPage()

const consoleMsgs = []
const pageErrors = []
page.on('console', (m) => consoleMsgs.push({ t: Date.now(), type: m.type(), text: m.text() }))
page.on('pageerror', (e) => pageErrors.push({ t: Date.now(), msg: e.message }))

// Use demoMode=widgets which keeps bridges live
await page.goto('http://localhost:8080/?demoMode=widgets', { waitUntil: 'load' })
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
await sleep(3000)

// Check for CommanderSelectionDialog
const afterPlay = await page.evaluate(() => ({
    cards: document.querySelectorAll('.commander-selection-card').length,
    modalText: Array.from(document.querySelectorAll('[class*="messagebox"], .message-box, [class*="MessageBox"]')).map(m => m.textContent.trim().substring(0, 100)),
    buttons: Array.from(document.querySelectorAll('button')).filter(b => b.offsetParent !== null).map(b => b.textContent.trim().substring(0, 40)).filter(t => t.length > 0)
}))
console.log('After PLAY:', JSON.stringify(afterPlay, null, 2))

await page.screenshot({ path: `${ART}/demo-widgets-after-play.png`, fullPage: false })

// If there is a commander selection dialog
const cardCount = afterPlay.cards
if (cardCount > 0) {
    await page.locator('.commander-selection-card').first().click()
    await sleep(2500)

    const afterCmdSelect = await page.evaluate(() => ({
        cards: document.querySelectorAll('.commander-selection-card').length,
        buttons: Array.from(document.querySelectorAll('button')).filter(b => b.offsetParent !== null).map(b => b.textContent.trim().substring(0, 30)).filter(t => t.length > 0).slice(0, 20)
    }))
    console.log('After commander select:', JSON.stringify(afterCmdSelect, null, 2))

    // Click single player if asked
    await page.evaluate(() => {
        for (const c of document.querySelectorAll('.commander-selection-card')) {
            const txt = c.textContent.toUpperCase()
            if (txt.includes('SINGLE PLAYER') || txt.includes('1 PLAYER')) { c.click(); return }
        }
    })
    await sleep(2500)

    // Click Start/Confirm
    await page.evaluate(() => {
        for (const b of document.querySelectorAll('button')) {
            const txt = b.textContent.trim().toUpperCase()
            if (txt === 'START' || txt === 'START GAME' || txt === 'CONFIRM' || txt.includes('BEGIN')) { b.click(); return }
        }
    })
    await sleep(5000)

    const afterStart = await page.evaluate(() => ({
        gameplayUI: !!document.querySelector('.gameplay-ui'),
        tileCount: document.querySelectorAll('[data-testid="territory-icon"]').length
    }))
    console.log('After start:', JSON.stringify(afterStart, null, 2))

    await page.screenshot({ path: `${ART}/demo-gameplay.png`, fullPage: false })

    if (afterStart.tileCount > 0) {
        const beforeClicks = consoleMsgs.length

        // The critical test
        const t0 = Date.now()
        await page.locator('[data-testid="territory-icon"]').first().click({ force: true, timeout: 5000 }).catch((e) => console.log('Click 1 error: ' + e.message))
        const click1Dur = Date.now() - t0
        console.log(`Click 1: ${click1Dur}ms`)
        await sleep(1500)

        const t1 = Date.now()
        await page.locator('[data-testid="territory-icon"]').nth(1).click({ force: true, timeout: 5000 }).catch((e) => console.log('Click 2 error: ' + e.message))
        const click2Dur = Date.now() - t1
        console.log(`Click 2: ${click2Dur}ms (>3000 = softlock)`)

        await sleep(1500)
        await page.screenshot({ path: `${ART}/after-clicks.png`, fullPage: false })

        const clickMsgs = consoleMsgs.slice(beforeClicks)
        console.log(`Console during clicks: ${clickMsgs.length}`)
        for (const m of clickMsgs.filter(m => m.type === 'error' || m.text.includes('softlock') || m.text.includes('Map') || m.text.includes('Territory'))) {
            console.log(`  [${m.type}] ${m.text.substring(0, 200)}`)
        }
    }
}

writeFileSync(`${ART}/probe6-console.log`, consoleMsgs.map(m => `[${new Date(m.t).toISOString()}] [${m.type}] ${m.text}`).join('\n'))
writeFileSync(`${ART}/probe6-page-errors.log`, pageErrors.map(e => `[${new Date(e.t).toISOString()}] ${e.msg}`).join('\n'))

console.log(`\n=== Total console: ${consoleMsgs.length}, errors: ${pageErrors.length} ===`)

await browser.close()