import { chromium } from 'playwright'
import { setTimeout as sleep } from 'node:timers/promises'
import { mkdirSync, writeFileSync } from 'node:fs'

const ART = '/tmp/ag-softlock-probe'
mkdirSync(ART, { recursive: true })

const browser = await chromium.launchPersistentContext('/tmp/ag-profile-softlock5', {
    headless: true,
    viewport: { width: 1920, height: 1080 },
    args: ['--no-sandbox']
})
const page = await browser.newPage()

const consoleMsgs = []
const pageErrors = []
page.on('console', (m) => consoleMsgs.push({ t: Date.now(), type: m.type(), text: m.text() }))
page.on('pageerror', (e) => pageErrors.push({ t: Date.now(), msg: e.message }))

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
await sleep(3000)

const stateAfterPlay = await page.evaluate(() => {
    return {
        cards: document.querySelectorAll('.commander-selection-card').length,
        dialogs: Array.from(document.querySelectorAll('[class*="dialog"], [class*="Dialog"]')).map(el => ({ cls: el.className.substring(0, 80), text: el.textContent.trim().substring(0, 100) })),
        buttons: Array.from(document.querySelectorAll('button')).filter(b => b.offsetParent !== null).map(b => b.textContent.trim().substring(0, 40)).filter(t => t.length > 0).slice(0, 30),
        // Look for any visible modal or popup
        visibleModal: !!document.querySelector('[class*="modal"], [class*="Modal"]'),
        // Find elements that look like the OK button or messagebox
        messageBoxes: document.querySelectorAll('.message-box, [class*="MessageBox"]').length
    }
})
console.log('After PLAY:', JSON.stringify(stateAfterPlay, null, 2))

// Check if the messageBox is shown — the existing browser log showed an OK button appearing.
const okVisible = await page.evaluate(() => {
    const btns = Array.from(document.querySelectorAll('button'))
    const ok = btns.find(b => b.textContent.trim() === 'OK')
    if (ok) {
        return { found: true, visible: ok.offsetParent !== null }
    }
    return { found: false }
})
console.log('OK button:', JSON.stringify(okVisible))

// Click OK if there is one
if (okVisible.found && okVisible.visible) {
    await page.evaluate(() => {
        const btns = Array.from(document.querySelectorAll('button'))
        const ok = btns.find(b => b.textContent.trim() === 'OK' && b.offsetParent !== null)
        if (ok) ok.click()
    })
    await sleep(3000)

    const afterOK = await page.evaluate(() => ({
        cards: document.querySelectorAll('.commander-selection-card').length,
        gameplayUI: !!document.querySelector('.gameplay-ui'),
        visibleTexts: Array.from(document.querySelectorAll('button')).filter(b => b.offsetParent !== null).map(b => b.textContent.trim().substring(0, 30)).filter(t => t.length > 0).slice(0, 20)
    }))
    console.log('After OK:', JSON.stringify(afterOK, null, 2))

    // Maybe another OK?
    const ok2 = await page.evaluate(() => {
        const btns = Array.from(document.querySelectorAll('button'))
        const ok = btns.find(b => b.textContent.trim() === 'OK' && b.offsetParent !== null)
        if (ok) ok.click()
        return !!ok
    })
    await sleep(2000)
    if (ok2) console.log('Clicked second OK')

    const afterOK2 = await page.evaluate(() => ({
        cards: document.querySelectorAll('.commander-selection-card').length,
        gameplayUI: !!document.querySelector('.gameplay-ui'),
        visibleTexts: Array.from(document.querySelectorAll('button')).filter(b => b.offsetParent !== null).map(b => b.textContent.trim().substring(0, 30)).filter(t => t.length > 0).slice(0, 20)
    }))
    console.log('After OK2:', JSON.stringify(afterOK2, null, 2))
}

// Save console for debugging
writeFileSync(`${ART}/probe5-console.log`, consoleMsgs.map(m => `[${new Date(m.t).toISOString()}] [${m.type}] ${m.text}`).join('\n'))

await page.screenshot({ path: `${ART}/state-after-play.png`, fullPage: false })
await browser.close()