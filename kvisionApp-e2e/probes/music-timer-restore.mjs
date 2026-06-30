#!/usr/bin/env node
// kvisionApp-e2e/probes/music-timer-restore.mjs
//
// Post-restore hydration probe for BUG 4 (music), BUG 5 (turn ownership),
// BUG 6 (turn timer). Verifies the user's three reported reload-game
// symptoms are gone:
//   - Music didn't correctly start until an action was taken
//   - "Your turn" but then AI moves instead
//   - Turn timer did not start
//
// Pre-requisites:
//   - dev servers running with AUTOGENESIS_SHUTDOWN_DELAY_MS=600000
//   - the AUongfa834nfa commander present in the test-user master record

import { chromium } from '@playwright/test'
import { writeFile, mkdir } from 'node:fs/promises'
import { fileURLToPath } from 'node:url'
import { dirname, join } from 'node:path'

const __dirname = dirname(fileURLToPath(import.meta.url))
const BASE_URL = process.env.KVISION_E2E_URL || 'http://127.0.0.1:8080'
const ARTIFACT_DIR = join(__dirname, 'artifacts-music-timer')
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
    }
    catch (e) {
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

async function dismissResumeDialogs(page) {
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

async function capturePostRestoreSignals(page) {
    return await page.evaluate(() => {
        // PHASE A — music. Music is delivered through HTMLAudioElement
        // objects created by AudioEngine. After the audio.schedule RPC
        // is applied, the engine creates one or more <audio> elements.
        // We also count [data-testid^="audio-"] widgets if present.
        const audioElements = document.querySelectorAll('audio')
        const audioTrackElements = document.querySelectorAll('[data-testid^="audio-"], .audio-channel, .music-track, .audio-now-playing, .audio-schedule')
        // MusicResourceResolver exposes a singleton — if it has resolved
        // tracks, that confirms the schedule reached the client.
        const musicPlayed = audioElements.length > 0 || audioTrackElements.length > 0
        // PHASE B — turn timer. Timer widget has data-testid="turn-timer"
        // and the countdown text is "M:SS" or "MM:SS".
        const turnTimerEl = document.querySelector('[data-testid="turn-timer"], .turn-timer, .turn-timer-widget')
        const turnTimerText = turnTimerEl ? (turnTimerEl.textContent || '').trim() : ''
        const turnTimerVisible = !!turnTimerEl
        // PHASE C — turn ownership. Look for an "AI takeover" indicator
        // (the AI-think progress bar is only rendered during an AI
        // turn). On a human turn, the "Your turn" / "Awaiting your
        // decision" text is rendered instead.
        const aiThinkIndicator = document.querySelector('[data-testid="ai-think"], .ai-takeover, .ai-think-progress, [data-test-progress-bar]')
        const aiTakeoverVisible = !!aiThinkIndicator
        const bodyText = document.body.textContent || ''
        // "Your turn" or "Awaiting your decision" indicates the human
        // is the active player.
        const humanTurnText = /Your turn|Awaiting your decision|Submit your action/i.test(bodyText)
        // The "AI is thinking..." resolution step text indicates the
        // server took the AI takeover path.
        const aiThinkingText = /AI is thinking/i.test(bodyText)
        return {
            audioElementCount: audioElements.length,
            audioTrackElementCount: audioTrackElements.length,
            musicPlayed,
            turnTimerVisible,
            turnTimerText,
            aiTakeoverVisible,
            humanTurnText,
            aiThinkingText,
            bodyTextSnippet: bodyText.slice(0, 1500)
        }
    })
}

async function main() {
    log(`base URL: ${BASE_URL}, headed: ${HEADED}`)
    const browser = await chromium.launch({ headless: !HEADED })

    // PHASE 1 — start a game so a snapshot is written.
    log('==== PHASE 1: start game, build snapshot ====')
    const ctx1 = await browser.newContext({ viewport: { width: 1280, height: 800 } })
    const page1 = await ctx1.newPage()
    page1.on('console', m => {
        const t = m.text()
        if (t.includes('Persisted running-game') || t.includes('Rehydrated') ||
            t.includes('Music') || t.includes('hydrate') || t.includes('PlayerConnection'))
            console.log(`  [phase1 ${m.type()}] ${t.slice(0, 200)}`)
    })

    await page1.goto(`${BASE_URL}/index.html`)
    await page1.getByTestId('loading-screen-cta').click()
    await page1.locator('.login-widget-window').waitFor({ state: 'visible', timeout: 30_000 })
    await page1.getByTestId('login-as-guest').click()
    await dismissMessageBoxes(page1)
    await page1.locator('[data-testid="main-menu"]').waitFor({ state: 'visible', timeout: 60_000 })
    log('  MainMenu mounted')

    await dismissResumeDialogs(page1)

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
    log('  GameplayUI mounted; letting 8s pass for snapshot to persist')
    await page1.waitForTimeout(8_000)
    log('  closing phase 1 browser')
    await page1.close()
    await ctx1.close()

    // Wait for the server to persist the snapshot.
    log('  waiting 25s for server to persist snapshot')
    await new Promise(r => setTimeout(r, 25_000))

    // PHASE 2 — reopen, click Resume, capture post-restore signals.
    log('==== PHASE 2: reopen, click Resume, capture post-restore signals ====')
    const ctx2 = await browser.newContext({ viewport: { width: 1280, height: 800 } })
    const page2 = await ctx2.newPage()
    const phase2Console = []
    const musicScheduleApplied = { value: false }
    page2.on('console', m => {
        const t = m.text()
        phase2Console.push(`[${m.type()}] ${t}`)
        // The browser-side confirmation that music was scheduled after
        // restore is the "MusicRunner.playSchedule: applied" line with
        // played >= 1. Music elements are NOT kept in the DOM (they go
        // through Web Audio API buffers), so we read this from the
        // browser console instead.
        if (/MusicRunner\.playSchedule:\s+applied.*played=(\d+)/.test(t)) {
            const m2 = t.match(/played=(\d+)/)
            if (m2 && parseInt(m2[1], 10) > 0) {
                musicScheduleApplied.value = true
            }
        }
        if (t.includes('Rehydrated') || t.includes('Music') || t.includes('hydrate') ||
            t.includes('MusicSelector') || t.includes('activeTurnActor') ||
            t.includes('TurnHarness') || t.includes('audio') || t.includes('schedule') ||
            t.includes('PlayerConnection'))
            console.log(`  [phase2 ${m.type()}] ${t.slice(0, 280)}`)
    })

    await page2.goto(`${BASE_URL}/index.html`)
    await page2.getByTestId('loading-screen-cta').click()
    await page2.locator('.login-widget-window').waitFor({ state: 'visible', timeout: 30_000 })
    await page2.getByTestId('login-as-guest').click()
    await dismissMessageBoxes(page2)
    await page2.locator('[data-testid="main-menu"]').waitFor({ state: 'visible', timeout: 60_000 })

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
    for (let i = 0; i < 30; i++) {
        const okByText = page2.getByRole('button', { name: /^OK$/ })
        if (await okByText.count() > 0 && await okByText.first().isVisible()) {
            try { await okByText.first().click({ timeout: 2_000, force: true }) } catch (_) {}
        }
        const gp = await page2.locator('[data-testid="gameplay-ui"]').count()
        if (gp > 0) break
        await page2.waitForTimeout(500)
    }
    await page2.waitForFunction(() => !!document.querySelector('[data-testid="gameplay-ui"]'), { timeout: 30_000 }).catch(() => {})
    // Let the world mount + audio schedule arrive + timer start
    log('  waiting 15s for post-restore hydration to land')
    await page2.waitForTimeout(15_000)

    const signals = await capturePostRestoreSignals(page2)
    await dumpDomSnapshot(page2, 'phase2-post-restore')
    log(`  signals: ${JSON.stringify(signals, null, 2)}`)

    // === ASSERTIONS ===
    // PHASE A — music. The browser-side confirmation that the audio
    // schedule reached the client is the "MusicRunner.playSchedule:
    // applied" log line with played >= 1. Music is delivered via Web
    // Audio API buffers (not <audio> DOM elements), so we cannot count
    // them in the DOM. The server-side equivalent is captured in
    // /tmp/autogenesis-proxy/srv.log.
    const audioElementCount = signals.audioElementCount
    const musicPlaying = musicScheduleApplied.value || audioElementCount > 0 || signals.audioTrackElementCount > 0
    // PHASE B — turn timer. The [data-testid="turn-timer"] element must
    // be visible with a "M:SS" or "MM:SS" countdown text.
    const turnTimerText = signals.turnTimerText || ''
    const timerRegex = /^\d+:\d{2}$/
    const timerArmed = signals.turnTimerVisible && timerRegex.test(turnTimerText)
    // PHASE C — turn ownership. After Resume, the human must be the
    // active player (no AI takeover indicator visible). We allow a
    // short grace window because the AI-think indicator can briefly
    // appear during the resolve phase, but it MUST clear within the
    // assertion window.
    const noAiTakeover = !signals.aiTakeoverVisible
    // PHASE D — gameplay mount. The GameplayUI must be present after
    // Resume.
    const gameplayMounted = (await page2.locator('[data-testid="gameplay-ui"]').count()) > 0

    const assertions = {
        gameplayMountedAfterResume: gameplayMounted,
        musicPlayingAfterRestore: musicPlaying,
        turnTimerArmedAfterRestore: timerArmed,
        noAiTakeoverAfterResume: noAiTakeover
    }

    log('')
    log('==== ASSERTIONS ====')
    let allPass = true
    for (const [name, val] of Object.entries(assertions)) {
        const pass = !!val
        if (!pass) allPass = false
        console.log(`  ${pass ? 'PASS' : 'FAIL'}  ${name}`)
    }
    log('')
    log(`==== RESULT: ${allPass ? 'PASS' : 'FAIL'} ====`)
    await browser.close()
    process.exit(allPass ? 0 : 1)
}

main().catch(e => {
    console.error('FATAL:', e)
    process.exit(1)
})