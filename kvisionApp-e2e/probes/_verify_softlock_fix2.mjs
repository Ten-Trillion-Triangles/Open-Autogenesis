#!/usr/bin/env node
// Verifies the user-reported second symptom: "open the menu by clicking
// a tile, then close it, the entire collection manager softlocks and
// the app stops responding to mouse inputs".
//
// After the previous probe confirms the thumbnail renders, we open
// MapDetailWindow on the map card, close it, then attempt a second
// thumbnail render + verify mouse responsiveness.

import { chromium } from '@playwright/test'
import { setTimeout as sleep } from 'node:timers/promises'

const ART = '/tmp/ag-softlock-verify-2'
import { mkdirSync, writeFileSync } from 'node:fs'
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

log('Step 1: navigate + LoadingScreen + Login As Guest')
await page.goto('http://localhost:8080/', { waitUntil: 'load' })
await page.locator('[data-testid="loading-screen-cta"]').click()
await page.waitForSelector('[data-testid="login-as-guest"]', { timeout: 15000 })
await page.locator('[data-testid="login-as-guest"]').click()

await page.waitForSelector('.autogenesis-message-box-overlay button', { timeout: 30000 })
await page.locator('.autogenesis-message-box-overlay button').first().click({ timeout: 5000 })
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

log('Step 4: switch to Maps tab (this triggers refreshMapCatalogue)')
await page.evaluate(() => {
    for (const t of document.querySelectorAll('.collection-tab-button')) {
        if (t.title === 'Maps') { t.click(); return }
    }
})

log('Step 5: wait for first render to complete')
const tStart = Date.now()
let firstRenderedDataUrl = null
for (let i = 0; i < 30; i++) {
    await sleep(500)
    const entry = consoleMsgs.find(m =>
        m.text.includes('MapCardThumbnailRenderer') &&
        m.text.includes('applyDataUrl COMPLETED')
    )
    if (entry) {
        firstRenderedDataUrl = entry.text.match(/dataUrl=(\d+)/)?.[1]
        log(`  t=${Date.now() - tStart}ms: first render complete, dataUrl=${firstRenderedDataUrl} chars`)
        break
    }
}

log('Step 6: click the map card to open MapDetailWindow')
const t6 = Date.now()
await page.evaluate(() => {
    const card = document.querySelector("[data-testid^='map-card-']")
    if (card) card.click()
})
// MapDetailWindow shows via "data-testid=\"map-detail-close\""
await page.waitForSelector('[data-testid="map-detail-close"]', { timeout: 5000 })
const openLatency = Date.now() - t6
log(`  MapDetailWindow open latency: ${openLatency}ms`)

// Wait a moment for the click to settle
await sleep(1500)

log('Step 7: close MapDetailWindow')
const t7 = Date.now()
await page.locator('[data-testid="map-detail-close"]').click()
const closeLatency = Date.now() - t7
log(`  MapDetailWindow close latency: ${closeLatency}ms`)

// Wait 2 seconds
await sleep(2000)

log('Step 8: test mouse responsiveness after close')
const mapCenter = await page.evaluate(() => {
    const mv = document.querySelector('.map-viewer-container, .collection-overlay')
    if (!mv) return null
    const r = mv.getBoundingClientRect()
    return { x: r.left + r.width / 2, y: r.top + r.height / 2 }
})
let mouseLatency = -1
if (mapCenter) {
    const t8 = Date.now()
    await page.mouse.move(mapCenter.x - 200, mapCenter.y - 100)
    await page.mouse.move(mapCenter.x - 100, mapCenter.y - 50)
    await page.mouse.click(mapCenter.x, mapCenter.y)
    mouseLatency = Date.now() - t8
}
log(`  Mouse click latency after close: ${mouseLatency}ms`)

log('Step 9: trigger second render by switching tabs and back')
await sleep(1500)
const t9 = Date.now()
// Switch to COMMANDERS tab and back to MAPS
await page.evaluate(() => {
    for (const t of document.querySelectorAll('.collection-tab-button')) {
        if (t.title === 'Commanders') { t.click(); return }
    }
})
await sleep(2000)
await page.evaluate(() => {
    for (const t of document.querySelectorAll('.collection-tab-button')) {
        if (t.title === 'Maps') { t.click(); return }
    }
})
const secondRenderStart = Date.now()

let secondRenderedDataUrl = null
let secondRenderComplete = false
for (let i = 0; i < 30; i++) {
    await sleep(500)
    // Find the SECOND applyDataUrl COMPLETED entry
    const entries = consoleMsgs.filter(m =>
        m.text.includes('MapCardThumbnailRenderer') &&
        m.text.includes('applyDataUrl COMPLETED')
    )
    const newEntries = entries.filter(m => !firstRenderedDataUrl || m.t > t9 + 500)
    if (newEntries.length >= 1 && (Date.now() - t9) > 1000) {
        const entry = newEntries[0]
        secondRenderedDataUrl = entry.text.match(/dataUrl=(\d+)/)?.[1]
        secondRenderComplete = true
        log(`  t=${Date.now() - secondRenderStart}ms: second render complete, dataUrl=${secondRenderedDataUrl} chars`)
        break
    }
}

log('Step 10: final mouse-responsiveness probe')
const t10 = Date.now()
await page.mouse.click(mapCenter.x + 50, mapCenter.y + 50)
const finalLatency = Date.now() - t10
log(`  Final mouse click latency: ${finalLatency}ms`)

await page.screenshot({ path: `${ART}/verify2-final.png`, fullPage: false })

log('\n=== VERDICT ===')
log(`  First render dataUrl:  ${firstRenderedDataUrl ?? 'NEVER'} chars`)
log(`  Second render dataUrl: ${secondRenderedDataUrl ?? 'NEVER'} chars`)
log(`  Mouse click latency after close: ${mouseLatency}ms`)
log(`  Final click latency:            ${finalLatency}ms`)

const okFirst = firstRenderedDataUrl !== null && parseInt(firstRenderedDataUrl) < 100000
const okSecond = secondRenderedDataUrl !== null && parseInt(secondRenderedDataUrl) < 100000
const okMouse = mouseLatency >= 0 && mouseLatency < 1000
const okFinal = finalLatency >= 0 && finalLatency < 1000

if (okFirst && okSecond && okMouse && okFinal) {
    log('\n*** PASS: open-close-render cycle is non-blocking ***')
} else {
    log('\n*** FAIL ***')
    log(`  first:  ${okFirst}`)
    log(`  second: ${okSecond}`)
    log(`  mouse1: ${okMouse}`)
    log(`  mouse2: ${okFinal}`)
    process.exit(1)
}

await browser.close()
