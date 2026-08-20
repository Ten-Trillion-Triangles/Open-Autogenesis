import { chromium } from 'playwright'
import { setTimeout as sleep } from 'node:timers/promises'
import { mkdirSync, writeFileSync } from 'node:fs'

const ART = '/tmp/ag-softlock-probe'
mkdirSync(ART, { recursive: true })

const browser = await chromium.launchPersistentContext('/tmp/ag-profile-softlock8', {
    headless: true,
    viewport: { width: 1920, height: 1080 },
    args: ['--no-sandbox']
})
const page = await browser.newPage()

const consoleMsgs = []
const pageErrors = []
page.on('console', (m) => consoleMsgs.push({ t: Date.now(), type: m.type(), text: m.text() }))
page.on('pageerror', (e) => pageErrors.push({ t: Date.now(), msg: e.message }))

// Full demoMode should seed world AND skip bridges
await page.goto('http://localhost:8080/?demoMode=full&skipLogin=true', { waitUntil: 'load' })
await sleep(5000)

const cta = await page.getByTestId('loading-screen-cta').count()
if (cta > 0) {
    await page.getByTestId('loading-screen-cta').click()
    await sleep(2000)
}

try {
    await page.locator('[data-testid="main-menu"]').waitFor({ state: 'visible', timeout: 5000 })
} catch {
    // Already in gameplay?
    const state = await page.evaluate(() => ({
        gameplayUI: !!document.querySelector('.gameplay-ui'),
        mapViewer: !!document.querySelector('.map-viewer-container'),
        mainMenu: !!document.querySelector('[data-testid="main-menu"]')
    }))
    console.log('No main menu, current state:', JSON.stringify(state, null, 2))
}

await page.screenshot({ path: `${ART}/demo-full.png`, fullPage: false })

const state = await page.evaluate(() => ({
    gameplayUI: !!document.querySelector('.gameplay-ui'),
    mapViewer: !!document.querySelector('.map-viewer-container'),
    mainMenu: !!document.querySelector('[data-testid="main-menu"]'),
    territoryIcons: document.querySelectorAll('[data-testid="territory-icon"]').length
}))
console.log('Final state:', JSON.stringify(state, null, 2))

await browser.close()
