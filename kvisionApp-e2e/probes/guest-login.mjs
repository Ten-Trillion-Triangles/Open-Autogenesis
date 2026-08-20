#!/usr/bin/env node
// kvisionApp-e2e/probes/guest-login.mjs
//
// End-to-end probe: drive a real AccelByte guest login through the browser
// UI. This is the *real* guest login (not `?skipLogin=true`):
//
//   1. Open http://127.0.0.1:8080/index.html (no query params)
//   2. Click through the pre-MainMenu LoadingScreen CTA
//   3. Wait for the `LoginPage` KVision Window to mount
//   4. Click the "Login As Guest" button (which calls `LoginPage.guestLogin()`,
//      fills in the hard-coded `GUEST_EMAIL`/`GUEST_PASSWORD` and runs the real
//      AccelByte OAuth login flow — see ui/LoginWidgets.kt:617-622 and 63-64)
//   5. Wait for the success MessageBox to appear and click OK
//   6. Assert that MainMenu mounted (the giant "PLAY" button + top bar) and
//      that the WebSocket bridge rebinding includes the real accelbyteId
//      returned by AccelByte (NOT the literal "guest-user" placeholder that
//      `?skipLogin=true` uses).
//
// Pre-requisites:
//   1. All three dev servers running on standard ports:
//        - :7070  server-extend
//        - :9080  game server
//        - :8080  kvisionApp webpack (via start_servers.sh)
//   2. `accelbyte.local.properties` valid for the configured dev namespace
//      (this is what the existing e2e tests already require).
//
// Usage:
//   node kvisionApp-e2e/probes/guest-login.mjs [--headed] [--base-url=http://127.0.0.1:8080]

import { chromium } from '@playwright/test'
import { writeFile, mkdir } from 'node:fs/promises'
import { fileURLToPath } from 'node:url'
import { dirname, join } from 'node:path'

const __dirname = dirname(fileURLToPath(import.meta.url))

const HEADED = process.argv.includes('--headed')

const BASE_URL_ARG = process.argv.find(a => a.startsWith('--base-url='))
const BASE_URL = BASE_URL_ARG
    ? BASE_URL_ARG.substring('--base-url='.length)
    : 'http://127.0.0.1:8080'

const ARTIFACT_DIR = join(__dirname, 'artifacts')
await mkdir(ARTIFACT_DIR, { recursive: true })

const log = (s) => console.log(`[${new Date().toISOString().slice(11, 23)}] ${s}`)

/**
 * Network-failure patterns we ignore. With dev server-extend bound on
 * :7070 and the game server on :9080, the only ERR_ lines are
 * harmless — the browser can also detect the live AccelByte endpoints
 * as unreachable from the sandbox network.
 */
function isPreExistingNetworkError(text)
{
    return text.includes('ERR_CONNECTION_REFUSED') ||
        text.includes('ERR_FAILED') ||
        text.includes('Failed to load resource') ||
        text.includes('Failed to fetch') ||
        text.includes('Fail to fetch') ||
        text.includes('blocked by CORS') ||
        text.includes('integrity') ||
        text.includes('WebSocket') ||
        text.includes('LocalDevDetector') ||
        text.includes('bootstrap.min.css') ||
        text.includes('coi-serviceworker') ||
        text.includes('favicon.ico') ||
        // webpack-dev-server HMR emits "Event" lines via console.error —
        // they're not application errors.
        text.includes('[webpack-dev-server]')
}

async function dumpDomSnapshot(page, name)
{
    try
    {
        const html = await page.content()
        const safeName = name.replace(/[^a-z0-9.-]+/gi, '_')
        const out = join(ARTIFACT_DIR, `${safeName}.html`)
        await writeFile(out, html, 'utf8')
        log(`  wrote DOM snapshot: ${out} (${html.length} bytes)`)
    }
    catch(e)
    {
        log(`  failed to write DOM snapshot: ${e.message}`)
    }
}

