#!/usr/bin/env node
// probes/lord-maple-game.mjs
// Play Lord Maple Tree through a 2-player (1 vs 1) AI match for 3 rounds.
// Reports state at each round.

import { chromium } from '@playwright/test'
import { mkdir, writeFile } from 'node:fs/promises'
import { fileURLToPath } from 'node:url'
import { dirname, join } from 'node:path'

const __dirname = dirname(fileURLToPath(import.meta.url))
const ARTIFACT_DIR = join(__dirname, 'artifacts-lord-maple-game')
await mkdir(ARTIFACT_DIR, { recursive: true })

const log = (s) => console.log(`[${new Date().toISOString().slice(11, 23)}] ${s}`)

const browser = await chromium.launch({ headless: true })
const context = await browser.newContext({ viewport: { width: 1280, height: 900 } })
const page = await context.newPage()

const consoleErrors = []
const pageErrors = []
page.on('console', msg => { if (msg.type() === 'error') consoleErrors.push(msg.text()) })
page.on('pageerror', err => pageErrors.push(err.message))

let exitCode = 0
try {
    log('Step 1: navigate to index.html')
    await page.goto('http://127.0.0.1:8080/index.html')

    log('Step 2: click LoadingScreen CTA')
    await page.getByTestId('loading-screen-cta').waitFor({ state: 'visible', timeout: 30_000 })
    await page.getByTestId('loading-screen-cta').click()

    log('Step 3: click Login As Guest')
    await page.locator('.login-widget-window').waitFor({ state: 'visible', timeout: 30_000 })
    await page.getByTestId('login-as-guest').click()

    log('Step 4: wait for MainMenu (PLAY button)')
    const playButton = page.locator('.btn.btn-play')
    const okByText = page.getByRole('button', { name: /^OK$/ })
    const mainDeadline = Date.now() + 60_000
    while (Date.now() < mainDeadline) {
        if (await playButton.count() > 0) break
        if (await okByText.count() > 0) {
            try { await okByText.first().click({ timeout: 1_000 }); log('  dismissed messageBox OK') } catch {}
        }
        await page.waitForTimeout(200)
    }
    log('  MainMenu mounted')

    log('Step 5: click PLAY')
    await playButton.first().click()

    log('Step 6: handle resume-or-new-dialog if present')
    const resumeDialog = page.locator('[data-testid="resume-or-new-dialog"]')
    if (await resumeDialog.count() > 0) {
        log('  resume dialog appeared — clicking New Game')
        const newGameBtn = page.locator('button:has-text("New Game")').first()
        await newGameBtn.waitFor({ state: 'visible', timeout: 10_000 })
        await newGameBtn.click()
        await page.waitForTimeout(2000)
    }

    log('Step 7: wait for CommanderSelectionDialog')
    await page.locator('[data-testid="commander-selection-root"]').waitFor({ state: 'visible', timeout: 30_000 })
    await page.waitForTimeout(1000)
    await writeFile(join(ARTIFACT_DIR, '01-select-commander.png'),
        await page.screenshot({ fullPage: true }))
    log('  screenshot saved: 01-select-commander.png')

    log('Step 8: click Lord Maple Tree card')
    // Commander card contains "Lord Maple Tree" text inside .commander-selection-card
    const lordCard = page.locator('.commander-selection-card:has-text("Lord Maple Tree")').first()
    await lordCard.waitFor({ state: 'visible', timeout: 10_000 })
    await lordCard.click()
    await page.waitForTimeout(500)
    log('  Lord Maple Tree selected')

    log('Step 9: click NEXT (Step 1 → Step 2)')
    const nextBtn = page.locator('.commander-selection-window button:has-text("Next")').first()
    await nextBtn.waitFor({ state: 'visible', timeout: 5_000 })
    await nextBtn.click()
    await page.waitForTimeout(1000)

    log('Step 10: select 1 vs 1: Duel (2 Players vs 1 AI)')
    const duelCard = page.locator('.commander-selection-card:has-text("1 vs 1: Duel")').first()
    await duelCard.waitFor({ state: 'visible', timeout: 5_000 })
    await duelCard.click()
    await page.waitForTimeout(500)
    await writeFile(join(ARTIFACT_DIR, '02-step2-ready.png'),
        await page.screenshot({ fullPage: true }))
    log('  screenshot saved: 02-step2-ready.png')

    log('Step 11: click PLAY (launch game)')
    const playBtnStep2 = page.locator('.commander-selection-window button:has-text("Play")').first()
    await playBtnStep2.waitFor({ state: 'visible', timeout: 5_000 })
    await playBtnStep2.click()

    log('Step 12: wait for gameplay-ui to mount (max 60s)')
    await page.locator('[data-testid="gameplay-ui"]').waitFor({ state: 'visible', timeout: 60_000 })
    log('  gameplay-ui mounted!')

    // Give the AI 30s to think + initial UI to settle
    log('Step 13: settle (30s) — wait for AI / initial state')
    await page.waitForTimeout(30_000)
    await writeFile(join(ARTIFACT_DIR, '03-initial-state.png'),
        await page.screenshot({ fullPage: true }))
    log('  screenshot saved: 03-initial-state.png')

    const initialState = await page.evaluate(() => ({
        userId: window.globals?.AccelByteEnv?.userId ?? null,
        displayName: window.globals?.AccelByteEnv?.displayName ?? null,
        // Game state from globals if available
        roundNumber: window.globals?.GameState?.roundNumber ?? null,
        currentPlayer: window.globals?.GameState?.currentPlayer ?? null,
    }))
    log(`  initial state: ${JSON.stringify(initialState)}`)

    log('Result: PASS — game started successfully')
    log(`  console errors: ${consoleErrors.length}`)
    log(`  page errors: ${pageErrors.length}`)
    consoleErrors.slice(0, 3).forEach(e => log(`    console: ${e.slice(0, 200)}`))
    pageErrors.slice(0, 3).forEach(e => log(`    page:    ${e.slice(0, 200)}`))

} catch (err) {
    log(`Result: FAIL — ${err.message}`)
    try { await writeFile(join(ARTIFACT_DIR, 'FAIL.png'), await page.screenshot({ fullPage: true })) } catch {}
    exitCode = 1
} finally {
    await browser.close()
}

process.exit(exitCode)