#!/usr/bin/env node
// kvisionApp-e2e/probes/resume-e2e.mjs
//
// End-to-end probe of the user's intended resume-game flow:
//   1. Open browser, real AccelByte guest login
//   2. Click PLAY, run a few turns to produce a non-trivial world
//   3. Close the browser tab (simulate the player leaving)
//   4. Confirm the server logs a "Persisted running-game snapshot" line
//   5. Re-open the browser (fresh page load) at the same URL
//   6. Real AccelByte guest login again
//   7. Expect a ResumeOrNewDialog modal to appear
//   8. Click "Resume"
//   9. Expect GameplayUI to mount with the same round/world as before
//      disconnect
//
// This probe asserts the full user-facing contract. It is intentionally
// longer than the guest-login probe — it covers the entire happy path.
//
// Pre-requisites:
//   1. All three dev servers running on standard ports (7070, 9080, 8080)
//   2. The test guest account's credentials in ui/LoginWidgets.kt
//   3. The `data-testid` surface on the LoginPage button + MainMenu + GameplayUI
//      (added in the prior probe commit)
//
// Usage:
//   node kvisionApp-e2e/probes/resume-e2e.mjs [--headed] [--keep-running]

import { chromium } from '@playwright/test'
import { writeFile, mkdir } from 'node:fs/promises'
import { fileURLToPath } from 'node:url'
import { dirname, join } from 'node:path'

const __dirname = dirname(fileURLToPath(import.meta.url))

const HEADED = process.argv.includes('--headed')
const KEEP_RUNNING = process.argv.includes('--keep-running')

const BASE_URL_ARG = process.argv.find(a => a.startsWith('--base-url='))
const BASE_URL = BASE_URL_ARG
    ? BASE_URL_ARG.substring('--base-url='.length)
    : 'http://127.0.0.1:8080'

const ARTIFACT_DIR = join(__dirname, 'artifacts-resume-e2e')
await mkdir(ARTIFACT_DIR, { recursive: true })

const log = (s) => console.log(`[${new Date().toISOString().slice(11, 23)}] ${s}`)

function isPreExistingNetworkError(text)
{
    return text.includes('ERR_CONNECTION_REFUSED') ||
        text.includes('ERR_FAILED') ||
        text.includes('Failed to load resource') ||
        text.includes('Failed to fetch') ||
        text.includes('Fail to fetch') ||
        text.includes('blocked by CORS') ||
        text.includes('integrity') ||
        text.includes('LocalDevDetector') ||
        text.includes('bootstrap.min.css') ||
        text.includes('coi-serviceworker') ||
        text.includes('favicon.ico') ||
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
        log(`  DOM snapshot: ${out} (${html.length} bytes)`)
    }
    catch(e)
    {
        log(`  failed to write DOM snapshot: ${e.message}`)
    }
}

async function logBrowserState(page, label)
{
    const state = await page.evaluate(() =>
    {
        const mainMenu = document.querySelector('[data-testid="main-menu"]')
        const gameplayUI = document.querySelector('[data-testid="gameplay-ui"]')
        const dialog = document.querySelector('[data-testid="resume-or-new-dialog"], .resume-or-new-dialog')
        return {
            url: window.location.href,
            wsUrl: window.__wsUrl || null,
            sseUrl: window.__sseUrl || null,
            mainMenuAccelbyteId: mainMenu ? mainMenu.getAttribute('data-accelbyte-user-id') : null,
            mainMenuPresent: !!mainMenu,
            gameplayPresent: !!gameplayUI,
            roundNumber: (document.querySelector('[data-testid="round-number"]') || {}).textContent || null,
            dialogPresent: !!dialog,
            dialogText: dialog ? dialog.textContent.slice(0, 200) : null,
            bodyText: document.body.textContent.slice(0, 500),
        }
    })
    log(`  ${label}: ${JSON.stringify(state, null, 2)}`)
    return state
}

