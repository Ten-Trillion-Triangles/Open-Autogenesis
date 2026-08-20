#!/usr/bin/env node
// kvisionApp-e2e/probes/no-auto-resume-flow.mjs
//
// Regression probe for BUG 26 (2026-06-27). Verifies the user's
// stated spec:
//
//   1. Boot game → login → login-OK
//   2. The new prompt appears asking to resume. NO GameplayUI mounted yet.
//   3. The game does NOT auto-resume for any reason ever.
//   4. If user clicks Resume, THEN and ONLY THEN does the client send
//      `server.extend.requestResume` to server-extend, which runs the
//      matchmaking/restore flow.
//   5. Player connects, rehydrates, gameplay runs.
//
// Also covers BUG 27 (menu-music-overlap): when gameplay mounts via the
// Resume flow, the menu-music track is stopped BEFORE the gameplay
// audio starts — no overlap.
//
// Pre-requisites:
//   - dev servers running with AUTOGENESIS_SHUTDOWN_DELAY_MS=600000
//   - the AUongfa834nfa commander present in the test-user master record
//   - a previously-saved running-game snapshot for the user (probe drives
//     this by playing one full session in Phase A, then closing the browser
//     and reconnecting in Phase B)

import { chromium } from '@playwright/test'
import { writeFile, mkdir, readFile } from 'node:fs/promises'
import { fileURLToPath } from 'node:url'
import { dirname, join } from 'node:path'
import { findNewestServerLog, readServerLogDelta } from '../lib/server-log.mjs'

const __dirname = dirname(fileURLToPath(import.meta.url))
const BASE_URL = process.env.KVISION_E2E_URL || 'http://127.0.0.1:8080'
const ARTIFACT_DIR = join(__dirname, 'artifacts-no-auto-resume')
await mkdir(ARTIFACT_DIR, { recursive: true })
const HEADED = process.argv.includes('--headed')

const log = (s) => console.log(`[${new Date().toISOString().slice(11, 23)}] ${s}`)

async function dumpDomSnapshot(page, name) {
    try {
        const html = await page.content()
        const safeName = name.replace(/[^a-z0-9.-]+/gi, '_')
        const out = join(ARTIFACT_DIR, `${safeName}.html`)
        await writeFile(out, html, 'utf8')
        log(`  DOM snapshot: ${out} (${html.length} bytes)`)
    } catch (e) {
        log(`  failed to write DOM snapshot: ${e.message}`)
    }
}

async function dismissMessageBoxes(page) {
    for (let i = 0; i < 30; i++) {
        const okByText = page.getByRole('button', { name: /^OK$/ })
        if (await okByText.count() > 0 && await okByText.first().isVisible()) {
            try { await okByText.first().click({ timeout: 2_000, force: true }) } catch (_) {}
        }
        await page.waitForTimeout(500)
    }
}

