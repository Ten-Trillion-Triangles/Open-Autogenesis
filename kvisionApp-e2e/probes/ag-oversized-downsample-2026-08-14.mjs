#!/usr/bin/env node
// kvisionApp-e2e/probes/ag-oversized-downsample-2026-08-14.mjs
//
// Issue #3 verification probe: prove the downsample actually fires on a
// fixture that is GENUINELY above the 256K-token cap, counts the resulting
// tokens, and asserts the safety agent received an image that fits the
// cap BEFORE running.
//
// Strategy:
//   1. Real AccelByte OAuth login.
//   2. Open Collection > Maps tab.
//   3. Upload kvisionApp-e2e/tests/fixtures/oversized-1024.map
//      (PNG inside is 2,759,177 bytes ~= 1,730,004 tokens at 0.627 t/b,
//       ~6.7x over the 256K cap).
//   4. Wait for the safety decision (~30-60s).
//   5. Read the gate-call.json the server wrote to
//      ${traceDir}/MapUploadGate/gate-call.json — imageBytes must be
//      <= MAX_SAFE_BINARY_BYTES (~408 KB).
//   6. Read trace.json and extract totalInputTokens for the image pipe
//      — must be <= 256,000.
//
// We read server-side files directly because the gate writes them at
// HTTP completion, regardless of whether the client receives the
// notification. This is the ground truth.

import { chromium } from 'playwright'
import { writeFile, mkdir, readFile } from 'node:fs/promises'
import { existsSync, statSync, readFileSync } from 'node:fs'
import { join, dirname } from 'node:path'
import { fileURLToPath } from 'node:url'

const __filename = fileURLToPath(import.meta.url)
const __dirname = dirname(__filename)
const ROOT = join(__dirname, '..', '..')
const FIXTURE = join(ROOT, 'kvisionApp-e2e', 'tests', 'fixtures', 'oversized-1024.map')
const ARTIFACT_DIR = '/tmp/ag-oversized-downsample-2026-08-14'

const FIXTURE_BYTES = statSync(FIXTURE).size
const PNG_BYTES = 2759177
const PNG_EST_TOKENS = Math.round(PNG_BYTES * 0.627)
const MAX_BYTES = Math.floor(256000 / 0.627)  // 408,293 bytes
const CAP_TOKENS = 256000

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

async function readGateArtifacts()
{
    const traceDir = '/home/cage/.tpipe/debug/trace/MapUploadGate'
    const gateCallPath = join(traceDir, 'gate-call.json')
    const tracePath = join(traceDir, 'trace.json')
    if(!existsSync(gateCallPath) || !existsSync(tracePath))
    {
        return { error: 'trace files missing at ' + traceDir }
    }
    const gateCall = JSON.parse(readFileSync(gateCallPath, 'utf8'))
    const trace = JSON.parse(readFileSync(tracePath, 'utf8'))

    // Find the most recent PIPE_SUCCESS events. For the image pipe, the
    // API_CALL_SUCCESS that has totalInputTokens tells us what the
    // safety agent saw.
    const imagePipeApiCalls = trace.filter(e =>
        e.eventType === 'API_CALL_SUCCESS' &&
        e.pipeName === 'image pipe' &&
        typeof e.metadata?.totalInputTokens === 'number')

    const imagePipeInputTokens = imagePipeApiCalls.length > 0
        ? imagePipeApiCalls[imagePipeApiCalls.length - 1].metadata.totalInputTokens
        : null

    const imagePipeOutputTokens = imagePipeApiCalls.length > 0
        ? imagePipeApiCalls[imagePipeApiCalls.length - 1].metadata.totalOutputTokens
        : null

    const textPipeApiCalls = trace.filter(e =>
        e.eventType === 'API_CALL_SUCCESS' &&
        e.pipeName === 'text pipe' &&
        typeof e.metadata?.totalInputTokens === 'number')

    const textPipeInputTokens = textPipeApiCalls.length > 0
        ? textPipeApiCalls[textPipeApiCalls.length - 1].metadata.totalInputTokens
        : null

    return {
        gateCall,
        traceSummary: {
            eventCount: trace.length,
            imagePipeApiCallCount: imagePipeApiCalls.length,
            imagePipeInputTokens,
            imagePipeOutputTokens,
            textPipeInputTokens
        }
    }
}

