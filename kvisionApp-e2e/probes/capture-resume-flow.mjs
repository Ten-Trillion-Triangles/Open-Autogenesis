#!/usr/bin/env node
// Resume flow screenshot capture — captures BOTH the Resume dialog
// (with 3 buttons) AND the resumed game state after clicking Resume.

import { chromium } from '@playwright/test'
import { join } from 'node:path'
import { mkdir } from 'node:fs/promises'

const log = (s) => console.log(`[${new Date().toISOString().slice(11, 23)}] ${s}`)
const SCREENSHOT_DIR = join(import.meta.dirname, 'artifacts-echo-verify')
await mkdir(SCREENSHOT_DIR, { recursive: true })

const browser = await chromium.launch({ headless: true })
const ctx = await browser.newContext({ viewport: { width: 1280, height: 800 } })
const page = await ctx.newPage()

async function dismissOkBoxes(page) {
    for (let i = 0; i < 10; i++) {
        const dismissed = await page.evaluate(() => {
            const overlays = document.querySelectorAll('.autogenesis-message-box-overlay, [class*="modal"], [class*="popup"]')
            for (const overlay of overlays) {
                if (overlay.offsetWidth === 0 || overlay.offsetHeight === 0) continue
                const buttons = overlay.querySelectorAll('button')
                for (const btn of buttons) {
                    if (btn.textContent.trim().toLowerCase() === 'ok') {
                        btn.click()
                        return true
                    }
                }
            }
            return false
        })
        if (dismissed) await page.waitForTimeout(500)
        else break
    }
}

try {
    log('=== STEP 1: Login as Guest ===')
    await page.goto('http://127.0.0.1:8080/index.html')
    const cta = page.getByTestId('loading-screen-cta')
    await cta.waitFor({ state: 'visible', timeout: 30_000 })
    await cta.click()
    const guestBtn = page.getByTestId('login-as-guest')
    await guestBtn.waitFor({ state: 'visible', timeout: 30_000 })
    await guestBtn.click()
    log('  Logged in as guest')
    
    log('=== STEP 2: Dismiss "Login Complete" message ===')
    await page.waitForFunction(() => document.querySelector('.autogenesis-message-box-overlay') !== null, { timeout: 30_000 }).catch(() => {})
    await dismissOkBoxes(page)
    log('  Login Complete dismissed')
    
    log('=== STEP 3: Wait for ResumeOrNewDialog ===')
    await page.locator('[data-testid="resume-or-new-dialog"]').waitFor({ state: 'visible', timeout: 30_000 })
    log('  Resume dialog visible')
    
    // Verify the 3 buttons
    const dialogText = await page.locator('[data-testid="resume-or-new-dialog"]').textContent()
    log(`  Dialog text: ${dialogText?.slice(0, 300)}`)
    const hasResume = /Resume/i.test(dialogText)
    const hasNewGame = /New Game/i.test(dialogText)
    const hasCancel = /Cancel/i.test(dialogText)
    log(`  Resume button: ${hasResume}, New Game button: ${hasNewGame}, Cancel button: ${hasCancel}`)
    
    log('=== STEP 4: Capture screenshot of the 3-button Resume dialog ===')
    await page.waitForTimeout(1000) // let any animations settle
    const dialogPath = `${SCREENSHOT_DIR}/screenshot-resume-dialog.png`
    await page.screenshot({ path: dialogPath, fullPage: false })
    log(`  📸 SCREENSHOT 1 saved: ${dialogPath}`)
    
    log('=== STEP 5: Click the Resume button ===')
    await page.locator('[data-testid="resume-or-new-dialog"] button:has-text("Resume")').first().click({ force: true })
    log('  Resume clicked')
    
    log('=== STEP 6: Dismiss "Match Resumed" modal if any ===')
    await page.waitForTimeout(3000)
    await dismissOkBoxes(page)
    log('  Match Resumed modal dismissed')
    
    log('=== STEP 7: Wait for GameplayUI to mount ===')
    await page.locator('[data-testid="gameplay-ui"]').waitFor({ state: 'visible', timeout: 30_000 })
    log('  GameplayUI mounted')
    
    log('=== STEP 8: Wait for AI work stream to populate ===')
    await page.waitForTimeout(20_000) // let AI's narrative stream in
    
    log('=== STEP 9: Capture screenshot of resumed game UI ===')
    const gamePath = `${SCREENSHOT_DIR}/screenshot-resumed-game.png`
    await page.screenshot({ path: gamePath, fullPage: false })
    log(`  📸 SCREENSHOT 2 saved: ${gamePath}`)
    
    // Verify the resume state
    const finalState = await page.evaluate(() => ({
        hasGameplayUI: !!document.querySelector('[data-testid="gameplay-ui"]'),
        bodyText: document.body.textContent.slice(0, 1500),
    }))
    log(`  hasGameplayUI=${finalState.hasGameplayUI}`)
    log(`  Body text snippet: ${finalState.bodyText.slice(0, 500)}`)
    
    log('=== TEST COMPLETE ===')
    log(`✅ Both screenshots captured:`)
    log(`   1. Resume dialog: ${dialogPath}`)
    log(`   2. Resumed game: ${gamePath}`)
} catch (e) {
    log(`ERROR: ${e.message}`)
    log(e.stack)
    await page.screenshot({ path: `${SCREENSHOT_DIR}/error.png`, fullPage: false }).catch(() => {})
} finally {
    await browser.close()
}