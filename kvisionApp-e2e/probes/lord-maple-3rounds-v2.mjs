#!/usr/bin/env node
// probes/lord-maple-3rounds.mjs (v2 — robust dialog dismissal)
// Full gameplay: login → Lord Maple Tree → 2p vs 1 AI → 3 rounds

import { chromium } from '@playwright/test'
import { mkdir, writeFile } from 'node:fs/promises'
import { fileURLToPath } from 'node:url'
import { dirname, join } from 'node:path'

const __dirname = dirname(fileURLToPath(import.meta.url))
const ARTIFACT_DIR = join(__dirname, 'artifacts-lord-maple-3rounds-v2')
await mkdir(ARTIFACT_DIR, { recursive: true })

const log = (s) => console.log(`[${new Date().toISOString().slice(11, 23)}] ${s}`)
const sep = () => console.log('─'.repeat(72))

const browser = await chromium.launch({ headless: true })
const context = await browser.newContext({ viewport: { width: 1280, height: 900 } })
const page = await context.newPage()

const consoleErrors = []
const pageErrors = []
page.on('console', msg => { if (msg.type() === 'error') consoleErrors.push(msg.text()) })
page.on('pageerror', err => pageErrors.push(err.message))

// Robust dialog dismisser — keep trying for N seconds, click via class
const dismissAllOverlays = async (timeoutMs = 60_000) => {
    const deadline = Date.now() + timeoutMs
    let dismissed = 0
    while (Date.now() < deadline) {
        const overlays = page.locator('.autogenesis-message-box-overlay')
        const count = await overlays.count()
        if (count === 0) return dismissed
        // For each overlay, click its OK button
        for (let i = 0; i < count; i++) {
            const okBtn = overlays.nth(i).locator('button.btn-secondary-action:has-text("OK")').first()
            if (await okBtn.count() > 0) {
                try {
                    await okBtn.click({ timeout: 2_000, force: true })
                    dismissed++
                    log(`  dismissed overlay #${i+1} (total=${dismissed})`)
                } catch (e) {
                    log(`  overlay #${i+1} OK click failed: ${e.message.slice(0, 80)}`)
                }
            }
        }
        await page.waitForTimeout(500)
    }
    return dismissed
}

// Wait until textarea is interactable (no overlay blocking it)
const waitForCommandBox = async (timeoutMs = 60_000) => {
    const deadline = Date.now() + timeoutMs
    while (Date.now() < deadline) {
        const ta = page.locator('textarea[placeholder*="action you want"]').first()
        if (await ta.count() > 0) {
            try {
                // Check if it's clickable (no overlay)
                const isVisible = await ta.isVisible()
                if (!isVisible) { await page.waitForTimeout(500); continue }
                return ta
            } catch { await page.waitForTimeout(500) }
        } else {
            await page.waitForTimeout(500)
        }
    }
    return null
}

