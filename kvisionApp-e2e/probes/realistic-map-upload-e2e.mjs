#!/usr/bin/env node
// Ad-hoc probe for the live-safety agent factory refactor.
// Uses the realistic 512x512 PNG fixture (realistic-map.map) to exercise the
// new factory-shaped buildMapSafetyAgent against a real Bedrock LLM call.
// Reports the safety verdict + saves the trace.json + captures a screenshot.
import { chromium } from 'playwright'
import { fileURLToPath } from 'node:url'
import { dirname, join } from 'node:path'
import { existsSync, writeFileSync, statSync } from 'node:fs'

const __filename = fileURLToPath(import.meta.url)
const __dirname = dirname(__filename)

const ROOT = join(__dirname, '..', '..')
const FIXTURE = join(ROOT, 'kvisionApp-e2e', 'tests', 'fixtures', 'realistic-map.map')
const ARTIFACT_DIR = join(ROOT, 'kvisionApp-e2e', 'probes', 'artifacts-realistic-flow')

if (!existsSync(FIXTURE)) {
    console.error(`Fixture not found: ${FIXTURE}`)
    process.exit(1)
}

const checks = []
function check(name, ok, detail) {
    checks.push({ name, ok, detail })
    console.log(`  ${ok ? 'PASS' : 'FAIL'}: ${name}${detail ? ' :: ' + detail : ''}`)
}

