#!/usr/bin/env node
// probes/lord-maple-final2.mjs
// Play Turn 2 + Turn 3 with proper wait-for-completion cycles

import { chromium } from '@playwright/test'
import { mkdir, writeFile } from 'node:fs/promises'
import { fileURLToPath } from 'node:url'
import { dirname, join } from 'node:path'

const __dirname = dirname(fileURLToPath(import.meta.url))
const ARTIFACT_DIR = join(__dirname, 'artifacts-lord-maple-final2')
await mkdir(ARTIFACT_DIR, { recursive: true })

const log = (s) => console.log(`[${new Date().toISOString().slice(11, 23)}] ${s}`)
const sep = () => console.log('─'.repeat(72))

const browser = await chromium.launch({ headless: true })
const context = await browser.newContext({ viewport: { width: 1280, height: 900 } })
const page = await context.newPage()

const dismissAllOverlays = async (timeoutMs = 5_000) => {
    const deadline = Date.now() + timeoutMs
    while (Date.now() < deadline) {
        const overlays = page.locator('.autogenesis-message-box-overlay')
        if (await overlays.count() === 0) return 0
        let dismissed = 0
        for (let i = 0; i < await overlays.count(); i++) {
            const ok = overlays.nth(i).locator('button.btn-secondary-action:has-text("OK")').first()
            if (await ok.count() > 0) {
                try { await ok.click({ force: true, timeout: 2000 }); dismissed++ } catch {}
            }
        }
        await page.waitForTimeout(500)
    }
    return 0
}

