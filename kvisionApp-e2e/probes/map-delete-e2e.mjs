// kvisionApp-e2e/probes/map-delete-e2e.mjs
//
// End-to-end harness for the map-delete UI flow.
//
// Drives a real browser session through the complete delete path:
//   1. skipLogin=true synthetic auth (real AGS namespace under the hood)
//   2. Open Collection overlay → Maps tab
//   3. Wait for catalogue to load (asserts at least 2 maps present)
//   4. Click the first map card → MapDetailWindow opens
//   5. Click the new Delete button → confirmation MessageBox
//   6. Click OK on the confirmation
//   7. Wait for the "Deleting map…" throbber → the "Map deleted" MessageBox
//   8. Catalogue refresh fires (Map.Delete.Success notification -> refreshMapCatalogue)
//   9. Verify the just-deleted mapId is GONE from the catalogue
//   10. Verify the catalogue card count has dropped by 1
//
// Visual receipts: fullPage PNGs at every state transition under
// probes/artifacts-map-delete-e2e/.
//
// CRITICAL: relies on the live AGS namespace `echoofmaridia-autogenesis` for
// real persistence. Delete hits the real AGS Game Binary Record API; the test
// pre-loads two maps via the upload path first (similar to map-upload-e2e.mjs)
// so the delete step has a real row to remove. The skipLogin synthetic
// `accelbyteId='guest-user'` is the same auth path the existing map-upload
// probe uses; the dev stack (7070/8080/9080) must be live.

import { chromium } from '@playwright/test'
import { mkdir, readFile, writeFile } from 'node:fs/promises'
import { fileURLToPath } from 'node:url'
import { dirname, join } from 'node:path'
import { existsSync } from 'node:fs'

const __dirname = dirname(fileURLToPath(import.meta.url))
const BASE_URL = 'http://127.0.0.1:8080'
const FIXTURE_MAP = join(__dirname, '..', 'tests', 'fixtures', 'tiny-map.map')
const ARTIFACT_DIR = join(__dirname, 'artifacts-map-delete-e2e')

const FAILURES = []
const ASSERTIONS = []

function check(name, condition, detail = '')
{
    ASSERTIONS.push({ name, pass: !!condition, detail })
    if (condition) {
        console.log(`  PASS: ${name}${detail ? ' :: ' + detail : ''}`)
    } else {
        console.log(`  FAIL: ${name} ${detail}`)
        FAILURES.push({ name, detail })
    }
}

