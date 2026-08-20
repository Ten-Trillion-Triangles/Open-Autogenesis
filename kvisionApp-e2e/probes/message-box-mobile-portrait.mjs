#!/usr/bin/env node
// kvisionApp-e2e/probes/message-box-mobile-portrait.mjs
//
// Mobile-portrait rendering probe for the MessageBox overlay.
// Drives a Playwright session at iPhone 12 dimensions (390x844) and verifies
// that the @media (max-width: 600px) override in night-mode.css fires
// correctly:
//   - data-mobile-layout="portrait" is set on the root (matchMedia listener)
//   - no horizontal overflow
//   - Modal scales to <= 95vw on portrait
//   - btn-secondary-action buttons are at least 48px tall (Material Design tap target minimum)
//   - Modal body content (message text) fits within viewport width
//
// Pre-req: dev server running at http://127.0.0.1:8080 (default kvisionApp
// dev port). Run with: node kvisionApp-e2e/probes/message-box-mobile-portrait.mjs
//
// Usage:
//   node kvisionApp-e2e/probes/message-box-mobile-portrait.mjs [--base-url=http://127.0.0.1:8080]

import { chromium, devices } from '@playwright/test'
import { mkdir } from 'node:fs/promises'
import { fileURLToPath } from 'node:url'
import { dirname, join } from 'node:path'

const __dirname = dirname(fileURLToPath(import.meta.url))

const BASE_URL_ARG = process.argv.find(a => a.startsWith('--base-url='))
const BASE_URL = BASE_URL_ARG
    ? BASE_URL_ARG.substring('--base-url='.length)
    : 'http://127.0.0.1:8080'

const ARTIFACT_DIR = join(__dirname, 'artifacts-message-box-mobile')
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

    // Wait for the message box overlay to appear.
    await page.waitForSelector('[data-testid="autogenesis-message-box-overlay"]', { timeout: 15000 })
    // Give the matchMedia listener time to attach and set data-mobile-layout.
    await page.waitForFunction(
        () => document.querySelector('[data-testid="autogenesis-message-box-overlay"]')?.hasAttribute('data-mobile-layout'),
        { timeout: 5000 }
    )

    const root = await page.$('[data-testid="autogenesis-message-box-overlay"]')
    check('[data-testid="autogenesis-message-box-overlay"] is mounted', root !== null)

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

    const modalBox = await page.evaluate(() => {
        const el = document.querySelector('.autogenesis-message-box-content')
        if (!el) return null
        const r = el.getBoundingClientRect()
        return { width: r.width, height: r.height }
    })
    check('Modal scales to <= 95vw on portrait', modalBox && modalBox.width <= 390 * 0.95, `(got: ${modalBox?.width}px vs 95vw=${390 * 0.95}px)`)

    const buttons = await page.evaluate(() => {
        const els = document.querySelectorAll('.btn-secondary-action')
        return Array.from(els).map(el => {
            const r = el.getBoundingClientRect()
            return { height: r.height }
        })
    })
    check('btn-secondary-action buttons >= 48px tall', buttons.length > 0 && buttons.every(b => b.height >= 48), `(got: ${buttons.map(b => b.height + 'px').join(', ')})`)

    const bodyContent = await page.evaluate(() => {
        const el = document.querySelector('.autogenesis-message-box-content p')
        if (!el) return null
        const r = el.getBoundingClientRect()
        const s = window.getComputedStyle(el)
        return {
            scrollWidth: el.scrollWidth,
            clientWidth: el.clientWidth,
            innerWidth: window.innerWidth,
            fontSize: s.fontSize,
        }
    })
    check('Modal body content fits within viewport width', bodyContent && bodyContent.scrollWidth <= bodyContent.innerWidth, `(scrollWidth=${bodyContent?.scrollWidth}, innerWidth=${bodyContent?.innerWidth})`)

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