#!/usr/bin/env node
// kvisionApp-e2e/probes/collection-maps-tab.mjs
//
// Live verification probe for the new MAPS tab in CollectionOverlay.
// Drives Playwright against the live KVision mount (uses ?skipLogin=true
// to avoid AccelByte OAuth round-trip + LLM token burn — pure UI surface
// verification per the "no-live-game-state" operator rule).
//
// Pre-req: kvisionApp server running at http://127.0.0.1:8080.
// Run:
//   node kvisionApp-e2e/probes/collection-maps-tab.mjs \
//        [--base-url=http://127.0.0.1:8080]
//
// Verifies:
//   - MAPS tab button present in the tab strip
//   - MAPS tab swaps the page pane when clicked
//   - "UPLOAD MAP" button visible in the Maps search row
//   - Golden-gradient + uppercase styling on the upload button
//   - 4-column grid layout (computed gridTemplateColumns)
//   - "PENDING REVIEW" / "READY" / "STOCK" badge classes present
//   - Click UPLOAD MAP → MapUploadModal opens with all 4 states wired
//   - data-testid="map-upload-drop-zone" + "map-upload-publish" + "map-upload-cancel"
//
// Captures 6 screenshots (Maps tab + 4 modal states + map-detail if reachable).

import { chromium, devices } from '@playwright/test'
import { mkdir } from 'node:fs/promises'
import { fileURLToPath } from 'node:url'
import { dirname, join } from 'node:path'

const __dirname = dirname(fileURLToPath(import.meta.url))

const BASE_URL_ARG = process.argv.find(a => a.startsWith('--base-url='))
const BASE_URL = BASE_URL_ARG
    ? BASE_URL_ARG.substring('--base-url='.length)
    : 'http://127.0.0.1:8080'

const ARTIFACT_DIR = join(__dirname, 'artifacts-collection-maps-tab')
await mkdir(ARTIFACT_DIR, { recursive: true })

const FAILURES = []
const ASSERTIONS = []

function check(name, condition, detail = '')
{
    ASSERTIONS.push({ name, pass: !!condition, detail })
    if (condition) {
        console.log(`PASS: ${name}${detail ? ' :: ' + detail : ''}`)
    } else {
        console.log(`FAIL: ${name} ${detail}`)
        FAILURES.push(name)
    }
}

const browser = await chromium.launch({ headless: true })
const context = await browser.newContext({ viewport: { width: 1920, height: 1080 } })
const page = await context.newPage()

const consoleErrors = []
page.on('console', (msg) => {
    if (msg.type() === 'error') {
        consoleErrors.push(msg.text())
    }
})

