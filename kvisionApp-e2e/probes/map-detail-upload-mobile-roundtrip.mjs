#!/usr/bin/env node
// kvisionApp-e2e/probes/map-detail-upload-mobile-baseline.mjs
//
// Baseline probe for the map-detail-window + map-upload-modal mobile layout work.
// Captures the CURRENT state of both widgets at:
//   - desktop viewport (1920x1080) — proves the standard layout still works
//   - mobile viewport (iPhone 12: 390x844) — shows what currently breaks
//
// Outputs go under kvisionApp-e2e/probes/artifacts-map-detail-upload-baseline/
// and are version-stamped so post-fix runs can diff against them.
//
// Pre-req: server-extend on 7070, game server on 9080, webpack on 8080.
//
// Usage:
//   node kvisionApp-e2e/probes/map-detail-upload-mobile-baseline.mjs

import { chromium, devices } from '@playwright/test'
import { mkdir } from 'node:fs/promises'
import { fileURLToPath } from 'node:url'
import { dirname, join } from 'node:path'

const __dirname = dirname(fileURLToPath(import.meta.url))

const BASE_URL = 'http://127.0.0.1:8080'
// Args: --pre-fix uses the original directory name (baseline); --post-fix
// (default) writes to a fresh post-fix directory so we can diff.
const FIX_FLAG = process.argv.includes('--pre-fix') ? 'pre-fix' : 'post-fix'
const ARTIFACT_DIR = join(__dirname, `artifacts-map-detail-${FIX_FLAG}`)
await mkdir(ARTIFACT_DIR, { recursive: true })

const ASSERTIONS = []
function check(name, condition, detail = '') {
    ASSERTIONS.push({ name, pass: !!condition, detail })
    console.log(`${condition ? 'PASS' : 'FAIL'}: ${name}${detail ? ' :: ' + detail : ''}`)
}

const browser = await chromium.launch({ headless: true })

// Helper: navigate through login flow → Collection → MAPS tab → open modal
async function navigateToCollectionMapsTab(page) {
    await page.goto(BASE_URL + '/index.html?skipLogin=true', { waitUntil: 'load', timeout: 60000 })
    await page.waitForTimeout(5000)
    const loadingScreen = await page.getByTestId('loading-screen-cta').count()
    if (loadingScreen > 0) {
        await page.getByTestId('loading-screen-cta').click()
        await page.waitForTimeout(2000)
    }
    await page.locator('[data-testid="main-menu"]').waitFor({ state: 'visible', timeout: 30000 })
    await page.evaluate(() => {
        for (const b of document.querySelectorAll('[data-testid="main-menu"] button')) {
            if (b.textContent.trim() === 'Collection') { b.click(); return }
        }
    })
    await page.waitForTimeout(3000)
    await page.locator('.collection-overlay').first().waitFor({ state: 'visible', timeout: 15000 })
    await page.evaluate(() => {
        for (const b of document.querySelectorAll('.collection-tab-button')) {
            if (b.title === 'Maps') { b.click(); return }
        }
    })
    await page.waitForTimeout(2000)
}

async function snapshotWidgetDimensions(page, widgetSelector) {
    return await page.evaluate((sel) => {
        const el = document.querySelector(sel)
        if (!el) return null
        const r = el.getBoundingClientRect()
        const cs = getComputedStyle(el)
        return {
            x: r.x, y: r.y, w: r.width, h: r.height,
            scrollWidth: el.scrollWidth,
            scrollHeight: el.scrollHeight,
            clientWidth: el.clientWidth,
            clientHeight: el.clientHeight,
            dataMobileLayout: el.getAttribute('data-mobile-layout'),
            display: cs.display,
            padding: cs.padding,
            gridTemplateColumns: cs.gridTemplateColumns,
            fontSize: cs.fontSize,
            innerWidth: window.innerWidth,
            innerHeight: window.innerHeight,
        }
    }, widgetSelector)
}

// ─────────────────────────────────────────────────────────────────────────
// PART 1: Desktop baseline (1920x1080)
// ─────────────────────────────────────────────────────────────────────────
console.log('\n========== DESKTOP BASELINE (1920x1080) ==========\n')
const desktopCtx = await browser.newContext({ viewport: { width: 1920, height: 1080 } })
const desktopPage = await desktopCtx.newPage()
desktopPage.on('pageerror', err => console.log(`PAGE ERROR (desktop): ${err.message}`))
await navigateToCollectionMapsTab(desktopPage)
await desktopPage.screenshot({ path: join(ARTIFACT_DIR, 'desktop-01-collection-maps-tab.png'), fullPage: true })

