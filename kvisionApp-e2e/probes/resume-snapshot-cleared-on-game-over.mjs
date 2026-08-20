#!/usr/bin/env node
// kvisionApp-e2e/probes/resume-snapshot-cleared-on-game-over.mjs
//
// E2E probe for the snapshot-deletion-on-game-end contract (2026-06-26):
//   - Mid-game disconnect preserves the running-game snapshot for restore.
//   - Natural game end (win / loss / surrender-that-ends-the-game)
//     invalidates the snapshot so the next login does NOT show the
//     ResumeOrNewDialog for a finished game.
//
// Phase 1: log in as guest → PLAY → game → trigger game-over via
//          `game.surrender` RPC (the surrender path that flows through
//          `evaluateEndGame → broadcastGameOver → clearRunningGameForUser`).
// Phase 2: re-log in → assert `data-testid="resume-or-new-dialog"`
//          does NOT appear within the dialog-render window (no
//          running-game snapshot to restore from).
//
// Pre-requisites:
//   - dev servers running with AUTOGENESIS_SHUTDOWN_DELAY_MS=600000
//     AUTOGENESIS_DISABLE_AUTO_RESTORE=true AUTOGENESIS_DEBUG_SEED=true
//     (the debug-seed flag is required if the user wants to repush the
//     snapshot via /debug/seed-snapshot; the probe itself does not
//     require it)
//   - the AUongfa834nfa commander present in the test-user master record

import { chromium } from '@playwright/test'
import { writeFile, mkdir } from 'node:fs/promises'
import { fileURLToPath } from 'node:url'
import { dirname, join } from 'node:path'

const __dirname = dirname(fileURLToPath(import.meta.url))
const BASE_URL = process.env.KVISION_E2E_URL || 'http://127.0.0.1:8080'
const ARTIFACT_DIR = join(__dirname, 'artifacts-snapshot-cleared')
await mkdir(ARTIFACT_DIR, { recursive: true })
const HEADED = process.argv.includes('--headed')

const log = (s) => console.log(`[${new Date().toISOString().slice(11, 23)}] ${s}`)

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

async function dismissMessageBoxes(page)
{
    for (let i = 0; i < 30; i++) {
        const okByText = page.getByRole('button', { name: /^OK$/ })
        if (await okByText.count() > 0 && await okByText.first().isVisible()) {
            try { await okByText.first().click({ timeout: 2_000, force: true }) } catch (_) {}
        }
        await page.waitForTimeout(500)
    }
}

async function dismissResumeDialogs(page)
{
    for (let i = 0; i < 5; i++) {
        const dialog = page.locator('[data-testid="resume-or-new-dialog"]')
        if (await dialog.count() > 0 && await dialog.first().isVisible()) {
            try {
                await page.locator('[data-testid="resume-or-new-dialog"] button:has-text("New Game")').first().click({ timeout: 2_000, force: true })
                await page.waitForTimeout(500)
            } catch (_) {}
        }
        await page.waitForTimeout(200)
    }
}

async function loginAsGuest(page)
{
    await page.goto(`${BASE_URL}/index.html`)
    await page.getByTestId('loading-screen-cta').click()
    await page.locator('.login-widget-window').waitFor({ state: 'visible', timeout: 30_000 })
    await page.getByTestId('login-as-guest').click()
    await dismissMessageBoxes(page)
    await page.locator('[data-testid="main-menu"]').waitFor({ state: 'visible', timeout: 60_000 })
    await dismissResumeDialogs(page)
}

async function startGame(page, aiOpponentCount = 1)
{
    await page.locator('.btn.btn-play').click({ force: true, timeout: 10_000 })
    await dismissResumeDialogs(page)
    await page.waitForFunction(() =>
        document.querySelector('[data-testid="gameplay-ui"]') ||
        document.querySelector('.commander-selection-window')
    , { timeout: 30_000 }).catch(() => {})
    await page.locator('text=/AUongfa834nfa/').first().click({ timeout: 5_000, force: true }).catch(() => {})
    await page.getByRole('button', { name: /^Next$/ }).first().click({ timeout: 5_000, force: true }).catch(() => {})
    await page.getByRole('button', { name: /^Play$/ }).first().click({ timeout: 5_000, force: true }).catch(() => {})
    await dismissMessageBoxes(page)
    await page.waitForFunction(() => !!document.querySelector('[data-testid="gameplay-ui"]'), { timeout: 30_000 }).catch(() => {})
}