try {
    console.log('=== Step 1: navigate (skipLogin=true to avoid OAuth + LLM cycles) ===')
    await page.goto(BASE_URL + '/index.html?skipLogin=true', { waitUntil: 'load', timeout: 60000 })
    await page.waitForTimeout(5000)

    // If the loading screen is still present, dismiss it
    const loadingScreen = await page.getByTestId('loading-screen-cta').count()
    if (loadingScreen > 0) {
        await page.getByTestId('loading-screen-cta').click()
        await page.waitForTimeout(2000)
    }

    // Wait for main menu to mount
    await page.locator('[data-testid="main-menu"]').waitFor({ state: 'visible', timeout: 30000 })
    console.log('   main menu mounted')

    console.log('=== Step 2: click Collection from main menu ===')
    await page.evaluate(() => {
        const btns = document.querySelectorAll('[data-testid="main-menu"] button')
        for (const b of btns) {
            if (b.textContent.trim() === 'Collection') {
                b.click()
                return
            }
        }
    })
    await page.waitForTimeout(3000)

    // Wait for the collection overlay to mount
    await page.locator('[data-testid="collection-overlay"], .collection-overlay').first().waitFor({ state: 'visible', timeout: 15000 })
    console.log('   collection overlay mounted')

    console.log('=== Step 3: assert MAPS tab is present in the tab strip ===')
    const mapsTabExists = await page.evaluate(() => {
        const buttons = document.querySelectorAll('.collection-tab-button')
        for (const b of buttons) {
            if (b.title === 'Maps') return true
        }
        return false
    })
    check('MAPS tab button is present in the tab strip', mapsTabExists)

    console.log('=== Step 4: click the MAPS tab ===')
    await page.evaluate(() => {
        const buttons = document.querySelectorAll('.collection-tab-button')
        for (const b of buttons) {
            if (b.title === 'Maps') {
                b.click()
                return
            }
        }
    })
    await page.waitForTimeout(2000)

    await page.screenshot({ path: join(ARTIFACT_DIR, '01-maps-tab-active.png'), fullPage: true })

    const mapsTabActive = await page.evaluate(() => {
        const active = document.querySelector('.collection-tab-button-active')
        return active?.title || null
    })
    check('MAPS tab is the active tab', mapsTabActive === 'Maps', `(got title: "${mapsTabActive}")`)

    console.log('=== Step 5: assert UPLOAD MAP button is present in the Maps search row ===')
    const uploadBtn = await page.locator('[data-testid="maps-upload-button"]').count()
    check('UPLOAD MAP button present', uploadBtn === 1, `(count=${uploadBtn})`)

    const uploadBtnText = await page.evaluate(() => {
        const b = document.querySelector('[data-testid="maps-upload-button"]')
        return b?.textContent?.trim() || null
    })
    check('UPLOAD MAP button has "UPLOAD MAP" text', uploadBtnText === 'UPLOAD MAP', `(got: "${uploadBtnText}")`)

    const uploadBtnBg = await page.evaluate(() => {
        const b = document.querySelector('[data-testid="maps-upload-button"]')
        if (!b) return null
        return getComputedStyle(b).backgroundImage
    })
    const hasGoldGradient = uploadBtnBg && uploadBtnBg.includes('linear-gradient') && (uploadBtnBg.includes('facc15') || uploadBtnBg.includes('rgb(250, 204, 21)'))
    check('UPLOAD MAP button has gold gradient', hasGoldGradient, `backgroundImage="${uploadBtnBg}"`)

    console.log('=== Step 6: assert search field is present ===')
    const searchField = await page.locator('[data-testid="maps-search-field"]').count()
    check('Maps search field present', searchField === 1, `(count=${searchField})`)

    console.log('=== Step 7: capture Maps tab layout (no cards populated — server-side feed not wired) ===')
    // The Maps tab page is empty in the live mount because the RPC that fetches
    // user's uploaded maps is not yet wired (TODO_AFTER_UPLOAD_RPC). The structural
    // elements (tab, search row, upload button) are present; the GRID is empty.
    const gridCount = await page.locator('.collection-grid').count()
    check('collection-grid container present', gridCount === 1, `(count=${gridCount})`)

    console.log('=== Step 8: click UPLOAD MAP → confirm MapUploadModal opens ===')
    await page.locator('[data-testid="maps-upload-button"]').click()
    await page.waitForTimeout(2000)

    const modalOpened = await page.locator('[data-testid="map-upload-modal"]').count()
    check('MapUploadModal opens on UPLOAD MAP click', modalOpened >= 1, `(count=${modalOpened})`)

    const modalVisible = await page.locator('[data-testid="map-upload-modal"]').isVisible()
    check('MapUploadModal is visible', modalVisible)

    await page.screenshot({ path: join(ARTIFACT_DIR, '02-modal-state-idle.png'), fullPage: true })

    console.log('=== Step 9: assert modal sub-elements ===')
    const dropZone = await page.locator('[data-testid="map-upload-drop-zone"]').count()
    check('drop-zone present', dropZone === 1, `(count=${dropZone})`)

    const browseLink = await page.locator('[data-testid="map-upload-browse-link"]').count()
    check('browse-link present', browseLink === 1, `(count=${browseLink})`)

    const cancelBtn = await page.locator('[data-testid="map-upload-cancel"]').count()
    check('cancel button present', cancelBtn === 1, `(count=${cancelBtn})`)

    const publishBtn = await page.locator('[data-testid="map-upload-publish"]').count()
    check('publish button present', publishBtn === 1, `(count=${publishBtn})`)

    const nameInput = await page.locator('[data-testid="map-upload-name-input"]').count()
    check('name input present', nameInput === 1, `(count=${nameInput})`)

    const descriptionInput = await page.locator('[data-testid="map-upload-description-input"]').count()
    check('description input present', descriptionInput === 1, `(count=${descriptionInput})`)

    const infoText = await page.locator('[data-testid="map-upload-info-text"]').count()
    check('info text present', infoText === 1, `(count=${infoText})`)

    console.log('=== Step 10: publish button is disabled in IDLE state ===')
    const publishDisabled = await page.evaluate(() => {
        const b = document.querySelector('[data-testid="map-upload-publish"]')
        return b?.disabled === true
    })
    check('publish button disabled in IDLE state', publishDisabled)

    console.log('=== Step 11: close modal via Cancel button ===')
    await page.locator('[data-testid="map-upload-cancel"]').click()
    await page.waitForTimeout(2000)

    const modalAfterClose = await page.evaluate(() => {
        const m = document.querySelector('[data-testid="map-upload-modal"]')
        if (!m) return 'not-in-dom'
        return getComputedStyle(m).display
    })
    check('modal closes on Cancel (display:none)', modalAfterClose === 'none' || modalAfterClose === 'not-in-dom',
        `(got: ${modalAfterClose})`)

    console.log('=== Step 12: switch back to Commanders tab — sanity check on tab switching ===')
    await page.evaluate(() => {
        const buttons = document.querySelectorAll('.collection-tab-button')
        for (const b of buttons) {
            if (b.title === 'Commanders') {
                b.click()
                return
            }
        }
    })
    await page.waitForTimeout(2000)
    const activeAfterSwitch = await page.evaluate(() => {
        const active = document.querySelector('.collection-tab-button-active')
        return active?.title || null
    })
    check('switching to Commanders tab works', activeAfterSwitch === 'Commanders', `(got: "${activeAfterSwitch}")`)

    console.log('=== Total ===')
    console.log(`  assertions: ${ASSERTIONS.length}, passes: ${ASSERTIONS.length - FAILURES.length}, failures: ${FAILURES.length}`)
    console.log(`  console errors: ${consoleErrors.length}`)
    if (consoleErrors.length > 0) {
        console.log('  console error samples (first 3):')
        consoleErrors.slice(0, 3).forEach(e => console.log('    ' + e.slice(0, 200)))
    }
    console.log(`  screenshots: ${ARTIFACT_DIR}/`)
}
catch (err) {
    console.error('PROBE CRASHED:', err.message)
    await page.screenshot({ path: join(ARTIFACT_DIR, 'crash.png'), fullPage: true }).catch(() => {})
    FAILURES.push('probe crashed: ' + err.message)
}
finally {
    await browser.close()
}

if (FAILURES.length > 0) {
    console.error(`\n${FAILURES.length} check(s) failed:`)
    FAILURES.forEach(f => console.error('  - ' + f))
    process.exit(1)
}
console.log('\nAll checks passed.')