try {
    sep(); log('SETUP'); sep()
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
    log('  MainMenu mounted')

    // Try to RESUME existing game first
    await playButton.first().click()
    await page.waitForTimeout(3000)

    const resumeDialog = page.locator('[data-testid="resume-or-new-dialog"]')
    if (await resumeDialog.count() > 0) {
        log('  RESUME existing Lord Maple Tree game')
        const resumeBtn = page.locator('button:has-text("Resume")').first()
        await resumeBtn.click()
    } else {
        log('  no resume dialog — commander selection path')
        const cmdSel = page.locator('[data-testid="commander-selection-root"]')
        if (await cmdSel.count() > 0) {
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
    log('  gameplay-ui mounted')
    await dismissAllOverlays(10_000)

    // Find command textarea
    const findCmd = async () => {
        const ta = page.locator('textarea[placeholder*="action you want"]').first()
        return (await ta.count() > 0) ? ta : null
    }

    const sendCommand = async (cmd) => {
        let ta = await findCmd()
        if (!ta) {
            log('  waiting for command box...')
            for (let i = 0; i < 60; i++) {
                await dismissAllOverlays(3_000)
                ta = await findCmd()
                if (ta) break
                await page.waitForTimeout(3_000)
            }
            if (!ta) return false
        }
        await ta.click({ force: true })
        await ta.fill(cmd)
        await page.waitForTimeout(500)
        const sendBtn = page.locator('button.btn-play:has-text("Send")').first()
        if (await sendBtn.count() > 0) {
            await sendBtn.click({ force: true })
        } else {
            await ta.press('Shift+Enter')
        }
        log(`  SENT: "${cmd.slice(0, 80)}..."`)
        return true
    }

    // Wait for "Your Turn To Act" pattern
    const waitForYourTurn = async (maxMs = 360_000) => {
        const deadline = Date.now() + maxMs
        while (Date.now() < deadline) {
            const txt = await page.evaluate(() => document.querySelector('[data-testid="gameplay-ui"]')?.innerText || '')
            if (txt.includes('Your Turn To Act')) return true
            await dismissAllOverlays(2_000)
            await page.waitForTimeout(5_000)
        }
        return false
    }

    const captureScore = async (label) => {
        const txt = await page.evaluate(() => document.querySelector('[data-testid="gameplay-ui"]')?.innerText || '')
        const scoreM = txt.match(/Main\s*Score:\s*(\d+)/i)
        const turnM = txt.match(/Turn\s+(\d+)/i)
        const score = scoreM ? parseInt(scoreM[1]) : null
        const turn = turnM ? parseInt(turnM[1]) : null
        log(`  [${label}] score=${score} turn=${turn ?? '?'}`)
        return { score, turn }
    }

    sep(); log('ROUND 2 — Syrup Reserve Protocol'); sep()
    const r2Cmd = 'Lord Maple Tree invokes the Syrup Reserve Protocol. Triple golden syrup production this turn. My scientists in Slave Lake have perfected the explosive maple formula — nitro-syrup compressed into pancake-shaped grenades. Let King Candy tremble before the syrup refineries.'
    const beforeR2 = await captureScore('before R2')
    await writeFile(join(ARTIFACT_DIR, 'R2-00-before.png'), await page.screenshot({ fullPage: true }))
    await sendCommand(r2Cmd)
    await page.waitForTimeout(10_000)
    await writeFile(join(ARTIFACT_DIR, 'R2-01-after-send.png'), await page.screenshot({ fullPage: true }))

    log('  waiting for AI to resolve Round 2 (up to 5 min)...')
    const r2Resolved = await waitForYourTurn(360_000)
    if (r2Resolved) {
        log('  >>> R2 resolved!')
        const afterR2 = await captureScore('after R2 resolved')
        log(`  >>> SCORE CHANGE: ${beforeR2.score} -> ${afterR2.score} (delta=${afterR2.score - beforeR2.score})`)
        await writeFile(join(ARTIFACT_DIR, 'R2-02-resolved.png'), await page.screenshot({ fullPage: true }))

        // Capture details tab
        const detailsBtn = page.locator('button:has-text("Details")').first()
        if (await detailsBtn.count() > 0) {
            await detailsBtn.click()
            await page.waitForTimeout(2000)
            await writeFile(join(ARTIFACT_DIR, 'R2-03-details.png'), await page.screenshot({ fullPage: true }))
            const detailsTxt = await page.evaluate(() => document.querySelector('[data-testid="gameplay-ui"]')?.innerText || '')
            log(`  R2 Details: ${detailsTxt.replace(/\n+/g, ' | ').slice(0, 1000)}`)
        }
    } else {
        log('  R2 did NOT resolve within 5 min — moving on')
    }

    sep(); log('ROUND 3 — General Moustache Flanking'); sep()
    const r3Cmd = 'General Moustache leads the final flanking maneuver. The Ent Army surges from the northern forests into McSmarm Editconcise\'s stronghold. I demand unconditional surrender — or the syrup flood shall drown their entire domain. Lord Maple Tree has spoken.'
    const beforeR3 = await captureScore('before R3')
    await writeFile(join(ARTIFACT_DIR, 'R3-00-before.png'), await page.screenshot({ fullPage: true }))
    await sendCommand(r3Cmd)
    await page.waitForTimeout(10_000)
    await writeFile(join(ARTIFACT_DIR, 'R3-01-after-send.png'), await page.screenshot({ fullPage: true }))

    log('  waiting for AI to resolve Round 3 (up to 5 min)...')
    const r3Resolved = await waitForYourTurn(360_000)
    if (r3Resolved) {
        log('  >>> R3 resolved!')
        const afterR3 = await captureScore('after R3 resolved')
        log(`  >>> SCORE CHANGE: ${beforeR3.score} -> ${afterR3.score} (delta=${afterR3.score - beforeR3.score})`)
        await writeFile(join(ARTIFACT_DIR, 'R3-02-resolved.png'), await page.screenshot({ fullPage: true }))

        const detailsBtn = page.locator('button:has-text("Details")').first()
        if (await detailsBtn.count() > 0) {
            await detailsBtn.click()
            await page.waitForTimeout(2000)
            await writeFile(join(ARTIFACT_DIR, 'R3-03-details.png'), await page.screenshot({ fullPage: true }))
            const detailsTxt = await page.evaluate(() => document.querySelector('[data-testid="gameplay-ui"]')?.innerText || '')
            log(`  R3 Details: ${detailsTxt.replace(/\n+/g, ' | ').slice(0, 1000)}`)
        }
    } else {
        log('  R3 did NOT resolve within 5 min')
    }

    sep(); log('FINAL'); sep()
    const finalScore = await captureScore('FINAL')
    await writeFile(join(ARTIFACT_DIR, 'final.png'), await page.screenshot({ fullPage: true }))
    log(`FINAL SCORE: ${finalScore.score}`)
    log('Result: DONE')
} catch (err) {
    log(`FAIL — ${err.message}`)
    console.log(err.stack)
} finally {
    await browser.close()
}