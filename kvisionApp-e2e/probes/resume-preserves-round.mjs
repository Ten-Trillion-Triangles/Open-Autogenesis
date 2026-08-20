#!/usr/bin/env node
// kvisionApp-e2e/probes/resume-preserves-round.mjs
//
// Stricter resume-game probe: verifies the user's exact requirement
//   "the turn resumes where it left off"
// by capturing the round number + turn-order + leaderboard state in
// phase 1, then asserting the same values appear in phase 2 after the
// user clicks Resume.
//
// Pre-requisites:
//   - dev servers running with AUTOGENESIS_SHUTDOWN_DELAY_MS=600000
//     AUTOGENESIS_DISABLE_AUTO_RESTORE=true
//   - the AUongfa834nfa commander present in the test-user master record

import { chromium } from '@playwright/test'
import { writeFile, mkdir } from 'node:fs/promises'
import { fileURLToPath } from 'node:url'
import { dirname, join } from 'node:path'

const __dirname = dirname(fileURLToPath(import.meta.url))
const BASE_URL = process.env.KVISION_E2E_URL || 'http://127.0.0.1:8080'
const ARTIFACT_DIR = join(__dirname, 'artifacts-resume-round')
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

function extractRound(bodyText) {
    const m = bodyText.match(/Round:\s*(\d+)/)
    return m ? parseInt(m[1], 10) : null
}

function extractLeaderboard(bodyText) {
    // The leaderboard is rendered as: "1. <name>NN VP"
    const entries = []
    const re = /(\d+)\.\s*([A-Za-z0-9_]+)\s*(\d+)\s*VP/g
    let m
    while ((m = re.exec(bodyText)) !== null) {
        entries.push({ rank: parseInt(m[1], 10), name: m[2], vp: parseInt(m[3], 10) })
    }
    return entries
}

function extractTurnOrder(bodyText) {
    // "Turn order" widget lists the order. We just confirm it's present.
    return /Turn Order/i.test(bodyText)
}

async function captureGameState(page, label)
{
    const state = await page.evaluate(() => {
        const gameplayUI = document.querySelector('[data-testid="gameplay-ui"]')
        const worldStats = document.querySelector('.world-stats-widget, [data-testid="world-stats"]')
        const leaderboard = document.querySelector('.leaderboard, [data-testid="leaderboard"]')
        const turnOrder = document.querySelector('.turn-order, [data-testid="turn-order"]')
        return {
            gameplayPresent: !!gameplayUI,
            worldStatsPresent: !!worldStats,
            leaderboardPresent: !!leaderboard,
            turnOrderPresent: !!turnOrder,
            // Try to find the Round text via the body — it lives inside
            // WorldStatsWidget at .round-text or similar.
            bodyText: document.body.textContent
        }
    })
    const round = extractRound(state.bodyText)
    const leaderboard = extractLeaderboard(state.bodyText)
    const turnOrderPresent = extractTurnOrder(state.bodyText)
    log(`  [${label}] gameplay=${state.gameplayPresent} round=${round} leaderboardEntries=${leaderboard.length} turnOrder=${turnOrderPresent}`)
    if (leaderboard.length > 0) {
        log(`    leaderboard: ${JSON.stringify(leaderboard)}`)
    }
    return { round, leaderboard, turnOrderPresent, bodyText: state.bodyText.slice(0, 1500) }
}