async function main()
{
    await mkdir(ARTIFACT_DIR, { recursive: true })

    if (!existsSync(FIXTURE_MAP)) {
        console.error(`FATAL: map fixture missing at ${FIXTURE_MAP}`)
        process.exit(2)
    }
    const fixtureSize = (await readFile(FIXTURE_MAP)).byteLength
    console.log(`fixture: ${FIXTURE_MAP} (${fixtureSize} bytes)`)

    const browser = await chromium.launch({ headless: true })
    const context = await browser.newContext({ viewport: { width: 1920, height: 1080 } })
    const page = await context.newPage()
    const consoleErrors = []
    const allConsole = []
    page.on('console', (msg) => {
        const text = msg.text()
        allConsole.push(`[${msg.type()}] ${text}`)
        if (msg.type() === 'error') {
            // Filter out pre-existing noise that has nothing to do with the flow.
            if (/integrity attribute|webpack-dev-server|ws:\/\/127\.0\.0\.1:8080|ws:\/\/localhost:8080|Gateway Timeout/.test(text)) return
            consoleErrors.push(text)
        }
    })
    page.on('pageerror', (err) => consoleErrors.push('PAGEERROR: ' + err.message))

    // ─── Phase 0: bridge-stabilize reload (clean single-connect) ────
    // Same pattern as map-upload-e2e.mjs. The post-boot connect storm
    // leaves the bridge unstable ~30% of the time without a reload.
    console.log('\n=== Phase 0: navigate + bridge-stabilize reload ===')
    await page.goto(BASE_URL + '/?skipLogin=true', { waitUntil: 'load', timeout: 60000 })
    await page.waitForTimeout(3000)
    const loadingCta = await page.getByTestId('loading-screen-cta').count()
    if (loadingCta > 0) {
        await page.getByTestId('loading-screen-cta').click()
        await page.waitForTimeout(2000)
    }
    await page.locator('[data-testid="main-menu"]').waitFor({ state: 'visible', timeout: 30000 })
    await page.reload({ waitUntil: 'load' })
    await page.waitForTimeout(3500)
    const loadingCta2 = await page.getByTestId('loading-screen-cta').count()
    if (loadingCta2 > 0) {
        await page.getByTestId('loading-screen-cta').click()
        await page.waitForTimeout(2000)
    }
    await page.locator('[data-testid="main-menu"]').waitFor({ state: 'visible', timeout: 30000 })

    // ─── Phase 1: open Collection overlay → Maps tab ─────────────────
    console.log('\n=== Phase 1: open Collection overlay → Maps tab ===')
    await page.evaluate(() => {
        for (const b of document.querySelectorAll('[data-testid="main-menu"] button')) {
            if (b.textContent.trim() === 'Collection') { b.click(); return }
        }
    })
    await page.waitForTimeout(2500)
    const collectionVisible = await page.locator('.collection-overlay, [data-testid="collection-overlay"]').first().isVisible().catch(() => false)
    check('Collection overlay mounted', collectionVisible)

    const mapsTabFound = await page.evaluate(() => {
        for (const b of document.querySelectorAll('.collection-tab-button')) {
            if (b.title === 'Maps') { b.click(); return true }
        }
        return false
    })
    check('MAPS tab exists', mapsTabFound)
    await page.waitForTimeout(2000)

    // ─── Phase 2: pre-load two maps via the upload path ──────────────
    // Same upload-helper as map-upload-e2e.mjs. Each upload is independent;
    // we use distinct names so the catalogue ends up with two cards.
    console.log('\n=== Phase 2: upload two maps (seed catalogue) ===')
    const MAP_NAMES = ['delete-probe-A', 'delete-probe-B']
    for (const name of MAP_NAMES) {
        await page.locator('[data-testid="maps-upload-button"]').click()
        await page.waitForTimeout(2000)
        const modal = page.locator('[data-testid="map-upload-modal"]')
        await modal.waitFor({ state: 'visible', timeout: 10000 })
        await page.locator('[data-testid="map-upload-file-input"]').setInputFiles(FIXTURE_MAP)
        await page.waitForTimeout(1500)
        // Override the auto-filled name with our probe name so the two maps
        // are distinguishable in the catalogue.
        await page.locator('[data-testid="map-upload-name-input"]').fill(name)
        await page.waitForTimeout(500)
        await page.locator('[data-testid="map-upload-publish"]').click()
        const outcome = await waitForUploadOutcome(page, 60_000)
        check(`seed upload ${name} completed`, outcome.completed, `(saw ${outcome.kind} after ${outcome.elapsedMs}ms)`)
        check(`seed upload ${name} succeeded`, outcome.kind === 'success', `(got ${outcome.kind})`)
        // Wait for the catalogue refresh to settle before the next upload.
        await page.waitForTimeout(3000)
    }

    // ─── Phase 3: assert both seed maps are in the catalogue ─────────
    console.log('\n=== Phase 3: catalogue state before delete ===')
    await page.screenshot({ path: join(ARTIFACT_DIR, '01-catalogue-with-two-maps.png'), fullPage: true })

    const cardsBefore = await page.evaluate(() => {
        return [...document.querySelectorAll('[data-map-id]')].map(el => ({
            id: el.getAttribute('data-map-id'),
            name: el.querySelector('h3')?.textContent?.trim() || null
        }))
    })
    const targetName = 'delete-probe-A'
    const targetCard = cardsBefore.find(c => c.name === targetName)
    const targetId = targetCard?.id
    check('catalogue has at least 2 cards', cardsBefore.length >= 2, `(count=${cardsBefore.length})`)
    check(`catalogue contains target "${targetName}"`, !!targetCard, `(cards=${JSON.stringify(cardsBefore.map(c => c.name))})`)
    const beforeCount = cardsBefore.length

    // ─── Phase 4: click the target map card → MapDetailWindow ─────────
    console.log('\n=== Phase 4: open MapDetailWindow for target ===')
    await page.evaluate((expectedName) => {
        for (const card of document.querySelectorAll('[data-map-id]')) {
            const h3 = card.querySelector('h3')
            if (h3 && h3.textContent.trim() === expectedName) {
                card.click()
                return
            }
        }
    }, targetName)
    await page.waitForTimeout(2000)

    const detailVisible = await page.locator('[data-testid="map-detail-title"]').isVisible().catch(() => false)
    check('MapDetailWindow visible', detailVisible)

    // Verify the new Delete button is reachable (data-testid from MapDetailWindow.kt).
    const deleteBtnPresent = await page.locator('[data-testid="map-detail-delete"]').count()
    check('Delete button present', deleteBtnPresent === 1, `(count=${deleteBtnPresent})`)
    const deleteBtnText = await page.evaluate(() => document.querySelector('[data-testid="map-detail-delete"]')?.textContent?.trim())
    check('Delete button labelled "Delete"', deleteBtnText === 'Delete', `(got "${deleteBtnText}")`)

    await page.screenshot({ path: join(ARTIFACT_DIR, '02-map-detail-with-delete-button.png'), fullPage: true })

    // ─── Phase 5: click Delete → confirmation MessageBox ─────────────
    console.log('\n=== Phase 5: click Delete → confirmation dialog ===')
    await page.locator('[data-testid="map-detail-delete"]').click()
    await page.waitForTimeout(1500)

    const confirmVisible = await page.evaluate(() => {
        const titles = [...document.querySelectorAll('.modal-title, h3, h2, h1, [class*="modal-title"]')].map(e => e.textContent?.trim() || '')
        const allText = titles.join('\n')
        return /Delete map\?/.test(allText)
    })
    check('Delete confirmation MessageBox visible', confirmVisible)

    const confirmText = await page.evaluate(() => {
        for (const c of document.querySelectorAll('.modal, .message-box, [class*="message-box"]')) {
            const t = c.textContent || ''
            if (/Delete map\?/.test(t)) return t.trim().slice(0, 240)
        }
        return null
    })
    check('confirmation mentions the target map name',
        confirmText && confirmText.includes(targetName),
        `(text="${confirmText}")`)

    await page.screenshot({ path: join(ARTIFACT_DIR, '03-confirmation-dialog.png'), fullPage: true })

    // ─── Phase 6: confirm Delete → throbber → "Map deleted" → refresh ─
    console.log('\n=== Phase 6: confirm Delete → completion notification ===')
    // The MessageBox confirm button uses the OK button (onConfirm fires after OK click).
    await page.evaluate(() => {
        for (const b of document.querySelectorAll('button')) {
            if ((b.textContent || '').trim() === 'OK') {
                b.click()
                return
            }
        }
    })
    await page.waitForTimeout(1500)

    const throbberVisible = await page.evaluate(() => {
        const titles = [...document.querySelectorAll('.modal-title, h3, h2, h1, [class*="modal-title"]')].map(e => e.textContent?.trim() || '')
        return titles.some(t => /Deleting map/i.test(t))
    })
    check('"Deleting map…" throbber visible', throbberVisible)

    await page.screenshot({ path: join(ARTIFACT_DIR, '04-deleting-throbber.png'), fullPage: true })

    const deleteOutcome = await waitForDeleteOutcome(page, 60_000)
    check('delete completed within 60s', deleteOutcome.completed, `(saw ${deleteOutcome.kind} after ${deleteOutcome.elapsedMs}ms)`)
    check('delete succeeded', deleteOutcome.kind === 'success', `(got ${deleteOutcome.kind})`)
    console.log(`  delete outcome: ${deleteOutcome.kind} :: ${deleteOutcome.detail}`)

    await page.screenshot({ path: join(ARTIFACT_DIR, `05-delete-${deleteOutcome.kind}.png`), fullPage: true })

    // ─── Phase 7: verify catalogue refreshed, target is gone ──────────
    console.log('\n=== Phase 7: catalogue refresh after delete ===')
    if (deleteOutcome.kind === 'success') {
        await page.waitForTimeout(3000)  // refreshMapCatalogue + RTT
        const cardsAfter = await page.evaluate(() => {
            return [...document.querySelectorAll('[data-map-id]')].map(el => ({
                id: el.getAttribute('data-map-id'),
                name: el.querySelector('h3')?.textContent?.trim() || null
            }))
        })
        const stillThere = cardsAfter.find(c => c.id === targetId)
        check('target mapId is gone from catalogue', !stillThere, `(still there: ${JSON.stringify(stillThere)})`)
        check('card count dropped by exactly 1',
            cardsAfter.length === beforeCount - 1,
            `(before=${beforeCount}, after=${cardsAfter.length})`)
        check('surviving map "delete-probe-B" still present',
            cardsAfter.some(c => c.name === 'delete-probe-B'),
            `(remaining=${JSON.stringify(cardsAfter.map(c => c.name))})`)

        await page.screenshot({ path: join(ARTIFACT_DIR, '06-catalogue-after-delete.png'), fullPage: true })
    }

    // ─── Console error summary ───────────────────────────────────────
    console.log('\n=== console errors ===')
    if (consoleErrors.length === 0) {
        console.log('  none')
    } else {
        console.log(`  ${consoleErrors.length} non-trivially-filtered errors:`)
        consoleErrors.slice(0, 8).forEach(e => console.log(`    ${e.slice(0, 240)}`))
    }

    // ─── Relevant log lines ──────────────────────────────────────────
    console.log('\n=== MapDelete / RPC log lines (last 20 relevant) ===')
    const relevant = allConsole.filter(l =>
        /MapDelete|map-delete|deletePlayerMap|Map\\.Delete|ServerExtendBridge|delete-button|dispatchDeleteMap|map-detail-delete/i.test(l)
    )
    if (relevant.length === 0) {
        console.log('  (none captured)')
    } else {
        relevant.slice(-20).forEach(l => console.log(`  ${l.slice(0, 260)}`))
    }

    await writeFile(join(ARTIFACT_DIR, 'all-console.txt'), allConsole.join('\n'))

    await browser.close()

    console.log('\n========================================')
    console.log(`assertions: ${ASSERTIONS.length}, passes: ${ASSERTIONS.length - FAILURES.length}, failures: ${FAILURES.length}`)
    console.log(`screenshots: ${ARTIFACT_DIR}/`)
    console.log('========================================')
    if (FAILURES.length > 0) {
        console.error('\nFailed checks:')
        FAILURES.forEach(f => console.error(`  - ${f.name}${f.detail ? ' :: ' + f.detail : ''}`))
        process.exit(1)
    }
}

