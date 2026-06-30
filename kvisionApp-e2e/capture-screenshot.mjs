import { chromium } from '@playwright/test'
import { join } from 'node:path'
const browser = await chromium.launch({ headless: true })
const ctx = await browser.newContext({ viewport: { width: 1280, height: 800 } })
const page = await ctx.newPage()
await page.goto('http://127.0.0.1:8080/index.html')
const cta = page.getByTestId('loading-screen-cta')
await cta.waitFor({ state: 'visible', timeout: 30000 })
await cta.click()
const guestButton = page.getByTestId('login-as-guest')
    .or(page.getByRole('button', { name: 'Login As Guest' }))
await guestButton.first().click()
// Dismiss post-login message
for (let i = 0; i < 30; i++) {
    const okByText = page.getByRole('button', { name: /^OK$/ })
    if (await okByText.count() > 0 && await okByText.first().isVisible()) {
        try { await okByText.first().click({ timeout: 2000, force: true }) } catch {}
        break
    }
    await page.waitForTimeout(500)
}
await page.locator('[data-testid="main-menu"]').waitFor({ state: 'visible', timeout: 30000 })
// Check if Resume dialog appears
let dialogAppeared = false
for (let i = 0; i < 60; i++) {
    const dialog = await page.$('[data-testid="resume-or-new-dialog"]')
    if (dialog) {
        const visible = await dialog.isVisible()
        if (visible) {
            dialogAppeared = true
            break
        }
    }
    await page.waitForTimeout(1000)
}
if (dialogAppeared) {
    console.log('Resume dialog appeared, clicking Resume')
    await page.locator('[data-testid="resume-or-new-dialog"] button:has-text("Resume")').first().click({ force: true })
    // Wait for game UI to mount
    await page.waitForFunction(() => document.querySelector('[data-testid="gameplay-ui"]') !== null, { timeout: 30000 })
    // Dismiss Match Resumed modal
    for (let i = 0; i < 10; i++) {
        const okByText = page.getByRole('button', { name: /^OK$/ })
        if (await okByText.count() > 0 && await okByText.first().isVisible()) {
            try { await okByText.first().click({ timeout: 2000, force: true }) } catch {}
            break
        }
        await page.waitForTimeout(500)
    }
    // Wait 10s for AI work stream to be active
    console.log('Waiting 10s for AI work stream activity...')
    await page.waitForTimeout(10000)
    // Take screenshot
    const screenshotPath = 'join(import.meta.dirname, 'artifacts-echo-verify/screenshot2.png')
    await page.screenshot({ path: screenshotPath, fullPage: false })
    console.log(`Screenshot: ${screenshotPath}`)
    // Check state
    const state = await page.evaluate(() => {
        return {
            bodyText: document.body.textContent.slice(0, 2000),
            hasGameplayUI: !!document.querySelector('[data-testid="gameplay-ui"]'),
            hasWorkStreamGlow: !!document.querySelector('.gh-tab-glow'),
            hasYourTurnText: /Your Turn To Act/i.test(document.body.textContent),
            hasOpponentTurnText: /Opponent's Turn|AI is thinking|AI's Turn/i.test(document.body.textContent),
        }
    })
    console.log('STATE:', JSON.stringify(state, null, 2))
} else {
    console.log('No resume dialog appeared — snapshot was already consumed')
}
await browser.close()
