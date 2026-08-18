#!/usr/bin/env node
// probes/probe-state.mjs — quick diagnostic to see what GuestAccount has
// Logs in, dismisses resume dialog, captures commander list + screenshots
import { chromium } from '@playwright/test'
import { mkdir, writeFile } from 'node:fs/promises'
import { fileURLToPath } from 'node:url'
import { dirname, join } from 'node:path'

const __dirname = dirname(fileURLToPath(import.meta.url))
const ARTIFACT_DIR = __dirname
const log = (s) => console.log(`[${new Date().toISOString().slice(11, 23)}] ${s}`)

const browser = await chromium.launch({ headless: true })
const context = await browser.newContext({ viewport: { width: 1280, height: 900 } })
const page = await context.newPage()

const consoleErrors = []
page.on('console', msg => { if (msg.type() === 'error') consoleErrors.push(msg.text()) })

try {
    log('Step 1: navigate')
    await page.goto('http://127.0.0.1:8080/index.html')

    log('Step 2: click LoadingScreen CTA')
    await page.getByTestId('loading-screen-cta').waitFor({ state: 'visible', timeout: 30_000 })
    await page.getByTestId('loading-screen-cta').click()

    log('Step 3: click Login As Guest')
    await page.locator('.login-widget-window').waitFor({ state: 'visible', timeout: 30_000 })
    await page.getByTestId('login-as-guest').click()

    log('Step 4: wait for MainMenu')
    const playButton = page.locator('.btn.btn-play')
    const deadline = Date.now() + 60_000
    let menuFound = false
    while (Date.now() < deadline) {
        if (await playButton.count() > 0) { menuFound = true; break }
        const ok = page.getByRole('button', { name: /^OK$/ })
        if (await ok.count() > 0) {
            try { await ok.first().click({ timeout: 1_000 }); log('  dismissed messageBox OK') } catch {}
        }
        await page.waitForTimeout(200)
    }
    if (!menuFound) throw new Error('MainMenu never appeared')

    const ls = await page.evaluate(() => ({
        userId: window.globals?.AccelByteEnv?.userId ?? null,
        displayName: window.globals?.AccelByteEnv?.displayName ?? null,
    }))
    log(`  userId=${ls.userId} displayName=${ls.displayName}`)

    log('Step 5: click PLAY')
    await playButton.first().click()

    log('Step 6: detect resume-or-new-dialog')
    const resumeDialog = page.locator('[data-testid="resume-or-new-dialog"]')
    const hasResumeDialog = await resumeDialog.count() > 0
    log(`  resume dialog present: ${hasResumeDialog}`)
    if (hasResumeDialog) {
        await writeFile(join(ARTIFACT_DIR, 'screenshot-resume-dialog.png'),
            await resumeDialog.first().screenshot())
        const dialogText = await resumeDialog.first().textContent()
        log(`  resume dialog text: ${dialogText.slice(0, 300)}`)
    }

    await writeFile(join(ARTIFACT_DIR, 'screenshot-after-play.png'),
        await page.screenshot({ fullPage: true }))
    log('  screenshot saved: screenshot-after-play.png')

    log('Result: PASS')
    log(`  console errors: ${consoleErrors.length}`)
    consoleErrors.slice(0, 5).forEach(e => log(`    ${e.slice(0, 200)}`))
} catch (err) {
    log(`Result: FAIL — ${err.message}`)
    try { await writeFile(join(ARTIFACT_DIR, 'screenshot-FAIL.png'), await page.screenshot({ fullPage: true })) } catch {}
} finally {
    await browser.close()
}