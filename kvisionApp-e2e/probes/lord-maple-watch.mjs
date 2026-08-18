#!/usr/bin/env node
// probes/lord-maple-watch.mjs
// Passive observer: connect, log game state every 5s for 3 minutes

import { chromium } from '@playwright/test'
import { mkdir, writeFile } from 'node:fs/promises'
import { fileURLToPath } from 'node:url'
import { dirname, join } from 'node:path'

const __dirname = dirname(fileURLToPath(import.meta.url))
const ARTIFACT_DIR = join(__dirname, 'artifacts-lord-maple-watch')
await mkdir(ARTIFACT_DIR, { recursive: true })

const log = (s) => console.log(`[${new Date().toISOString().slice(11, 23)}] ${s}`)
const browser = await chromium.launch({ headless: true })
const context = await browser.newContext({ viewport: { width: 1280, height: 900 } })
const page = await context.newPage()

try {
    log('navigate + login')
    await page.goto('http://127.0.0.1:8080/index.html')
    await page.getByTestId('loading-screen-cta').waitFor({ state: 'visible', timeout: 30_000 })
    await page.getByTestId('loading-screen-cta').click()
    await page.locator('.login-widget-window').waitFor({ state: 'visible', timeout: 30_000 })
    await page.getByTestId('login-as-guest').click()
    const playButton = page.locator('.btn.btn-play')
    const deadline = Date.now() + 60_000
    while (Date.now() < deadline) {
        if (await playButton.count() > 0) break
        const ok = page.getByRole('button', { name: /^OK$/ })
        if (await ok.count() > 0) { try { await ok.first().click({ timeout: 1_000 }) } catch {} }
        await page.waitForTimeout(300)
    }
    await playButton.first().click()
    await page.waitForTimeout(3000)

    const resumeDialog = page.locator('[data-testid="resume-or-new-dialog"]')
    if (await resumeDialog.count() > 0) {
        log('RESUME dialog — clicking RESUME')
        const resumeBtn = page.locator('button:has-text("Resume")').first()
        await resumeBtn.click()
    } else {
        const cmdSel = page.locator('[data-testid="commander-selection-root"]')
        if (await cmdSel.count() > 0) {
            log('commander selection — picking Lord Maple Tree + 1v1 duel + Play')
            await page.locator('.commander-selection-card:has-text("Lord Maple Tree")').first().click()
            await page.waitForTimeout(300)
            await page.locator('.commander-selection-window button:has-text("Next")').first().click()
            await page.waitForTimeout(500)
            await page.locator('.commander-selection-card:has-text("1 vs 1: Duel")').first().click()
            await page.waitForTimeout(300)
            await page.locator('.commander-selection-window button:has-text("Play")').first().click()
        }
    }
    await page.locator('[data-testid="gameplay-ui"]').waitFor({ state: 'visible', timeout: 60_000 })
    log('gameplay-ui mounted')

    // Dismiss any dialogs aggressively
    for (let i = 0; i < 30; i++) {
        const overlays = page.locator('.autogenesis-message-box-overlay')
        if (await overlays.count() === 0) break
        for (let j = 0; j < await overlays.count(); j++) {
            const ok = overlays.nth(j).locator('button.btn-secondary-action:has-text("OK")').first()
            if (await ok.count() > 0) {
                try { await ok.click({ force: true, timeout: 2000 }) } catch {}
            }
        }
        await page.waitForTimeout(500)
    }

    // Poll state every 8s for 5 minutes (or until turn completes)
    const seenStates = new Set()
    const stateHistory = []
    const startTime = Date.now()
    const maxDurationMs = 300_000 // 5 minutes

    while (Date.now() - startTime < maxDurationMs) {
        await page.waitForTimeout(8_000)
        const state = await page.evaluate(() => {
            const ui = document.querySelector('[data-testid="gameplay-ui"]')
            const text = ui ? ui.innerText : ''
            const turnMatch = text.match(/Turn\s+(\d+)/i)
            const scoreMatch = text.match(/Main\s*Score:\s*(\d+)/i)
            const yourTurn = text.includes('Your Turn') || text.includes('YOUR TURN')
            const aiTurn = text.includes('AI is thinking') || text.includes('AI is')
            const execPlayer = text.includes('Executing Player Command')
            const planning = text.includes('Planning') || text.includes('planning')
            return {
                turn: turnMatch ? parseInt(turnMatch[1]) : null,
                score: scoreMatch ? parseInt(scoreMatch[1]) : null,
                yourTurn,
                aiTurn,
                execPlayer,
                planning,
                snippet: text.replace(/\n+/g, ' | ').slice(0, 400),
            }
        })
        const stateKey = JSON.stringify({ turn: state.turn, score: state.score, yourTurn: state.yourTurn, aiTurn: state.aiTurn, execPlayer: state.execPlayer })
        if (!seenStates.has(stateKey)) {
            seenStates.add(stateKey)
            log(`STATE: ${stateKey}`)
            log(`  ${state.snippet}`)
            stateHistory.push(state)
            await writeFile(join(ARTIFACT_DIR, `state-${stateHistory.length}-t${state.turn ?? '?'}.png`),
                await page.screenshot({ fullPage: true }))
        }
        if (state.yourTurn) {
            log('  >>> YOUR TURN — round cycle complete!')
            // Click Details tab to see narrative
            const detailsTab = page.locator('button:has-text("Details")').first()
            if (await detailsTab.count() > 0) {
                await detailsTab.click()
                await page.waitForTimeout(1500)
                await writeFile(join(ARTIFACT_DIR, 'details-after-round.png'),
                    await page.screenshot({ fullPage: true }))
                const details = await page.locator('.game-history-window, [class*="game-history"]').first().textContent().catch(() => '')
                log(`  DETAILS (first 1500 chars): ${details.slice(0, 1500).replace(/\n+/g, ' | ')}`)
            }
            break
        }
    }

    // Click GO TO MAP if available
    const goToMap = page.locator('button:has-text("GO TO MAP"), a:has-text("GO TO MAP")').first()
    if (await goToMap.count() > 0) {
        try { await goToMap.click() } catch {}
        await page.waitForTimeout(2000)
        await writeFile(join(ARTIFACT_DIR, 'map-view.png'),
            await page.screenshot({ fullPage: true }))
    }

    log(`Total unique states observed: ${stateHistory.length}`)
    log('Result: DONE')
} catch (err) {
    log(`FAIL — ${err.message}`)
} finally {
    await browser.close()
}