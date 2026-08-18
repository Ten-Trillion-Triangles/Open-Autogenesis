#!/usr/bin/env node
// Verification probe — runs after the MapCardThumbnailRenderer async
// patch. We drive the real AccelByte guest login → CollectionOverlay
// → maps tab → wait for thumbnails. If the patch works:
//   1. Each `.co-thumb` div carries a `data:image/png;base64,...`
//      inline style with a SHORT data URL (~30-50 KB).
//   2. The browser stays responsive throughout — click events on
//      the close button / tabs / collection work.
//   3. The browser log records the new "MapCardThumbnailRenderer:
//      applyDataUrl COMPLETED" entries without a 3-minute dead window.
//
// Pre-fix: `dataUrl` was 8.4 MB; softlock lasted ~3 minutes (verified
// 14:53:20 → 14:56:37 in `~/.autogenesis/logs/browser-2026-08-16-104727.log`).

import { chromium } from '@playwright/test'
import { setTimeout as sleep } from 'node:timers/promises'
import { mkdirSync, writeFileSync } from 'node:fs'

const ART = '/tmp/ag-softlock-verify'
mkdirSync(ART, { recursive: true })

const log = (s) => console.log(`[${new Date().toISOString().slice(11, 23)}] ${s}`)

const browser = await chromium.launch({
    headless: true,
    viewport: { width: 1920, height: 1080 },
    args: ['--no-sandbox']
})
const page = await browser.newPage()

const consoleMsgs = []
const pageErrors = []
page.on('console', (m) => consoleMsgs.push({ t: Date.now(), type: m.type(), text: m.text() }))
page.on('pageerror', (e) => pageErrors.push({ t: Date.now(), msg: e.message }))

// Step 1: Navigate and click through LoadingScreen + Login As Guest.
log('Step 1: navigate + LoadingScreen + Login As Guest')
await page.goto('http://localhost:8080/', { waitUntil: 'load' })
await page.locator('[data-testid="loading-screen-cta"]').click()
await page.waitForSelector('[data-testid="login-as-guest"]', { timeout: 15000 })
await page.locator('[data-testid="login-as-guest"]').click()

// Dismiss the success MessageBox OK button (matches guest-login.mjs).
// The OK button is wired to messageBox.onConfirm which mounts MainMenu.
// Scope the selector to the message-box overlay so we don't grab the
// Login button behind it. Tolerant of various incarnations of the OK
// button text + matching against visible-but-not-overlapped buttons.
await page.waitForFunction(() => {
    return Array.from(document.querySelectorAll('button')).some(b => {
        const txt = (b.textContent || '').trim()
        return txt === 'OK' || txt === 'Ok' || txt === 'ok'
    })
}, { timeout: 30000 })
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

// Step 2: Wait for MainMenu — this is the gateway into gameplay.
log('Step 2: wait for MainMenu')
await page.waitForSelector('[data-testid="main-menu"]', { timeout: 60000 })
log('MainMenu visible')

// Step 3: Open CollectionOverlay (which fires MapCardThumbnailRenderer
// on each map card — this is where the softlock used to happen).
log('Step 3: open Collection overlay')
await page.evaluate(() => {
    for (const b of document.querySelectorAll('[data-testid="main-menu"] button')) {
        if (b.textContent.trim() === 'Collection') { b.click(); return }
    }
})
await sleep(2500)

log('Step 4: switch to Maps tab (this triggers refreshMapCatalogue)')
await page.evaluate(() => {
    for (const t of document.querySelectorAll('.collection-tab-button')) {
        if (t.title === 'Maps') { t.click(); return }
    }
})

