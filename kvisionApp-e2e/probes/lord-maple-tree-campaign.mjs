#!/usr/bin/env node
// kvisionApp-e2e/probes/lord-maple-tree-campaign.mjs
//
// LORD MAPLE TREE'S GRAND IMPERIAL CAMPAIGN
// Records the entire browser tab while one turn plays out.
//
// Based on the proven _tmp-one-turn.mjs pattern (verified 2026-07-04):
//   - Uses Playwright's getByRole/getByTestId for click reliability
//   - Targets textarea by placeholder rather than DOM scope
//   - Uses page.click() instead of page.evaluate(b.click()) — Playwright's
//     real-mouse-click is more reliable than synthetic .click() events
//   - Always clicks the "Play vs AI" card BEFORE the step-2 Play button
//
// Output:
//   - video: <artifact-dir>/<session>.webm
//   - snapshots: <artifact-dir>/step-XX.png

import { chromium } from '/home/natan/Desktop/WS/Autogenesis/kvisionApp-e2e/node_modules/@playwright/test/index.mjs'
import { mkdir, writeFile, stat } from 'node:fs/promises'
import { fileURLToPath } from 'node:url'
import { dirname, join } from 'node:path'

const __dirname = dirname(fileURLToPath(import.meta.url))
const ARTIFACT_DIR = join(__dirname, 'artifacts-lord-maple-tree')
await mkdir(ARTIFACT_DIR, { recursive: true })

const BASE_URL = 'http://127.0.0.1:8080'
const log = (s) => console.log(`[${new Date().toISOString().slice(11, 23)}] ${s}`)
const sleep = (ms) => new Promise(r => setTimeout(r, ms))

async function shot(page, name) {
    const fn = `${name}.png`
    await page.screenshot({ path: join(ARTIFACT_DIR, fn), fullPage: true })
    log(`SNAPSHOT ${fn}`)
}

async function dismissModalOk(page, label, totalMs = 4000) {
    const start = Date.now()
    while (Date.now() - start < totalMs) {
        const ok = page.getByRole('button', { name: /^OK$/ }).first()
        if (await ok.isVisible({ timeout: 200 }).catch(() => false)) {
            try { await ok.click({ timeout: 1000 }); log(`  ${label}: dismissed messageBox OK`); return true }
            catch {}
        }
        await sleep(150)
    }
    return false
}

// ---------------------------------------------------------------------------
const browser = await chromium.launch({ headless: true, args: ['--no-sandbox'] })
const context = await browser.newContext({
    viewport: { width: 1920, height: 1080 },
    recordVideo: {
        dir: ARTIFACT_DIR,
        size: { width: 1920, height: 1080 },
    },
})
const page = await context.newPage()
const videoPath = await page.video().path()
log(`Video recorder armed: ${videoPath}`)

const consoleErrors = []
const pageErrors = []
page.on('console', m => {
    const t = m.text()
    if (m.type() === 'error') {
        // Filter the known noise
        if (t.includes('integrity') || t.includes('webpack-dev-server') || t.includes("ws://127.0.0.1:8080/ws' failed")) return
        consoleErrors.push(t.slice(0, 200))
    }
})
page.on('pageerror', e => pageErrors.push(e.message.slice(0, 200)))

let exitCode = 0