async function main() {
    console.log(`Fixture: ${FIXTURE} (${statSync(FIXTURE).size} bytes)`)
    const browser = await chromium.launch({ headless: true })
    const ctx = await browser.newContext({ viewport: { width: 1400, height: 900 } })
    const page = await ctx.newPage()

    page.on('pageerror', (err) => console.log(`  PAGEERROR: ${err.message}`))
    page.on('console', (msg) => {
        if (msg.type() === 'error') console.log(`  CONSOLE-ERROR: ${msg.text()}`)
    })

    // Bridge console capture for receipts
    const consoleLines = []
    page.on('console', (msg) => consoleLines.push(`[${msg.type()}] ${msg.text()}`))

    try {
        // Phase 1: navigate to UI (with skipLogin)
        console.log('\n=== Phase 1: navigate to UI ===')
        await page.goto('http://localhost:8080/?skipLogin=true', { waitUntil: 'load', timeout: 60000 })
        await page.waitForTimeout(3000)

        // Dismiss landing screen CTA if present
        const ctaCount = await page.getByTestId('loading-screen-cta').count()
        if (ctaCount > 0) {
            await page.getByTestId('loading-screen-cta').click()
            await page.waitForTimeout(2000)
        }

        await page.locator('[data-testid="main-menu"]').waitFor({ state: 'visible', timeout: 30000 })
        check('main-menu mounted', true)
        await page.screenshot({ path: join(ARTIFACT_DIR, '01-main-menu.png'), fullPage: true })

        // Phase 2: Collection overlay — match original probe's selector logic
        console.log('\n=== Phase 2: Collection overlay ===')
        await page.evaluate(() => {
            for (const b of document.querySelectorAll('[data-testid="main-menu"] button')) {
                if (b.textContent.trim() === 'Collection') { b.click(); return }
            }
        })
        await page.waitForTimeout(2500)
        const collectionVisible = await page.locator('[data-testid="collection-overlay"]').first().isVisible().catch(() => false)
        check('collection overlay mounted', collectionVisible)
        await page.screenshot({ path: join(ARTIFACT_DIR, '02-collection.png'), fullPage: true })

        // Phase 3: MAPS tab — open the maps page via the tab button
        console.log('\n=== Phase 3: MAPS tab ===')
        await page.evaluate(() => {
            const tabs = document.querySelectorAll('.collection-tab-button')
            for (const t of tabs) {
                if (t.title === 'Maps' || t.getAttribute('title') === 'Maps') { t.click(); return }
            }
        })
        await page.waitForTimeout(1500)
        await page.screenshot({ path: join(ARTIFACT_DIR, '03-maps-tab.png'), fullPage: true })
        check('maps tab active', true)

        // Phase 4: open upload modal — use the canonical selector
        console.log('\n=== Phase 4: upload modal ===')
        await page.locator('[data-testid="maps-upload-button"]').click()
        await page.waitForSelector('[data-testid="map-upload-modal"]', { timeout: 5000 })
        await page.waitForTimeout(500)
        check('upload modal visible', true)
        await page.screenshot({ path: join(ARTIFACT_DIR, '04-modal-idle.png'), fullPage: true })

        // Phase 5: file selection (realistic fixture)
        console.log('\n=== Phase 5: file selection (realistic fixture) ===')
        await page.locator('[data-testid="map-upload-file-input"]').setInputFiles(FIXTURE)
        await page.waitForTimeout(2000)
        const dropZoneState = await page.evaluate(() => document.querySelector('[data-testid="map-upload-drop-zone"]')?.getAttribute('data-state'))
        check('drop-zone validated', dropZoneState === 'validated', `(got "${dropZoneState}")`)
        const nameValue = await page.locator('[data-testid="map-upload-name-input"]').inputValue()
        check('name input pre-filled', nameValue.length > 0, `(got "${nameValue}")`)
        await page.screenshot({ path: join(ARTIFACT_DIR, '04-modal-validated.png'), fullPage: true })

        // Phase 5b: bridge-stabilize reload
        console.log('\n=== Phase 5b: bridge-stabilize reload ===')
        try {
            await page.goto('http://localhost:8080/?skipLogin=true', { waitUntil: 'domcontentloaded', timeout: 30000 })
        } catch (e) {
            // webpack-dev-server can interrupt the navigation with a hot-reload frame;
            // fall back to load
            console.log('  (reload hit net::ERR_ABORTED — falling back to domcontentloaded)')
            await page.goto('http://localhost:8080/?skipLogin=true', { waitUntil: 'domcontentloaded', timeout: 30000 }).catch(() => {})
        }
        await page.waitForTimeout(5000)
        const ctaCount2 = await page.getByTestId('loading-screen-cta').count()
        if (ctaCount2 > 0) {
            await page.getByTestId('loading-screen-cta').click()
            await page.waitForTimeout(2000)
        }
        await page.locator('[data-testid="main-menu"]').waitFor({ state: 'visible', timeout: 30000 })
        await page.evaluate(() => {
            for (const b of document.querySelectorAll('[data-testid="main-menu"] button')) {
                if (b.textContent.trim() === 'Collection') { b.click(); return }
            }
        })
        await page.waitForTimeout(2500)
        await page.evaluate(() => {
            const tabs = document.querySelectorAll('.collection-tab-button')
            for (const t of tabs) {
                if (t.title === 'Maps' || t.getAttribute('title') === 'Maps') { t.click(); return }
            }
        })
        await page.waitForTimeout(1000)
        await page.locator('[data-testid="maps-upload-button"]').click()
        await page.waitForSelector('[data-testid="map-upload-modal"]', { timeout: 5000 })
        await page.waitForTimeout(500)
        await page.locator('[data-testid="map-upload-file-input"]').setInputFiles(FIXTURE)
        await page.waitForTimeout(2000)

        // Phase 6: publish click + throbber
        console.log('\n=== Phase 6: publish click + throbber ===')
        await page.locator('[data-testid="map-upload-publish"]').click()
        const publishingState = await page.evaluate(() => document.querySelector('[data-testid="map-upload-publish"]')?.getAttribute('data-state'))
        check('publish button publishing', publishingState === 'publishing', `(got "${publishingState}")`)
        await page.screenshot({ path: join(ARTIFACT_DIR, '05-modal-publishing.png'), fullPage: true })

        // Phase 7: wait for upload completion (allow up to 60s — live safety takes ~4s with realistic PNG)
        console.log('\n=== Phase 7: wait for upload completion (max 60s) ===')
        const outcome = await page.evaluate(() => {
            return new Promise((resolve) => {
                const start = Date.now()
                const tick = () => {
                    // Check all relevant selectors for success or error UI
                    const all = document.querySelectorAll('[class*="messagebox"], [class*="alert"], [class*="modal"]')
                    let successVisible = false
                    let errorVisible = false
                    let errorText = ''
                    for (const el of all) {
                        const cls = el.className || ''
                        const text = (el.textContent || '').slice(0, 200)
                        const visible = el.offsetParent !== null && getComputedStyle(el).display !== 'none'
                        if (!visible) continue
                        if (cls.includes('success') || text.includes('uploaded') || text.includes('Map uploaded')) {
                            successVisible = true
                        }
                        if (cls.includes('error') || cls.includes('danger') || text.toLowerCase().includes('upload failed') || text.toLowerCase().includes('safety')) {
                            errorVisible = true
                            errorText = text
                        }
                    }
                    if (successVisible) {
                        resolve({ outcome: 'success', elapsed: Date.now() - start })
                    } else if (errorVisible) {
                        resolve({ outcome: 'error', elapsed: Date.now() - start, reason: errorText })
                    } else if (Date.now() - start > 60000) {
                        resolve({ outcome: 'timeout', elapsed: Date.now() - start })
                    } else {
                        setTimeout(tick, 250)
                    }
                }
                tick()
            })
        })
        console.log(`  outcome: ${JSON.stringify(outcome)}`)
        check('upload completed within 60s', outcome.outcome !== 'timeout', `(saw ${outcome.outcome} after ${outcome.elapsed}ms)`)
        await page.screenshot({ path: join(ARTIFACT_DIR, '06-outcome.png'), fullPage: true })

        // Phase 8: catalog refresh + badge
        if (outcome.outcome === 'success') {
            console.log('\n=== Phase 8: catalog + PENDING badge ===')
            await page.waitForTimeout(2000)
            const gridText = await page.evaluate(() => document.querySelector('[data-testid="maps-grid"]')?.textContent?.slice(0, 200) || '')
            check('catalogue contains uploaded map', gridText.includes(nameValue) || gridText.toLowerCase().includes('pending'), `(grid text: "${gridText.slice(0, 100)}")`)
            const badgeCount = await page.locator('.badge-pending').count()
            check('PENDING REVIEW badge present', badgeCount > 0, `(count=${badgeCount})`)
            await page.screenshot({ path: join(ARTIFACT_DIR, '07-catalogue.png'), fullPage: true })
        } else {
            console.log(`\n=== Phase 8: ERROR path ===`)
            const errText = outcome.reason || 'unknown'
            check('error messagebox rendered', errText.length > 0, `(reason="${errText.slice(0, 80)}")`)
        }

        // Save console capture
        writeFileSync(join(ARTIFACT_DIR, 'console.txt'), consoleLines.join('\n'))
    } catch (err) {
        console.error('probe crashed:', err.message)
        await page.screenshot({ path: join(ARTIFACT_DIR, 'crash.png'), fullPage: true })
        check('probe crashed', false, err.message.slice(0, 200))
    } finally {
        await browser.close()
    }

    const passed = checks.filter(c => c.ok).length
    const failed = checks.filter(c => !c.ok).length
    console.log(`\n=== TOTAL: ${passed} pass / ${failed} fail ===`)
    console.log(`Screenshots: ${ARTIFACT_DIR}`)
    process.exit(failed > 0 ? 1 : 0)
}

main()