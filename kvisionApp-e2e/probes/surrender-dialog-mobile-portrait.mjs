#!/usr/bin/env node
// kvisionApp-e2e/probes/surrender-dialog-mobile-portrait.mjs
//
// Mobile-portrait rendering probe for the SurrenderConfirmDialog.
// Drives a Playwright session at iPhone 12 dimensions (390x844) and verifies
// that the @media (max-width: 600px) override in night-mode.css fires
// correctly:
//   - data-mobile-layout="portrait" is set on the root (matchMedia listener)
//   - no horizontal overflow
//   - CANCEL and SURRENDER buttons are each at least 48px tall (Material Design
//     tap target minimum) and stack vertically (distinct y-coordinates)
//   - SURRENDER button width fits within 90vw
//
// Pre-req: dev server running at http://127.0.0.1:8080 (default kvisionApp
// dev port). Run with: node kvisionApp-e2e/probes/surrender-dialog-mobile-portrait.mjs
//
// Usage:
//   node kvisionApp-e2e/probes/surrender-dialog-mobile-portrait.mjs [--base-url=http://127.0.0.1:8080]

import { chromium, devices } from '@playwright/test'
import { mkdir } from 'node:fs/promises'
import { fileURLToPath } from 'node:url'
import { dirname, join } from 'node:path'

const __dirname = dirname(fileURLToPath(import.meta.url))

const BASE_URL_ARG = process.argv.find(a => a.startsWith('--base-url='))
const BASE_URL = BASE_URL_ARG
    ? BASE_URL_ARG.substring('--base-url='.length)
    : 'http://127.0.0.1:8080'

const ARTIFACT_DIR = join(__dirname, 'artifacts-surrender-dialog-mobile')
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

    // The SurrenderConfirmDialog is shown via game UI — wait for the root to appear.
    await page.waitForSelector('[data-testid="surrender-confirm-dialog-root"]', { timeout: 15000 })
    // Give the matchMedia listener time to attach and set data-mobile-layout.
    await page.waitForFunction(
        () => document.querySelector('[data-testid="surrender-confirm-dialog-root"]')?.hasAttribute('data-mobile-layout'),
        { timeout: 5000 }
    )

    const root = await page.$('[data-testid="surrender-confirm-dialog-root"]')
    check('surrender dialog root is mounted', root !== null)

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

    // SURRENDER button — btn-surrender-confirm class
    const surrenderBtn = await page.evaluate(() => {
        const el = document.querySelector('.btn-surrender-confirm')
        if (!el) return null
        const r = el.getBoundingClientRect()
        return { width: r.width, height: r.height, top: r.top, bottom: r.bottom }
    })
    check('SURRENDER button is at least 48px tall', surrenderBtn && surrenderBtn.height >= 48, `(got: ${surrenderBtn?.height}px)`)
    check('SURRENDER button width fits within 90vw', surrenderBtn && surrenderBtn.width <= 390 * 0.9, `(got: ${surrenderBtn?.width}px, 90vw=${390 * 0.9}px)`)

    // CANCEL button — btn-secondary class
    const cancelBtn = await page.evaluate(() => {
        const el = document.querySelector('.btn-secondary')
        if (!el) return null
        const r = el.getBoundingClientRect()
        return { width: r.width, height: r.height, top: r.top, bottom: r.bottom }
    })
    check('CANCEL button is at least 48px tall', cancelBtn && cancelBtn.height >= 48, `(got: ${cancelBtn?.height}px)`)

    // Vertical stacking: distinct y-coordinates (top values differ by at least a few pixels)
    check(
        'two buttons stack vertically on portrait',
        cancelBtn && surrenderBtn && Math.abs(cancelBtn.top - surrenderBtn.top) > 4,
        `(cancel.top=${cancelBtn?.top}, surrender.top=${surrenderBtn?.top})`
    )

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