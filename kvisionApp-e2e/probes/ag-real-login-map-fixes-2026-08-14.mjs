#!/usr/bin/env node
// kvisionApp-e2e/probes/ag-real-login-map-fixes-2026-08-14.mjs
//
// COMPREHENSIVE real-AccelByte-login end-to-end probe.
//
// Scenario:
//   1. Real AccelByte guest OAuth login (uses the test guest account in
//      LoginWidgets.kt:63-64, NOT ?skipLogin). The server returns a real
//      UUID accelbyteId, e.g. "<REDACTED_USER_ID>".
//   2. Open Collection -> Maps tab -> upload fixture -> wait for success ->
//      assert card rendered + thumb has literal data URL (NOT %3B).
//   3. CLOSE the entire browser context (simulates logout + browser quit).
//   4. New context. Real AccelByte guest login again (same guest account).
//   5. Open Collection -> Maps tab -> assert saved map card is there AND
//      thumbnail renders. This is the operator's exact bug:
//      "after saving, and logging in does it suddenly not pull down my
//       saved map but did prior".
//
// All four bugs are exercised:
//   Bug #1 (login+reload pulls down): phase C
//   Bug #2 (thumbnail visual):       B5, C2 screenshots
//   Bug #3 (downsample + token):     exercised in B (gate enforces
//                                     pre-safety downsample)
//   Bug #4 (thumbnail render):       B4/B5 + C2/C3 assertions
//
// Required: server-extend :7070, game-server :9080, webpack :8080.

import { chromium } from 'playwright'
import { writeFile, mkdir } from 'node:fs/promises'
import { existsSync, statSync } from 'node:fs'
import { join, dirname } from 'node:path'
import { fileURLToPath } from 'node:url'

const __filename = fileURLToPath(import.meta.url)
const __dirname = dirname(__filename)
const ROOT = join(__dirname, '..', '..')
const FIXTURE = join(ROOT, 'kvisionApp-e2e', 'tests', 'fixtures', 'realistic-map.map')
const ARTIFACT_DIR = '/tmp/ag-real-login-fix-2026-08-14'

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
        text.includes('Fail to fetch') ||
        text.includes('blocked by CORS') ||
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

