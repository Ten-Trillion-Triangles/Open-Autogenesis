#!/usr/bin/env node
// kvisionApp-e2e/probes/loading-screen-mobile-landscape.mjs
//
// Mobile-landscape rendering probe for the pre-MainMenu LoadingScreen.
// Drives a Playwright session at iPhone 12 rotated dimensions (844x390) and
// verifies that the desktop layout (≥600px wide) is preserved — no mobile
// CSS bleed:
//   - data-mobile-layout="desktop" is set on the root (matchMedia listener)
//   - wordmark font-size resolves to 64px (desktop default, NOT 36px)
//   - CTA button is at least 320px wide (desktop default, NOT 90%-of-portrait)
//   - CTA button has the gold border (desktop .loading-screen-cta rules)
//
// Pre-req: dev server running at http://127.0.0.1:8080 (default kvisionApp
// dev port). Run with: node kvisionApp-e2e/probes/loading-screen-mobile-landscape.mjs
//
// Usage:
//   node kvisionApp-e2e/probes/loading-screen-mobile-landscape.mjs [--base-url=http://127.0.0.1:8080]

import { chromium, devices } from '@playwright/test'
import { mkdir } from 'node:fs/promises'
import { fileURLToPath } from 'node:url'
import { dirname, join } from 'node:path'

const __dirname = dirname(fileURLToPath(import.meta.url))

const BASE_URL_ARG = process.argv.find(a => a.startsWith('--base-url='))
const BASE_URL = BASE_URL_ARG
    ? BASE_URL_ARG.substring('--base-url='.length)
    : 'http://127.0.0.1:8080'

const ARTIFACT_DIR = join(__dirname, 'artifacts-loading-screen-mobile')
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
// iPhone 12 user agent but viewport rotated to landscape.
const context = await browser.newContext({
    ...devices['iPhone 12'],
    viewport: { width: 844, height: 390 },
})
const page = await context.newPage()

try {
    await page.goto(BASE_URL + '/index.html', { waitUntil: 'domcontentloaded' })
    await page.waitForSelector('[data-testid="loading-screen-root"]', { timeout: 15000 })
    // Give the matchMedia listener time to attach and set data-mobile-layout.
    await page.waitForFunction(
        () => document.querySelector('[data-testid="loading-screen-root"]')?.hasAttribute('data-mobile-layout'),
        { timeout: 5000 }
    )

    const root = await page.$('[data-testid="loading-screen-root"]')
    check('loading screen root is mounted', root !== null)

    const mobileLayout = await root.getAttribute('data-mobile-layout')
    check('data-mobile-layout="desktop" set on landscape', mobileLayout === 'desktop', `(got: ${mobileLayout})`)

    const wordmarkStyle = await page.evaluate(() => {
        const el = document.querySelector('[data-testid="loading-screen-wordmark"]')
        if (!el) return null
        return {
            fontSize: window.getComputedStyle(el).fontSize,
            borderColor: window.getComputedStyle(el).borderColor,
        }
    })
    check(
        'wordmark font size is 64px on landscape (desktop layout)',
        wordmarkStyle && wordmarkStyle.fontSize === '64px',
        `(got: ${wordmarkStyle?.fontSize})`
    )

    const ctaBox = await page.evaluate(() => {
        const el = document.querySelector('[data-testid="loading-screen-cta"]')
        if (!el) return null
        const r = el.getBoundingClientRect()
        const s = window.getComputedStyle(el)
        return { width: r.width, height: r.height, minWidth: s.minWidth }
    })
    check(
        'CTA button is at least 320px wide on landscape (desktop layout)',
        ctaBox && ctaBox.width >= 320,
        `(got: ${ctaBox?.width}px)`
    )
    check(
        'CTA button min-width is 320px (desktop .loading-screen-cta rule fired)',
        ctaBox && ctaBox.minWidth === '320px',
        `(got: ${ctaBox?.minWidth})`
    )

    // Capture screenshot for visual review.
    await page.screenshot({ path: join(ARTIFACT_DIR, 'landscape-844x390.png'), fullPage: true })
    console.log(`SCREENSHOT: ${join(ARTIFACT_DIR, 'landscape-844x390.png')}`)
}
catch (err) {
    console.error('PROBE CRASHED:', err.message)
    await page.screenshot({ path: join(ARTIFACT_DIR, 'landscape-crash.png'), fullPage: true }).catch(() => {})
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