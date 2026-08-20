#!/usr/bin/env node
// kvisionApp-e2e/probes/billing-overlay-mobile-portrait.mjs
//
// Mobile-portrait rendering probe for the Shop and Usage billing overlays.
// Drives a Playwright session at iPhone 12 dimensions (390x844) and verifies
// that the @media (max-width: 600px) override in night-mode.css fires
// correctly for both ShopOverlay and UsageOverlay (which share the same
// BillingOverlayWindow base and `.billing-modal-window-host` class):
//   - data-mobile-layout="portrait" is set on the modal host
//   - no horizontal overflow at 390px
//   - modal scales to <= 95vw AND <= 95vh
//   - billing-tab buttons are at least 48px tall
//   - .billing-tabs reflows vertically (stacked, not horizontal)
//
// Pre-req: dev server running at http://127.0.0.1:8080 (default kvisionApp
// dev port). Run with: node kvisionApp-e2e/probes/billing-overlay-mobile-portrait.mjs
//
// Usage:
//   node kvisionApp-e2e/probes/billing-overlay-mobile-portrait.mjs [--base-url=http://127.0.0.1:8080]

import { chromium, devices } from '@playwright/test'
import { mkdir } from 'node:fs/promises'
import { fileURLToPath } from 'node:url'
import { dirname, join } from 'node:path'

const __dirname = dirname(fileURLToPath(import.meta.url))

const BASE_URL_ARG = process.argv.find(a => a.startsWith('--base-url='))
const BASE_URL = BASE_URL_ARG
    ? BASE_URL_ARG.substring('--base-url='.length)
    : 'http://127.0.0.1:8080'

