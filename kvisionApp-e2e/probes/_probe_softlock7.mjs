import { chromium } from 'playwright'
import { setTimeout as sleep } from 'node:timers/promises'
import { mkdirSync, writeFileSync } from 'node:fs'

const ART = '/tmp/ag-softlock-probe'
mkdirSync(ART, { recursive: true })

const browser = await chromium.launchPersistentContext('/tmp/ag-profile-softlock7', {
    headless: true,
    viewport: { width: 1920, height: 1080 },
    args: ['--no-sandbox']
})
const page = await browser.newPage()

const consoleMsgs = []
const pageErrors = []
page.on('console', (m) => consoleMsgs.push({ t: Date.now(), type: m.type(), text: m.text() }))
page.on('pageerror', (e) => pageErrors.push({ t: Date.now(), msg: e.message }))

// demoMode=full: bridges skipped, DemoFixtures used
await page.goto('http://localhost:8080/?demoMode=full', { waitUntil: 'load' })
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
await sleep(2500)

const afterPlay = await page.evaluate(() => ({
    cards: document.querySelectorAll('.commander-selection-card').length,
    modal: Array.from(document.querySelectorAll('[class*="dialog"], [class*="Dialog"], [class*="window"]')).slice(0, 5).map(e => ({ cls: e.className.substring(0, 80), text: e.textContent.trim().substring(0, 80) })),
    buttons: Array.from(document.querySelectorAll('button')).filter(b => b.offsetParent !== null).map(b => b.textContent.trim().substring(0, 30)).filter(t => t.length > 0).slice(0, 20)
}))
console.log('After PLAY:', JSON.stringify(afterPlay, null, 2))

await page.screenshot({ path: `${ART}/demo-full-play.png`, fullPage: false })

await browser.close()
process.exit(0)