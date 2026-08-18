#!/usr/bin/env node
// kvisionApp-e2e/probes/collection-overlay-mobile-portrait.mjs
//
// Mobile-portrait rendering probe for the CollectionOverlay widget.
// Drives a Playwright session at iPhone 12 dimensions (390x844) and verifies
// that the @media (max-width: 600px) override in night-mode.css fires
// correctly:
//   - data-mobile-layout="portrait" is set on the root (matchMedia listener)
//   - no horizontal overflow
//   - tab buttons meet minimum touch-target sizes (48px tall, 44px wide)
//   - sub-windows (login-widget-window) fit within viewport when present
//
// Pre-req: dev server running at http://127.0.0.1:8080 (default kvisionApp
// dev port). Run with: node kvisionApp-e2e/probes/collection-overlay-mobile-portrait.mjs
//
// Usage:
//   node kvisionApp-e2e/probes/collection-overlay-mobile-portrait.mjs [--base-url=http://127.0.0.1:8080]

import { chromium, devices } from '@playwright/test'
import { mkdir } from 'node:fs/promises'
import { fileURLToPath } from 'node:url'
import { dirname, join } from 'node:path'

const __dirname = dirname(fileURLToPath(import.meta.url))

const BASE_URL_ARG = process.argv.find(a => a.startsWith('--base-url='))
const BASE_URL = BASE_URL_ARG
    ? BASE_URL_ARG.substring('--base-url='.length)
    : 'http://127.0.0.1:8080'

const ARTIFACT_DIR = join(__dirname, 'artifacts-collection-overlay-mobile')
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
    // Wait for the collection overlay root to appear.
    // Try data-testid first, fall back to class selector.
    const overlayEl = await page.waitForSelector(
        '[data-testid="collection-overlay"], .collection-overlay',
        { timeout: 15000 }
    )
    // Give the matchMedia listener time to attach and set data-mobile-layout.
    await page.waitForFunction(
        () => {
            const el = document.querySelector('[data-testid="collection-overlay"]') ||
                document.querySelector('.collection-overlay')
            return el?.hasAttribute('data-mobile-layout')
        },
        { timeout: 5000 }
    )

    // Use whichever selector resolved
    const rootSelector = await page.evaluate(() =>
        document.querySelector('[data-testid="collection-overlay"]') ??
        document.querySelector('.collection-overlay')
    )
    const root = rootSelector
        ? (await page.evaluate(
            (sel) => document.querySelector(sel) !== null,
            rootSelector
          ))
        : false

    const overlayRoot = await page.$('[data-testid="collection-overlay"]') ||
                        await page.$('.collection-overlay')
    check('collection overlay is mounted', overlayRoot !== null,
        `(used selector: ${overlayRoot ? '[data-testid=collection-overlay]' : '.collection-overlay'})`)

    const mobileLayout = await overlayRoot?.getAttribute('data-mobile-layout')
    check('data-mobile-layout="portrait" set', mobileLayout === 'portrait', `(got: ${mobileLayout})`)

    const overflow = await page.evaluate(() => {
        return document.documentElement.scrollWidth > window.innerWidth
    })
    const dims = await page.evaluate(() => ({
        scrollWidth: document.documentElement.scrollWidth,
        innerWidth: window.innerWidth,
    }))
    check('no horizontal overflow at 390px',
        !overflow,
        `(scrollWidth=${dims.scrollWidth}, innerWidth=${dims.innerWidth})`)

    // Check tab button sizes (general — all .collection-tab-button)
    const tabButtons = await page.evaluate(() => {
        const els = Array.from(document.querySelectorAll('.collection-tab-button'))
        return els.map(el => {
            const r = el.getBoundingClientRect()
            return { width: r.width, height: r.height }
        })
    })
    check('collection tab buttons found', tabButtons.length >= 2,
        `(found ${tabButtons.length}, expected at least 2)`)
    if (tabButtons.length >= 1) {
        check('collection tab button height >= 48px (active)',
            tabButtons.some(b => b.height >= 48),
            `(${tabButtons.map(b => b.height.toFixed(1)).join('px, ')}px)`)
        check('collection tab button width >= 44px',
            tabButtons.every(b => b.width >= 44),
            `(${tabButtons.map(b => b.width.toFixed(1)).join('px, ')}px)`)
    }

    // Check active tab specifically
    const activeTab = await page.evaluate(() => {
        const el = document.querySelector('.collection-tab-button-active')
        if (!el) return null
        const r = el.getBoundingClientRect()
        return { width: r.width, height: r.height }
    })
    check('active tab button found', activeTab !== null)
    if (activeTab) {
        check('active tab button height >= 48px', activeTab.height >= 48,
            `(got ${activeTab.height}px)`)
        check('active tab button width >= 44px', activeTab.width >= 44,
            `(got ${activeTab.width}px)`)
    }

    // Check sub-windows fit within viewport (they may not be visible until opened)
    const subWindows = await page.evaluate(() => {
        const els = Array.from(document.querySelectorAll('.login-widget-window'))
        return els.map(el => {
            const r = el.getBoundingClientRect()
            const visible = el.checkVisibility?.() ?? (r.width > 0 && r.height > 0)
            return {
                width: r.width,
                height: r.height,
                visible,
                fitsHorizontally: r.width <= window.innerWidth,
                fitsVertically: r.height <= window.innerHeight,
            }
        })
    })
    // Sub-windows are optional/detail windows — only assert if present AND visible
    const visibleSubWindows = subWindows.filter(w => w.visible)
    if (visibleSubWindows.length > 0) {
        check('visible sub-windows fit within viewport width',
            visibleSubWindows.every(w => w.fitsHorizontally),
            visibleSubWindows.map(w => `w=${w.width}, innerW=${window.innerWidth}`).join('; '))
        check('visible sub-windows fit within viewport height',
            visibleSubWindows.every(w => w.fitsVertically),
            visibleSubWindows.map(w => `h=${w.height}, innerH=${window.innerHeight}`).join('; '))
    } else {
        console.log('SKIP: no visible .login-widget-window sub-windows to check (not yet opened)')
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