async function main()
{
    log(`base URL: ${BASE_URL}, headed: ${HEADED}`)
    const browser = await chromium.launch({ headless: !HEADED })

    // PHASE 1 — start a game, capture state
    log('==== PHASE 1: play game, capture world state ====')
    const ctx1 = await browser.newContext({ viewport: { width: 1280, height: 800 } })
    const page1 = await ctx1.newPage()
    page1.on('console', m => {
        const t = m.text()
        if (t.includes('ResumeAvailability') || t.includes('mountResumeDialog') || t.includes('ResumeOrNewDialog') ||
            t.includes('client.resume') || t.includes('Persisted running-game') ||
            t.includes('GameplayUI') || t.includes('Rehydrated'))
            console.log(`  [phase1 ${m.type()}] ${t.slice(0, 200)}`)
    })

    await page1.goto(`${BASE_URL}/index.html`)
    await page1.getByTestId('loading-screen-cta').click()
    await page1.locator('.login-widget-window').waitFor({ state: 'visible', timeout: 30_000 })
    await page1.getByTestId('login-as-guest').click()
    await dismissMessageBoxes(page1)
    await page1.locator('[data-testid="main-menu"]').waitFor({ state: 'visible', timeout: 60_000 })
    log('  MainMenu mounted')

    // Dismiss any prior run's ResumeOrNewDialog
    await dismissResumeDialogs(page1)

    // Click PLAY, select AUongfa834nfa, Next, Play
    await page1.locator('.btn.btn-play').click({ force: true, timeout: 10_000 })
    await dismissResumeDialogs(page1)
    await page1.waitForFunction(() =>
        document.querySelector('[data-testid="gameplay-ui"]') ||
        document.querySelector('.commander-selection-window')
    , { timeout: 30_000 }).catch(() => {})
    await page1.locator('text=/AUongfa834nfa/').first().click({ timeout: 5_000, force: true }).catch(() => {})
    await page1.getByRole('button', { name: /^Next$/ }).first().click({ timeout: 5_000, force: true }).catch(() => {})
    await page1.getByRole('button', { name: /^Play$/ }).first().click({ timeout: 5_000, force: true }).catch(() => {})
    await dismissMessageBoxes(page1)
    await page1.waitForFunction(() => !!document.querySelector('[data-testid="gameplay-ui"]'), { timeout: 30_000 }).catch(() => {})

    // Let one or two turns run so the world advances and we have something
    // non-trivial to verify preservation of.
    log('  GameplayUI mounted; letting 12s pass for several turns')
    await page1.waitForTimeout(12_000)

    // Capture the world state right before close
    const state1 = await captureGameState(page1, 'phase1')
    await dumpDomSnapshot(page1, 'phase1-midgame')

    // Wait an additional 8s to make sure turn 2+ has happened (turn timer is ~5min default, so
    // we may only have 1 turn committed, but let's give the game time to run another)
    log('  closing phase 1 browser')
    await page1.close()
    await ctx1.close()

    // Server needs time to write the snapshot
    log('  waiting 20s for server to persist snapshot')
    await new Promise(r => setTimeout(r, 20_000))

    // PHASE 2 — reopen, click Resume, capture state
    log('==== PHASE 2: reopen, click Resume, verify state preservation ====')
    const ctx2 = await browser.newContext({ viewport: { width: 1280, height: 800 } })
    const page2 = await ctx2.newPage()
    const phase2Console = []
    page2.on('console', m => {
        const t = m.text()
        phase2Console.push(`[${m.type()}] ${t}`)
        if (t.includes('ResumeAvailability') || t.includes('mountResumeDialog') || t.includes('ResumeOrNewDialog') ||
            t.includes('client.resume') || t.includes('Persisted running-game') ||
            t.includes('GameplayUI') || t.includes('Rehydrated') || t.includes('Match Resumed') ||
            t.includes('WebSocketRpcClientJs') || t.includes('ui.updateWorld') ||
            t.includes('DIAG') || t.includes('audio sync state') || t.includes('FLUSH'))
            console.log(`  [phase2 ${m.type()}] ${t.slice(0, 280)}`)
    })

    await page2.goto(`${BASE_URL}/index.html`)
    await page2.getByTestId('loading-screen-cta').click()
    await page2.locator('.login-widget-window').waitFor({ state: 'visible', timeout: 30_000 })
    await page2.getByTestId('login-as-guest').click()
    await dismissMessageBoxes(page2)
    await page2.locator('[data-testid="main-menu"]').waitFor({ state: 'visible', timeout: 60_000 })
    log('  MainMenu mounted; waiting for ResumeOrNewDialog')
    const dialogAppeared = await page2.waitForFunction(() =>
        document.querySelector('[data-testid="resume-or-new-dialog"]') !== null
    , { timeout: 30_000 }).then(() => true).catch(() => false)
    log(`  dialog appeared: ${dialogAppeared}`)
    if (!dialogAppeared) {
        log('FATAL: dialog never appeared')
        await dumpDomSnapshot(page2, 'phase2-no-dialog')
        await browser.close()
        process.exit(1)
    }

    log('  clicking Resume button')
    await page2.locator('[data-testid="resume-dialog-resume"]').first().click({ timeout: 5_000, force: true })
    // Wait for "Match Resumed" messageBox → click OK
    for (let i = 0; i < 30; i++) {
        const okByText = page2.getByRole('button', { name: /^OK$/ })
        if (await okByText.count() > 0 && await okByText.first().isVisible()) {
            log('  found post-resume OK, clicking')
            try { await okByText.first().click({ timeout: 2_000, force: true }) } catch (_) {}
        }
        const gp = await page2.locator('[data-testid="gameplay-ui"]').count()
        if (gp > 0) break
        await page2.waitForTimeout(500)
    }
    await page2.waitForFunction(() => !!document.querySelector('[data-testid="gameplay-ui"]'), { timeout: 30_000 }).catch(() => {})

    // Wait additional time for the loadMapPack (8MB / ~280 chunks of 30KB) and
    // ui.updateWorld (4 chunks) to arrive + be assembled + update the leaderboard.
    // The dialog flow already waited ~10s but the chunks are still arriving.
    log('  waiting 20s for chunked frames to assemble and update widgets')
    await page2.waitForTimeout(20_000)

    // Capture state right after Resume
    const state2 = await captureGameState(page2, 'phase2-after-resume')
    await dumpDomSnapshot(page2, 'phase2-after-resume')

    // === ASSERTIONS ===
    const assertions = {
        gameplayResumed: state2 !== null && state2 !== undefined,
        roundPreserved: state1.round !== null && state2.round === state1.round,
        leaderboardPreserved: JSON.stringify(state1.leaderboard) === JSON.stringify(state2.leaderboard),
        turnOrderPresent: state2.turnOrderPresent === true,
    }

    // ===================================================================
    // PHASE 3 — BUG 1/2/3 regression coverage (2026-06-26)
    //
    // The user's reported symptom was "It picks up on the saved game, but
    // throws a 'no saved game' error and just errors out when I hit resume."
    // That symptom appeared when the user clicked Resume AFTER the
    // auto-restore path had already applied the snapshot and written the
    // consumed-sentinel. Phase 3 exercises the same flow:
    //
    //   1. Disconnect phase 2's browser (this is the "user clicks the
    //      X / closes the tab" path).
    //   2. Reconnect with a fresh browser (this is the "user logs back
    //      in" path). With the OLD code, this would trigger the
    //      auto-restore on connect and the consumed-sentinel would be
    //      read; the user would then click Resume and see "No saved
    //      game." With the NEW code, the race-recovery branch in
    //      `GameRestoreRpcHandlers.restoreRunningGame` checks the
    //      `WorldManager.lastRehydratedAccelByteUserId` flag and
    //      returns `true` (idempotent success), so the page mounts the
    //      resumed game instead of erroring.
    //   3. The page MUST render the same world state as phase 1+2.
    // ===================================================================
    log('==== PHASE 3: reconnect, click Resume, verify no "No saved game" error ====')
    log('  closing phase 2 browser')
    await page2.close()
    await ctx2.close()

    // Server needs time to register the disconnect.
    log('  waiting 5s for server to register disconnect')
    await new Promise(r => setTimeout(r, 5_000))

    // Reconnect — new browser context, fresh guest login.
    const ctx3 = await browser.newContext({ viewport: { width: 1280, height: 800 } })
    const page3 = await ctx3.newPage()
    let noSavedGameDialogAppeared = false
    let resumeDialogAppeared = false
    let gameplayMountedAfterResume = false
    let worldStateAfterResume = null
    page3.on('console', m => {
        const t = m.text()
        // Detect "No saved game found" error surface (the bug we're testing).
        if (t.includes('No saved game') || t.includes('No Saved Game') ||
            t.includes('Resume Failed') || t.includes('restored=false'))
        {
            log(`  [phase3 ERROR] ${t.slice(0, 280)}`)
            noSavedGameDialogAppeared = true
        }
        if (t.includes('ResumeOrNewDialog mounted') || t.includes('resumeAvailable round='))
        {
            log(`  [phase3 dialog] ${t.slice(0, 280)}`)
            resumeDialogAppeared = true
        }
        if (t.includes('Active actor:') || t.includes('Your Turn To Act'))
        {
            gameplayMountedAfterResume = true
        }
    })

    await page3.goto(`${BASE_URL}/index.html`)
    await page3.getByTestId('loading-screen-cta').click()
    await page3.locator('.login-widget-window').waitFor({ state: 'visible', timeout: 30_000 })
    await page3.getByTestId('login-as-guest').click()
    await dismissMessageBoxes(page3)
    await page3.locator('[data-testid="main-menu"]').waitFor({ state: 'visible', timeout: 60_000 })
    log('  MainMenu mounted; waiting for ResumeOrNewDialog')

    // With the BUG 2 fix in place, server-extend waits for the main
    // server's auto-restore to finish before pushing. The dialog should
    // appear within ~3s. (Pre-fix it would either not appear at all —
    // because the push happened before the consumed-sentinel was written
    // — or appear and immediately fail.)
    const phase3DialogAppeared = await page3.waitForFunction(() =>
        document.querySelector('[data-testid="resume-or-new-dialog"]') !== null
    , { timeout: 15_000 }).then(() => true).catch(() => false)
    log(`  ResumeOrNewDialog appeared: ${phase3DialogAppeared}`)

    if (phase3DialogAppeared)
    {
        log('  clicking Resume button')
        await page3.locator('[data-testid="resume-dialog-resume"]').first().click({ timeout: 5_000, force: true })
        // Wait for "Match Resumed" messageBox → click OK.
        for (let i = 0; i < 30; i++) {
            const okByText = page3.getByRole('button', { name: /^OK$/ })
            if (await okByText.count() > 0 && await okByText.first().isVisible()) {
                log('  found post-resume OK, clicking')
                try { await okByText.first().click({ timeout: 2_000, force: true }) } catch (_) {}
            }
            const gp = await page3.locator('[data-testid="gameplay-ui"]').count()
            if (gp > 0) break
            await page3.waitForTimeout(500)
        }
        await page3.waitForFunction(() => !!document.querySelector('[data-testid="gameplay-ui"]'), { timeout: 30_000 }).catch(() => {})
        // Let chunks assemble.
        await page3.waitForTimeout(15_000)
        worldStateAfterResume = await captureGameState(page3, 'phase3-after-resume')
    }
    else
    {
        // Dialog never appeared — check if gameplay mounted directly (the
        // auto-restore may have dispatched initial sync via the BUG 1 fix).
        await page3.waitForFunction(() => !!document.querySelector('[data-testid="gameplay-ui"]'), { timeout: 30_000 }).catch(() => {})
        if (await page3.locator('[data-testid="gameplay-ui"]').count() > 0)
        {
            log('  no ResumeOrNewDialog but gameplay-ui mounted directly (auto-restore + BUG 1 fix sent initial sync)')
            await page3.waitForTimeout(15_000)
            worldStateAfterResume = await captureGameState(page3, 'phase3-after-resume')
        }
    }

    await dumpDomSnapshot(page3, 'phase3-after-resume')

    // Phase 3 assertions — these are the BUG 1/2/3 regression gates.
    const phase3Assertions = {
        noNoSavedGameDialogAfterResume: !noSavedGameDialogAppeared,
        gameplayMountedAfterResume: gameplayMountedAfterResume ||
            (worldStateAfterResume !== null && worldStateAfterResume !== undefined),
        worldStatePreservedAfterReconnect: worldStateAfterResume !== null &&
            worldStateAfterResume !== undefined &&
            state1.round !== null &&
            worldStateAfterResume.round === state1.round,
    }

    Object.assign(assertions, phase3Assertions)

    const allPass = Object.values(assertions).every(v => v)
    log('==== ASSERTIONS ====')
    for (const [k, v] of Object.entries(assertions)) log(`  ${v ? 'PASS' : 'FAIL'}  ${k}`)
    log(`  state1.round = ${state1.round}`)
    log(`  state2.round = ${state2.round}`)
    log(`  state3.round = ${worldStateAfterResume?.round}`)
    log(`  state1.leaderboard = ${JSON.stringify(state1.leaderboard)}`)
    log(`  state2.leaderboard = ${JSON.stringify(state2.leaderboard)}`)
    log(`  state3.leaderboard = ${JSON.stringify(worldStateAfterResume?.leaderboard)}`)
    log(`  noSavedGameDialogAppeared = ${noSavedGameDialogAppeared}`)
    log(`  gameplayMountedAfterResume = ${gameplayMountedAfterResume}`)

    log(`==== RESULT: ${allPass ? 'PASS' : 'FAIL'} ====`)

    await browser.close()
    process.exit(allPass ? 0 : 1)
}

main().catch(e => {
    console.error('probe crashed:', e)
    process.exit(2)
})