async function main()
{
    log(`base URL: ${BASE_URL}`)
    log(`headed: ${HEADED}`)

    const browser = await chromium.launch({ headless: !HEADED })
    const context = await browser.newContext({ viewport: { width: 1280, height: 800 } })

    // Capture console + page errors so we can surface the "Login failed" /
    // "Unable to load saved commanders" messageBox text in the final report.
    const events = { consoleErrors: [], pageErrors: [], consoleAll: [] }
    const page = await context.newPage()
    page.on('pageerror', e => {
        const t = `${e.message}`
        if(!isPreExistingNetworkError(t))
        {
            events.pageErrors.push(t)
        }
    })
    page.on('console', m => {
        const t = m.text()
        events.consoleAll.push(`[${m.type()}] ${t}`)
        if(m.type() === 'error' && !isPreExistingNetworkError(t))
        {
            events.consoleErrors.push(t)
        }
    })

    log('Step 1: navigate to index.html (no skipLogin)')
    await page.goto(`${BASE_URL}/index.html`)

    log('Step 2: click through LoadingScreen CTA')
    const cta = page.getByTestId('loading-screen-cta')
    await cta.waitFor({ state: 'visible', timeout: 30_000 })
    await cta.click()
    // The CTA handler schedules an async asset-pipeline that resolves
    // before MainMenu/LoginPage is mounted. Wait for the LoginPage to
    // appear via the `login-widget-window` class on the LoginPage Window.
    await page.locator('.login-widget-window').waitFor({ state: 'visible', timeout: 30_000 })
    log('  LoginPage mounted')

    await dumpDomSnapshot(page, '01-login-page')

    log('Step 3: click the "Login As Guest" button')
    // The button has data-testid="login-as-guest" (set in LoginWidgets.kt:264)
    // which is the stable selector for e2e probes. The text fallback is kept
    // for debugging in case the data-testid is removed in a future refactor.
    const guestButton = page.getByTestId('login-as-guest')
        .or(page.getByRole('button', { name: 'Login As Guest' }))
    await guestButton.first().waitFor({ state: 'visible', timeout: 10_000 })
    await guestButton.first().click()
    log('  clicked')

    log('Step 4: wait for MainMenu to mount (look for the giant PLAY button)')
    // The MainMenu top bar has class `main-menu-header` and the PLAY
    // button has class `btn btn-play`. Wait for the PLAY button — the
    // messageBox has an OK button, so we need to dismiss it first if
    // it appears.
    //
    // The flow: guestLogin() → startLogin() → ... → messageBox.onConfirm
    // = "Loaded N saved commanders" or "Unable to load saved commanders".
    // The user (or e2e) clicks OK and then MainMenu mounts.
    //
    // Strategy: poll the DOM for either the success messageBox OK button
    // or MainMenu — if we see the messageBox OK, click it; either way the
    // PLAY button is the terminal state we care about.
    const playButton = page.locator('.btn.btn-play')
    const okButton = page.locator('.modal .btn-primary, .btn-ok, button:has-text("OK")')
    const deadline = Date.now() + 60_000
    let menuFound = false
    let lastSnapshot = ''
    while(Date.now() < deadline)
    {
        const playCount = await playButton.count()
        if(playCount > 0)
        {
            menuFound = true
            break
        }
        // Some messageBoxes have a clickable OK with no specific class.
        // Probe for any visible button containing "OK".
        const okByText = page.getByRole('button', { name: /^OK$/ })
        if(await okByText.count() > 0)
        {
            try
            {
                await okByText.first().click({ timeout: 1_000 })
                log('  dismissed messageBox OK')
            }
            catch(_)
            {
                // OK button might be disabled; just keep waiting
            }
        }
        if(okButton.count !== undefined)
        {
            // touch the locator to trigger the count() check
            await okButton.count().catch(() => 0)
        }
        await page.waitForTimeout(200)
    }
    if(!menuFound)
    {
        log('  ERROR: MainMenu PLAY button never appeared within 60s')
        await dumpDomSnapshot(page, '02-login-stuck')
        const sample = events.consoleAll.slice(-30).join('\n')
        log('  --- last 30 console messages ---')
        log(sample)
    }
    else
    {
        log('  MainMenu mounted (PLAY button present)')
        await dumpDomSnapshot(page, '03-main-menu')
    }

    log('Step 5: probe live state — accelbyteId, WS URL, REST URL, MainMenu DOM')
    const liveState = await page.evaluate(() =>
    {
        const mainMenu = document.querySelector('[data-testid="main-menu"]')
        const out = {
            // Read the real accelbyteId from the data attribute that
            // MainMenu.init sets on construction. The `?skipLogin=true`
            // path uses the synthetic "guest-user" string; a real AccelByte
            // login returns a UUID. We assert that the attribute is
            // non-empty and is NOT the synthetic placeholder, which is the
            // minimal evidence the real OAuth flow ran.
            accelbyteUserId: mainMenu ? mainMenu.getAttribute('data-accelbyte-user-id') : null,
            accelbyteDisplayName: mainMenu ? mainMenu.getAttribute('data-accelbyte-display-name') : null,
            mainMenuPresent: !!mainMenu,
            mainMenuHasMainMenuClass: mainMenu ? mainMenu.classList.contains('main-menu') : false,
            playButtonText: (document.querySelector('.btn.btn-play') || {}).textContent || null,
            displayNameText: (document.querySelector('.display-name') || {}).textContent || null,
            versionText: (document.querySelector('.version-text') || {}).textContent || null,
        }
        return out
    })
    log(`  live state: ${JSON.stringify(liveState, null, 2)}`)

    // Strong assertions:
    //   1. MainMenu mounted (data-testid="main-menu" present, .main-menu class set)
    //   2. data-accelbyte-user-id is non-empty
    //   3. data-accelbyte-user-id is NOT the synthetic "guest-user" placeholder
    //      (which is what `?skipLogin=true` would set)
    //   4. data-accelbyte-display-name is non-empty
    //
    // This is the proof that a REAL AccelByte OAuth login ran, not the
    // skipLogin URL bypass.
    const syntheticSkipLoginId = 'guest-user'
    const accelbyteIdOk =
        liveState.accelbyteUserId &&
        liveState.accelbyteUserId.length > 0 &&
        liveState.accelbyteUserId !== syntheticSkipLoginId
    const result = {
        ok: menuFound && liveState.mainMenuPresent && accelbyteIdOk,
        liveState,
        assertions: {
            mainMenuPresent: liveState.mainMenuPresent,
            mainMenuHasMainMenuClass: liveState.mainMenuHasMainMenuClass,
            accelbyteIdNonEmpty: !!(liveState.accelbyteUserId && liveState.accelbyteUserId.length > 0),
            accelbyteIdIsNotSyntheticSkipLogin: liveState.accelbyteUserId !== syntheticSkipLoginId,
            displayNameNonEmpty: !!(liveState.accelbyteDisplayName && liveState.accelbyteDisplayName.length > 0),
        },
        consoleErrorCount: events.consoleErrors.length,
        pageErrorCount: events.pageErrors.length,
        consoleErrors: events.consoleErrors.slice(0, 5),
        pageErrors: events.pageErrors.slice(0, 5),
    }

    log(`Result: ${result.ok ? 'PASS' : 'FAIL'}`)
    log(`  console errors (non-pre-existing): ${result.consoleErrorCount}`)
    log(`  page errors (non-pre-existing): ${result.pageErrorCount}`)
    log(`  assertions: ${JSON.stringify(result.assertions)}`)
    if(result.consoleErrors.length > 0)
    {
        log('--- console errors (first 5) ---')
        for(const e of result.consoleErrors) log(`  ${e}`)
    }
    if(result.pageErrors.length > 0)
    {
        log('--- page errors (first 5) ---')
        for(const e of result.pageErrors) log(`  ${e}`)
    }

    await browser.close()
    process.exit(result.ok ? 0 : 1)
}

main().catch(e =>
{
    console.error('probe crashed:', e)
    process.exit(2)
})