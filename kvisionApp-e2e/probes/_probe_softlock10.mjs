import { chromium } from 'playwright'
import { setTimeout as sleep } from 'node:timers/promises'
import { mkdirSync, writeFileSync } from 'node:fs'

const ART = '/tmp/ag-softlock-probe'
mkdirSync(ART, { recursive: true })

const browser = await chromium.launchPersistentContext('/tmp/ag-profile-softlock10', {
    headless: true,
    viewport: { width: 1920, height: 1080 },
    args: ['--no-sandbox']
})
const page = await browser.newPage()

const consoleMsgs = []
const pageErrors = []
page.on('console', (m) => consoleMsgs.push({ t: Date.now(), type: m.type(), text: m.text() }))
page.on('pageerror', (e) => pageErrors.push({ t: Date.now(), msg: e.message }))

await page.goto('http://localhost:8080/', { waitUntil: 'load' })
await sleep(4000)

const cta = await page.getByTestId('loading-screen-cta').count()
if (cta > 0) {
    await page.getByTestId('loading-screen-cta').click()
    await sleep(2000)
}

await page.waitForSelector('[data-testid="login-as-guest"]', { timeout: 15000 })
console.log('Login page visible')

// Snapshot state BEFORE clicking
const beforeClick = await page.evaluate(() => ({
    visibleWindows: Array.from(document.querySelectorAll('[class*="dialog"], [class*="window"], [class*="overlay"]')).slice(0, 10).map(el => ({ cls: el.className.substring(0, 60), text: el.textContent.trim().substring(0, 60) })),
    messageBoxes: document.querySelectorAll('.message-box').length,
    buttons: Array.from(document.querySelectorAll('button')).filter(b => b.offsetParent !== null).map(b => b.textContent.trim().substring(0, 30)).filter(t => t.length > 0).slice(0, 20)
}))
console.log('Before click:', JSON.stringify(beforeClick, null, 2))

await page.locator('[data-testid="login-as-guest"]').click()
console.log('Clicked Login As Guest')

// Watch state every 2s for 20s
for (let i = 0; i < 10; i++) {
    await sleep(2000)
    const state = await page.evaluate(() => ({
        mainMenu: !!document.querySelector('[data-testid="main-menu"]'),
        messageBoxText: Array.from(document.querySelectorAll('.message-box, [class*="MessageBox"]')).map(m => m.textContent.trim().substring(0, 100)),
        visibleWindows: Array.from(document.querySelectorAll('[class*="dialog"], [class*="window"], [class*="overlay"]')).filter(e => e.offsetParent !== null).slice(0, 5).map(el => ({ cls: el.className.substring(0, 60), text: el.textContent.trim().substring(0, 80) })),
        buttons: Array.from(document.querySelectorAll('button')).filter(b => b.offsetParent !== null).map(b => b.textContent.trim().substring(0, 30)).filter(t => t.length > 0).slice(0, 10)
    }))
    console.log(`t=${(i+1)*2}s:`, JSON.stringify(state, null, 2))
    if (state.mainMenu) {
        console.log('MainMenu appeared!')
        break
    }
}

await page.screenshot({ path: `${ART}/after-guest-click.png`, fullPage: false })

writeFileSync(`${ART}/probe10-console.log`, consoleMsgs.map(m => `[${new Date(m.t).toISOString()}] [${m.type}] ${m.text}`).join('\n'))
writeFileSync(`${ART}/probe10-page-errors.log`, pageErrors.map(e => `[${new Date(e.t).toISOString()}] ${e.msg}`).join('\n'))

console.log(`\n=== Console: ${consoleMsgs.length}, errors: ${pageErrors.length} ===`)

await browser.close()
