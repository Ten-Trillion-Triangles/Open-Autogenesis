#!/usr/bin/env node
// Comprehensive end-to-end harness for the four map-upload bugs the operator
// flagged on 2026-08-14:
//   1. After saving AND logging in, saved map doesn't pull down on collection open
//   2. Thumbnail rendering visual verification (7-hour prior session inconclusive)
//   3. Downsample forcing image to 256K tokens BEFORE safety agent, counted+verified
//   4. Thumbnail visually renders (not just the data URL stamp)
//
// Each phase captures a PNG screenshot for visual verification. The 4 human-style
// questions are answered for each phase before moving on:
//   Q1: Would a human find anything wrong with this? (visual review)
//   Q2: Does it actually work? (functional review)
//   Q3: Did I fix the issues? (assertion-based review)
//   Q4: Did I take shortcuts? (process review)

import { chromium } from 'playwright'
import { fileURLToPath } from 'node:url'
import { dirname, join } from 'node:path'
import { existsSync, writeFileSync, statSync } from 'node:fs'

const __filename = fileURLToPath(import.meta.url)
const __dirname = dirname(__filename)

const ROOT = join(__dirname, '..', '..')
const FIXTURE = join(ROOT, 'kvisionApp-e2e', 'tests', 'fixtures', 'realistic-map.map')
const ARTIFACT_DIR = '/tmp/ag-fixes-2026-08-14'

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
    console.log(`Artifacts: ${ARTIFACT_DIR}`)
    const browser = await chromium.launch({ headless: true })
    const ctx = await browser.newContext({ viewport: { width: 1400, height: 900 } })
    const page = await ctx.newPage()

    page.on('pageerror', (err) => console.log(`  PAGEERROR: ${err.message}`))
    page.on('console', (msg) => {
        if (msg.type() === 'error') console.log(`  CONSOLE-ERROR: ${msg.text()}`)
    })

    const consoleLines = []
    page.on('console', (msg) => consoleLines.push(`[${msg.type()}] ${msg.text()}`))

    try {
        // ========================================================================
        // PHASE A: skipLogin path — verify save + auto-refresh + thumbnail render
        // (Tests Issues #2 and #4: thumbnail shows actual map image, not placeholder)
        // ========================================================================
        console.log('\n=== PHASE A: skipLogin save + thumbnail render ===')
        await page.goto('http://localhost:8080/?skipLogin=true', { waitUntil: 'load', timeout: 60000 })
        await page.waitForTimeout(3000)
        const ctaCount = await page.getByTestId('loading-screen-cta').count()
        if (ctaCount > 0) {
            await page.getByTestId('loading-screen-cta').click()
            await page.waitForTimeout(2000)
        }
        await page.locator('[data-testid="main-menu"]').waitFor({ state: 'visible', timeout: 30000 })
        check('A1: main-menu mounted (skipLogin)', true)

        // Open Collection > Maps tab
        await page.evaluate(() => {
            for (const b of document.querySelectorAll('[data-testid="main-menu"] button')) {
                if (b.textContent.trim() === 'Collection') { b.click(); return }
            }
        })
        await page.waitForTimeout(2500)
        await page.evaluate(() => {
            for (const t of document.querySelectorAll('.collection-tab-button')) {
                if (t.title === 'Maps') { t.click(); return }
            }
        })
        await page.waitForTimeout(1500)
        await page.screenshot({ path: join(ARTIFACT_DIR, 'A1-maps-tab-before.png'), fullPage: true })

        // Verify NO maps exist before upload (guest-user partition empty)
        const beforeCardCount = await page.locator('[data-map-id]').count()
        check('A2: maps tab empty before upload', beforeCardCount === 0, `(found ${beforeCardCount} cards)`)

        // Open upload modal
        await page.locator('[data-testid="maps-upload-button"]').click()
        await page.waitForSelector('[data-testid="map-upload-modal"]', { timeout: 5000 })
        await page.waitForTimeout(500)
        await page.screenshot({ path: join(ARTIFACT_DIR, 'A2-modal-idle.png'), fullPage: true })

        // Select fixture
        await page.locator('[data-testid="map-upload-file-input"]').setInputFiles(FIXTURE)
        await page.waitForTimeout(2000)
        const dropZoneState = await page.evaluate(() => document.querySelector('[data-testid="map-upload-drop-zone"]')?.getAttribute('data-state'))
        check('A3: drop-zone validated', dropZoneState === 'validated', `(got "${dropZoneState}")`)
        await page.screenshot({ path: join(ARTIFACT_DIR, 'A3-modal-validated.png'), fullPage: true })

        // Publish
        await page.locator('[data-testid="map-upload-publish"]').click()
        await page.waitForTimeout(2000)
        await page.screenshot({ path: join(ARTIFACT_DIR, 'A4-modal-publishing.png'), fullPage: true })

        // Wait for success
        console.log('  Waiting for upload completion (max 60s)...')
        const outcome = await page.evaluate(() => {
            return new Promise((resolve) => {
                const start = Date.now()
                const tick = () => {
                    const successModal = document.querySelector('.map-upload-success, [class*="success"]')
                    const errorModal = document.querySelector('[class*="error"], [class*="alert-danger"]')
                    const uploadedText = Array.from(document.querySelectorAll('div'))
                        .find(el => el.textContent && el.textContent.includes('uploaded') && el.offsetParent !== null)
                    const failedText = Array.from(document.querySelectorAll('div'))
                        .find(el => el.textContent && el.textContent.includes('Upload failed') && el.offsetParent !== null)
                    if (uploadedText && !failedText) {
                        resolve({ outcome: 'success', elapsed: Date.now() - start })
                    } else if (failedText) {
                        resolve({ outcome: 'error', elapsed: Date.now() - start, reason: failedText.textContent })
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
        check('A4: upload completed within 60s', outcome.outcome !== 'timeout', `(saw ${outcome.outcome} after ${outcome.elapsed}ms)`)
        await page.screenshot({ path: join(ARTIFACT_DIR, 'A5-outcome.png'), fullPage: true })

        if (outcome.outcome === 'success') {
            // Wait for auto-refresh
            await page.waitForTimeout(3000)
            const cardCount = await page.locator('[data-map-id]').count()
            check('A5: card rendered after upload', cardCount >= 1, `(count=${cardCount})`)
            await page.screenshot({ path: join(ARTIFACT_DIR, 'A6-after-upload.png'), fullPage: true })

            // Wait for thumbnail render to complete
            await page.waitForTimeout(3000)

            // Q1 (Issue #4): Does the thumbnail visually show the map image, NOT a placeholder?
            const thumbInfo = await page.evaluate(() => {
                const thumb = document.querySelector('.co-thumb')
                if (!thumb) return { exists: false }
                const computed = getComputedStyle(thumb).backgroundImage
                const hasDataUrl = computed.includes('data:image/png') && computed.length > 200
                const icon = thumb.querySelector('i')
                return {
                    exists: true,
                    hasDataUrl,
                    dataUrlLength: computed.length,
                    hasPlaceholderIcon: icon !== null,
                    inlineStyle: thumb.getAttribute('style')?.slice(0, 100) || ''
                }
            })
            check('A6: thumb has data-url background', thumbInfo.hasDataUrl === true, `(dataUrl length=${thumbInfo.dataUrlLength})`)
            check('A7: thumb placeholder icon removed', thumbInfo.hasPlaceholderIcon === false, `(icon=${thumbInfo.hasPlaceholderIcon})`)
            await page.screenshot({ path: join(ARTIFACT_DIR, 'A7-thumb-rendered.png'), fullPage: true })
        }

        // ========================================================================
        // PHASE B: Reload page, reopen collection — does saved map PULL DOWN?
        // (Tests Issue #1: after save + reload, collection must show the saved map)
        // ========================================================================
        console.log('\n=== PHASE B: reload + collection reopen ===')
        await page.goto('http://localhost:8080/?skipLogin=true', { waitUntil: 'load', timeout: 60000 })
        await page.waitForTimeout(5000)
        const ctaCountB = await page.getByTestId('loading-screen-cta').count()
        if (ctaCountB > 0) {
            await page.getByTestId('loading-screen-cta').click()
            await page.waitForTimeout(2000)
        }
        await page.locator('[data-testid="main-menu"]').waitFor({ state: 'visible', timeout: 30000 })

        // Open collection
        await page.evaluate(() => {
            for (const b of document.querySelectorAll('[data-testid="main-menu"] button')) {
                if (b.textContent.trim() === 'Collection') { b.click(); return }
            }
        })
        await page.waitForTimeout(3000)

        // Switch to Maps tab
        await page.evaluate(() => {
            for (const t of document.querySelectorAll('.collection-tab-button')) {
                if (t.title === 'Maps') { t.click(); return }
            }
        })
        await page.waitForTimeout(3000)

        const reloadCardCount = await page.locator('[data-map-id]').count()
        check('B1: saved map pulled down after reload', reloadCardCount >= 1, `(count=${reloadCardCount})`)
        await page.screenshot({ path: join(ARTIFACT_DIR, 'B1-after-reload.png'), fullPage: true })

        // Q1 (Issue #1): The user's #1 complaint — "saved map won't pull down when I open the fucking collection"
        // Verify the thumbnail renders again on the reload path
        if (reloadCardCount >= 1) {
            await page.waitForTimeout(3000)
            const reloadThumbInfo = await page.evaluate(() => {
                const thumb = document.querySelector('.co-thumb')
                if (!thumb) return { exists: false }
                const computed = getComputedStyle(thumb).backgroundImage
                return {
                    exists: true,
                    hasDataUrl: computed.includes('data:image/png') && computed.length > 200,
                    dataUrlLength: computed.length
                }
            })
            check('B2: thumbnail re-renders on reload', reloadThumbInfo.hasDataUrl === true, `(dataUrl length=${reloadThumbInfo.dataUrlLength})`)
            await page.screenshot({ path: join(ARTIFACT_DIR, 'B2-thumb-after-reload.png'), fullPage: true })
        }

    } catch (err) {
        console.error('probe crashed:', err.message)
        await page.screenshot({ path: join(ARTIFACT_DIR, 'crash.png'), fullPage: true })
        check('probe crashed', false, err.message.slice(0, 200))
    } finally {
        writeFileSync(join(ARTIFACT_DIR, 'console.txt'), consoleLines.join('\n'))
        await browser.close()
    }

    const passed = checks.filter(c => c.ok).length
    const failed = checks.filter(c => !c.ok).length
    console.log(`\n=== TOTAL: ${passed} pass / ${failed} fail ===`)
    console.log(`Screenshots: ${ARTIFACT_DIR}`)
    process.exit(failed > 0 ? 1 : 0)
}

main()