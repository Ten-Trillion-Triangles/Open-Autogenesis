#!/usr/bin/env node
// kvisionApp-e2e/probes/settings-mobile-portrait.mjs
//
// Mobile-portrait rendering probe for the in-game SettingsWidget.
// Drives a Playwright session at iPhone 12 dimensions (390x844) and verifies
// that the @media (max-width: 600px) override in night-mode.css fires
// correctly:
//   - data-mobile-layout="portrait" is set on the root (matchMedia listener)
//   - no horizontal overflow
//   - Settings controls (toggle, slider, button) meet 44px tap target minimum
//   - Modal scales to <= 95vw on portrait (getBoundingClientRect)
//
// Pre-req: dev server running at http://127.0.0.1:8080 (default kvisionApp
// dev port). Run with: node kvisionApp-e2e/probes/settings-mobile-portrait.mjs
//
// Usage:
//   node kvisionApp-e2e/probes/settings-mobile-portrait.mjs [--base-url=http://127.0.0.1:8080]

import { chromium, devices } from '@playwright/test'
import { mkdir } from 'node:fs/promises'
import { fileURLToPath } from 'node:url'
import { dirname, join } from 'node:path'

const __dirname = dirname(fileURLToPath(import.meta.url))

const BASE_URL_ARG = process.argv.find(a => a.startsWith('--base-url='))
const BASE_URL = BASE_URL_ARG
    ? BASE_URL_ARG.substring('--base-url='.length)
    : 'http://127.0.0.1:8080'

const ARTIFACT_DIR = join(__dirname, 'artifacts-settings-mobile-portrait')
await mkdir(ARTIFACT_DIR, { recursive: true })

const FAILURES = []

function check(name, condition, detail = '')
{
    if (condition) {
        console.log(`PASS: ${name}`)
    } else {
        console.log(`FAIL: ${name} ${detail}`)
        FAILURES.push(name)
    }
}

const browser = await chromium.launch()
const context = await browser.newContext({
    ...devices['iPhone 12'],
})
const page = await context.newPage()

try {
    await page.goto(BASE_URL + '/index.html', { waitUntil: 'domcontentloaded' })
    // Wait for the settings widget root to appear. The widget is mounted by
    // GameplayUI when the in-game settings button is activated; we poll for
    // the data-testid set by the matchMedia listener below.
    await page.waitForSelector('[data-testid="settings-widget-root"]', { timeout: 15000 })
    // Give the matchMedia listener time to attach and set data-mobile-layout.
    await page.waitForFunction(
        () => document.querySelector('[data-testid="settings-widget-root"]')?.hasAttribute('data-mobile-layout'),
        { timeout: 5000 }
    )

    const root = await page.$('[data-testid="settings-widget-root"]')
    check('Settings root is mounted', root !== null)

    const mobileLayout = await root.getAttribute('data-mobile-layout')
    check('data-mobile-layout="portrait" set', mobileLayout === 'portrait', `(got: ${mobileLayout})`)

    const overflow = await page.evaluate(() => {
        return document.documentElement.scrollWidth > window.innerWidth
    })
    const dims = await page.evaluate(() => ({
        scrollWidth: document.documentElement.scrollWidth,
        innerWidth: window.innerWidth,
    }))
    check('no horizontal overflow at 390px', !overflow, `(scrollWidth=${dims.scrollWidth}, innerWidth=${dims.innerWidth})`)

    // Verify all interactive controls meet the 44px minimum tap target.
    // KVision renders checkboxes as <input type="checkbox"> and rangeInputs as
    // <input type="range"> inside kvision-generated wrappers; buttons are
    // <button class="btn">.  We query all three types under the settings root.
    const tapTargets = await page.evaluate(() => {
        const root = document.querySelector('[data-testid="settings-widget-root"]')
        if (!root) return []
        const checks = Array.from(root.querySelectorAll('input[type="checkbox"]'))
        const ranges = Array.from(root.querySelectorAll('input[type="range"]'))
        const buttons = Array.from(root.querySelectorAll('button'))
        return [...checks, ...ranges, ...buttons].map(el => {
            const r = el.getBoundingClientRect()
            return { tag: el.tagName, type: el.type, width: r.width, height: r.height }
        })
    })
    check('Settings controls present (toggle/slider/button)', tapTargets.length >= 3, `(found ${tapTargets.length})`)
    for (const t of tapTargets) {
        const minDim = Math.min(t.width, t.height)
        check(
            `Control <${t.tag.toLowerCase()}${t.type ? ` type="${t.type}"` : ''}> tap target >= 44px`,
            minDim >= 44,
            `(got: ${t.width}x${t.height})`
        )
    }

    // Modal must scale to <= 95vw on portrait (getBoundingClientRect width vs 95vw).
    const modalRect = await page.evaluate(() => {
        const root = document.querySelector('[data-testid="settings-widget-root"]')
        if (!root) return null
        const r = root.getBoundingClientRect()
        return { width: r.width, vw: window.innerWidth }
    })
    check('Modal present in DOM', modalRect !== null)
    if (modalRect) {
        const vw95 = modalRect.vw * 0.95
        check(
            'Modal scales to <= 95vw on portrait',
            modalRect.width <= vw95,
            `(modal width=${modalRect.width}, 95vw=${vw95.toFixed(1)})`
        )
    }

    // Capture screenshot for visual review (sanity check only — programmatic
    // measurements are the source of truth).
    await page.screenshot({ path: join(ARTIFACT_DIR, 'portrait-390x844.png'), fullPage: true })
    console.log(`SCREENSHOT: ${join(ARTIFACT_DIR, 'portrait-390x844.png')}`)
}
catch (err) {
    console.error('PROBE CRASHED:', err.message)
    await page.screenshot({ path: join(ARTIFACT_DIR, 'portrait-crash.png'), fullPage: true }).catch(() => {})
    FAILURES.push('probe crashed: ' + err.message)
}
finally {
    await browser.close()
}

if (FAILURES.length > 0) {
    console.error(`\n${FAILURES.length} check(s) failed: ${FAILURES.join(', ')}`)
    process.exit(1)
}
console.log('\nAll checks passed.')
