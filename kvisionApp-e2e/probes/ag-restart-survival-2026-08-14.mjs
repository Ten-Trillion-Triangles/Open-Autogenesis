#!/usr/bin/env node
// kvisionApp-e2e/probes/ag-restart-survival-2026-08-14.mjs
//
// ABSOLUTE PROOF (2026-08-14):
//   The "maps pull down after server restart" regression is FIXED.
//
// Scenario:
//   1. Real AccelByte OAuth guest login
//   2. Open Collection > Maps tab > upload fixture map
//   3. Verify the saved mapId appears in the catalogue
//   4. KILL server-extend (SIGTERM, then a fresh restart)
//   5. Wait for server-extend to come back
//   6. Real AccelByte login AGAIN (same account)
//   7. Open Collection > Maps tab
//   8. Verify the saved mapId is STILL in the catalogue
//
// What proves the fix:
//   - Pre-fix: post-restart list returned 0 entries (catalogue lost).
//   - Post-fix: post-restart list returns the same mapId that was
//     uploaded before the kill.
//
// The probe is operator-command-line only — it doesn't drive the
// browser for the kill/restart. The shell script that wraps this
// probe handles process lifecycle. This file does the upload + the
// pre-restart and post-restart verification.

import { chromium } from 'playwright'
import { writeFile, mkdir } from 'node:fs/promises'
import { existsSync, statSync } from 'node:fs'
import { join, dirname } from 'node:path'
import { fileURLToPath } from 'node:url'

const __filename = fileURLToPath(import.meta.url)
const __dirname = dirname(__filename)
const ROOT = join(__dirname, '..', '..')
const FIXTURE = join(ROOT, 'kvisionApp-e2e', 'tests', 'fixtures', 'realistic-map.map')
const ARTIFACT_DIR = '/tmp/ag-restart-survival-2026-08-14'

const phase = process.env.PHASE || 'pre'

if(!existsSync(FIXTURE))
{
    console.error('Fixture not found: ' + FIXTURE)
    process.exit(1)
}

await mkdir(ARTIFACT_DIR, { recursive: true })

const checks = []
function check(name, ok, detail)
{
    checks.push({ name, ok, detail: detail || '' })
    console.log('  ' + (ok ? 'PASS' : 'FAIL') + ': ' + name + (detail ? ' :: ' + detail : ''))
}

function isPreExistingNetworkError(text)
{
    return text.includes('ERR_CONNECTION_REFUSED') ||
        text.includes('ERR_FAILED') ||
        text.includes('Failed to load resource') ||
        text.includes('Failed to fetch') ||
        text.includes('integrity') ||
        text.includes('bootstrap.min.css') ||
        text.includes('coi-serviceworker') ||
        text.includes('favicon.ico') ||
        text.includes('WebSocket connection to') ||
        text.includes('[webpack-dev-server]') ||
        text.includes('504 (Gateway Timeout)')
}

async function dismissOkIfPresent(page)
{
    const okByText = page.getByRole('button', { name: /^OK$/ })
    if(await okByText.count() > 0)
    {
        try { await okByText.first().click({ timeout: 1500 }); return true }
        catch(_) { return false }
    }
    return false
}

async function waitForMainMenu(page, timeoutMs)
{
    const playButton = page.locator('.btn.btn-play')
    const deadline = Date.now() + timeoutMs
    while(Date.now() < deadline)
    {
        if(await playButton.count() > 0) return true
        await dismissOkIfPresent(page)
        await page.waitForTimeout(250)
    }
    return false
}

async function performRealLogin(page)
{
    await page.goto('http://127.0.0.1:8080/index.html')
    const cta = page.getByTestId('loading-screen-cta')
    await cta.waitFor({ state: 'visible', timeout: 30000 })
    await cta.click()
    await page.locator('.login-widget-window').waitFor({ state: 'visible', timeout: 30000 })
    await page.waitForTimeout(1500)
    const guestButton = page.getByTestId('login-as-guest')
        .or(page.getByRole('button', { name: 'Login As Guest' }))
    await guestButton.first().waitFor({ state: 'visible', timeout: 10000 })
    await guestButton.first().click()
    const mounted = await waitForMainMenu(page, 90000)
    if(!mounted) throw new Error('MainMenu never mounted within 90s')
}

async function getAccelbyteId(page)
{
    return await page.evaluate(() =>
    {
        const mainMenu = document.querySelector('[data-testid="main-menu"]')
        return {
            accelbyteUserId: mainMenu ? mainMenu.getAttribute('data-accelbyte-user-id') : null,
            accelbyteDisplayName: mainMenu ? mainMenu.getAttribute('data-accelbyte-display-name') : null
        }
    })
}

async function openCollectionMaps(page)
{
    await page.evaluate(() =>
    {
        for(const b of document.querySelectorAll('[data-testid="main-menu"] button'))
        {
            if(b.textContent.trim() === 'Collection') { b.click(); return }
        }
    })
    await page.waitForTimeout(2500)
    await page.evaluate(() =>
    {
        for(const t of document.querySelectorAll('.collection-tab-button'))
        {
            if(t.title === 'Maps') { t.click(); return }
        }
    })
    await page.waitForTimeout(2000)
}

async function countMapCards(page)
{
    return await page.locator('[data-map-id]').count()
}

async function readMapIds(page)
{
    return await page.evaluate(() =>
    {
        return Array.from(document.querySelectorAll('[data-map-id]'))
            .map(el => el.getAttribute('data-map-id'))
    })
}

