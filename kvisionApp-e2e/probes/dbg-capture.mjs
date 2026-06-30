#!/usr/bin/env node
import { chromium } from '@playwright/test'

const browser = await chromium.launch({ headless: true })
const ctx = await browser.newContext({ viewport: { width: 1280, height: 800 } })
const page = await ctx.newPage()

async function dismissOk(page) {
    await page.evaluate(() => {
        const overlays = document.querySelectorAll('.autogenesis-message-box-overlay')
        for (const overlay of overlays) {
            const buttons = overlay.querySelectorAll('button')
            for (const btn of buttons) {
                if (btn.textContent.trim().toLowerCase() === 'ok') btn.click()
            }
        }
    })
}

page.on('console', m => console.log('CONSOLE:', m.text().slice(0, 200)))
page.on('pageerror', e => console.log('PAGE ERR:', e.message.slice(0, 200)))

try {
    await page.goto('http://127.0.0.1:8080/index.html')
    await page.getByTestId('loading-screen-cta').waitFor({ state: 'visible', timeout: 30000 })
    await page.getByTestId('loading-screen-cta').click()
    await page.getByTestId('login-as-guest').click()
    console.log('logged in')
    await page.waitForTimeout(5000)
    await dismissOk(page)
    console.log('dismissed Login Complete')
    await page.waitForTimeout(5000)
    const state = await page.evaluate(() => ({
        hasMainMenu: !!document.querySelector('[data-testid="main-menu"]'),
        hasResumeDialog: !!document.querySelector('[data-testid="resume-or-new-dialog"]'),
        bodyText: document.body.textContent.slice(0, 800),
    }))
    console.log('STATE:', JSON.stringify(state, null, 2))
    await page.screenshot({ path: '/tmp/dbg-state.png', fullPage: false })
} catch (e) {
    console.log('ERR:', e.message)
} finally {
    await browser.close()
}
