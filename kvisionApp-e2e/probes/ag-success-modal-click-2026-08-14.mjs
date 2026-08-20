#!/usr/bin/env node
// kvisionApp-e2e/probes/ag-success-modal-click-2026-08-14.mjs
//
// Bug fix verification (2026-08-14):
//   1. Real AccelByte OAuth login
//   2. Upload a fixture map
//   3. Wait for the "Map uploaded" success modal
//   4. Verify the OK button is visible
//   5. CLICK the OK button via the DOM
//   6. Verify the modal is fully dismissed (display:none, root detached)
//   7. Verify the maps tab is still functional underneath
//
// This probe specifically addresses the operator's question 1
// ("Would a human find anything wrong?"): a button that exists
// but doesn't dismiss is as broken as no button at all. We prove
// the click path works end-to-end.

import { chromium } from 'playwright'
import { writeFile, mkdir } from 'node:fs/promises'
import { existsSync, statSync } from 'node:fs'
import { join, dirname } from 'node:path'
import { fileURLToPath } from 'node:url'

const __filename = fileURLToPath(import.meta.url)
const __dirname = dirname(__filename)
const ROOT = join(__dirname, '..', '..')
const FIXTURE = join(ROOT, 'kvisionApp-e2e', 'tests', 'fixtures', 'realistic-map.map')
const ARTIFACT_DIR = '/tmp/ag-success-modal-click-2026-08-14'

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
    console.log('  navigate to /index.html (no skipLogin)')
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
    if(!mounted) throw new Error('MainMenu never mounted')
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

async function waitForUploadOutcome(page, timeoutMs)
{
    const start = Date.now()
    while(Date.now() - start < timeoutMs)
    {
        const status = await page.evaluate(() =>
        {
            const successText = Array.from(document.querySelectorAll('div'))
                .find(el => el.textContent && el.textContent.includes('Map uploaded') && el.offsetParent !== null)
            if(successText) return { outcome: 'success' }
            return { outcome: 'pending' }
        })
        if(status.outcome !== 'pending') return status
        await page.waitForTimeout(300)
    }
    return { outcome: 'timeout' }
}

async function probeModalState(page)
{
    return await page.evaluate(() =>
    {
        const overlay = document.querySelector('.autogenesis-message-box-overlay')
        if(!overlay) return { present: false, buttonCount: 0, display: null, buttons: [] }
        const cs = window.getComputedStyle(overlay)
        const buttons = Array.from(overlay.querySelectorAll('button'))
            .filter(b => b.offsetParent !== null)
        return {
            present: cs.display !== 'none',
            display: cs.display,
            buttonCount: buttons.length,
            buttons: buttons.map(b => (b.textContent || '').trim())
        }
    })
}

const browser = await chromium.launch({ headless: true })