// Open the upload modal (this is what the user wanted to see)
await desktopPage.locator('[data-testid="maps-upload-button"]').click()
await desktopPage.waitForTimeout(2000)
const uploadModalDesktop = await snapshotWidgetDimensions(desktopPage, '[data-testid="map-upload-modal"]')
console.log('UploadModal @ desktop:', JSON.stringify(uploadModalDesktop, null, 2))
check('UploadModal visible at desktop', uploadModalDesktop && uploadModalDesktop.w > 0)
check('UploadModal data-mobile-layout="desktop" at desktop viewport', uploadModalDesktop?.dataMobileLayout === 'desktop',
    `(got: ${uploadModalDesktop?.dataMobileLayout})`)
await desktopPage.screenshot({ path: join(ARTIFACT_DIR, 'desktop-02-map-upload-modal.png'), fullPage: true })

// Close modal
await desktopPage.locator('[data-testid="map-upload-cancel"]').click()
await desktopPage.waitForTimeout(1500)

// Programmatically inject a MapDetailWindow into the collection overlay
// (since the live upload flow doesn't populate cards yet — TODO_AFTER_UPLOAD_RPC).
// Use a unique data-testid stub to avoid colliding with the real widget's
// data-testid="map-detail-window" (which the real widget now exposes per the
// parent-convention fix).
await desktopPage.evaluate(() => {
    const detail = document.createElement('div')
    detail.className = 'login-widget-window'
    detail.setAttribute('data-testid', 'map-detail-window-stub')
    detail.style.cssText = 'position:fixed;top:50%;left:50%;width:700px;height:600px;margin:-300px 0 0 -350px;padding:25px;z-index:9100;background:rgba(8,10,24,0.95);border:2px solid rgba(94,106,220,0.5);display:flex;flex-direction:column;'
    detail.innerHTML = `
        <h2 data-testid="map-detail-title" style="color:#06b6d4;margin:0 0 8px 0;">Map Details</h2>
        <div data-testid="map-detail-name" style="font-size:20px;color:#fff;margin-bottom:6px;">San Martello</div>
        <div data-testid="map-detail-meta" style="font-size:13px;color:#94a3b8;margin-bottom:12px;">Created 2026-08-15 · 1672×941 · 6.3 MB</div>
        <div data-testid="map-detail-author" style="font-size:14px;color:#cbd5e1;margin-bottom:4px;">Author: test-user</div>
        <div data-testid="map-detail-players" style="font-size:14px;color:#cbd5e1;margin-bottom:4px;">Players: 2</div>
        <div data-testid="map-detail-territories" style="font-size:14px;color:#cbd5e1;margin-bottom:4px;">Territories: 49</div>
        <div data-testid="map-detail-schema" style="font-size:14px;color:#cbd5e1;margin-bottom:4px;">Schema: v3</div>
        <div data-testid="map-detail-sha" style="font-size:11px;color:#64748b;margin-bottom:14px;font-family:monospace;">sha256: a1b2c3…</div>
        <div style="flex:1;"></div>
        <div style="display:flex;gap:10px;">
            <button data-testid="map-detail-delete" class="btn btn-secondary-action map-detail-delete" style="flex:1;padding:10px;">DELETE</button>
            <button data-testid="map-detail-download" class="btn btn-secondary-action" style="flex:1;padding:10px;">Download .map</button>
            <button data-testid="map-detail-close" class="btn btn-secondary-action" style="flex:1;padding:10px;">CLOSE</button>
        </div>
    `
    document.body.appendChild(detail)
})
await desktopPage.waitForTimeout(1000)
const detailDesktop = await snapshotWidgetDimensions(desktopPage, '[data-testid="map-detail-window-stub"]')
console.log('MapDetailWindow @ desktop:', JSON.stringify(detailDesktop, null, 2))
check('MapDetailWindow visible at desktop', detailDesktop && detailDesktop.w > 0)
await desktopPage.screenshot({ path: join(ARTIFACT_DIR, 'desktop-03-map-detail-window.png'), fullPage: true })

await desktopCtx.close()

// ─────────────────────────────────────────────────────────────────────────
// PART 2: Mobile baseline (iPhone 12: 390x844)
// ─────────────────────────────────────────────────────────────────────────
console.log('\n========== MOBILE BASELINE (iPhone 12: 390x844) ==========\n')
const mobileCtx = await browser.newContext({ ...devices['iPhone 12'] })
const mobilePage = await mobileCtx.newPage()
mobilePage.on('pageerror', err => console.log(`PAGE ERROR (mobile): ${err.message}`))
await navigateToCollectionMapsTab(mobilePage)
await mobilePage.screenshot({ path: join(ARTIFACT_DIR, 'mobile-01-collection-maps-tab.png'), fullPage: true })

