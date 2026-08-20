import { chromium } from 'playwright'
import { setTimeout as sleep } from 'node:timers/promises'
import { mkdirSync, writeFileSync, readFileSync } from 'node:fs'
import { spawnSync } from 'node:child_process'

const ART = '/tmp/ag-softlock-probe'
mkdirSync(ART, { recursive: true })

const browser = await chromium.launchPersistentContext('/tmp/ag-profile-softlock2', {
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

// Click PLAY button
await page.evaluate(() => {
    for (const b of document.querySelectorAll('[data-testid="main-menu"] button')) {
        if (b.textContent.trim() === 'PLAY') { b.click(); return }
    }
})
await sleep(3000)

// What happened? Look for Commander selection dialog or similar
const afterPlay = await page.evaluate(() => {
    return {
        visibleWindows: Array.from(document.querySelectorAll('[class*="dialog"], [class*="window"], [class*="overlay"], [class*="modal"]')).slice(0, 10).map(el => ({
            cls: el.className.substring(0, 100),
            visible: el.offsetParent !== null,
            text: el.textContent.trim().substring(0, 80)
        })),
        buttons: Array.from(document.querySelectorAll('button')).slice(0, 20).map(b => ({
            txt: b.textContent.trim().substring(0, 40),
            visible: b.offsetParent !== null
        })).filter(b => b.visible && b.txt.length > 0)
    }
})
console.log('After PLAY:', JSON.stringify(afterPlay, null, 2))

await page.screenshot({ path: `${ART}/after-play.png`, fullPage: false })

// Try to find the path to gameplay. Maybe there's a Commander selection dialog.
const commanderSelected = await page.evaluate(() => {
    // Click on the first commander card if visible
    const cards = document.querySelectorAll('[data-testid*="commander"], .commander-card')
    for (const c of cards) {
        c.click()
        return { found: true, count: cards.length }
    }
    // Try clicking something that looks like "Lord Maple Tree"
    for (const el of document.querySelectorAll('*')) {
        if (el.textContent.trim() === 'Lord Maple Tree' && el.children.length < 3) {
            el.click()
            return { found: true, via: 'text', parent: el.parentElement?.className }
        }
    }
    return { found: false }
})
console.log('Commander selected:', JSON.stringify(commanderSelected))

await sleep(3000)

const afterCommander = await page.evaluate(() => {
    return {
        buttons: Array.from(document.querySelectorAll('button')).slice(0, 20).map(b => ({
            txt: b.textContent.trim().substring(0, 40),
            visible: b.offsetParent !== null
        })).filter(b => b.visible && b.txt.length > 0),
        gameplayUI: !!document.querySelector('.gameplay-ui')
    }
})
console.log('After commander select:', JSON.stringify(afterCommander, null, 2))

await page.screenshot({ path: `${ART}/after-commander.png`, fullPage: false })

// Save the latest console + page errors so we can read them later
writeFileSync(`${ART}/console.log`, consoleMsgs.map(m => `[${new Date(m.t).toISOString()}] [${m.type}] ${m.text}`).join('\n'))
writeFileSync(`${ART}/page-errors.log`, pageErrors.map(e => `[${new Date(e.t).toISOString()}] ${e.msg}\n${e.stack}`).join('\n\n'))

console.log(`\n=== Total console messages: ${consoleMsgs.length} ===`)
console.log(`=== Page errors: ${pageErrors.length} ===`)

await browser.close()