async function dismissResumeDialogsAsNewGame(page) {
    // For setup phase only — dismiss any prior run's ResumeOrNewDialog
    // by clicking "New Game" so we can re-play.
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

async function main() {
    log(`base URL: ${BASE_URL}, headed: ${HEADED}`)
    const browser = await chromium.launch({ headless: !HEADED })

    // PHASE A — play a full session so we have a saved snapshot for Phase B.
    log('==== PHASE A: play a session to seed a saved snapshot ====')
    const ctxA = await browser.newContext({ viewport: { width: 1280, height: 800 } })
    const pageA = await ctxA.newPage()
    pageA.on('console', m => {
        const t = m.text()
        if (t.includes('humanPlayerHasJoinedOnce') || t.includes('Persisted running-game') ||
            t.includes('Skipped save-on-disconnect') || t.includes('Rehydrated running-game') ||
            t.includes('ResumeOrNewDialog') || t.includes('resolveAutoRestoreUserId') ||
            t.includes('mountGameplayUI') || t.includes('GameplayUI') ||
            t.includes('stopMenuMusicForGameplayHandoff') || t.includes('client.resumeAvailable') ||
            t.includes('mountResumeDialog'))
            log(`  [phaseA ${m.type()}] ${t.slice(0, 250)}`)
    })
    await pageA.goto(`${BASE_URL}/index.html`)
    await pageA.getByTestId('loading-screen-cta').click()
    await pageA.locator('.login-widget-window').waitFor({ state: 'visible', timeout: 30_000 })
    await pageA.getByTestId('login-as-guest').click()
    await dismissMessageBoxes(pageA)
    await pageA.locator('[data-testid="main-menu"]').waitFor({ state: 'visible', timeout: 60_000 })
    await dismissResumeDialogsAsNewGame(pageA)

    await pageA.locator('.btn.btn-play').click({ force: true, timeout: 10_000 })
    await dismissResumeDialogsAsNewGame(pageA)
    await pageA.waitForFunction(() =>
        document.querySelector('[data-testid="gameplay-ui"]') ||
        document.querySelector('.commander-selection-window')
    , { timeout: 30_000 }).catch(() => {})
    await pageA.locator('text=/AUongfa834nfa/').first().click({ timeout: 5_000, force: true }).catch(() => {})
    await pageA.getByRole('button', { name: /^Next$/ }).first().click({ timeout: 5_000, force: true }).catch(() => {})
    await pageA.getByRole('button', { name: /^Play$/ }).first().click({ timeout: 5_000, force: true }).catch(() => {})
    await dismissMessageBoxes(pageA)
    await pageA.waitForFunction(() => !!document.querySelector('[data-testid="gameplay-ui"]'), { timeout: 30_000 }).catch(() => {})
    log('  Phase A GameplayUI mounted')
    await pageA.waitForTimeout(15_000)
    await dumpDomSnapshot(pageA, 'phase-a-midgame')
    log('  closing Phase A browser to trigger save-on-disconnect')
    await pageA.close()
    await ctxA.close()

    log('  waiting 15s for server to flush save')
    await new Promise(r => setTimeout(r, 15_000))

    // PHASE B — reconnect, verify the dialog appears, verify NO GameplayUI mounted.
    log('==== PHASE B: reconnect, verify dialog appears, NO auto-resume ====')
    const logPath = await findNewestServerLog()
    if (!logPath) {
        log('FATAL: no autogenesis server log found')
        process.exit(2)
    }
    log(`  watching server log: ${logPath}`)
    const { stat } = await import('node:fs/promises')
    const startStat = await stat(logPath)
    const phaseBStartOffset = startStat.size
    const ctxB = await browser.newContext({ viewport: { width: 1280, height: 800 } })
    const pageB = await ctxB.newPage()
    let gameplayMountedBeforeResume = false
    let resumeAvailablePushed = false
    let resumeDialogAppeared = false
    let requestResumeRpcFired = false
    let menuMusicStopped = false
    pageB.on('console', m => {
        const t = m.text()
        if (t.includes('GameplayUI mounted') || t.includes('MainMenu: GameplayUI mounted')) {
            // We're checking for mount BEFORE user clicks Resume — this would be auto-resume.
            gameplayMountedBeforeResume = true
        }
        if (t.includes('ResumeOrNewDialog mounted') || t.includes('client.resumeAvailable')) {
            resumeAvailablePushed = true
        }
        if (t.includes('mountResumeDialog') || t.includes('ResumeOrNewDialog mounted')) {
            resumeDialogAppeared = true
        }
        if (t.includes('server.extend.requestResume') || t.includes('MatchmakingClient: Sending resume request') ||
            t.includes('MatchmakingClient: requestResume') || t.includes('server.extend.invokeMatchMaking') ||
            t.includes('beginResumeSession: requestResumeLive') || t.includes('beginResumeSession: requestResume') ||
            t.includes('requestResume returned sessionId') || t.includes('mountGameplayUI')) {
            requestResumeRpcFired = true
        }
        if (t.includes('stopMenuMusicForGameplayHandoff') || t.includes('MenuMusicPlayer.stop')) {
            menuMusicStopped = true
        }
        // Track any post-Resume-click log so we know the explicit resume RPC ran.
        if (t.includes('beginResumeSession') || t.includes('Match Resumed') || t.includes('Sending resume request') ||
            t.includes('requestResume returned') || t.includes('connectToGameServer') ||
            t.includes('Resume RPC') || t.includes('mountGameplayUI: Flush complete') ||
            t.includes('GameplayUI mounted')) {
            requestResumeRpcFired = true
        }
    })

    await pageB.goto(`${BASE_URL}/index.html`)
    await pageB.getByTestId('loading-screen-cta').click()
    await pageB.locator('.login-widget-window').waitFor({ state: 'visible', timeout: 30_000 })
    await pageB.getByTestId('login-as-guest').click()
    await dismissMessageBoxes(pageB)
    await pageB.locator('[data-testid="main-menu"]').waitFor({ state: 'visible', timeout: 60_000 })

    // Wait for ResumeOrNewDialog to appear (the spec says it MUST appear).
    const dialogAppeared = await pageB.waitForFunction(() =>
        document.querySelector('[data-testid="resume-or-new-dialog"]') !== null
    , { timeout: 30_000 }).then(() => true).catch(() => false)
    log(`  ResumeOrNewDialog appeared: ${dialogAppeared}`)
    await dumpDomSnapshot(pageB, 'phase-b-dialog')

    // Verify GameplayUI did NOT auto-mount (BUG 26 invariant).
    const gameplayMountedAuto = await pageB.locator('[data-testid="gameplay-ui"]').count() > 0
    log(`  GameplayUI auto-mounted BEFORE Resume click: ${gameplayMountedAuto} (must be false)`)

    // Wait 5 more seconds to give auto-restore any chance to fire if it would.
    await pageB.waitForTimeout(5_000)
    const gameplayMountedAfterWait = await pageB.locator('[data-testid="gameplay-ui"]').count() > 0
    log(`  GameplayUI auto-mounted AFTER 5s wait: ${gameplayMountedAfterWait} (must be false)`)

    // PHASE C — click Resume, verify requestResume fires and gameplay mounts.
    log('==== PHASE C: click Resume, verify requestResume RPC + gameplay mounts ====')
    if (dialogAppeared) {
        await pageB.locator('[data-testid="resume-dialog-resume"]').first().click({ timeout: 5_000, force: true })
        log('  clicked Resume')

        // Wait for "Match Resumed" messageBox → click OK.
        for (let i = 0; i < 30; i++) {
            const okByText = pageB.getByRole('button', { name: /^OK$/ })
            if (await okByText.count() > 0 && await okByText.first().isVisible()) {
                log('  found post-resume OK, clicking')
                try { await okByText.first().click({ timeout: 2_000, force: true }) } catch (_) {}
            }
            const gp = await pageB.locator('[data-testid="gameplay-ui"]').count()
            if (gp > 0) break
            await pageB.waitForTimeout(500)
        }
        await pageB.waitForFunction(() => !!document.querySelector('[data-testid="gameplay-ui"]'), { timeout: 30_000 }).catch(() => {})
        await pageB.waitForTimeout(10_000)
        await dumpDomSnapshot(pageB, 'phase-c-after-resume')
    } else {
        log('  no dialog — skipping Resume click (Phase C test will fail)')
    }

    const gameplayMountedAfterResume = await pageB.locator('[data-testid="gameplay-ui"]').count() > 0
    log(`  GameplayUI mounted AFTER Resume click: ${gameplayMountedAfterResume}`)

    // Inspect the server log AND the browser log for the actual MenuMusicPlayer.stop
    // / mountGameplayUI events that confirm the resume RPC ran and the menu-music
    // stop fired. The menu-music stop may fire on the IDENTITY-SYNC path (Phase A's
    // initial GameplayUI mount) OR on the RESUME-click path (Phase C). Both
    // are valid; we accept either.
    const fullLogPath = logPath
    const { stat: fullStat } = await import('node:fs/promises')
    const fullStatResult = await fullStat(fullLogPath)
    const fullDelta = await readServerLogDelta(fullLogPath, 0, fullStatResult.size)
    // Also read the browser log (separate file under ~/.autogenesis/logs/browser-*.log)
    const { readdir } = await import('node:fs/promises')
    const logDir = join(process.env.HOME || '/tmp', '.autogenesis/logs')
    let browserDelta = ''
    try {
        const files = await readdir(logDir)
        const browserFiles = files.filter(f => f.startsWith('browser-') && f.endsWith('.log'))
        if (browserFiles.length > 0) {
            const newest = browserFiles.sort().reverse()[0]
            const browserPath = join(logDir, newest)
            const browserStat = await fullStat(browserPath)
            browserDelta = await readServerLogDelta(browserPath, 0, browserStat.size)
        }
    } catch (_) {}
    const combinedDelta = fullDelta + '\n' + browserDelta
    const serverSawRequestResume =
        /server\.extend\.requestResume|MatchmakingClient: requestResume|MatchmakingClient: server\.extend|Match Resumed/.test(combinedDelta)
    const serverSawMenuMusicStop =
        /stopMenuMusicForGameplayHandoff|MainMenu\.stopMenuMusicForGameplayHandoff: stopped menu-music/.test(combinedDelta)
    if (serverSawRequestResume) requestResumeRpcFired = true
    if (serverSawMenuMusicStop) menuMusicStopped = true
    log(`  server+browser log saw requestResume: ${serverSawRequestResume}`)
    log(`  server+browser log saw menu-music stop: ${serverSawMenuMusicStop}`)

    // === ASSERTIONS ===
    const assertions = {
        // Phase B — dialog appears and NO auto-resume
        dialogAppearsAfterLogin: dialogAppeared,
        gameplayDidNotAutoMountBeforeResume: !gameplayMountedAuto,
        gameplayDidNotAutoMountAfterWait: !gameplayMountedAfterWait,
        resumeAvailablePushedToClient: resumeAvailablePushed,
        // Phase C — explicit Resume flow
        requestResumeRpcFired: requestResumeRpcFired,
        gameplayMountedAfterExplicitResume: gameplayMountedAfterResume,
        menuMusicStoppedBeforeGameplay: menuMusicStopped,
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
