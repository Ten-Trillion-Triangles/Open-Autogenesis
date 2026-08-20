#!/usr/bin/env node
// Captures both the 3-button Resume dialog AND the resumed game state.
import { chromium } from '@playwright/test'
import { join } from 'node:path'

const log = (s) => console.log(`[${new Date().toISOString().slice(11, 23)}] ${s}`)
const SCREENSHOT_DIR = join(import.meta.dirname, 'artifacts-echo-verify')

const browser = await chromium.launch({ headless: true })
const ctx = await browser.newContext({ viewport: { width: 1280, height: 800 } })
const page = await ctx.newPage()

async function dismissMessageBoxes(page, timeoutMs = 15000) {
    const start = Date.now()
    while (Date.now() - start < timeoutMs) {
        try {
            const dismissed = await page.evaluate(() => {
                const overlays = document.querySelectorAll('.autogenesis-message-box-overlay, [class*="modal"], [class*="popup"]')
                for (const overlay of overlays) {
                    if (overlay.offsetWidth === 0 || overlay.offsetHeight === 0) continue
                    const buttons = overlay.querySelectorAll('button')
                    for (const btn of buttons) {
                        const text = btn.textContent.trim().toLowerCase()
                        if (text === 'ok' || text === 'o.k.') {
                            btn.click()
                            return true
                        }
                    }
                }
                const allOk = Array.from(document.querySelectorAll('button')).filter(b => b.textContent.trim().toLowerCase() === 'ok')
                if (allOk.length > 0) {
                    allOk[0].click()
                    return true
                }
                return false
            })
            if (dismissed) {
                await page.waitForTimeout(800)
            } else {
                await page.waitForTimeout(300)
            }
        } catch {
            await page.waitForTimeout(300)
        }
    }
}

try {
    log('navigating to index.html')
    await page.goto('http://127.0.0.1:8080/index.html')
    const cta = page.getByTestId('loading-screen-cta')
    await cta.waitFor({ state: 'visible', timeout: 30_000 })
    await cta.click()
    const guestBtn = page.getByTestId('login-as-guest')
    await guestBtn.waitFor({ state: 'visible', timeout: 30_000 })
    await guestBtn.click()

    log('dismissing Login Complete')
    await page.waitForFunction(() => document.querySelector('.autogenesis-message-box-overlay') !== null, { timeout: 30_000 }).catch(() => {})
    await dismissMessageBoxes(page, 5000)

    log('waiting for Resume dialog (3 buttons: Resume / New Game / Cancel)')
    const dialog = page.locator('[data-testid="resume-or-new-dialog"]')
    await dialog.waitFor({ state: 'visible', timeout: 30_000 })
    log('Resume dialog visible')
    await page.waitForTimeout(500)

    // CAPTURE THE 3-BUTTON DIALOG
    await page.screenshot({ path: `${SCREENSHOT_DIR}/screenshot-resume-dialog.png`, fullPage: false })
    log(`saved: ${SCREENSHOT_DIR}/screenshot-resume-dialog.png`)

    const dialogText = await dialog.textContent()
    log(`dialog text: ${dialogText?.slice(0, 300)}`)

    // Click Resume
    log('clicking Resume button')
    const resumeBtn = page.getByRole('button', { name: /^Resume$/ }).first()
    await resumeBtn.click({ force: true })

    log('waiting for Match Resumed modal to dismiss')
    await dismissMessageBoxes(page, 10000)

    log('waiting for GameplayUI to mount')
    await page.locator('[data-testid="gameplay-ui"]').waitFor({ state: 'visible', timeout: 30_000 })
    log('GameplayUI mounted')

    log('waiting 10s for stream content to populate')
    await page.waitForTimeout(10_000)

    // CAPTURE THE RESUMED GAME STATE
    await page.screenshot({ path: `${SCREENSHOT_DIR}/screenshot-resumed-game.png`, fullPage: false })
    log(`saved: ${SCREENSHOT_DIR}/screenshot-resumed-game.png`)

    const state = await page.evaluate(() => ({
        round: document.body.textContent.match(/Round:\s*(\d+)/i)?.[1] || null,
        activeActor: (document.body.textContent.match(/Active\s*Actor:?\s*([A-Za-z0-9\s]+)/i) || [])[1]?.trim(),
        bodyTextSnippet: document.body.textContent.slice(0, 800),
    }))
    log(`state: ${JSON.stringify(state).slice(0, 400)}`)

    log('BOTH SCREENSHOTS CAPTURED SUCCESSFULLY')
} catch (e) {
    log(`ERROR: ${e.message}`)
    await page.screenshot({ path: `${SCREENSHOT_DIR}/error.png`, fullPage: false }).catch(() => {})
} finally {
    await browser.close()
}