async function performRealLogin(page, name)
{
    console.log('  navigate to /index.html (no skipLogin)')
    await page.goto('http://127.0.0.1:8080/index.html')
    console.log('  click LoadingScreen CTA')
    const cta = page.getByTestId('loading-screen-cta')
    await cta.waitFor({ state: 'visible', timeout: 30000 })
    await cta.click()
    console.log('  wait for LoginPage')
    await page.locator('.login-widget-window').waitFor({ state: 'visible', timeout: 30000 })
    await page.waitForTimeout(1500)
    console.log('  click Login As Guest')
    const guestButton = page.getByTestId('login-as-guest')
        .or(page.getByRole('button', { name: 'Login As Guest' }))
    await guestButton.first().waitFor({ state: 'visible', timeout: 10000 })
    await guestButton.first().click()
    console.log('  wait for MainMenu mount (max 90s)')
    const mounted = await waitForMainMenu(page, 90000)
    if(!mounted)
    {
        await page.screenshot({ path: join(ARTIFACT_DIR, name + '-login-stuck.png'), fullPage: true }).catch(() => {})
        throw new Error('MainMenu PLAY button never appeared within 90s')
    }
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

async function waitForUploadOutcome(page, timeoutMs)
{
    const start = Date.now()
    while(Date.now() - start < timeoutMs)
    {
        const status = await page.evaluate(() =>
        {
            const successText = Array.from(document.querySelectorAll('div'))
                .find(el => el.textContent && el.textContent.includes('Map uploaded') && el.offsetParent !== null)
            const errorText = Array.from(document.querySelectorAll('div'))
                .find(el => el.textContent && el.textContent.includes('Upload failed') && el.offsetParent !== null)
            if(successText) return { outcome: 'success' }
            if(errorText) return { outcome: 'error', reason: errorText.textContent }
            return { outcome: 'pending' }
        })
        if(status.outcome !== 'pending') return status
        await page.waitForTimeout(300)
    }
    return { outcome: 'timeout' }
}

async function probeThumb(page)
{
    return await page.evaluate(() =>
    {
        const thumb = document.querySelector('.co-thumb')
        if(!thumb) return { exists: false }
        const cs = window.getComputedStyle(thumb)
        return {
            exists: true,
            dataUrlLength: cs.backgroundImage.length,
            hasDataUrl: cs.backgroundImage.includes('data:image/png;base64,'),
            hasPercentEncodedPrefix: cs.backgroundImage.includes('image/png%3B'),
            hasIcon: !!thumb.querySelector('i')
        }
    })
}

async function main()
{
    let savedMapId = null
    let firstAccelbyteId = null
    let firstDisplayName = null
    let secondAccelbyteId = null
    let secondDisplayName = null
    let cardCountAfterReload = 0
    const consoleAll = []
    const consoleErrors = []

    const browser = await chromium.launch({ headless: true })
    try
    {
        const ctx1 = await browser.newContext({ viewport: { width: 1400, height: 900 } })
        const page = await ctx1.newPage()
        page.on('pageerror', e =>
        {
            const t = '' + e.message
            if(!isPreExistingNetworkError(t)) consoleErrors.push(t)
        })
        page.on('console', m =>
        {
            const t = m.text()
            consoleAll.push('[' + m.type() + '] ' + t)
            if(m.type() === 'error' && !isPreExistingNetworkError(t)) consoleErrors.push(t)
        })

        console.log('\n=== PHASE A: REAL AccelByte OAuth guest login ===')
        await performRealLogin(page, 'A')
        const liveA = await getAccelbyteId(page)
        firstAccelbyteId = liveA.accelbyteUserId
        firstDisplayName = liveA.accelbyteDisplayName
        console.log('  accelbyteUserId: ' + firstAccelbyteId)
        console.log('  accelbyteDisplayName: ' + firstDisplayName)
        check('A1: MainMenu mounted after real login',
            liveA.accelbyteUserId !== null,
            'got accelbyteId=' + firstAccelbyteId)
        check('A2: real accelbyteId is not the "guest-user" placeholder',
            firstAccelbyteId !== null && firstAccelbyteId !== 'guest-user' && firstAccelbyteId.length > 20,
            'got "' + firstAccelbyteId + '" -- UUID-shaped = real AccelByte user')
        check('A3: display name is non-empty',
            !!firstDisplayName && firstDisplayName.length > 0,
            'got "' + firstDisplayName + '"')
        await page.screenshot({ path: join(ARTIFACT_DIR, 'A1-after-real-login.png'), fullPage: true })

        console.log('\n=== PHASE B: upload fixture on real accelbyteId partition ===')
        await openCollectionMaps(page)
        await page.waitForTimeout(1500)
        await page.screenshot({ path: join(ARTIFACT_DIR, 'B1-maps-tab-empty.png'), fullPage: true })

        await page.locator('[data-testid="maps-upload-button"]').click()
        await page.waitForSelector('[data-testid="map-upload-modal"]', { timeout: 5000 })
        await page.waitForTimeout(500)
        await page.locator('[data-testid="map-upload-file-input"]').setInputFiles(FIXTURE)
        await page.waitForTimeout(2000)
        const dropZoneState = await page.evaluate(() =>
            document.querySelector('[data-testid="map-upload-drop-zone"]')?.getAttribute('data-state'))
        check('B1: drop-zone validated', dropZoneState === 'validated', 'got "' + dropZoneState + '"')

        await page.locator('[data-testid="map-upload-publish"]').click()
        await page.waitForTimeout(2000)
        await page.screenshot({ path: join(ARTIFACT_DIR, 'B2-modal-publishing.png'), fullPage: true })
        const outcome = await waitForUploadOutcome(page, 120000)
        console.log('  upload outcome: ' + JSON.stringify(outcome))
        check('B2: upload succeeded (safety passed + AGS stored)',
            outcome.outcome === 'success',
            'saw ' + outcome.outcome + (outcome.reason ? ' reason=' + outcome.reason.slice(0, 100) : ''))
        await page.screenshot({ path: join(ARTIFACT_DIR, 'B3-outcome.png'), fullPage: true })

        // B2.5 (bug fix 2026-08-14): the "Map uploaded" success MessageBox
        // MUST have a visible OK button. Pre-fix: only auto-hide timer,
        // user had no dismiss control.
        const okButtonPresent = await page.evaluate(() =>
        {
            const titleDivs = Array.from(document.querySelectorAll('h3'))
                .filter(h => h.textContent && h.textContent.includes('Map uploaded') && h.offsetParent !== null)
            if (titleDivs.length === 0)
            {
                return { autoHideFired: true, present: false, buttonCount: 0 }
            }
            const titleEl = titleDivs[0]
            // MessageBox wraps content in rootPanel; walk up to find the
            // root overlay div (the visible<MessageBox root>).
            let overlay = titleEl
            while (overlay && overlay.parentElement && !overlay.classList.contains('autogenesis-message-box-overlay'))
            {
                overlay = overlay.parentElement
            }
            const root = overlay || document.querySelector('.autogenesis-message-box-overlay')
            if (!root) return { present: false, buttonCount: 0 }
            const buttons = Array.from(root.querySelectorAll('button'))
                .filter(b => b.offsetParent !== null)
            return {
                present: true,
                buttonCount: buttons.length,
                buttonTexts: buttons.map(b => (b.textContent || '').trim())
            }
        })
        check('B2.5: success modal has visible OK button (regression 2026-08-14)',
            okButtonPresent.present === true && okButtonPresent.buttonCount >= 1,
            'present=' + okButtonPresent.present + ', buttonCount=' + okButtonPresent.buttonCount + ', texts=' + JSON.stringify(okButtonPresent.buttonTexts))
        await page.screenshot({ path: join(ARTIFACT_DIR, 'B3.5-success-with-ok-button.png'), fullPage: true })

        await page.waitForTimeout(5000)
        const cardCountAfter = await page.locator('[data-map-id]').count()
        check('B3: card rendered after upload (auto-refresh)', cardCountAfter >= 1, 'count=' + cardCountAfter)
        await page.screenshot({ path: join(ARTIFACT_DIR, 'B4-after-upload-cards.png'), fullPage: true })

        await page.waitForTimeout(5000)
        const thumbInfo = await probeThumb(page)
        check('B4: card thumb has literal data URL (NOT %3B-encoded)',
            thumbInfo.hasDataUrl === true && thumbInfo.hasPercentEncodedPrefix === false,
            'dataUrl=' + thumbInfo.dataUrlLength + 'chars, hasPercentEncoded=' + thumbInfo.hasPercentEncodedPrefix)
        check('B5: card thumb placeholder icon removed',
            thumbInfo.hasIcon === false,
            'iconPresent=' + thumbInfo.hasIcon)
        await page.screenshot({ path: join(ARTIFACT_DIR, 'B5-thumb-rendered.png'), fullPage: true })

        savedMapId = await page.evaluate(() =>
        {
            const card = document.querySelector('[data-map-id]')
            return card ? card.getAttribute('data-map-id') : null
        })
        console.log('  savedMapId: ' + savedMapId)
        await writeFile(join(ARTIFACT_DIR, 'saved-map-id.txt'), savedMapId || 'null')

        await ctx1.close()
        console.log('  PHASE A+B done. Context closed (simulates full logout).')

        console.log('\n=== PHASE C: New context. Real AccelByte login AGAIN. Reopen Collection. ===')
        const ctx2 = await browser.newContext({ viewport: { width: 1400, height: 900 } })
        const page2 = await ctx2.newPage()
        page2.on('pageerror', e =>
        {
            const t = '' + e.message
            if(!isPreExistingNetworkError(t)) consoleErrors.push(t)
        })
        page2.on('console', m =>
        {
            const t = m.text()
            consoleAll.push('[' + m.type() + '] ' + t)
            if(m.type() === 'error' && !isPreExistingNetworkError(t)) consoleErrors.push(t)
        })

        await performRealLogin(page2, 'C')
        const liveC = await getAccelbyteId(page2)
        secondAccelbyteId = liveC.accelbyteUserId
        secondDisplayName = liveC.accelbyteDisplayName
        console.log('  re-login accelbyteId: ' + secondAccelbyteId)
        check('C1: re-login accelbyteId MATCHES first login',
            firstAccelbyteId !== null && secondAccelbyteId === firstAccelbyteId,
            'first=' + firstAccelbyteId + ', re-login=' + secondAccelbyteId)

        await openCollectionMaps(page2)
        await page2.waitForTimeout(3000)
        await page2.screenshot({ path: join(ARTIFACT_DIR, 'C1-relogin-maps-tab.png'), fullPage: true })

        cardCountAfterReload = await page2.locator('[data-map-id]').count()
        check('C2: saved map pulled down after logout+relogin',
            cardCountAfterReload >= 1,
            'count=' + cardCountAfterReload + ', savedMapId was ' + savedMapId)

        if(cardCountAfterReload >= 1)
        {
            await page2.waitForTimeout(5000)
            const reloadThumbInfo = await probeThumb(page2)
            check('C3: re-login thumb has literal data URL',
                reloadThumbInfo.hasDataUrl === true && reloadThumbInfo.hasPercentEncodedPrefix === false,
                'dataUrl=' + reloadThumbInfo.dataUrlLength + 'chars, hasPercentEncoded=' + reloadThumbInfo.hasPercentEncodedPrefix)
            check('C4: re-login thumb placeholder icon removed',
                reloadThumbInfo.hasIcon === false,
                'iconPresent=' + reloadThumbInfo.hasIcon)
            await page2.screenshot({ path: join(ARTIFACT_DIR, 'C2-relogin-thumb-rendered.png'), fullPage: true })
        }

        await ctx2.close()
    }
    catch(err)
    {
        console.error('probe crashed:', err && err.message)
        check('probe crashed', false, err && err.message ? err.message.slice(0, 200) : 'unknown')
    }
    finally
    {
        try
        {
            await browser.close()
        }
        catch(_)
        {
        }

        try
        {
            await writeFile(join(ARTIFACT_DIR, 'console.txt'), consoleAll.join('\n'))
            await writeFile(join(ARTIFACT_DIR, 'console-errors.txt'), consoleErrors.join('\n'))
            await writeFile(join(ARTIFACT_DIR, 'saved-state.json'), JSON.stringify({
                firstLoginAccelbyteId: firstAccelbyteId,
                firstLoginDisplayName: firstDisplayName,
                savedMapId: savedMapId,
                reLoginAccelbyteId: secondAccelbyteId,
                reLoginDisplayName: secondDisplayName,
                cardCountAfterReload: cardCountAfterReload
            }, null, 2))
        }
        catch(_)
        {
        }

        const passed = checks.filter(c => c.ok).length
        const failed = checks.filter(c => !c.ok).length
        console.log('\n=== TOTAL: ' + passed + ' pass / ' + failed + ' fail ===')
        console.log('Non-pre-existing console errors: ' + consoleErrors.length)
        if(consoleErrors.length > 0)
        {
            console.log('--- console errors ---')
            for(const e of consoleErrors.slice(0, 5)) console.log('  ' + e)
        }
        console.log('Screenshots: ' + ARTIFACT_DIR)
        process.exit(failed > 0 ? 1 : 0)
    }
}

main().catch(e =>
{
    console.error('top-level crash:', e)
    process.exit(2)
})