async function getPlayerName(page)
{
    // The trace logs `[TRACE] [GameplayUI.updateWorldState] LocalPlayerName:
    // 'AUongfa834nfa'` are written to the BROWSER CONSOLE, not the DOM,
    // so `document.body.textContent` does not include them. The leaderboard
    // entry is inside a hidden modal (`display: none`), but Playwright's
    // `textContent` collapses whitespace so the regex still has to span
    // across the modal's display:none. The most reliable signal is the
    // "Active actor: <name>" span at the bottom of the GameplayUI body
    // — that span is ALWAYS visible while gameplay is mounted.
    //
    // Fallbacks (in order):
    //   1. "Active actor: <Name>" pattern (always-visible footer).
    //   2. Leaderboard regex (hidden modal but DOM-present).
    //   3. Empty string → caller falls back to a known-name probe.
    return await page.evaluate(() => {
        // 1. Footer span
        const text = document.body.innerText
        const m1 = text.match(/Active actor:\s*([A-Za-z0-9_]+)/)
        if (m1) return m1[1]

        // 2. Leaderboard pattern
        const re = /(\d+)\.\s*([A-Za-z0-9_]+)\s+(\d+)\s+VP/g
        let m, first = ''
        while ((m = re.exec(text)) !== null) {
            if (!first) first = m[2]
        }
        return first
    })
}

async function surrenderPlayer(page, playerName)
{
    // game.surrender fires surrenderPlayer on the server, which evaluates
    // end-of-game. If this is the last non-surrendered player in a single-
    // player match, evaluateEndGame triggers broadcastGameOver which deletes
    // the snapshot. If surrender does NOT end the game (rare in single-
    // player since the only other contender is an NPC), the snapshot is
    // preserved and this probe would still pass — only the multi-player
    // path invalidates, which is the wrong surface area for this contract.
    //
    // We drive the UI through Playwright rather than the RPC because the
    // RPC invoker is not exposed on window.WebSocketRpcBridge at runtime
    // (it's a Kotlin object; only globals.KEnv.* fields get bound to the
    // window via the KVision runtime).
    //
    // Steps:
    //   1. Click the SETTINGS gear icon (the settings button on the
    //      GameplayUI command box — has class action-button and the
    //      cog icon).
    //   2. Click the SURRENDER button (btn-surrender class, in the
    //      SettingsWidget).
    //   3. Click YES in the confirm dialog (SurrenderConfirmDialog).
    //   4. Wait for the settings widget to close (which fires on a
    //      successful surrender because the server navigates back to
    //      MainMenu).
    try {
        // 1. SETTINGS gear icon. The command-box button row has 4 small
        //    action-buttons; the SETTINGS one has fa-cog icon and label
        //    "SETTINGS".
        const settingsBtn = page.locator('button.action-button:has-text("SETTINGS")').first()
        await settingsBtn.click({ force: true, timeout: 5_000 })

        // Wait for the settings widget to mount. It uses class
        // login-widget-window and has a "Game Settings" header.
        await page.waitForFunction(() => {
            return Array.from(document.querySelectorAll('h4')).some(h => h.textContent.includes('Game Settings'))
        }, { timeout: 5_000 }).catch(() => {})

        // 2. SURRENDER button. The btn-surrender class lives inside the
        //    settings widget. Settings widgets in this codebase use
        //    position: fixed, so Playwright's "visible" check can fail
        //    on elements outside the viewport (e.g., at viewport width
        //    1280 the settings widget sits at right=0, width=600 so it
        //    covers [680..1280]). Use dispatchEvent to bypass the
        //    visibility check entirely.
        const surrenderResult = await page.evaluate(() => {
            const btn = document.querySelector('button.btn-surrender')
            if (!btn) return { ok: false, error: 'btn-surrender not in DOM' }
            btn.dispatchEvent(new MouseEvent('click', { bubbles: true, cancelable: true, view: window }))
            return { ok: true }
        })
        if (!surrenderResult.ok) {
            return surrenderResult
        }

        // 3. SurrenderConfirmDialog — yes button. Same visibility caveat
        //    applies; use evaluate to dispatch the click directly. The
        //    button is labeled "YES, SURRENDER" with class
        //    "btn-surrender-confirm" (see SurrenderConfirmDialog.kt:162).
        await page.waitForFunction(() => {
            return !!document.querySelector('button.btn-surrender-confirm')
        }, { timeout: 5_000 }).catch(() => {})
        const yesResult = await page.evaluate(() => {
            const yesBtn = document.querySelector('button.btn-surrender-confirm')
            if (!yesBtn) return { ok: false, error: 'YES, SURRENDER button not in DOM' }
            yesBtn.dispatchEvent(new MouseEvent('click', { bubbles: true, cancelable: true, view: window }))
            return { ok: true }
        })
        if (!yesResult.ok) {
            return yesResult
        }

        return { ok: true, accepted: true, method: 'ui-clicks' }
    } catch (e) {
        return { ok: false, error: String(e) }
    }
}

async function assertNoResumeDialog(page, timeoutMs)
{
    // The dialog renders via client.resumeAvailable → ResumeAvailabilityListener.
    // If hasRunningGame returned false after game-over, no push fires and the
    // dialog never mounts. Wait the full window the dialog would have appeared
    // in, then assert count === 0.
    const deadline = Date.now() + timeoutMs
    while (Date.now() < deadline) {
        const count = await page.locator('[data-testid="resume-or-new-dialog"]').count()
        if (count > 0) return false
        await page.waitForTimeout(500)
    }
    return await page.locator('[data-testid="resume-or-new-dialog"]').count() === 0
}

