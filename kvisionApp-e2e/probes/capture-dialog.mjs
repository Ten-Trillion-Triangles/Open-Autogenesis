#!/usr/bin/env node
// Quick capture of the Resume dialog with 3 buttons
import { chromium } from '@playwright/test'
import { join } from 'node:path'

const log = (s) => console.log(`[${new Date().toISOString().slice(11, 23)}] ${s}`)
const SCREENSHOT_DIR = join(import.meta.dirname, 'artifacts-echo-verify')

const browser = await chromium.launch({ headless: true })
const ctx = await browser.newContext({ viewport: { width: 1280, height: 800 } })
const page = await ctx.newPage()

try {
    log('navigating to index.html')
    await page.goto('http://127.0.0.1:8080/index.html')

    log('waiting for loading screen CTA')
    const cta = page.getByTestId('loading-screen-cta')
    await cta.waitFor({ state: 'visible', timeout: 30_000 })
    await cta.click()

    log('waiting for login-as-guest button')
    const guestBtn = page.getByTestId('login-as-guest')
    await guestBtn.waitFor({ state: 'visible', timeout: 30_000 })
    await guestBtn.click()

    log('waiting for Resume dialog')
    await page.locator('[data-testid="resume-or-new-dialog"]').waitFor({ state: 'visible', timeout: 30_000 })
    log('Resume dialog visible — taking screenshot')

    await page.waitForTimeout(500) // let any animations settle
    await page.screenshot({ path: `${SCREENSHOT_DIR}/resume-dialog-3-buttons.png`, fullPage: false })
    log(`screenshot saved: ${SCREENSHOT_DIR}/resume-dialog-3-buttons.png`)

    // Verify the 3 buttons exist
    const dialogText = await page.locator('[data-testid="resume-or-new-dialog"]').textContent()
    log(`dialog text: ${dialogText?.slice(0, 300)}`)

    const hasResume = /Resume/i.test(dialogText)
    const hasNewGame = /New Game/i.test(dialogText)
    const hasCancel = /Cancel/i.test(dialogText)
    log(`Resume button: ${hasResume}, New Game button: ${hasNewGame}, Cancel button: ${hasCancel}`)

    log('test PASSED' + (hasResume && hasNewGame && hasCancel ? '' : ' — FAILED'))
} catch (e) {
    log(`ERROR: ${e.message}`)
    await page.screenshot({ path: `${SCREENSHOT_DIR}/error.png`, fullPage: false }).catch(() => {})
} finally {
    await browser.close()
}