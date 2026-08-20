#!/usr/bin/env node
// kvisionApp-e2e/probes/loading-screen-mobile-portrait.mjs
//
// Mobile-portrait rendering probe for the pre-MainMenu LoadingScreen.
// Drives a Playwright session at iPhone 12 dimensions (390x844) and verifies
// that the @media (max-width: 600px) override in night-mode.css fires
// correctly:
//   - data-mobile-layout="portrait" is set on the root (matchMedia listener)
//   - no horizontal overflow
//   - wordmark font-size resolves to 36px (CSS @media override)
//   - CTA button is at least 48px tall (Material Design tap target minimum)
//   - CTA tap target width is at least 44px (Apple HIG tap target minimum)
//
// Pre-req: dev server running at http://127.0.0.1:8080 (default kvisionApp
// dev port). Run with: node kvisionApp-e2e/probes/loading-screen-mobile-portrait.mjs
//
// Usage:
//   node kvisionApp-e2e/probes/loading-screen-mobile-portrait.mjs [--base-url=http://127.0.0.1:8080]

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
const context = await browser.newContext({
    ...devices['iPhone 12'],
})
const page = await context.newPage()

try {
    await page.goto(BASE_URL + '/index.html', { waitUntil: 'domcontentloaded' })
    // Wait for the loading screen root to appear (it mounts at app start).
    await page.waitForSelector('[data-testid="loading-screen-root"]', { timeout: 15000 })
    // Give the matchMedia listener time to attach and set data-mobile-layout.
    await page.waitForFunction(
        () => document.querySelector('[data-testid="loading-screen-root"]')?.hasAttribute('data-mobile-layout'),
        { timeout: 5000 }
    )

    const root = await page.$('[data-testid="loading-screen-root"]')
    check('loading screen root is mounted', root !== null)

    const mobileLayout = await root.getAttribute('data-mobile-layout')
    check('data-mobile-layout="portrait" set', mobileLayout === 'portrait', `(got: ${mobileLayout})`)

    const overflow = await page.evaluate(() => {
        return document.documentElement.scrollWidth > window.innerWidth
    })
    const dims = await page.evaluate(() => ({
        scrollWidth: document.documentElement.scrollWidth,
        innerWidth: window.innerWidth,
    }))
    check('no horizontal overflow', !overflow, `(scrollWidth=${dims.scrollWidth}, innerWidth=${dims.innerWidth})`)

    const wordmarkStyle = await page.evaluate(() => {
        const el = document.querySelector('[data-testid="loading-screen-wordmark"]')
        if (!el) return null
        const s = window.getComputedStyle(el)
        return {
            fontSize: s.fontSize,
            scrollWidth: el.scrollWidth,
            clientWidth: el.clientWidth,
        }
    })
    check('wordmark is visible', wordmarkStyle !== null)
    check(
        'wordmark fits within element width',
        wordmarkStyle && wordmarkStyle.scrollWidth <= wordmarkStyle.clientWidth + 1,
        `(scrollWidth=${wordmarkStyle?.scrollWidth}, clientWidth=${wordmarkStyle?.clientWidth})`
    )
    check(
        'wordmark font size is 36px on portrait',
        wordmarkStyle && wordmarkStyle.fontSize === '36px',
        `(got: ${wordmarkStyle?.fontSize})`
    )

    const ctaBox = await page.evaluate(() => {
        const el = document.querySelector('[data-testid="loading-screen-cta"]')
        if (!el) return null
        const r = el.getBoundingClientRect()
        return { width: r.width, height: r.height }
    })
    check('CTA button is at least 48px tall', ctaBox && ctaBox.height >= 48, `(got: ${ctaBox?.height}px)`)
    check('CTA button is at least 44px wide', ctaBox && ctaBox.width >= 44, `(got: ${ctaBox?.width}px)`)

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