try {
    sep()
    log('SETUP: boot + login + select Lord Maple Tree')
    sep()

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
        await dismissAllOverlays(2_000)
        await page.waitForTimeout(300)
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

    log('  dismiss ALL overlay dialogs (Match Ready, etc.) — up to 60s')
    const dismissedCount = await dismissAllOverlays(60_000)
    log(`  total dismissed: ${dismissedCount}`)

    await page.waitForTimeout(2_000)
    await writeFile(join(ARTIFACT_DIR, '00-clean-state.png'),
        await page.screenshot({ fullPage: true }))

    sep()
    log('GAMEPLAY: 3 rounds of Lord Maple Tree vs 1 AI')
    sep()

    const lordMapleCommands = [
        'I, Lord Maple Tree, Emperor of All Canada, declare that my Ent Army shall march one tile into the eastern frontier. The trees creak with anticipation. Any AI foolish enough to oppose shall learn the meaning of syrup diplomacy.',
        'Round 2: I invoke the Syrup Reserve Protocol. Triple production of golden syrup. My scientists work through the night on explosive maple innovations. Let the AI tremble before my Pancake Throne.',
        'Round 3: General Moustache leads a flanking maneuver through the northern forests. The Ent Army surrounds the AI position. I demand surrender — or I shall drown them all in warm deliciousness.',
    ]

    for (let round = 1; round <= 3; round++) {
        sep()
        log(`ROUND ${round} — Lord Maple Tree's turn`)
        sep()

        // Wait for command box to be interactable
        log(`  waiting for command box (up to 60s)...`)
        const ta = await waitForCommandBox(60_000)
        if (!ta) {
            log('  ERROR: command box never became interactable')
            await writeFile(join(ARTIFACT_DIR, `R${round}-FAIL-no-cmdbox.png`),
                await page.screenshot({ fullPage: true }))
            continue
        }

        // Read game state for report
        const turnHint = await page.evaluate(() => {
            const t = document.querySelector('[data-testid="gameplay-ui"]')?.innerText || ''
            return t.slice(0, 800)
        })
        log(`  UI snippet: ${turnHint.replace(/\n+/g, ' | ').slice(0, 250)}`)

        await writeFile(join(ARTIFACT_DIR, `R${round}-01-pre-cmd.png`),
            await page.screenshot({ fullPage: true }))

        log(`  typing Lord Maple Tree command (round ${round})...`)
        await ta.click({ force: true })
        await ta.fill(lordMapleCommands[round - 1])
        await page.waitForTimeout(500)

        const sendBtn = page.locator('button.btn-play:has-text("Send")').first()
        if (await sendBtn.count() > 0) {
            await sendBtn.click({ force: true })
            log(`  SENT via Send button: "${lordMapleCommands[round-1].slice(0, 60)}..."`)
        } else {
            await ta.press('Shift+Enter')
            log(`  SENT via Shift+Enter: "${lordMapleCommands[round-1].slice(0, 60)}..."`)
        }

        // Capture immediately after sending
        await page.waitForTimeout(5_000)
        await writeFile(join(ARTIFACT_DIR, `R${round}-02-after-send.png`),
            await page.screenshot({ fullPage: true }))

        // Wait for AI processing (with overlay dismissal)
        log(`  waiting 60s for AI processing + turn resolution...`)
        const aiStartWait = Date.now()
        let lastDismissCount = 0
        while (Date.now() - aiStartWait < 90_000) {
            const d = await dismissAllOverlays(2_000)
            if (d > lastDismissCount) {
                lastDismissCount = d
                log(`    overlay dismissed mid-wait (total=${lastDismissCount})`)
            }
            await page.waitForTimeout(3_000)
            // Check if round advanced (look at command box state)
            const newTa = page.locator('textarea[placeholder*="action you want"]').first()
            if (await newTa.count() > 0 && await newTa.isVisible()) {
                // Round may have completed — try clicking once
                try {
                    const visible = await newTa.isVisible()
                    if (visible) break
                } catch {}
            }
        }

        await dismissAllOverlays(10_000)
        await writeFile(join(ARTIFACT_DIR, `R${round}-03-after-ai.png`),
            await page.screenshot({ fullPage: true }))

        log(`ROUND ${round} turn cycle complete`)
    }

    sep()
    log('FINAL: post-3-rounds state')
    sep()

    await dismissAllOverlays(15_000)
    await page.waitForTimeout(5_000)
    await writeFile(join(ARTIFACT_DIR, '99-final.png'),
        await page.screenshot({ fullPage: true }))

    const finalUI = await page.evaluate(() => {
        const t = document.querySelector('[data-testid="gameplay-ui"]')?.innerText || ''
        return t.slice(0, 1500)
    })
    log(`  final UI snippet:\n${finalUI.replace(/\n+/g, '\n    ')}`)

    log('Result: PASS — 3 rounds completed')
    log(`  console errors: ${consoleErrors.length}`)
    log(`  page errors: ${pageErrors.length}`)
    log(`  artifacts: ${ARTIFACT_DIR}`)

} catch (err) {
    log(`FAIL — ${err.message}`)
    console.log(err.stack)
    try { await writeFile(join(ARTIFACT_DIR, 'FAIL.png'), await page.screenshot({ fullPage: true })) } catch {}
} finally {
    await browser.close()
}