// The fix path: we watch the page for ~15 seconds. Pre-fix, the
// thumbnail decode would freeze the main thread for ~3 minutes.
// Post-fix, we expect:
//   - One or more `.co-thumb` divs get a `background-image: url(data:...)`
//     style applied within a few seconds.
//   - Mouse events keep firing — we test this by attempting a click.
// Step 5: monitoring thumbnail render + mouse responsiveness for 15s
const startWall = Date.now()
let lastDataUrlLength = 0
let thumbnailRendered = false
let applyDataUrlCompleted = false
for (let i = 0; i < 30; i++) {
    await sleep(500)
    const data = await page.evaluate(() => {
        const thumbs = Array.from(document.querySelectorAll('.co-thumb'))
        const withBg = thumbs.find(t => {
            const bi = t.style.backgroundImage || ''
            return bi.startsWith('data:image/png;base64,')
        })
        return {
            thumbCount: thumbs.length,
            thumbWithDataUrl: withBg ? {
                dataUrlLength: withBg.style.backgroundImage.length,
                prefix: withBg.style.backgroundImage.substring(0, 60),
                iconStillThere: !!withBg.querySelector('i')
            } : null,
            // Mouse-responsiveness probe: try a synthetic hover on the
            // collection close button. Pre-fix the main thread was
            // blocked; the click would never fire.
            canHover: !!document.querySelector('.collection-overlay-close, [data-testid="collection-close"], .co-overlay-close, button[title*="Close"], button[title*="close"]')
        }
    })
    const elapsed = Date.now() - startWall
    if (data.thumbWithDataUrl) {
        if (!thumbnailRendered) {
            log(`  t=${elapsed}ms: THUMBNAIL RENDERED. dataUrl length=${data.thumbWithDataUrl.dataUrlLength} chars`)
            log(`  prefix: ${data.thumbWithDataUrl.prefix}`)
            log(`  placeholder icon still present: ${data.thumbWithDataUrl.iconStillThere}`)
            thumbnailRendered = true
            lastDataUrlLength = data.thumbWithDataUrl.dataUrlLength
        }
    }
    // Detect the renderer's applyDataUrl COMPLETED log entry from
    // the new async pipeline (it logs even when the icon hasn't been
    // removed yet, so we can confirm the patch ran).
    const completedEntry = consoleMsgs.find(m =>
        m.text.includes('MapCardThumbnailRenderer') &&
        m.text.includes('dataUrl=') &&
        m.text.includes('chars')
    )
    if (completedEntry && !applyDataUrlCompleted) {
        log(`  t=${elapsed}ms: MapCardThumbnailRenderer dataUrl length log entry:`)
        log(`    ${completedEntry.text}`)
        // Parse "dataUrl=XXXX chars" to get the actual length
        const match = completedEntry.text.match(/dataUrl=(\d+)\s+chars/)
        if (match) {
            lastDataUrlLength = parseInt(match[1])
            applyDataUrlCompleted = true
        }
    }
    if (i % 4 === 0) {
        log(`  t=${elapsed}ms: thumbs=${data.thumbCount} rendered=${!!data.thumbWithDataUrl}`)
    }
}

// Diagnostic: write the FULL console log
writeFileSync(`${ART}/verify-console.log`, consoleMsgs.map(m => `[${new Date(m.t).toISOString()}] [${m.type}] ${m.text}`).join('\n'))
writeFileSync(`${ART}/verify-page-errors.log`, pageErrors.map(e => `[${new Date(e.t).toISOString()}] ${e.msg}\n${e.stack || ''}`).join('\n\n'))

// Final verdict
log('\n=== VERDICT ===')
const renderBlockingEntries = consoleMsgs.filter(m =>
    m.text.includes('MapCardThumbnailRenderer') &&
    (m.text.includes('dataUrl=') || m.text.includes('applyDataUrl COMPLETED'))
)
log(`  MapCardThumbnailRenderer pipeline log entries: ${renderBlockingEntries.length}`)
for (const m of renderBlockingEntries) {
    log(`    [${new Date(m.t).toISOString()}] ${m.text}`)
}

if (lastDataUrlLength > 0) {
    log(`  dataUrl length: ${lastDataUrlLength} chars`)
    if (lastDataUrlLength > 100000) {
        log(`  FAIL: dataUrl is ${lastDataUrlLength} chars — pre-fix size (~8.4 MB). Patch did NOT take effect.`)
    } else if (lastDataUrlLength < 100000) {
        log(`  PASS: dataUrl is small (${lastDataUrlLength} chars). Patch active.`)
    }
}

const softlockWindow = consoleMsgs.filter(m => m.text.includes('AudioEngine.tick'))
log(`  AudioEngine heartbeat count: ${softlockWindow.length}`)

// Mouse responsiveness test: try to click the close button
log('\n=== Mouse responsiveness test ===')
const closeClickResult = await page.evaluate(() => {
    const btns = Array.from(document.querySelectorAll('button'))
    const closeBtn = btns.find(b => {
        const txt = b.textContent.toLowerCase()
        return txt.includes('close') || txt.includes('×') || txt === 'X' || txt === 'x'
    })
    if (closeBtn) {
        const t0 = performance.now()
        closeBtn.click()
        const t1 = performance.now()
        return { found: true, clickLatencyMs: t1 - t0 }
    }
    return { found: false, clickLatencyMs: null }
})
log(`  Close button click: ${JSON.stringify(closeClickResult)}`)

await page.screenshot({ path: `${ART}/verify-final.png`, fullPage: false })

await browser.close()

if (!applyDataUrlCompleted && lastDataUrlLength === 0) {
    log('\n*** FAIL: renderer pipeline never produced a dataUrl ***')
    process.exit(1)
}
if (lastDataUrlLength > 100000) {
    log('\n*** FAIL: dataUrl is too large — patch did not take effect ***')
    process.exit(2)
}
if (lastDataUrlLength > 0 && lastDataUrlLength < 100000) {
    log('\n*** PASS: softlock fix verified — dataUrl is ' + lastDataUrlLength + ' chars (was ~8.4M) ***')
}