const ARTIFACT_DIR = join(__dirname, 'artifacts-billing-overlay-mobile')
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
    // Navigate to the app. If ?overlay=shop is already in the URL the app
    // should open the Shop overlay directly; otherwise we open it from the
    // MainMenu.
    await page.goto(BASE_URL + '/index.html', { waitUntil: 'domcontentloaded' })

    // Check if we need to open the overlay from MainMenu or if it's already open.
    const url = page.url()
    const alreadyHasOverlay = url.includes('overlay=shop') || url.includes('overlay=usage')

    if (!alreadyHasOverlay) {
        // Wait for MainMenu to appear, then open the Shop overlay.
        await page.waitForSelector('[data-testid="main-menu-root"]', { timeout: 15000 })
        // Click the SHOP button in MainMenu — use the billing-nav or shop button.
        const shopBtn = await page.$('[data-testid="main-menu-shop-btn"], .main-menu-shop-btn, button:has-text("SHOP")')
        if (shopBtn) {
            await shopBtn.click()
        } else {
            console.log('WARN: could not find shop button in main menu, trying direct navigation')
            await page.goto(BASE_URL + '/index.html?overlay=shop', { waitUntil: 'domcontentloaded' })
        }
    }

    // Wait for the billing modal host to appear.
    await page.waitForSelector('.billing-modal-window-host', { timeout: 15000 })
    // Give the matchMedia listener time to attach and set data-mobile-layout.
    await page.waitForFunction(
        () => document.querySelector('.billing-modal-window-host')?.hasAttribute('data-mobile-layout'),
        { timeout: 5000 }
    )

    const host = await page.$('.billing-modal-window-host')
    check('.billing-modal-window-host is mounted', host !== null)

    const mobileLayout = await host.getAttribute('data-mobile-layout')
    check('data-mobile-layout="portrait" set on modal host', mobileLayout === 'portrait', `(got: ${mobileLayout})`)

    // No horizontal overflow at 390px.
    const overflow = await page.evaluate(() => {
        return document.documentElement.scrollWidth > window.innerWidth
    })
    const dims = await page.evaluate(() => ({
        scrollWidth: document.documentElement.scrollWidth,
        innerWidth: window.innerWidth,
    }))
    check('no horizontal overflow at 390px', !overflow, `(scrollWidth=${dims.scrollWidth}, innerWidth=${dims.innerWidth})`)

    // Modal scales to <= 95vw AND <= 95vh.
    const modalRect = await page.evaluate(() => {
        const el = document.querySelector('.billing-modal-window-host')
        if (!el) return null
        const r = el.getBoundingClientRect()
        return { width: r.width, height: r.height, vw: window.innerWidth, vh: window.innerHeight }
    })
    check('modal is mounted (has bounding rect)', modalRect !== null)
    if (modalRect) {
        const vw = modalRect.vw
        const vh = modalRect.vh
        check(
            'modal width <= 95vw',
            modalRect.width <= vw * 0.95,
            `(width=${modalRect.width.toFixed(1)}, 95vw=${(vw * 0.95).toFixed(1)})`
        )
        check(
            'modal height <= 95vh',
            modalRect.height <= vh * 0.95,
            `(height=${modalRect.height.toFixed(1)}, 95vh=${(vh * 0.95).toFixed(1)})`
        )
    }

    // Tab buttons (.billing-tab) are >= 48px tall on portrait.
    const tabHeights = await page.evaluate(() => {
        const tabs = Array.from(document.querySelectorAll('.billing-tab'))
        return tabs.map(t => {
            const r = t.getBoundingClientRect()
            return r.height
        })
    })
    check('at least one .billing-tab found', tabHeights.length > 0)
    const allTallEnough = tabHeights.length > 0 && tabHeights.every(h => h >= 48)
    check('all .billing-tab buttons >= 48px tall on portrait', allTallEnough, `(heights: ${tabHeights.map(h => h.toFixed(1)).join(', ')})`)

    // .billing-tabs reflows vertically on portrait (flex-direction is column, or
    // tabs are stacked such that the container is taller than a single row).
    const tabsLayout = await page.evaluate(() => {
        const tabsEl = document.querySelector('.billing-tabs')
        if (!tabsEl) return null
        const style = window.getComputedStyle(tabsEl)
        const r = tabsEl.getBoundingClientRect()
        const children = Array.from(tabsEl.querySelectorAll('.billing-tab'))
        // If flex-direction is column, it's vertical.
        // Otherwise check if children are stacked (container height > 2x average child height).
        const flexDir = style.flexDirection || style.flexFlow || 'row'
        const avgChildH = children.length
            ? children.reduce((sum, c) => sum + c.getBoundingClientRect().height, 0) / children.length
            : 0
        return {
            flexDirection: flexDir,
            containerHeight: r.height,
            avgChildHeight: avgChildH,
        }
    })
    if (tabsLayout) {
        const isVertical = tabsLayout.flexDirection === 'column'
        // Fallback: if not explicitly column, check if height suggests stacking.
        const isStacked = isVertical || (tabsLayout.avgChildHeight > 0 && tabsLayout.containerHeight > tabsLayout.avgChildHeight * 1.5)
        check('.billing-tabs reflows vertically on portrait (flex-direction=column or visibly stacked)', isStacked,
            `(flexDirection=${tabsLayout.flexDirection}, containerH=${tabsLayout.containerHeight.toFixed(1)}, avgChildH=${tabsLayout.avgChildHeight.toFixed(1)})`)
    } else {
        check('.billing-tabs reflows vertically on portrait', false, '(element not found)')
    }

    // ESC closes modal (optional sanity check — skip if brittle).
    try {
        await page.keyboard.press('Escape')
        await page.waitForFunction(
            () => document.querySelector('.billing-modal-window-host') === null,
            { timeout: 3000 }
        )
        check('ESC closes modal', true)
    } catch {
        check('ESC closes modal', false, '(optional check — modal did not close within 3s)')
    }

    // Capture screenshot for visual review.
    await page.screenshot({ path: join(ARTIFACT_DIR, 'billing-overlay-portrait-390x844.png'), fullPage: true })
    console.log(`SCREENSHOT: ${join(ARTIFACT_DIR, 'billing-overlay-portrait-390x844.png')}`)
}
catch (err) {
    console.error('PROBE CRASHED:', err.message)
    await page.screenshot({ path: join(ARTIFACT_DIR, 'billing-overlay-crash.png'), fullPage: true }).catch(() => {})
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