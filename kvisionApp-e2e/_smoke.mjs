#!/usr/bin/env node
// Minimal smoke: navigate, click LoadingScreen CTA, click Login As Guest, screenshot at 5s.
import { chromium } from '@playwright/test'
import { setTimeout as sleep } from 'node:timers/promises'
import { mkdirSync } from 'node:fs'

const ART = '/tmp/ag-smoke'
mkdirSync(ART, { recursive: true })

const browser = await chromium.launch({ headless: true, viewport: { width: 1920, height: 1080 }, args: ['--no-sandbox'] })
const page = await browser.newPage()

const consoleAll = []
page.on('console', m => consoleAll.push({ t: Date.now(), type: m.type(), text: m.text() }))
page.on('pageerror', e => consoleAll.push({ t: Date.now(), type: 'PAGE_ERROR', text: e.message }))

await page.goto('http://localhost:8080/', { waitUntil: 'load' })
console.log('[smoke] page loaded')
await page.locator('[data-testid="loading-screen-cta"]').click()
console.log('[smoke] LoadingScreen CTA clicked')
await page.waitForSelector('[data-testid="login-as-guest"]', { timeout: 15000 })
console.log('[smoke] login-as-guest visible')
await page.locator('[data-testid="login-as-guest"]').click()
console.log('[smoke] login-as-guest clicked')

// 5 seconds: did anything happen?
for (let i = 0; i < 5; i++) {
    await sleep(1000)
    const state = await page.evaluate(() => ({
        buttons: Array.from(document.querySelectorAll('button')).map(b => b.textContent?.trim()).filter(t => t).slice(0, 10),
        messageBox: document.querySelector('.autogenesis-message-box-overlay, [data-testid*="message-box"]')?.outerHTML?.slice(0, 200),
        hasMainMenu: !!document.querySelector('[data-testid="main-menu"]'),
        hasLogin: !!document.querySelector('[data-testid="login-as-guest"]')
    }))
    console.log(`[smoke ${i+1}s] buttons=${state.buttons.join('|')} mainMenu=${state.hasMainMenu} loginStill=${state.hasLogin}`)
}

console.log('[smoke] console after 5s:')
for (const m of consoleAll.slice(-20)) {
    console.log(`  [${new Date(m.t).toISOString()}] ${m.type}: ${m.text.substring(0, 200)}`)
}

await page.screenshot({ path: `${ART}/smoke-after-login.png`, fullPage: false })
await browser.close()