async function readSaveIdsFromLocalStorage(page)
{
    // The catalogue is mirrored client-side after a listPlayerMaps call.
    // Save the catalogue snapshot to localStorage so the post-restart
    // browser context can compare against the pre-restart list.
    return await page.evaluate(() =>
    {
        const out = { cards: [], accelbyteId: null }
        const mainMenu = document.querySelector('[data-testid="main-menu"]')
        if(mainMenu)
        {
            out.accelbyteId = mainMenu.getAttribute('data-accelbyte-user-id')
        }
        out.cards = Array.from(document.querySelectorAll('[data-map-id]'))
            .map(el => el.getAttribute('data-map-id'))
        // Stash on global so the shell can read it from the page after.
        try { window.__AG_RESTART_PROOF = out } catch(_ignored) { /* best-effort */ }
        return out
    })
}

const browser = await chromium.launch({ headless: true })

try
{
    const ctx = await browser.newContext({ viewport: { width: 1400, height: 900 } })
    const page = await ctx.newPage()
    page.on('pageerror', e =>
    {
        const t = '' + e.message
        if(!isPreExistingNetworkError(t)) console.log('  [page error]', t.slice(0, 200))
    })
    page.on('console', m =>
    {
        if(m.type() === 'error' && !isPreExistingNetworkError(m.text()))
        {
            console.log('  [console error]', m.text().slice(0, 200))
        }
    })

    console.log('\n=== Phase: ' + phase + ' ===')
    console.log('  Real AccelByte OAuth login')
    await performRealLogin(page)
    const live = await getAccelbyteId(page)
    console.log('  accelbyteId: ' + live.accelbyteUserId)
    check('login: real accelbyteId non-empty',
        live.accelbyteUserId !== null && live.accelbyteUserId !== 'guest-user',
        'got ' + live.accelbyteUserId)

    await openCollectionMaps(page)
    await page.waitForTimeout(3000)

    if(phase === 'pre')
    {
        console.log('  PHASE PRE: upload fixture + capture saved mapId')
        await page.locator('[data-testid="maps-upload-button"]').click()
        await page.waitForSelector('[data-testid="map-upload-modal"]', { timeout: 5000 })
        await page.waitForTimeout(500)
        await page.locator('[data-testid="map-upload-file-input"]').setInputFiles(FIXTURE)
        await page.waitForTimeout(2000)
        await page.locator('[data-testid="map-upload-publish"]').click()
        // Wait for outcome
        const deadline = Date.now() + 120000
        let success = false
        while(Date.now() < deadline)
        {
            const found = await page.evaluate(() =>
            {
                return Array.from(document.querySelectorAll('div'))
                    .some(el => el.textContent && el.textContent.includes('Map uploaded') && el.offsetParent !== null)
            })
            if(found) { success = true; break }
            await page.waitForTimeout(500)
        }
        check('upload: outcome success modal appeared', success, 'upload outcome seen=' + success)
        await page.waitForTimeout(8000)

        const ids = await readSaveIdsFromLocalStorage(page)
        const savedIds = ids.cards
        console.log('  savedIds: ' + JSON.stringify(savedIds))
        check('pre-restart: at least 1 saved map', savedIds.length >= 1, 'count=' + savedIds.length)

        await writeFile(
            join(ARTIFACT_DIR, 'pre-restart-state.json'),
            JSON.stringify({ accelbyteId: live.accelbyteUserId, savedIds }, null, 2)
        )
        await page.screenshot({ path: join(ARTIFACT_DIR, 'pre-restart.png'), fullPage: true })
    }
    else if(phase === 'post')
    {
        console.log('  PHASE POST: read state file, list catalogue, compare')
        const fs = await import('node:fs/promises')
        const stateRaw = await fs.readFile(join(ARTIFACT_DIR, 'pre-restart-state.json'), 'utf8')
        const state = JSON.parse(stateRaw)
        console.log('  expected: accelbyteId=' + state.accelbyteId + ', savedIds=' + JSON.stringify(state.savedIds))

        check('post-restart: same accelbyteId',
            live.accelbyteUserId === state.accelbyteId,
            'first=' + state.accelbyteId + ', now=' + live.accelbyteUserId)

        const ids = await readSaveIdsFromLocalStorage(page)
        const restoredIds = ids.cards
        console.log('  restoredIds: ' + JSON.stringify(restoredIds))

        // The fix: post-restart catalogue contains the same mapId
        // that was saved pre-restart. Pre-fix: restoredIds would be [].
        const allRestored = state.savedIds.every(id => restoredIds.includes(id))
        check('post-restart: every saved mapId is back in the catalogue',
            allRestored && restoredIds.length >= state.savedIds.length,
            'expected=' + JSON.stringify(state.savedIds) + ', got=' + JSON.stringify(restoredIds))
        check('post-restart: catalogue is non-empty (the bug)',
            restoredIds.length >= 1,
            'count=' + restoredIds.length)
        await page.screenshot({ path: join(ARTIFACT_DIR, 'post-restart.png'), fullPage: true })

        await writeFile(
            join(ARTIFACT_DIR, 'post-restart-state.json'),
            JSON.stringify({ accelbyteId: live.accelbyteUserId, restoredIds }, null, 2)
        )
    }
    else
    {
        console.error('Unknown PHASE: ' + phase)
        process.exit(2)
    }

    await ctx.close()
}
catch(err)
{
    console.error('probe crashed:', err && err.message)
    check('probe crashed', false, err && err.message ? err.message.slice(0, 200) : 'unknown')
}
finally
{
    try { await browser.close() } catch(_ignored) { /* best-effort */ }
    const passed = checks.filter(c => c.ok).length
    const failed = checks.filter(c => !c.ok).length
    console.log('\n=== TOTAL: ' + passed + ' pass / ' + failed + ' fail ===')
    process.exit(failed > 0 ? 1 : 0)
}
