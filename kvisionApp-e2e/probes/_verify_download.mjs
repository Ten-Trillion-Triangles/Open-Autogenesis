#!/usr/bin/env node
// Verify the user-reported "Download .map" zero-byte regression is fixed.
// Drives: login as guest → Collection overlay → Maps tab → click map
// card → click Download .map → intercept the browser download via
// Playwright's downloads API → verify the file has non-zero size and
// the magic bytes "PK\x03\x04" (a real zip).

import { chromium } from '@playwright/test'
import { setTimeout as sleep } from 'node:timers/promises'
import { mkdirSync, statSync, readFileSync } from 'node:fs'

const ART = '/tmp/ag-download-verify'
mkdirSync(ART, { recursive: true })

const log = (s) => console.log(`[${new Date().toISOString().slice(11, 23)}] ${s}`)

const browser = await chromium.launch({
    headless: true,
    viewport: { width: 1920, height: 1080 },
    args: ['--no-sandbox']
})
const context = await browser.newContext({ acceptDownloads: true })
const page = await context.newPage()

const consoleMsgs = []
page.on('console', (m) => consoleMsgs.push({ t: Date.now(), type: m.type(), text: m.text() }))

try {
log('Step 1: navigate + LoadingScreen + Login As Guest')
await page.goto('http://localhost:8080/', { waitUntil: 'load' })
log('  page loaded')
await page.locator('[data-testid="loading-screen-cta"]').click()
log('  LoadingScreen CTA clicked')
await page.waitForSelector('[data-testid="login-as-guest"]', { timeout: 15000 })
log('  Login As Guest button visible')
await page.locator('[data-testid="login-as-guest"]').click()
log('  Login As Guest clicked, awaiting OK button')
// Tolerant OK-button search: see verify_softlock_fix.mjs for the
// rationale — the message-box overlay classname is unstable.
await page.waitForFunction(() => {
    return Array.from(document.querySelectorAll('button')).some(b => {
        const txt = (b.textContent || '').trim()
        return txt === 'OK' || txt === 'Ok' || txt === 'ok'
    })
}, { timeout: 30000 })
log('  OK button appeared')
const okClicked = await page.evaluate(() => {
    const btns = Array.from(document.querySelectorAll('button'))
        .filter(b => /^(OK|Ok|ok)$/.test((b.textContent || '').trim()))
    // Prefer the LAST OK button (more likely to be the messageBox one)
    const okBtn = btns[btns.length - 1] || btns[0]
    if (okBtn) { okBtn.click(); return true }
    return false
})
if (!okClicked) throw new Error('OK button not found')
log('  dismissed login MessageBox OK')

log('Step 2: wait for MainMenu')
await page.waitForSelector('[data-testid="main-menu"]', { timeout: 60000 })
log('MainMenu visible')

log('Step 3: open Collection overlay')
await page.evaluate(() => {
    for (const b of document.querySelectorAll('[data-testid="main-menu"] button')) {
        if (b.textContent.trim() === 'Collection') { b.click(); return }
    }
})
await sleep(2500)

log('Step 4: switch to Maps tab')
await page.evaluate(() => {
    for (const t of document.querySelectorAll('.collection-tab-button')) {
        if (t.title === 'Maps') { t.click(); return }
    }
})
await sleep(2000)

log('Step 5: click map card → opens MapDetailWindow')
const mapCard = await page.locator("[data-testid^='map-card-']").first()
await mapCard.click()
await page.waitForSelector('[data-testid="map-detail-download"]', { timeout: 10000 })
log('  MapDetailWindow open')

log('Step 6: click Download .map and intercept the file')
const downloadPromise = page.waitForEvent('download', { timeout: 60000 })
await page.locator('[data-testid="map-detail-download"]').click()
log('  Download click registered — waiting for browser download event')

const download = await downloadPromise
const suggestedFilename = download.suggestedFilename()
const savePath = `${ART}/${suggestedFilename}`
await download.saveAs(savePath)
log(`  Suggested filename: ${suggestedFilename}`)
log(`  Saved to: ${savePath}`)

log('Step 7: verify file contents')
const st = statSync(savePath)
log(`  File size: ${st.size} bytes`)
const magic = readFileSync(savePath).slice(0, 4)
const magicHex = magic.toString('hex')
log(`  First 4 bytes (hex): ${magicHex}`)
const isZip = magicHex === '504b0304'

log('\n=== VERDICT ===')
if (st.size === 0) {
    log('  *** FAIL: file is 0 bytes (the user-reported regression) ***')
    process.exit(1)
}
if (!isZip) {
    log(`  *** FAIL: file does not start with zip magic (got ${magicHex}) ***`)
    process.exit(2)
}
log(`  *** PASS: download delivered ${st.size} bytes, magic=${magicHex} (zip) ***`)

// Also dump a few of the MapDetailWindow logs to confirm the new path
const relevantMsgs = consoleMsgs.filter(m =>
    m.text.includes('MapDetailWindow') &&
    (m.text.includes('download click') ||
     m.text.includes('streaming') ||
     m.text.includes('legacy path') ||
     m.text.includes('Download'))
)
log('\n  Browser logs from download flow:')
for (const m of relevantMsgs.slice(0, 10)) {
    log(`    [${new Date(m.t).toISOString()}] ${m.text.substring(0, 200)}`)
}
}
catch (e) {
    log(`*** ERROR: ${e.message} ***`)
    log(`  Stack: ${e.stack}`)
    process.exit(99)
}
finally {
    await browser.close()
}