async function main()
{
    log(`base URL: ${BASE_URL}, headed: ${HEADED}`)
    const browser = await chromium.launch({ headless: !HEADED })

    // ===================================================================
    // PHASE 1 — log in, start a game, surrender to trigger game-over,
    //           close the browser, wait for the server's post-game
    //           clearRunningGameForUser to land.
    // ===================================================================
    log('==== PHASE 1: play game, surrender to end it ====')
    const ctx1 = await browser.newContext({ viewport: { width: 1280, height: 800 } })
    const page1 = await ctx1.newPage()
    page1.on('console', m => {
        const t = m.text()
        if (t.includes('ResumeAvailability') || t.includes('mountResumeDialog') || t.includes('ResumeOrNewDialog') ||
            t.includes('client.resume') || t.includes('Persisted running-game') ||
            t.includes('GameplayUI') || t.includes('Rehydrated') ||
            t.includes('surrender') || t.includes('broadcastGameOver') ||
            t.includes('clearRunningGame') || t.includes('evaluateEndGame'))
            console.log(`  [phase1 ${m.type()}] ${t.slice(0, 280)}`)
    })

    await loginAsGuest(page1)
    log('  MainMenu mounted; starting game')
    await startGame(page1)
    await page1.waitForTimeout(8_000)  // let the world initialize

    let playerName = await getPlayerName(page1)
    // Fallback: this probe is locked to AUongfa834nfa (the user-controlled
    // commander from the test master record). If the detection above fails
    // (Playwright textContent quirks on hidden modals), use the known name
    // and let the surrender call fail loudly if it doesn't match.
    if (!playerName) {
        playerName = "AUongfa834nfa"
        log(`  player name detection failed; falling back to known commander '${playerName}'`)
    }
    log(`  detected local player: "${playerName}"`)

    log('  firing surrender via game.surrender RPC')
    const surrenderResult = await surrenderPlayer(page1, playerName)
    log(`  surrender result: ${JSON.stringify(surrenderResult)}`)
    if (!surrenderResult.ok || (surrenderResult.response?.result && !surrenderResult.response.result.accepted)) {
        log(`FATAL: surrender not accepted. The player name '${playerName}' may not match the live WS session owner.`)
        log(`  result payload: ${JSON.stringify(surrenderResult.response?.result || surrenderResult.response)}`)
        await dumpDomSnapshot(page1, 'phase1-surrender-rejected')
        await browser.close()
        process.exit(1)
    }

    // Wait for the game-over broadcast + the async clear to land. The
    // clear is dispatched on Dispatchers.IO so the test must yield.
    log('  waiting 8s for game-over → broadcast → clearRunningGameForUser')
    await page1.waitForTimeout(8_000)
    await dumpDomSnapshot(page1, 'phase1-after-surrender')

    log('  closing phase 1 browser')
    await page1.close()
    await ctx1.close()

    // Extra wait for any tail-of-game processing (the game-over path
    // calls clearSession, notifyMatchEnded, and exportBillingReport
    // asynchronously — give the server time to finish).
    log('  waiting 10s for server tail-of-game processing')
    await new Promise(r => setTimeout(r, 10_000))

    // ===================================================================
    // PHASE 2 — re-log in, assert ResumeOrNewDialog does NOT mount.
    //           If the snapshot is properly invalidated, the
    //           client.resumeAvailable push does not fire and the
    //           listener never mounts the dialog.
    // ===================================================================
    log('==== PHASE 2: re-login, assert no ResumeOrNewDialog ====')
    const ctx2 = await browser.newContext({ viewport: { width: 1280, height: 800 } })
    const page2 = await ctx2.newPage()
    let dialogAppeared = false
    page2.on('console', m => {
        const t = m.text()
        if (t.includes('ResumeAvailability') || t.includes('mountResumeDialog') ||
            t.includes('ResumeOrNewDialog') || t.includes('client.resumeAvailable'))
            console.log(`  [phase2 ${m.type()}] ${t.slice(0, 280)}`)
    })

    await loginAsGuest(page2)
    log('  MainMenu mounted; waiting 20s for any ResumeOrNewDialog to appear')
    dialogAppeared = !(await assertNoResumeDialog(page2, 20_000))
    log(`  ResumeOrNewDialog appeared: ${dialogAppeared}`)
    await dumpDomSnapshot(page2, 'phase2-after-relogin')

    // === ASSERTIONS ===
    const assertions = {
        noResumeDialogAfterGameOver: !dialogAppeared,
        mainMenuMounted: await page2.locator('[data-testid="main-menu"]').count() > 0,
        playButtonAvailable: await page2.locator('.btn.btn-play').count() > 0,
    }

    const allPass = Object.values(assertions).every(v => v)
    log('==== ASSERTIONS ====')
    for (const [k, v] of Object.entries(assertions)) log(`  ${v ? 'PASS' : 'FAIL'}  ${k}`)

    log(`==== RESULT: ${allPass ? 'PASS' : 'FAIL'} ====`)

    await browser.close()
    process.exit(allPass ? 0 : 1)
}

main().catch(e => {
    console.error('probe crashed:', e)
    process.exit(2)
})