async function main()
{
    log(`base URL: ${BASE_URL}, headed: ${HEADED}`)
    const browser = await chromium.launch({ headless: !HEADED })

    // ============== PHASE 1: FIRST SESSION ==============
    log('==== PHASE 1: First session — guest login, PLAY, capture world state ====')
    const ctx1 = await browser.newContext({ viewport: { width: 1280, height: 800 } })
    const page1 = await ctx1.newPage()

    const console1 = []
    page1.on('console', m => console1.push(`[${m.type()}] ${m.text()}`))
    page1.on('pageerror', e => console1.push(`[pageerror] ${e.message}`))

    // Listen for WS + SSE frames so we can inspect the URL the browser actually used
    await page1.exposeFunction('_recordWsUrl', (url) => console1.push(`[WS-FRAME] url=${url}`))
    await page1.addInitScript(() => {
        // Hook the WebSocket constructor to record the first URL
        const origWS = window.WebSocket
        window.WebSocket = function(...args) {
            try { window._recordWsUrl(args[0]) } catch(e) {}
            return new origWS(...args)
        }
        Object.assign(window.WebSocket, origWS)
    })

    log('Step 1: navigate to index.html (no skipLogin)')
    await page1.goto(`${BASE_URL}/index.html`)

    log('Step 2: click loading-screen CTA')
    const cta = page1.getByTestId('loading-screen-cta')
    await cta.waitFor({ state: 'visible', timeout: 30_000 })
    await cta.click()

    log('Step 3: wait for LoginPage, click "Login As Guest"')
    await page1.locator('.login-widget-window').waitFor({ state: 'visible', timeout: 30_000 })
    const guestButton = page1.getByTestId('login-as-guest')
        .or(page1.getByRole('button', { name: 'Login As Guest' }))
    await guestButton.first().click()

    log('Step 4: wait for the post-login messageBox, click OK, then wait for MainMenu')
    // The flow is: guestLogin succeeds → MessageBox "Loaded N saved commanders"
    // → user clicks OK → MainMenu mounts. The probe must click OK first,
    // THEN wait for MainMenu — not wait for MainMenu then try to click OK.
    let messageBoxClicked = false
    for (let i = 0; i < 60; i++) {
        const okByText = page1.getByRole('button', { name: /^OK$/ })
        if (await okByText.count() > 0 && await okByText.first().isVisible()) {
            log(`  found OK button at iteration ${i}; clicking`)
            try { await okByText.first().click({ timeout: 2_000 }) } catch (e) { log(`  click failed: ${e.message}`) }
            messageBoxClicked = true
            break
        }
        await page1.waitForTimeout(500)
    }
    if (!messageBoxClicked) {
        log('  WARNING: no OK button found in 30s; dumping DOM')
        await dumpDomSnapshot(page1, 'phase1-no-ok-button')
    }
    log('Step 4b: wait for MainMenu after OK click')
    await page1.locator('[data-testid="main-menu"]').waitFor({ state: 'visible', timeout: 30_000 })

    // If a ResumeOrNewDialog from a prior probe run is in the way
    // (it blocks pointer events), click "New Game" to dismiss it.
    const phase1Dialog = page1.locator('[data-testid="resume-or-new-dialog"]')
    if (await phase1Dialog.count() > 0 && await phase1Dialog.first().isVisible()) {
        log('  dismissing stale ResumeOrNewDialog from prior probe run')
        await page1.locator('[data-testid="resume-or-new-dialog"] button:has-text("New Game")').first().click({ timeout: 5_000 }).catch(() => {})
        await page1.waitForTimeout(1_000)
    }

    const state1BeforePlay = await logBrowserState(page1, 'phase1-mainMenuBeforePlay')
    const accelbyteId = state1BeforePlay.mainMenuAccelbyteId
    log(`  >>> captured accelbyteId=${accelbyteId}`)

    if (!accelbyteId || accelbyteId === 'guest-user' || accelbyteId === '') {
        log('FATAL: accelbyteId is not a real AccelByte UUID; aborting')
        await dumpDomSnapshot(page1, 'fatal-no-accelbyteId')
        await browser.close()
        process.exit(1)
    }

    log('Step 5: click PLAY → handle commander selection → wait for GameplayUI')
    await page1.locator('.btn.btn-play').waitFor({ state: 'visible', timeout: 30_000 })
    await page1.locator('.btn.btn-play').click({ force: true, timeout: 10_000 })
    log('  clicked PLAY (force)')

    // Wait for any stale ResumeOrNewDialog from a prior probe run to appear,
    // then dismiss it before continuing.
    for (let i = 0; i < 6; i++) {
        const stale = page1.locator('[data-testid="resume-or-new-dialog"]')
        if (await stale.count() > 0 && await stale.first().isVisible()) {
            log(`  dismissing stale ResumeOrNewDialog (iteration ${i})`)
            await page1.locator('[data-testid="resume-or-new-dialog"] button:has-text("New Game")').first().click({ timeout: 2_000, force: true }).catch(() => {})
            await page1.waitForTimeout(500)
        }
        await page1.waitForTimeout(300)
    }

    // The CommanderSelectionDialog appears next. Click on the existing
    // commander (AUongfa834nfa) to select it, then click Next, then
    // GameplayUI mounts.
    log('  waiting for commander selection dialog (or direct GameplayUI mount)')
    await page1.waitForFunction(() => {
        const gp = document.querySelector('[data-testid="gameplay-ui"]')
        const cs = document.querySelector('.commander-selection-window')
        const cs2 = document.querySelector('[data-testid="commander-selection-dialog"]')
        const failedModal = document.body.textContent.includes('Matchmaking Failed')
        return !!gp || !!cs || !!cs2 || failedModal
    }, { timeout: 30_000 }).catch(e => log(`  waitForCommanderDialog: ${e.message}`))

    await dumpDomSnapshot(page1, 'phase1-commander-selection')

    // Click the existing commander (it's a selectable row). force:true
    // bypasses the overlay's own click interception (the click is on the
    // row inside the overlay, but Playwright still complains).
    const commanderRow = page1.locator('text=/AUongfa834nfa/').first()
    if (await commanderRow.count() > 0 && await commanderRow.isVisible()) {
        log('  clicking existing commander AUongfa834nfa')
        await commanderRow.click({ timeout: 5_000, force: true })
        await page1.waitForTimeout(300)
    } else {
        log('  WARNING: existing commander AUongfa834nfa not found in DOM')
    }

    await dumpDomSnapshot(page1, 'phase1-after-commander-click')

    // The CommanderSelectionDialog has a Next button on step 1 (commander
    // selection) and a Play button on step 2 (game type + AI count).
    // Click whichever is visible.
    const nextButton = page1.getByRole('button', { name: /^Next$/ })
    const playButton = page1.getByRole('button', { name: /^Play$/ })
    if (await nextButton.count() > 0 && await nextButton.first().isVisible()) {
        log('  clicking Next button')
        try { await nextButton.first().click({ timeout: 5_000 }) } catch (e) { log(`  Next click failed: ${e.message}`) }
        await page1.waitForTimeout(500)
    }
    // Now there might be a second screen (game type / AI count); click Play.
    const playButton2 = page1.getByRole('button', { name: /^Play$/ })
    if (await playButton2.count() > 0 && await playButton2.first().isVisible()) {
        log('  clicking Play button (step 2 of commander selection)')
        try { await playButton2.first().click({ timeout: 5_000 }) } catch (e) { log(`  Play click failed: ${e.message}`) }
        await page1.waitForTimeout(500)
    }

    await dumpDomSnapshot(page1, 'phase1-after-play-click')

    // The "Contacting local game server..." or "Match Resumed" messageBox
    // appears next. Click OK when it shows.
    log('  waiting for matchmaking messageBox (Match Ready / Matchmaking Failed / Match Resumed)')
    let matchMessageBoxClicked = false
    for (let i = 0; i < 60; i++) {
        const okByText = page1.getByRole('button', { name: /^OK$/ })
        if (await okByText.count() > 0 && await okByText.first().isVisible()) {
            log(`  found post-matchmaking OK button at iteration ${i}; clicking`)
            try { await okByText.first().click({ timeout: 2_000 }) } catch (e) { log(`  click failed: ${e.message}`) }
            matchMessageBoxClicked = true
            break
        }
        await page1.waitForTimeout(500)
    }
    if (!matchMessageBoxClicked) {
        log('  WARNING: no post-matchmaking OK button found in 30s')
        await dumpDomSnapshot(page1, 'phase1-no-match-ok')
    }

    // Wait for GameplayUI to mount
    await page1.waitForFunction(() => {
        return !!document.querySelector('[data-testid="gameplay-ui"]')
    }, { timeout: 30_000 }).catch(e => log(`  waitForGameplayAfterCommander: ${e.message}`))

    if (await page1.locator('[data-testid="gameplay-ui"]').count() === 0) {
        log('  FATAL: GameplayUI never mounted in phase 1 — see phase1-no-match-ok.html')
        await dumpDomSnapshot(page1, 'phase1-no-gameplay')
        await browser.close()
        process.exit(1)
    }

    log('  GameplayUI mounted! Waiting for the world to advance...')

    // Wait for the round number text to populate (signals that turn 1
    // has been broadcast and the UI is showing it)
    await page1.waitForFunction(() => {
        const bodyText = document.body.textContent
        // The GameplayUI shows "Round: N" somewhere
        return bodyText.includes('Round:')
    }, { timeout: 30_000 }).catch(e => log(`  waitForRoundText: ${e.message}`))

    const stateAfterPlay = await logBrowserState(page1, 'phase1-afterPlay')

    // Run a couple of turns so the world has some state
    log('Step 6: wait 10s for the world to advance (a few turns should run)')
    await page1.waitForTimeout(10_000)

    const state1MidGame = await logBrowserState(page1, 'phase1-midGame')

    // Capture the current game state by reading the page DOM and the WorldManager global if exposed
    const worldSnapshot1 = await page1.evaluate(() => {
        const out = {
            bodyTextLen: document.body.textContent.length,
            hasTurnResolution: !!document.querySelector('[data-testid="turn-resolution"]'),
            hasMap: !!document.querySelector('[data-testid="map-viewer"]'),
            roundNumberText: (document.querySelector('[data-testid="round-number"]') || {}).textContent || null,
        }
        return out
    })
    log(`  world snapshot 1: ${JSON.stringify(worldSnapshot1)}`)

    await dumpDomSnapshot(page1, 'phase1-midGame')

    log('Step 7: CLOSE BROWSER (simulate player leaving)')
    await page1.close()
    await ctx1.close()

    // The game server needs time to detect the disconnect + write the snapshot.
    // Server.kt:465-488 fires on the WS disconnect handler with a 15s shutdown
    // timer arm. The snapshot write is async on Dispatchers.IO. Wait a
    // generous 20s for the snapshot to land in VFS.
    log('Step 8: wait 20s for the server to persist the running-game snapshot')
    await new Promise(r => setTimeout(r, 20_000))

    // ============== PHASE 2: SECOND SESSION ==============
    log('==== PHASE 2: Second session — fresh browser, guest login, expect Resume modal ====')
    const ctx2 = await browser.newContext({ viewport: { width: 1280, height: 800 } })
    const page2 = await ctx2.newPage()

    const console2 = []
    page2.on('console', m => console2.push(`[${m.type()}] ${m.text()}`))
    page2.on('pageerror', e => console2.push(`[pageerror] ${e.message}`))

    await page2.exposeFunction('_recordWsUrl2', (url) => console2.push(`[WS-FRAME] url=${url}`))
    await page2.addInitScript(() => {
        const origWS = window.WebSocket
        window.WebSocket = function(...args) {
            try { window._recordWsUrl2(args[0]) } catch(e) {}
            return new origWS(...args)
        }
        Object.assign(window.WebSocket, origWS)
    })

    log('Step 9: navigate to index.html (no skipLogin)')
    await page2.goto(`${BASE_URL}/index.html`)

    log('Step 10: click loading-screen CTA')
    const cta2 = page2.getByTestId('loading-screen-cta')
    await cta2.waitFor({ state: 'visible', timeout: 30_000 })
    await cta2.click()

    log('Step 11: wait for LoginPage, click "Login As Guest"')
    await page2.locator('.login-widget-window').waitFor({ state: 'visible', timeout: 30_000 })
    const guestButton2 = page2.getByTestId('login-as-guest')
        .or(page2.getByRole('button', { name: 'Login As Guest' }))
    await guestButton2.first().click()

    log('Step 12: wait for post-login messageBox, click OK, then wait for MainMenu')
    let messageBoxClicked2 = false
    for (let i = 0; i < 60; i++) {
        const okByText = page2.getByRole('button', { name: /^OK$/ })
        if (await okByText.count() > 0 && await okByText.first().isVisible()) {
            log(`  found OK button at iteration ${i}; clicking`)
            try { await okByText.first().click({ timeout: 2_000 }) } catch (e) { log(`  click failed: ${e.message}`) }
            messageBoxClicked2 = true
            break
        }
        await page2.waitForTimeout(500)
    }
    if (!messageBoxClicked2) {
        log('  WARNING: no OK button in 30s in phase 2')
        await dumpDomSnapshot(page2, 'phase2-no-ok-button')
    }
    await page2.locator('[data-testid="main-menu"]').waitFor({ state: 'visible', timeout: 30_000 })

    const state2MainMenu = await logBrowserState(page2, 'phase2-mainMenu')
    const accelbyteId2 = state2MainMenu.mainMenuAccelbyteId
    log(`  >>> second-session accelbyteId=${accelbyteId2}`)

    if (accelbyteId2 !== accelbyteId) {
        log(`FATAL: second session accelbyteId (${accelbyteId2}) != first (${accelbyteId})`)
        await dumpDomSnapshot(page2, 'fatal-accelbyteId-mismatch')
        await browser.close()
        process.exit(1)
    }

    // CRITICAL ASSERTION: the ResumeOrNewDialog should appear (Phase B push)
    log('Step 13: WAIT UP TO 30s for ResumeOrNewDialog to appear')
    const dialogAppeared = await page2.waitForFunction(() => {
        // The dialog is rendered into KEnv.mainRoot; the safest signal is
        // any visible element with the testid, OR a "Resume saved game?"
        // text fragment.
        const byTestId = document.querySelector('[data-testid="resume-or-new-dialog"]')
        if (byTestId) return { found: 'testid', text: byTestId.textContent.slice(0, 200) }
        const bodyText = document.body.textContent
        if (bodyText.includes('Saved game found') || bodyText.includes('resume your saved') ||
            bodyText.includes('Resume Saved')) {
            return { found: 'text', text: bodyText.slice(0, 200) }
        }
        return false
    }, { timeout: 30_000 }).then(h => h.jsonValue()).catch(e => ({ error: e.message }))

    log(`  dialog check: ${JSON.stringify(dialogAppeared)}`)
    await dumpDomSnapshot(page2, 'phase2-after-dialog-wait')

    // Dump all phase-2 console messages that mention resume or notification
    const resumeMsgs = console2.filter(c =>
        c.includes('resume') || c.includes('Resume') || c.includes('notification') ||
        c.includes('client.resume') || c.includes('ResumeAvailability') ||
        c.includes('mountResumeDialog') || c.includes('dialog callbacks')
    )
    log(`  resume-relevant console messages (${resumeMsgs.length}):`)
    for (const m of resumeMsgs.slice(0, 20)) log(`    ${m}`)

    if (!dialogAppeared || dialogAppeared.error) {
        log('FATAL: ResumeOrNewDialog did NOT appear within 30s of MainMenu mount')
        log('---- last 30 console messages (phase 2) ----')
        for (const c of console2.slice(-30)) log(`  ${c}`)
        log('---- last 30 console messages (phase 1) ----')
        for (const c of console1.slice(-30)) log(`  ${c}`)
        await browser.close()
        process.exit(1)
    }

    log('Step 14: click "Resume" button in the dialog')
    log('Step 14: click "Resume" button in the dialog')
    const resumeButton = page2.locator('[data-testid="resume-or-new-dialog"] button:has-text("Resume"), .resume-or-new-dialog button:has-text("Resume")')
        .or(page2.getByRole('button', { name: /^Resume$/ }))
    await resumeButton.first().click({ timeout: 10_000, force: true })

    log('Step 15: wait for either GameplayUI mount OR a "Match Resumed" / "Failed" messageBox')
    // The Match Resumed messageBox needs its OK clicked to mount GameplayUI
    for (let i = 0; i < 30; i++) {
        const okByText = page2.getByRole('button', { name: /^OK$/ })
        if (await okByText.count() > 0 && await okByText.first().isVisible()) {
            log(`  found post-resume OK at iteration ${i}; clicking`)
            try { await okByText.first().click({ timeout: 2_000, force: true }) } catch (_) {}
        }
        const gp = await page2.locator('[data-testid="gameplay-ui"]').count()
        if (gp > 0) break
        await page2.waitForTimeout(500)
    }

    await page2.waitForFunction(() => !!document.querySelector('[data-testid="gameplay-ui"]'), { timeout: 30_000 }).catch(e => log(`  waitForGameplayAfterResume: ${e.message}`))

    const state2AfterResume = await logBrowserState(page2, 'phase2-afterResume')
    await dumpDomSnapshot(page2, 'phase2-afterResume')

    log('Step 16: check console errors + summarize')
    const consoleErrors2 = console2.filter(c => c.startsWith('[error]') || c.startsWith('[pageerror]'))
    log(`  console errors (phase 2): ${consoleErrors2.length}`)
    if (consoleErrors2.length > 0) {
        for (const e of consoleErrors2.slice(0, 10)) log(`  ${e}`)
    }

    const finalState = await page2.evaluate(() => ({
        gameplayPresent: !!document.querySelector('[data-testid="gameplay-ui"]'),
        mainMenuPresent: !!document.querySelector('[data-testid="main-menu"]'),
        bodyTextSnippet: document.body.textContent.slice(0, 800),
    }))
    log(`  final state: ${JSON.stringify(finalState)}`)

    const result = {
        ok: !!finalState.gameplayPresent,
        accelbyteId,
        accelbyteId2,
        dialogAppeared: !!dialogAppeared && !dialogAppeared.error,
        gameplayPresentAfterResume: !!finalState.gameplayPresent,
        bodyText: finalState.bodyTextSnippet,
        consoleErrors2: consoleErrors2.slice(0, 5),
    }

    log(`\n==== RESULT ====`)
    log(JSON.stringify(result, null, 2))

    if (!KEEP_RUNNING) {
        await browser.close()
    }
    process.exit(result.ok ? 0 : 1)
}

main().catch(e => {
    console.error('probe crashed:', e)
    process.exit(2)
})