async function readServerLog()
{
    const logs = [
        '/home/cage/.autogenesis/logs/server-extend-2026-08-14-100550.log'
    ]
    for(const p of logs)
    {
        if(existsSync(p))
        {
            const content = readFileSync(p, 'utf8')
            // Find MapUploadGate lines, sorted by timestamp
            const lines = content.split('\n').filter(l => l.includes('MapUploadGate'))
            return lines
        }
    }
    return []
}

async function main()
{
    const consoleAll = []
    const consoleErrors = []

    console.log('Fixture: ' + FIXTURE + ' (' + FIXTURE_BYTES + ' bytes total)')
    console.log('  PNG inside: ' + PNG_BYTES + ' bytes')
    console.log('  Estimated tokens @ 0.627 t/b: ' + PNG_EST_TOKENS + ' (' + (PNG_EST_TOKENS/CAP_TOKENS).toFixed(1) + 'x over the ' + CAP_TOKENS + '-token cap)')
    console.log('Artifacts: ' + ARTIFACT_DIR)

    const browser = await chromium.launch({ headless: true })
    let savedMapId = null
    let outcome = null
    try
    {
        const ctx = await browser.newContext({ viewport: { width: 1400, height: 900 } })
        const page = await ctx.newPage()
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

        console.log('\n=== PHASE A: Real AccelByte login ===')
        await performRealLogin(page)
        const live = await getAccelbyteId(page)
        console.log('  accelbyteUserId: ' + live.accelbyteUserId)
        check('A1: real login successful', live.accelbyteUserId !== null && live.accelbyteUserId !== 'guest-user',
            'got ' + live.accelbyteUserId)

        console.log('\n=== PHASE B: Upload OVERSIZED fixture ===')
        await openCollectionMaps(page)
        await page.waitForTimeout(1500)
        await page.screenshot({ path: join(ARTIFACT_DIR, 'B1-maps-tab.png'), fullPage: true })

        await page.locator('[data-testid="maps-upload-button"]').click()
        await page.waitForSelector('[data-testid="map-upload-modal"]', { timeout: 5000 })
        await page.waitForTimeout(500)
        await page.locator('[data-testid="map-upload-file-input"]').setInputFiles(FIXTURE)
        await page.waitForTimeout(3000)
        const dropZoneState = await page.evaluate(() =>
            document.querySelector('[data-testid="map-upload-drop-zone"]')?.getAttribute('data-state'))
        check('B1: oversized fixture validated', dropZoneState === 'validated', 'got "' + dropZoneState + '"')

        await page.locator('[data-testid="map-upload-publish"]').click()
        await page.waitForTimeout(2000)
        await page.screenshot({ path: join(ARTIFACT_DIR, 'B2-publishing.png'), fullPage: true })

        outcome = await waitForUploadOutcome(page, 180000)
        console.log('  upload outcome: ' + JSON.stringify(outcome))
        // Oversized maps that DOWN to <=408KB should succeed; maps that
        // downsample floor at 64px and STILL exceed should be rejected.
        // For our fixture (2.7MB random noise at 1024x1024), even the
        // 64x64 re-encode will likely exceed 408KB because noise is
        // incompressible, so we expect a rejection.
        const isSuccessOrExpectedRejection = outcome.outcome === 'success' ||
            (outcome.outcome === 'error' && /downsample|too large/i.test(outcome.reason || ''))
        check('B2: oversized upload completed (success or "too large" rejection)',
            isSuccessOrExpectedRejection,
            'outcome=' + outcome.outcome + (outcome.reason ? ', reason=' + outcome.reason.slice(0, 120) : ''))
        await page.screenshot({ path: join(ARTIFACT_DIR, 'B3-outcome.png'), fullPage: true })

        if(outcome.outcome === 'success')
        {
            await page.waitForTimeout(5000)
            savedMapId = await page.evaluate(() =>
            {
                const card = document.querySelector('[data-map-id]')
                return card ? card.getAttribute('data-map-id') : null
            })
            console.log('  savedMapId: ' + savedMapId)
            await writeFile(join(ARTIFACT_DIR, 'saved-map-id.txt'), savedMapId || 'null')
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
        try { await browser.close() } catch(ignored) {}
        await writeFile(join(ARTIFACT_DIR, 'console.txt'), consoleAll.join('\n')).catch(() => {})
        await writeFile(join(ARTIFACT_DIR, 'console-errors.txt'), consoleErrors.join('\n')).catch(() => {})
    }

    console.log('\n=== PHASE C: Read server-side gate-call.json + trace.json ===')
    const artifacts = await readGateArtifacts()
    if(artifacts.error)
    {
        check('C1: gate-call.json exists at trace dir', false, artifacts.error)
    }
    else
    {
        console.log('  gate-call.json: ' + JSON.stringify(artifacts.gateCall))
        console.log('  trace summary: ' + JSON.stringify(artifacts.traceSummary))
        await writeFile(join(ARTIFACT_DIR, 'gate-call.json'), JSON.stringify(artifacts.gateCall, null, 2))

        const gateCall = artifacts.gateCall
        check('C1: gate-call.json written', !!gateCall, 'gateCall.playerId=' + (gateCall.playerId || 'null'))

        // C2: imageBytes is the SIZE THE SAFETY PIPELINE SAW (post-downsample).
        // For oversized maps that fit post-downsample, this is < MAX_BYTES.
        // For oversized maps that exceed even after downsample, the gate
        // refuses them BEFORE calling the safety agent, so gate-call.json
        // would not be written. The presence of gate-call.json means the
        // safety agent was called — therefore imageBytes <= MAX_BYTES.
        check('C2: imageBytes that safety agent saw <= MAX_BYTES (408293)',
            typeof gateCall.imageBytes === 'number' && gateCall.imageBytes <= MAX_BYTES,
            'imageBytes=' + gateCall.imageBytes + ', MAX_BYTES=' + MAX_BYTES)

        // C3: token count under the 256K cap
        const trace = artifacts.traceSummary
        check('C3: image-pipe input tokens <= 256000',
            trace.imagePipeInputTokens !== null && trace.imagePipeInputTokens <= CAP_TOKENS,
            'imagePipeInputTokens=' + trace.imagePipeInputTokens + ', cap=' + CAP_TOKENS)

        // C4: text pipe also under cap (just being thorough)
        check('C4: text-pipe input tokens <= 256000',
            trace.textPipeInputTokens !== null && trace.textPipeInputTokens <= CAP_TOKENS,
            'textPipeInputTokens=' + trace.textPipeInputTokens + ', cap=' + CAP_TOKENS)

        // C5: the gate-call.json captures the actual byte size the safety agent received.
        // This is the receipt that the downsample ran. If imageBytes is significantly
        // smaller than the fixture PNG, downsample definitely ran.
        const fixturePngBytes = PNG_BYTES
        check('C5: imageBytes is reduced vs original (downsample ran)',
            typeof gateCall.imageBytes === 'number' && gateCall.imageBytes < fixturePngBytes,
            'imageBytes=' + gateCall.imageBytes + ' < fixturePngBytes=' + fixturePngBytes)
    }

    // C6: read the server log and verify the downsample pass messages exist
    console.log('\n=== PHASE D: Server log shows downsample ran ===')
    const logLines = await readServerLog()
    const downsampleLines = logLines.filter(l =>
        l.toLowerCase().includes('downsample') &&
        !l.toLowerCase().includes('saved gate-call') &&
        !l.toLowerCase().includes('saved trace'))
    await writeFile(join(ARTIFACT_DIR, 'downsample-log-lines.txt'), downsampleLines.join('\n'))
    console.log('  ' + downsampleLines.length + ' downsample-related log lines (last 5):')
    for(const l of downsampleLines.slice(-5))
    {
        console.log('    ' + l)
    }
    check('D1: server log has downsample-related lines', downsampleLines.length > 0,
        'count=' + downsampleLines.length)

    // Final summary
    const passed = checks.filter(c => c.ok).length
    const failed = checks.filter(c => !c.ok).length
    console.log('\n=== TOTAL: ' + passed + ' pass / ' + failed + ' fail ===')
    console.log('Non-pre-existing console errors: ' + consoleErrors.length)
    if(consoleErrors.length > 0)
    {
        console.log('--- console errors (first 5) ---')
        for(const e of consoleErrors.slice(0, 5)) console.log('  ' + e)
    }
    console.log('Screenshots: ' + ARTIFACT_DIR)
    process.exit(failed > 0 ? 1 : 0)
}

main().catch(e =>
{
    console.error('top-level crash:', e)
    process.exit(2)
})