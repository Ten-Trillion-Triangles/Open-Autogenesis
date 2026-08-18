#!/usr/bin/env node
// probes/lord-maple-deep-dive.mjs
// Inspect tabs + state after the rapid-fire 3-round probe

import { chromium } from '@playwright/test'
import { mkdir, writeFile } from 'node:fs/promises'
import { fileURLToPath } from 'node:url'
import { dirname, join } from 'node:path'

const __dirname = dirname(fileURLToPath(import.meta.url))
const ARTIFACT_DIR = join(__dirname, 'artifacts-lord-maple-deep-dive')
await mkdir(ARTIFACT_DIR, { recursive: true })

const log = (s) => console.log(`[${new Date().toISOString().slice(11, 23)}] ${s}`)
const browser = await chromium.launch({ headless: true })
const context = await browser.newContext({ viewport: { width: 1280, height: 900 } })
const page = await context.newPage()

try {
    log('  navigate + login')
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
        if (await ok.count() > 0) {
            try { await ok.first().click({ timeout: 1_000 }) } catch {}
        }
        await page.waitForTimeout(300)
    }
    log('  MainMenu mounted — checking resume dialog')

    // Resume-or-new dialog? Check
    await playButton.first().click()
    await page.waitForTimeout(3000)

    const resumeDialog = page.locator('[data-testid="resume-or-new-dialog"]')
    if (await resumeDialog.count() > 0) {
        log('  RESUME dialog appeared — clicking RESUME')
        const resumeBtn = page.locator('button:has-text("Resume")').first()
        await resumeBtn.click()
    } else {
        log('  no resume dialog — checking commander selection')
        const cmdSel = page.locator('[data-testid="commander-selection-root"]')
        if (await cmdSel.count() > 0) {
            log('  commander selection appeared — picking Lord Maple Tree + 1v1 duel + Play')
            await page.locator('.commander-selection-card:has-text("Lord Maple Tree")').first().click()
            await page.waitForTimeout(300)
            await page.locator('.commander-selection-window button:has-text("Next")').first().click()
            await page.waitForTimeout(500)
            await page.locator('.commander-selection-card:has-text("1 vs 1: Duel")').first().click()
            await page.waitForTimeout(300)
            await page.locator('.commander-selection-window button:has-text("Play")').first().click()
        }
    }

    // Wait for gameplay-ui
    await page.locator('[data-testid="gameplay-ui"]').waitFor({ state: 'visible', timeout: 60_000 })
    log('  gameplay-ui mounted')

    // Dismiss any dialogs
    for (let i = 0; i < 30; i++) {
        const overlays = page.locator('.autogenesis-message-box-overlay')
        const count = await overlays.count()
        if (count === 0) break
        for (let j = 0; j < count; j++) {
            const ok = overlays.nth(j).locator('button.btn-secondary-action:has-text("OK")').first()
            if (await ok.count() > 0) {
                try { await ok.click({ force: true, timeout: 2000 }) } catch {}
            }
        }
        await page.waitForTimeout(500)
    }

    await page.waitForTimeout(3000)

    // === Capture each tab ===
    const tabs = ['Story', 'Details', 'Geopolitics', 'Work Stream']
    for (const tab of tabs) {
        log(`  clicking ${tab} tab`)
        const tabBtn = page.locator(`button:has-text("${tab}"), [role="tab"]:has-text("${tab}")`).first()
        if (await tabBtn.count() > 0) {
            try { await tabBtn.click() } catch {}
            await page.waitForTimeout(1000)
            await writeFile(join(ARTIFACT_DIR, `tab-${tab.toLowerCase().replace(' ', '-')}.png`),
                await page.screenshot({ fullPage: true }))
            const txt = await page.locator('.game-history-window, [class*="game-history"]').first().textContent().catch(() => '')
            log(`    ${tab} content (first 600 chars): ${txt.slice(0, 600).replace(/\n+/g, ' | ')}`)
        }
    }

    // === Click GO TO MAP to see the map ===
    const goToMap = page.locator('button:has-text("GO TO MAP"), a:has-text("GO TO MAP")').first()
    if (await goToMap.count() > 0) {
        log('  clicking GO TO MAP')
        try { await goToMap.click() } catch {}
        await page.waitForTimeout(2000)
        await writeFile(join(ARTIFACT_DIR, 'map-view.png'),
            await page.screenshot({ fullPage: true }))
    }

    // === Final state — full UI text ===
    const fullState = await page.evaluate(() => {
        const ui = document.querySelector('[data-testid="gameplay-ui"]')
        return ui ? ui.innerText : ''
    })
    log('FULL UI STATE:')
    console.log(fullState.slice(0, 3000))

    log('Result: DONE')
} catch (err) {
    log(`FAIL — ${err.message}`)
    console.log(err.stack)
} finally {
    await browser.close()
}