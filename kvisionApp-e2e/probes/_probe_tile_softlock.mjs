#!/usr/bin/env node
// Tile click softlock reproducer — based on guest-login.mjs pattern
// Logs in as guest, enters gameplay via CommanderSelectionDialog →
// Single Player → waits for map load → clicks tiles to test for softlock.

import { chromium } from '@playwright/test'
import { writeFile, mkdir } from 'node:fs/promises'
import { fileURLToPath } from 'node:url'
import { dirname, join } from 'node:path'

const __dirname = dirname(fileURLToPath(import.meta.url))
const ART = '/tmp/ag-softlock-probe'
await mkdir(ART, { recursive: true })

const log = (s) => console.log(`[${new Date().toISOString().slice(11, 23)}] ${s}`)

const browser = await chromium.launch({
    headless: true,
    args: ['--no-sandbox']
})
const ctx = await browser.newContext({ viewport: { width: 1920, height: 1080 } })
const page = await ctx.newPage()

const consoleMsgs = []
const pageErrors = []
page.on('console', (m) => consoleMsgs.push({ t: Date.now(), type: m.type(), text: m.text() }))
page.on('pageerror', (e) => pageErrors.push({ t: Date.now(), msg: e.message }))

log('Step 1: navigate')
await page.goto('http://127.0.0.1:8080', { waitUntil: 'load' })

log('Step 2: LoadingScreen CTA')
await page.locator('[data-testid="loading-screen-cta"]').click()
await page.waitForSelector('[data-testid="login-as-guest"]', { timeout: 15000 })

log('Step 3: Login As Guest')
await page.locator('[data-testid="login-as-guest"]').click()
await page.waitForSelector('[data-testid="main-menu"]', { timeout: 60000 })
log('Step 4: MainMenu visible')

// Dismiss any OK messagebox from login
const okDismiss = await page.evaluate(() => {
    const btns = Array.from(document.querySelectorAll('button'))
    const ok = btns.find(b => b.textContent.trim() === 'OK' && b.offsetParent !== null)
    if (ok) { ok.click(); return true }
    return false
})
if (okDismiss) await sleep(1500)

// Click PLAY
log('Step 5: Click PLAY')
await page.evaluate(() => {
    for (const b of document.querySelectorAll('[data-testid="main-menu"] button')) {
        if (b.textContent.trim() === 'PLAY') { b.click(); return }
    }
})

// Wait for CommanderSelectionDialog
try {
    await page.waitForSelector('.commander-selection-card', { timeout: 15000 })
    log('Step 6: Commander dialog visible')
} catch (e) {
    log('FAIL: No commander dialog')
    await page.screenshot({ path: `${ART}/repro-no-commander.png`, fullPage: false })
    await browser.close()
    process.exit(1)
}

// Click first commander
await page.locator('.commander-selection-card').first().click()
await sleep(2500)

// Click single player
await page.evaluate(() => {
    for (const c of document.querySelectorAll('.commander-selection-card')) {
        const txt = c.textContent.toUpperCase()
        if (txt.includes('SINGLE PLAYER') || txt.includes('1 PLAYER')) { c.click(); return }
    }
})
await sleep(2500)

// Click Start
await page.evaluate(() => {
    for (const b of document.querySelectorAll('button')) {
        const txt = b.textContent.trim().toUpperCase()
        if (txt === 'START' || txt === 'START GAME' || txt === 'CONFIRM' || txt === 'BEGIN') { b.click(); return }
    }
})

// Wait for gameplay
try {
    await page.waitForSelector('.gameplay-ui', { timeout: 60000 })
    log('Step 7: Gameplay UI mounted')
} catch (e) {
    log('FAIL: No gameplay UI')
    await page.screenshot({ path: `${ART}/repro-no-gameplay.png`, fullPage: false })
    await browser.close()
    process.exit(1)
}

// Wait for map to load (chunked WS frames per the resume path)
log('Waiting 12s for map + world state to populate...')
await sleep(12000)
await page.screenshot({ path: `${ART}/repro-gameplay.png`, fullPage: false })

const tileCount = await page.locator('[data-testid="territory-icon"]').count()
log(`Territory icons: ${tileCount}`)

if (tileCount === 0) {
    log('FAIL: No tiles')
    const state = await page.evaluate(() => ({
        mapViewer: !!document.querySelector('.map-viewer-container'),
        visibleWindows: Array.from(document.querySelectorAll('[class*="dialog"], [class*="window"], [class*="overlay"]')).filter(e => e.offsetParent !== null).slice(0, 5).map(el => ({ cls: el.className.substring(0, 60), text: el.textContent.trim().substring(0, 80) })),
        buttons: Array.from(document.querySelectorAll('button')).filter(b => b.offsetParent !== null).map(b => b.textContent.trim().substring(0, 30)).filter(t => t.length > 0).slice(0, 15)
    }))
    log(JSON.stringify(state, null, 2))
    await browser.close()
    process.exit(1)
}

const beforeClicks = consoleMsgs.length

// === TILE CLICK TEST ===
log('=== Click test: first tile ===')
const t0 = Date.now()
await page.locator('[data-testid="territory-icon"]').first().click({ force: true, timeout: 5000 }).catch((e) => log('Click 1 error: ' + e.message))
const click1Dur = Date.now() - t0
log(`Click 1: ${click1Dur}ms`)
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
log(`TDW state: ${JSON.stringify(tdwInfo, null, 2)}`)

log('=== Click test: second tile ===')
const t1 = Date.now()
await page.locator('[data-testid="territory-icon"]').nth(1).click({ force: true, timeout: 5000 }).catch((e) => log('Click 2 error: ' + e.message))
const click2Dur = Date.now() - t1
log(`Click 2: ${click2Dur}ms (>3000 = softlock confirmed)`)
await sleep(1500)
await page.screenshot({ path: `${ART}/repro-after-clicks.png`, fullPage: false })

log('=== Click test: third tile ===')
const t2 = Date.now()
await page.locator('[data-testid="territory-icon"]').nth(2).click({ force: true, timeout: 5000 }).catch((e) => log('Click 3 error: ' + e.message))
const click3Dur = Date.now() - t2
log(`Click 3: ${click3Dur}ms`)
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
    await page.mouse.move(mapCenter.x - 100, mapCenter.y - 50)
    const mouseDur = Date.now() - t3
    log(`Mouse move latency: ${mouseDur}ms`)
}

await writeFile(`${ART}/repro-console.log`, consoleMsgs.map(m => `[${new Date(m.t).toISOString()}] [${m.type}] ${m.text}`).join('\n'))
await writeFile(`${ART}/repro-page-errors.log`, pageErrors.map(e => `[${new Date(e.t).toISOString()}] ${e.msg}\n${e.stack || ''}`).join('\n\n'))

log(`Total: ${consoleMsgs.length} console, ${pageErrors.length} errors`)
log(`Console during clicks: ${consoleMsgs.length - beforeClicks}`)

const clickMsgs = consoleMsgs.slice(beforeClicks)
for (const m of clickMsgs.filter(m => m.type === 'error' || m.text.includes('Territory') || m.text.includes('MapViewer') || m.text.includes('softlock'))) {
    log(`  [${m.type}] ${m.text.substring(0, 250)}`)
}

await browser.close()

import { setTimeout as sleep } from 'node:timers/promises'
log('Done')