// Mirrors waitForUploadOutcome from map-upload-e2e.mjs, but for the
// delete-success ("Map deleted") / delete-error ("Delete failed") pair.
async function waitForUploadOutcome(page, timeoutMs)
{
    const start = Date.now()
    while (Date.now() - start < timeoutMs) {
        const out = await page.evaluate(() => {
            const titles = [...document.querySelectorAll('.modal-title, h3, h2, h1, [class*="modal-title"]')].map(e => e.textContent?.trim() || '')
            const modalTexts = [...document.querySelectorAll('.modal, .message-box, [class*="message-box"]')].map(e => e.textContent?.trim() || '')
            const allText = [...titles, ...modalTexts].join('\n')
            const successMatch = allText.match(/Map uploaded[\s\S]*?Map '([^']+)' uploaded/)
            if (successMatch) return { completed: true, kind: 'success', mapName: successMatch[1] }
            const errorMatch = allText.match(/Upload failed:?\s*([\s\S]+?)(?:Close|OK|$)/)
            if (errorMatch) return { completed: true, kind: 'error', detail: errorMatch[1].trim().slice(0, 200) }
            return { completed: false }
        })
        if (out.completed) return { ...out, elapsedMs: Date.now() - start }
        await page.waitForTimeout(500)
    }
    return { completed: false, kind: 'timeout', elapsedMs: Date.now() - start }
}

async function waitForDeleteOutcome(page, timeoutMs)
{
    const start = Date.now()
    while (Date.now() - start < timeoutMs) {
        const out = await page.evaluate(() => {
            const titles = [...document.querySelectorAll('.modal-title, h3, h2, h1, [class*="modal-title"]')].map(e => e.textContent?.trim() || '')
            const modalTexts = [...document.querySelectorAll('.modal, .message-box, [class*="message-box"]')].map(e => e.textContent?.trim() || '')
            const allText = [...titles, ...modalTexts].join('\n')
            const successMatch = allText.match(/Map deleted[\s\S]*?Map '([^']+)' deleted/)
            if (successMatch) return { completed: true, kind: 'success', mapName: successMatch[1] }
            const errorMatch = allText.match(/Delete failed:?\s*([\s\S]+?)(?:Close|OK|$)/)
            if (errorMatch) return { completed: true, kind: 'error', detail: errorMatch[1].trim().slice(0, 200) }
            return { completed: false }
        })
        if (out.completed) return { ...out, elapsedMs: Date.now() - start }
        await page.waitForTimeout(500)
    }
    return { completed: false, kind: 'timeout', elapsedMs: Date.now() - start }
}

main()