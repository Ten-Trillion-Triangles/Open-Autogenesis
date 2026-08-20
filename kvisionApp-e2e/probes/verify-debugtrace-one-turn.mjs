#!/usr/bin/env node
// probes/verify-debugtrace-one-turn.mjs
// Drive ONE real turn through the live game (static kvisionApp on port 8080)
// so that the WorldTokenTrace writes a real trace file under
// ~/.autogenesis/logs/world-trace-*.log.

import { chromium } from '@playwright/test'
import { mkdir, writeFile } from 'node:fs/promises'
import { fileURLToPath } from 'node:url'
import { dirname, join } from 'node:path'

const __dirname = dirname(fileURLToPath(import.meta.url))
const ARTIFACT_DIR = join(__dirname, 'artifacts-debugtrace-verify')
await mkdir(ARTIFACT_DIR, { recursive: true })

const log = (s) => console.log(`[${new Date().toISOString().slice(11, 23)}] ${s}`)
const separator = () => console.log('─'.repeat(72))

let browser
try {
    browser = await chromium.launch({ headless: true })
    const context = await browser.newContext({ viewport: { width: 1280, height: 900 } })
    const page = await context.newPage()

    const consoleErrors = []
    page.on('console', msg => { if (msg.type() === 'error') consoleErrors.push(msg.text()) })

    separator()
    log('SETUP: click loading CTA, login as guest')
    separator()
    await page.goto('http://127.0.0.1:8080/index.html')
    await page.getByTestId('loading-screen-cta').waitFor({ state: 'visible', timeout: 30_000 })
    await page.getByTestId('loading-screen-cta').click()
    log('  clicked loading-screen CTA')

    await page.locator('.login-widget-window').waitFor({ state: 'visible', timeout: 30_000 })
    await page.getByTestId('login-as-guest').click()
    log('  clicked login-as-guest')

    separator()
    log('WAIT: resume-or-new dialog OR main menu (up to 120s)')
    separator()
    // Wait for EITHER the resume dialog or the MainMenu's PLAY button
    let dialog = null
    let playButton = null
    for (let i = 0; i < 120; i++) {
        const d = page.locator('[data-testid="resume-or-new-dialog"]')
        if (await d.count() > 0 && await d.isVisible().catch(() => false)) {
            dialog = d
            log('  resume dialog visible')
            break
        }
        const p = page.locator('.btn.btn-play').first()
        if (await p.count() > 0 && await p.isVisible().catch(() => false)) {
            playButton = p
            log('  PLAY button visible (no saved game)')
            break
        }
        // Also check the "OK" messageBox (login success dialog)
        const ok = page.getByRole('button', { name: /^OK$/ })
        if (await ok.count() > 0) {
            log('  dismissing messageBox OK')
            try { await ok.first().click({ timeout: 1_000 }) } catch {}
            await page.waitForTimeout(500)
        }
        await page.waitForTimeout(1_000)
    }

    await page.screenshot({ path: join(ARTIFACT_DIR, '01-pre-resume.png'), fullPage: true })

    if (dialog) {
        log('  clicking RESUME button')
        try {
            await page.locator('[data-testid="resume-dialog-resume"]').first().click({ force: true, timeout: 5_000 })
        } catch (e) {
            log('  RESUME force-click failed: ' + e.message)
        }
    } else if (playButton) {
        log('  no resume dialog; clicking PLAY')
        await playButton.click({ force: true })

        log('  waiting for commander-selection')
        await page.locator('[data-testid="commander-selection-root"]').waitFor({ state: 'visible', timeout: 60_000 })
        log('  selecting Lord Maple Tree')
        await page.locator('.commander-selection-card:has-text("Lord Maple Tree")').first().click()
        await page.locator('.commander-selection-window button:has-text("Next")').first().click()
        log('  selecting 1 vs 1: Duel')
        await page.locator('.commander-selection-card:has-text("1 vs 1: Duel")').first().click()
        await page.locator('.commander-selection-window button:has-text("Play")').first().click()
    } else {
        log('  ERROR: neither resume dialog nor PLAY button found within 120s')
    }

    await page.waitForTimeout(3_000)
    await page.screenshot({ path: join(ARTIFACT_DIR, '02-post-resume.png'), fullPage: true })

    separator()
    log('WAIT: gameplay-ui to mount (up to 90s)')
    separator()
    try {
        await page.locator('[data-testid="gameplay-ui"]').waitFor({ state: 'visible', timeout: 90_000 })
        log('  gameplay-ui mounted')
    } catch (e) {
        log('  gameplay-ui not visible within 90s')
    }
    await page.waitForTimeout(5_000)
    await page.screenshot({ path: join(ARTIFACT_DIR, '03-gameplay.png'), fullPage: true })

    separator()
    log('TURN: send a command so executeSingleTurn fires')
    separator()
    const ta = page.locator('textarea[placeholder*="action you want"]').first()
    let sent = false
    try {
        await ta.waitFor({ state: 'visible', timeout: 30_000 })
        await ta.click()
        await ta.fill('I, Lord Maple Tree, Emperor of All Canada, declare that the forests around my border shall extend one tile.')
        const sendBtn = page.locator('button.btn-play:has-text("Send")').first()
        await sendBtn.click()
        log('  command sent')
        sent = true
    } catch (e) {
        log('  could not send command (text-area may not be ready): ' + e.message)
    }

    // Give the server time to process the turn and write the trace file
    log('  waiting 90s for turn resolution + trace file write...')
    await page.waitForTimeout(90_000)
    await page.screenshot({ path: join(ARTIFACT_DIR, '04-after-turn.png'), fullPage: true })

    separator()
    log('VERIFY: trace file in ~/.autogenesis/logs/world-trace-*.log')
    separator()
    const fs = await import('node:fs/promises')
    const home = process.env.HOME || '/root'
    const traceFiles = await fs.readdir(`${home}/.autogenesis/logs`)
    const traceMatches = traceFiles.filter(f => f.startsWith('world-trace-2026-07-24'))
    log(`  trace files for today: ${traceMatches.length}`)
    traceMatches.forEach(f => log(`    ${f}`))
    log(`  console errors: ${consoleErrors.length}`)
    log(`  command sent: ${sent}`)
    log('PROBE COMPLETE')
} catch (e) {
    console.error('PROBE FAILED:', e.message)
    console.error(e.stack)
    process.exit(1)
} finally {
    if (browser) await browser.close()
}