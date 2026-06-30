#!/usr/bin/env node
// Capture the resumed game state with the Work Stream tab OPEN.
// Use page.mouse.click() to trigger real pointer events.

import { chromium } from '@playwright/test'
import { join } from 'node:path'
import { writeFile, mkdir } from 'node:fs/promises'

const BASE_URL = 'http://127.0.0.1:8080'
const SCREENSHOT_PATH = 'join(import.meta.dirname, 'artifacts-echo-verify/screenshot.png')

const log = (s) => console.log(`[${new Date().toISOString().slice(11, 23)}] ${s}`)

await mkdir('join(import.meta.dirname, 'artifacts-echo-verify', { recursive: true })

const browser = await chromium.launch({ headless: true })
const ctx = await browser.newContext({ viewport: { width: 1280, height: 800 } })
const page = await ctx.newPage()

await page.addInitScript(() => {
    let count = 0
    const orig = AudioBufferSourceNode.prototype.start
    AudioBufferSourceNode.prototype.start = function (...args) {
        count++
        window.__audioActiveBufferSources = count
        const onended = () => { count--; window.__audioActiveBufferSources = count }
        this.addEventListener('ended', onended, { once: true })
        return orig.apply(this, args)
    }
})

log('Step 1: navigate + login')
await page.goto(BASE_URL + '/index.html')
const cta = page.getByTestId('loading-screen-cta')
await cta.waitFor({ state: 'visible', timeout: 30_000 })
await cta.click()
const guestButton = page.getByTestId('login-as-guest')
    .or(page.getByRole('button', { name: 'Login As Guest' }))
await guestButton.first().click()

for (let i = 0; i < 30; i++) {
    const ok = page.getByRole('button', { name: /^OK$/ })
    if (await ok.count() > 0 && await ok.first().isVisible()) {
        try { await ok.first().click({ timeout: 2000, force: true }) } catch {}
        break
    }
    await page.waitForTimeout(500)
}
await page.locator('[data-testid="main-menu"]').waitFor({ state: 'visible', timeout: 30_000 })
log('Step 2: waiting for Resume dialog')

const dialogAppeared = await page.waitForFunction(() => {
    const d = document.querySelector('[data-testid="resume-or-new-dialog"]')
    return d && d.offsetWidth > 0
}, { timeout: 60_000 }).then(() => true).catch(() => false)
if (!dialogAppeared) {
    log('No Resume dialog — snapshot was consumed')
    await browser.close()
    process.exit(1)
}
log('Step 3: clicking Resume')
await page.locator('[data-testid="resume-or-new-dialog"] button:has-text("Resume")').first().click({ force: true })
await page.waitForFunction(() => document.querySelector('[data-testid="gameplay-ui"]') !== null, { timeout: 60_000 })

log('Step 4: dismissing Match Resumed modal')
for (let i = 0; i < 10; i++) {
    const ok = page.getByRole('button', { name: /^OK$/ })
    if (await ok.count() > 0 && await ok.first().isVisible()) {
        try { await ok.first().click({ timeout: 2000, force: true }) } catch {}
        break
    }
    await page.waitForTimeout(500)
}

log('Step 5: waiting 20s for AI work stream to generate narrative content')
await page.waitForTimeout(20_000)

// Find the Work Stream tab and use page.mouse.click
log('Step 6: finding and clicking Work Stream tab via mouse')
const tabInfo = await page.evaluate(() => {
    const tabs = document.querySelectorAll('.gh-tab-button')
    if (tabs.length === 0) return null
    let target = null
    for (const tab of tabs) {
        if (tab.textContent.toLowerCase().includes('work stream')) {
            target = tab
            break
        }
    }
    if (!target) target = tabs[tabs.length - 1]
    const rect = target.getBoundingClientRect()
    return {
        x: rect.left + rect.width / 2,
        y: rect.top + rect.height / 2,
        text: target.textContent,
        width: rect.width,
        height: rect.height,
        count: tabs.length,
    }
})
log(`Tab info: ${JSON.stringify(tabInfo)}`)
if (tabInfo) {
    await page.mouse.click(tabInfo.x, tabInfo.y)
    log(`Clicked at (${tabInfo.x}, ${tabInfo.y})`)
}

await page.waitForTimeout(3000)

const finalTabText = await page.locator('.gh-tab-button-active').textContent().catch(() => 'none')
log(`Active Game History tab: "${finalTabText}"`)

log('Step 7: taking screenshot')
await page.screenshot({ path: SCREENSHOT_PATH, fullPage: false })
log(`Screenshot: ${SCREENSHOT_PATH}`)

const finalState = await page.evaluate(() => ({
    activeTabText: document.querySelector('.gh-tab-button-active')?.textContent || 'none',
    tabCount: document.querySelectorAll('.gh-tab-button').length,
    hasGlow: !!document.querySelector('.gh-tab-glow'),
    audioCount: window.__audioActiveBufferSources || 0,
    awaitingText: /Awaiting|waiting on/i.test(document.body.textContent),
    streamingModalVisible: !!document.querySelector('.autogenesis-message-box-overlay'),
}))
log(`Final state: ${JSON.stringify(finalState, null, 2)}`)

await browser.close()
log('Done')