// Open upload modal
await mobilePage.locator('[data-testid="maps-upload-button"]').click()
await mobilePage.waitForTimeout(2000)
const uploadModalMobile = await snapshotWidgetDimensions(mobilePage, '[data-testid="map-upload-modal"]')
console.log('UploadModal @ mobile:', JSON.stringify(uploadModalMobile, null, 2))
check('UploadModal visible at mobile', uploadModalMobile && uploadModalMobile.w > 0)
check('UploadModal data-mobile-layout on mobile',
    uploadModalMobile?.dataMobileLayout === 'portrait' || uploadModalMobile?.dataMobileLayout === 'desktop',
    `(got: ${uploadModalMobile?.dataMobileLayout})`)
const uploadModalOverflows = uploadModalMobile && uploadModalMobile.w > uploadModalMobile.innerWidth
check('UploadModal does NOT overflow viewport horizontally', !uploadModalOverflows,
    `widget=${uploadModalMobile?.w}px viewport=${uploadModalMobile?.innerWidth}px`)
await mobilePage.screenshot({ path: join(ARTIFACT_DIR, 'mobile-02-map-upload-modal.png'), fullPage: true })

await mobilePage.locator('[data-testid="map-upload-cancel"]').click()
await mobilePage.waitForTimeout(1500)

// Inject the same MapDetailWindow stub at mobile viewport
await mobilePage.evaluate(() => {
    const detail = document.createElement('div')
    detail.className = 'login-widget-window'
    detail.setAttribute('data-testid', 'map-detail-window-stub')
    detail.style.cssText = 'position:fixed;top:50%;left:50%;width:700px;height:600px;margin:-300px 0 0 -350px;padding:25px;z-index:9100;background:rgba(8,10,24,0.95);border:2px solid rgba(94,106,220,0.5);display:flex;flex-direction:column;'
    detail.innerHTML = `
        <h2 data-testid="map-detail-title" style="color:#06b6d4;margin:0 0 8px 0;">Map Details</h2>
        <div data-testid="map-detail-name" style="font-size:20px;color:#fff;margin-bottom:6px;">San Martello</div>
        <div data-testid="map-detail-meta" style="font-size:13px;color:#94a3b8;margin-bottom:12px;">Created 2026-08-15 · 1672×941 · 6.3 MB</div>
        <div data-testid="map-detail-author" style="font-size:14px;color:#cbd5e1;margin-bottom:4px;">Author: test-user</div>
        <div data-testid="map-detail-players" style="font-size:14px;color:#cbd5e1;margin-bottom:4px;">Players: 2</div>
        <div data-testid="map-detail-territories" style="font-size:14px;color:#cbd5e1;margin-bottom:4px;">Territories: 49</div>
        <div data-testid="map-detail-schema" style="font-size:14px;color:#cbd5e1;margin-bottom:4px;">Schema: v3</div>
        <div data-testid="map-detail-sha" style="font-size:11px;color:#64748b;margin-bottom:14px;font-family:monospace;">sha256: a1b2c3…</div>
        <div style="flex:1;"></div>
        <div style="display:flex;gap:10px;">
            <button data-testid="map-detail-delete" class="btn btn-secondary-action map-detail-delete" style="flex:1;padding:10px;">DELETE</button>
            <button data-testid="map-detail-download" class="btn btn-secondary-action" style="flex:1;padding:10px;">Download .map</button>
            <button data-testid="map-detail-close" class="btn btn-secondary-action" style="flex:1;padding:10px;">CLOSE</button>
        </div>
    `
    document.body.appendChild(detail)
})
await mobilePage.waitForTimeout(1000)
const detailMobile = await snapshotWidgetDimensions(mobilePage, '[data-testid="map-detail-window-stub"]')
console.log('MapDetailWindow @ mobile:', JSON.stringify(detailMobile, null, 2))
check('MapDetailWindow visible at mobile', detailMobile && detailMobile.w > 0)
const detailOverflows = detailMobile && detailMobile.w > detailMobile.innerWidth
check('MapDetailWindow does NOT overflow viewport horizontally', !detailOverflows,
    `widget=${detailMobile?.w}px viewport=${detailMobile?.innerWidth}px`)
await mobilePage.screenshot({ path: join(ARTIFACT_DIR, 'mobile-03-map-detail-window.png'), fullPage: true })

await mobileCtx.close()
await browser.close()

console.log('\n========== SUMMARY ==========')
const passed = ASSERTIONS.filter(a => a.pass).length
console.log(`Assertions: ${passed}/${ASSERTIONS.length} passed`)
console.log(`Artifacts: ${ARTIFACT_DIR}`)
process.exit(ASSERTIONS.every(a => a.pass) ? 0 : 1)