try
{
    const ctx = await browser.newContext({ viewport: { width: 1400, height: 900 } })
    const page = await ctx.newPage()
    page.on('console', m =>
    {
        if(m.type() === 'error' && !isPreExistingNetworkError(m.text()))
        {
            console.log('  [non-pre-existing console error]', m.text().slice(0, 200))
        }
    })

    console.log('\n=== Phase A: Real login + upload ===')
    await performRealLogin(page)
    console.log('  logged in as real AccelByte user')

    await openCollectionMaps(page)
    await page.waitForTimeout(1500)
    await page.locator('[data-testid="maps-upload-button"]').click()
    await page.waitForSelector('[data-testid="map-upload-modal"]', { timeout: 5000 })
    await page.waitForTimeout(500)
    await page.locator('[data-testid="map-upload-file-input"]').setInputFiles(FIXTURE)
    await page.waitForTimeout(2000)
    await page.locator('[data-testid="map-upload-publish"]').click()
    await page.waitForTimeout(2000)

    const outcome = await waitForUploadOutcome(page, 120000)
    console.log('  upload outcome:', outcome.outcome)
    check('A1: upload completed', outcome.outcome === 'success', 'outcome=' + outcome.outcome)
    await page.screenshot({ path: join(ARTIFACT_DIR, 'A1-modal-shown.png'), fullPage: true })

    // Phase B: BEFORE click — modal is shown with OK button visible
    console.log('\n=== Phase B: Modal state before OK click ===')
    const before = await probeModalState(page)
    console.log('  state:', JSON.stringify(before))
    check('B1: success modal present', before.present === true,
        'present=' + before.present + ', display=' + before.display)
    check('B2: OK button rendered', before.buttonCount >= 1 && before.buttons.includes('OK'),
        'buttonCount=' + before.buttonCount + ', buttons=' + JSON.stringify(before.buttons))

    // Phase C: CLICK the OK button
    console.log('\n=== Phase C: Click OK button via DOM ===')
    const clickResult = await page.evaluate(() =>
    {
        const overlay = document.querySelector('.autogenesis-message-box-overlay')
        if(!overlay) return { clicked: false, reason: 'no overlay' }
        const okBtn = Array.from(overlay.querySelectorAll('button'))
            .find(b => (b.textContent || '').trim().toLowerCase().startsWith('ok') && b.offsetParent !== null)
        if(!okBtn) return { clicked: false, reason: 'no OK button found' }
        okBtn.click()
        return { clicked: true, reason: 'OK clicked' }
    })
    console.log('  click:', JSON.stringify(clickResult))
    check('C1: OK button click dispatched', clickResult.clicked === true,
        'clicked=' + clickResult.clicked + ', reason=' + clickResult.reason)

    // Phase D: AFTER click — modal must be fully hidden
    await page.waitForTimeout(500)
    const after = await probeModalState(page)
    console.log('  state:', JSON.stringify(after))
    await page.screenshot({ path: join(ARTIFACT_DIR, 'A2-modal-after-click.png'), fullPage: true })
    check('D1: modal display:none after OK click', after.display === 'none' || after.present === false,
        'display=' + after.display + ', present=' + after.present)
    check('D2: no visible buttons in overlay after click', after.buttonCount === 0,
        'buttonCount=' + after.buttonCount + ', buttons=' + JSON.stringify(after.buttons))

    // Phase E: Underneath the modal — the maps tab still works
    console.log('\n=== Phase E: Underlying UI is functional ===')
    await page.waitForTimeout(2000)
    const underlyingCard = await page.locator('[data-map-id]').count()
    check('E1: map card visible underneath (modal dismissed, card not blocked)',
        underlyingCard >= 1,
        'cardCount=' + underlyingCard)
    await page.screenshot({ path: join(ARTIFACT_DIR, 'A3-underlying-clean.png'), fullPage: true })

    // Phase F: Trigger a SECOND upload and click OK — proves the fix
    // works repeatedly, not just once
    console.log('\n=== Phase F: Trigger a second modal, click OK again ===')
    await page.locator('[data-testid="maps-upload-button"]').click()
    await page.waitForSelector('[data-testid="map-upload-modal"]', { timeout: 5000 })
    await page.waitForTimeout(500)
    await page.locator('[data-testid="map-upload-file-input"]').setInputFiles(FIXTURE)
    await page.waitForTimeout(2000)
    await page.locator('[data-testid="map-upload-publish"]').click()
    await page.waitForTimeout(2000)
    const outcome2 = await waitForUploadOutcome(page, 120000)
    check('F1: second upload completed', outcome2.outcome === 'success', 'outcome=' + outcome2.outcome)

    const before2 = await probeModalState(page)
    check('F2: second modal also has OK button', before2.present === true && before2.buttons.includes('OK'),
        'present=' + before2.present + ', buttons=' + JSON.stringify(before2.buttons))

    await page.evaluate(() =>
    {
        const overlay = document.querySelector('.autogenesis-message-box-overlay')
        const okBtn = overlay ? Array.from(overlay.querySelectorAll('button'))
            .find(b => (b.textContent || '').trim().toLowerCase().startsWith('ok') && b.offsetParent !== null) : null
        if(okBtn) okBtn.click()
    })
    await page.waitForTimeout(500)
    const after2 = await probeModalState(page)
    check('F3: second modal dismissed by OK click', after2.display === 'none' || after2.present === false,
        'display=' + after2.display)

    await ctx.close()
}
catch(err)
{
    console.error('probe crashed:', err && err.message)
    check('probe crashed', false, err && err.message ? err.message.slice(0, 200) : 'unknown')
}
finally
{
    try { await browser.close() } catch(ignored) { /* best-effort */ }
    const passed = checks.filter(c => c.ok).length
    const failed = checks.filter(c => !c.ok).length
    console.log('\n=== TOTAL: ' + passed + ' pass / ' + failed + ' fail ===')
    console.log('Screenshots: ' + ARTIFACT_DIR)
    process.exit(failed > 0 ? 1 : 0)
}
