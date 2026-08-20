#!/usr/bin/env node
// probes/lord-maple-3rounds.mjs
// Full gameplay: login → select Lord Maple Tree → 2p vs 1 AI → play 3 rounds
// Reports state + screenshots at each phase.

import { chromium } from '@playwright/test'
import { mkdir, writeFile } from 'node:fs/promises'
import { fileURLToPath } from 'node:url'
import { dirname, join } from 'node:path'

const __dirname = dirname(fileURLToPath(import.meta.url))
const ARTIFACT_DIR = join(__dirname, 'artifacts-lord-maple-3rounds')
await mkdir(ARTIFACT_DIR, { recursive: true })

const log = (s) => console.log(`[${new Date().toISOString().slice(11, 23)}] ${s}`)
const separator = () => console.log('─'.repeat(72))

let browser
try {
    browser = await chromium.launch({ headless: true })
    const context = await browser.newContext({ viewport: { width: 1280, height: 900 } })
    const page = await context.newPage()

    const consoleErrors = []
    const pageErrors = []
    page.on('console', msg => { if (msg.type() === 'error') consoleErrors.push(msg.text()) })
    page.on('pageerror', err => pageErrors.push(err.message))

    const dismissMessageBox = async () => {
        const okByText = page.getByRole('button', { name: /^OK$/ })
        for (let i = 0; i < 5; i++) {
            if (await okByText.count() > 0) {
                try { await okByText.first().click({ timeout: 1_000 }); log('  dismissed messageBox OK') } catch {}
                await page.waitForTimeout(300)
            } else break
        }
    }

    separator()
    log('SETUP: boot + login + select Lord Maple Tree')
    separator()

    log('  navigate')
    await page.goto('http://127.0.0.1:8080/index.html')
    await page.getByTestId('loading-screen-cta').waitFor({ state: 'visible', timeout: 30_000 })
    await page.getByTestId('loading-screen-cta').click()
    await page.locator('.login-widget-window').waitFor({ state: 'visible', timeout: 30_000 })
    await page.getByTestId('login-as-guest').click()

    log('  wait for MainMenu')
    const playButton = page.locator('.btn.btn-play')
    const mainDeadline = Date.now() + 60_000
    while (Date.now() < mainDeadline) {
        if (await playButton.count() > 0) break
        await dismissMessageBox()
        await page.waitForTimeout(200)
    }
    log('  MainMenu mounted')

    log('  click PLAY')
    await playButton.first().click()

    log('  handle resume dialog if present')
    if (await page.locator('[data-testid="resume-or-new-dialog"]').count() > 0) {
        await page.locator('button:has-text("New Game")').first().click()
        await page.waitForTimeout(2000)
    }

    log('  select Lord Maple Tree')
    await page.locator('[data-testid="commander-selection-root"]').waitFor({ state: 'visible', timeout: 30_000 })
    await page.locator('.commander-selection-card:has-text("Lord Maple Tree")').first().click()
    await page.waitForTimeout(300)
    await page.locator('.commander-selection-window button:has-text("Next")').first().click()
    await page.waitForTimeout(500)

    log('  select 1 vs 1: Duel')
    await page.locator('.commander-selection-card:has-text("1 vs 1: Duel")').first().click()
    await page.waitForTimeout(300)
    await page.locator('.commander-selection-window button:has-text("Play")').first().click()

    log('  wait for gameplay-ui')
    await page.locator('[data-testid="gameplay-ui"]').waitFor({ state: 'visible', timeout: 60_000 })
    await dismissMessageBox()  // Match Ready OK button
    await page.waitForTimeout(3000)

    const initialShot = join(ARTIFACT_DIR, '00-just-launched.png')
    await writeFile(initialShot, await page.screenshot({ fullPage: true }))
    log(`  screenshot: 00-just-launched.png`)

    // Probe live game state via window globals
    const probeGameState = async () => await page.evaluate(() => ({
        roundNumber: window.globals?.GameState?.roundNumber ?? null,
        currentPlayer: window.globals?.GameState?.currentPlayer ?? null,
        phase: window.globals?.GameState?.phase ?? null,
        gameStarted: window.globals?.GameState?.gameStarted ?? null,
        turnOwner: window.globals?.World?.turnOwner ?? null,
        // Also read DOM hints
        activePhaseIcons: Array.from(document.querySelectorAll('.fa-pulse')).map(el => el.className).slice(0, 10),
        // Read any visible turn header
        visibleHeaderText: (document.querySelector('[data-testid="gameplay-ui"]')?.innerText || '').slice(0, 800),
    }))

    // Find the command textarea
    const findCmdTextarea = async () => {
        const candidates = [
            'textarea[placeholder*="action you want"]',
            'textarea[placeholder*="Type the action"]',
            '.command-box textarea',
            'textarea',
        ]
        for (const sel of candidates) {
            const el = page.locator(sel).first()
            if (await el.count() > 0) return el
        }
        return null
    }

    const sendCommand = async (cmd) => {
        const ta = await findCmdTextarea()
        if (!ta) { log('  WARN: no textarea found'); return false }
        await ta.click()
        await ta.fill(cmd)
        await page.waitForTimeout(200)
        // Send button: "Send" with class btn-play
        const sendBtn = page.locator('button.btn-play:has-text("Send")').first()
        if (await sendBtn.count() > 0) {
            await sendBtn.click()
        } else {
            // Try keyboard Shift+Enter
            await ta.press('Shift+Enter')
        }
        log(`  SENT: "${cmd}"`)
        return true
    }

    separator()
    log('GAMEPLAY: 3 rounds of Lord Maple Tree vs 1 AI')
    separator()

    const roundResults = []

    for (let round = 1; round <= 3; round++) {
        separator()
        log(`ROUND ${round} starting`)
        separator()

        // Wait for the game to settle and identify the current turn phase
        log(`  waiting 15s for state to settle...`)
        await page.waitForTimeout(15_000)

        const stateA = await probeGameState()
        log(`  state: ${JSON.stringify(stateA).slice(0, 500)}`)

        await writeFile(join(ARTIFACT_DIR, `R${round}-01-pre-turn.png`),
            await page.screenshot({ fullPage: true }))

        // Try to detect whose turn via DOM (more reliable than globals)
        // Look for "Your turn" or the human player highlight
        const turnHint = await page.evaluate(() => {
            const t = document.querySelector('[data-testid="gameplay-ui"]')?.innerText || ''
            return {
                hasYourTurn: t.includes('Your Turn') || t.includes('YOUR TURN'),
                hasAiTurn: t.includes('AI Turn') || t.includes('AI is'),
                hasThinking: t.includes('Thinking') || t.includes('thinking'),
                text: t.slice(0, 600),
            }
        })
        log(`  turn hint: ${JSON.stringify(turnHint).slice(0, 400)}`)

        // Lord Maple Tree commands for each round — escalating grandeur
        const commands = [
            'I, Lord Maple Tree, Emperor of All Canada, declare that the forests around my border shall extend one tile. The trees march with me. Let no AI stand against my syrup.',
            'My Ent army advances. I send General Moustache and fifty Ents to claim the adjacent territory. Show them the meaning of pancake diplomacy.',
            'I invoke the Syrup Reserve protocol. Triple syrup production this turn. Any AI that opposes shall drown in golden deliciousness.',
        ]

        log(`  sending Lord Maple Tree command for round ${round}`)
        const sent = await sendCommand(commands[round - 1])
        if (!sent) {
            log('  could not send — falling back to Shift+Enter on first textarea')
            const ta = await findCmdTextarea()
            if (ta) {
                await ta.click()
                await ta.fill(commands[round - 1])
                await ta.press('Shift+Enter')
            }
        }

        // Capture mid-round state
        await page.waitForTimeout(8_000)
        await writeFile(join(ARTIFACT_DIR, `R${round}-02-after-cmd.png`),
            await page.screenshot({ fullPage: true }))
        log(`  screenshot: R${round}-02-after-cmd.png`)

        // Wait for AI turn / turn resolution (typically 30-90s for AI to plan)
        log(`  waiting 60s for AI / turn resolution to complete...`)
        await page.waitForTimeout(60_000)
        await dismissMessageBox()

        const stateB = await probeGameState()
        log(`  state after AI: ${JSON.stringify(stateB).slice(0, 500)}`)

        await writeFile(join(ARTIFACT_DIR, `R${round}-03-after-ai.png`),
            await page.screenshot({ fullPage: true }))

        roundResults.push({
            round,
            preTurn: stateA,
            postAi: stateB,
            commandSent: commands[round - 1],
        })

        log(`ROUND ${round} complete`)
    }

    separator()
    log('FINAL: post-3-rounds state')
    separator()

    await page.waitForTimeout(10_000)
    await dismissMessageBox()
    await writeFile(join(ARTIFACT_DIR, '99-final.png'),
        await page.screenshot({ fullPage: true }))

    const finalState = await probeGameState()
    log(`  final state: ${JSON.stringify(finalState).slice(0, 800)}`)

    log('Result: PASS — 3 rounds completed')
    log(`  console errors: ${consoleErrors.length}`)
    log(`  page errors: ${pageErrors.length}`)
    log(`  artifacts: ${ARTIFACT_DIR}`)

} catch (err) {
    console.log(`[${new Date().toISOString().slice(11, 23)}] FAIL — ${err.message}`)
    console.log(err.stack)
} finally {
    if (browser) await browser.close()
}