try {
    // === LOADING SCREEN ===
    log('Step 1: navigate to index.html')
    await page.goto(`${BASE_URL}/index.html`, { waitUntil: 'domcontentloaded' })
    await sleep(2500)
    await shot(page, 'step-01-loading-screen')

    // Click through loading screen CTA (try multiple selectors for safety)
    for (const sel of ['button:has-text("LOAD")', 'button:has-text("Enter")', 'button:has-text("Begin")', '.loading-screen button']) {
        const el = page.locator(sel).first()
        if (await el.isVisible({ timeout: 1500 }).catch(() => false)) {
            await el.click()
            log(`  clicked '${sel}'`)
            break
        }
    }
    await page.waitForSelector('input[type="email"]', { timeout: 15_000 })
    await shot(page, 'step-02-login-page')

    // === LOGIN ===
    log('Step 2: Login As Guest')
    await page.locator('[data-testid="login-as-guest"]').first().click()
    log('  clicked Login As Guest')
    await dismissModalOk(page, 'login-modal', 6000)

    // Wait for MainMenu
    log('Step 3: wait for MainMenu (PLAY button)')
    await page.waitForSelector('button:has-text("PLAY")', { timeout: 30_000 })
    log('  MainMenu mounted')

    // Handle Resume dialog (if it appears)
    log('Step 3b: handle Resume dialog if it appears')
    for (let i = 0; i < 30; i++) {
        const clicked = await page.evaluate(() => {
            const d = document.querySelector('[data-testid="resume-or-new-dialog"]')
            if (!d || d.offsetWidth === 0) return false
            for (const b of d.querySelectorAll('button'))
                if (b.textContent.trim() === 'New Game') { b.click(); return true }
            return false
        })
        if (clicked) { log('  Resume dialog dismissed via "New Game"'); break }
        await sleep(500)
    }
    await sleep(2000)
    await shot(page, 'step-03-main-menu')

    // === WIZARD ===
    // Check state: we may already be inside the CommanderSelectionDialog wizard
    // (because clicking "New Game" on the Resume dialog opens the wizard
    // directly, bypassing the MainMenu PLAY button).
    log('Step 4: detect wizard vs MainMenu state')
    await sleep(2500)
    let inWizard = await page.locator('.commander-selection-overlay').count() > 0
    if (!inWizard) {
        log('  no wizard yet — clicking MainMenu PLAY')
        await page.locator('button:has-text("PLAY")').first().click({ timeout: 10_000 })
        await sleep(3000)
        inWizard = await page.locator('.commander-selection-overlay').count() > 0
    }
    log(`  in wizard: ${inWizard}`)
    await shot(page, 'step-04-wizard-step1')

    log('Step 5: select Lord Maple Tree commander card')
    await page.waitForSelector('.commander-selection-overlay', { timeout: 15_000 })
    // Wait a bit for the wizard animations to settle
    await sleep(1500)
    // Click the first commander card (which is Lord Maple Tree in this tenant)
    const cardCount = await page.locator('.commander-selection-list .commander-selection-card').count()
    log(`  found ${cardCount} commander card(s)`)
    await page.locator('.commander-selection-list .commander-selection-card').first().click()
    log('  clicked first commander card')
    await sleep(1500)
    await shot(page, 'step-05-commander-selected')

    log('Step 6: click Next (wizard step 1 → step 2)')
    await page.getByRole('button', { name: /^Next$/ }).first().click()
    log('  clicked Next')
    await sleep(2000)
    await shot(page, 'step-06-wizard-step2')

    log('Step 7: click Play vs AI card (wizard step 2)')
    const step2Cards = await page.locator('.commander-selection-step-2 .commander-selection-card').count()
    log(`  found ${step2Cards} step-2 card(s)`)
    if (step2Cards > 0) {
        await page.locator('.commander-selection-step-2 .commander-selection-card').first().click()
        log('  clicked first step-2 card (Play vs AI)')
        await sleep(800)
    }

    log('Step 8: click PLAY (wizard step 2 confirm)')
    const playBtn = page.getByRole('button', { name: /^Play$/ }).first()
    await playBtn.waitFor({ state: 'visible', timeout: 10_000 })
    await playBtn.click()
    log('  clicked Play')
    await sleep(8000)
    await dismissModalOk(page, 'enter-game', 10_000)
    await shot(page, 'step-07-match-ready')

    // === ACTION ===
    log('Step 9: locate action textarea')
    const actionTextarea = page.locator('textarea[placeholder*="Type the action" i]').first()
    await actionTextarea.waitFor({ timeout: 20_000 })
    log('  action textarea visible')
    await sleep(3000)
    await shot(page, 'step-08-gameplay-ready')

    const COMMAND_TEXT = 'Dispatch the Slave Lake Ent Army southward to secure the maple syrup reserves. Hold the forests, advance the syrup, and crush any opposition with extreme prejudice. For the syrup, the pancakes, and the inevitable glory of the Golden Forest.'
    log(`Step 10: fill action (${COMMAND_TEXT.length} chars)`)
    await actionTextarea.fill(COMMAND_TEXT)
    await sleep(500)
    await shot(page, 'step-09-command-typed')

    log('Step 11: click Send')
    const sendBtn = page.getByRole('button', { name: /^Send$/ }).first()
    await sendBtn.waitFor({ state: 'visible', timeout: 5_000 })
    await sendBtn.click()
    log('  clicked Send')

    // Wait for the turn cycle to complete (narrative visible OR textarea cleared)
    log('Step 12: wait up to 6 min for turn to complete')
    const start = Date.now()
    let sawNarrative = false
    let phaseSeen = new Set()
    let lastSnapshot = 0
    while (Date.now() - start < 6 * 60 * 1000) {
        await dismissModalOk(page, 'response-modal', 1500)

        // Phase icons
        const phases = await page.evaluate(() => {
            const active = []
            const map = {
                'fa-flag': 'Start', 'fa-terminal': 'Action', 'fa-brain': 'Planning',
                'fa-pen-nib': 'Writing', 'fa-gavel': 'Judging', 'fa-truck-loading': 'Dispatch',
                'fa-users': 'NPCs', 'fa-globe-americas': 'World',
            }
            for (const ic of document.querySelectorAll('.fa-pulse')) {
                for (const [cls, name] of Object.entries(map)) {
                    if (ic.classList.contains(cls)) { active.push(name); break }
                }
            }
            return active
        })
        for (const phase of phases) {
            if (!phaseSeen.has(phase)) {
                phaseSeen.add(phase)
                log(`  PHASE: ${phase} (t=${((Date.now() - start) / 1000).toFixed(0)}s)`)
                await shot(page, `step-10-phase-${phase.toLowerCase()}`)
            }
        }

        // Narrative visible?
        const hasNarrative = await page.locator('.narrative, .narrative-window, .gameplay-narrative').first().isVisible({ timeout: 200 }).catch(() => false)
        if (hasNarrative) {
            sawNarrative = true
            log('  narrative visible')
            // Take snapshot and capture another 60s of activity, then break
            await shot(page, 'step-11-narrative')
            // Wait for World phase (last visible phase) then break
            if (phaseSeen.has('World')) {
                await sleep(5000)
                await shot(page, 'step-12-world-complete')
                break
            }
        }

        // Textarea cleared (action submitted)
        const cur = await actionTextarea.inputValue().catch(() => '')
        if (cur === '' && Date.now() - start > 5_000) {
            log('  textarea cleared (action submitted, AI processing)')
        }

        // Periodic snapshot every 30s
        if (Date.now() - lastSnapshot > 30_000) {
            await shot(page, `step-99-midgame-${Math.floor((Date.now() - start) / 1000)}s`)
            lastSnapshot = Date.now()
        }

        await sleep(3000)
    }

    log('Turn cycle complete. Final snapshot...')
    await shot(page, 'step-final')

    log('Stopping video recorder...')
} catch (err) {
    log(`FAIL: ${err.message}`)
    await shot(page, 'FAIL')
    exitCode = 1
} finally {
    await browser.close()
    try {
        const s = await stat(videoPath)
        log(`VIDEO: ${videoPath} (${s.size} bytes)`)
    } catch (e) {
        log(`VIDEO stat failed: ${e.message}`)
    }
    log(`console errors (filtered): ${consoleErrors.length}`)
    log(`page errors: ${pageErrors.length}`)
    if (consoleErrors.length) {
        log('  first 3 console errors:')
        consoleErrors.slice(0, 3).forEach(e => log(`    ${e}`))
    }
    log(`Result: ${exitCode === 0 ? 'PASS' : 'FAIL'}`)
    log(`Artifact dir: ${ARTIFACT_DIR}`)
    process.exit(exitCode)
}
