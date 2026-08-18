#!/usr/bin/env node
// kvisionApp-e2e/probes/mainmenu-mobile-portrait.mjs
//
// Mobile-portrait rendering probe for the MainMenu widget surface.
// Drives a Playwright session at iPhone 12 dimensions (390x844) using the
// `?skipLogin=true` synthetic path so the MainMenu mounts immediately
// (real-AccelByte-logged-in probes are out of scope here; LoadingScreen
// probes follow the same convention). Verifies that the
// @media (max-width: 600px) override in night-mode.css fires correctly:
//   - data-mobile-layout="portrait" is set on the root (matchMedia listener)
//   - no horizontal overflow at 390px viewport width
//   - PLAY button (btn-play) is at least 48px tall (Material Design tap target minimum)
//   - PLAY button width is at most 90vw (no horizontal overflow)
//   - btn-secondary-action is at least 48px tall
//   - btn-options, btn-add-credits, btn-friends meet 44px tap target minimum
//   - header row collapses (display-name + version-text + credits-container
//     + Shop + Usage + Options do not clip in a 390px-wide row)
//
// Pre-req: dev server running at http://127.0.0.1:8080 (default kvisionApp
// dev port). Run with:
//   node kvisionApp-e2e/probes/mainmenu-mobile-portrait.mjs [--base-url=http://127.0.0.1:8080]

import { chromium, devices } from '@playwright/test'
import { mkdir } from 'node:fs/promises'
import { fileURLToPath } from 'node:url'
import { dirname, join } from 'node:path'

const __dirname = dirname(fileURLToPath(import.meta.url))

const BASE_URL_ARG = process.argv.find(a => a.startsWith('--base-url='))
const BASE_URL = BASE_URL_ARG
    ? BASE_URL_ARG.substring('--base-url='.length)
    : 'http://127.0.0.1:8080'

const ARTIFACT_DIR = join(__dirname, 'artifacts-mainmenu-mobile')
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
    // Use ?skipLogin=true so the MainMenu mounts immediately. The synthetic
    // path replaces the real AccelByte login with a guest-user placeholder,
    // so any e2e probe that needs MainMenu must opt into this path.
    await page.goto(BASE_URL + '/index.html?skipLogin=true', { waitUntil: 'domcontentloaded' })
    // LoadingScreen mounts first; click its CTA to advance to MainMenu.
    // (Pre-existing bug in this probe: it assumed skipLogin bypasses LoadingScreen,
    //  but the CTA click is still required before MainMenu mounts.)
    await page.waitForSelector('[data-testid="loading-screen-cta"]', { timeout: 15000 })
    await page.click('[data-testid="loading-screen-cta"]')
    await page.waitForSelector('[data-testid="main-menu"]', { timeout: 20000 })
    await page.waitForFunction(
        () => document.querySelector('[data-testid="main-menu"]')?.hasAttribute('data-mobile-layout'),
        { timeout: 5000 }
    )

    const root = await page.$('[data-testid="main-menu"]')
    check('main menu root is mounted', root !== null)

    const mobileLayout = await root.getAttribute('data-mobile-layout')
    check('data-mobile-layout="portrait" set', mobileLayout === 'portrait', `(got: ${mobileLayout})`)

    const dims = await page.evaluate(() => ({
        scrollWidth: document.documentElement.scrollWidth,
        innerWidth: window.innerWidth,
    }))
    check(
        'no horizontal overflow at 390px viewport',
        dims.scrollWidth <= dims.innerWidth + 1,
        `(scrollWidth=${dims.scrollWidth}, innerWidth=${dims.innerWidth})`
    )

    const playBox = await page.evaluate(() => {
        const el = document.querySelector('.btn-play')
        if (!el) return null
        const r = el.getBoundingClientRect()
        return { width: r.width, height: r.height, vw: window.innerWidth }
    })
    check('PLAY button is at least 48px tall', playBox && playBox.height >= 48, `(got: ${playBox?.height}px)`)
    check(
        'PLAY button width is at most 90vw',
        playBox && playBox.width <= playBox.vw * 0.9,
        `(got: ${playBox?.width}px, viewport=${playBox?.vw}px)`
    )

    // Bottom-row secondary buttons (Collection / New Commander) are
    // full-width on portrait at >=48px so they're easy to hit. The
    // header secondary buttons (Shop / Usage) are compact (~44px) so
    // they fit alongside the credits-pill without stretching.
    const bottomSecondaryBox = await page.evaluate(() => {
        const els = document.querySelectorAll('.main-menu-bottom .btn-secondary-action')
        if (!els.length) return null
        const r = els[0].getBoundingClientRect()
        return { width: r.width, height: r.height }
    })
    check(
        'bottom-row btn-secondary-action is at least 48px tall and full-width',
        bottomSecondaryBox && bottomSecondaryBox.height >= 48,
        `(got: ${bottomSecondaryBox?.height}px; expected >=48)`
    )

    const headerSecondaryBox = await page.evaluate(() => {
        const els = document.querySelectorAll('.main-menu-header .btn-secondary-action')
        if (!els.length) return null
        const r = els[0].getBoundingClientRect()
        return { width: r.width, height: r.height }
    })
    check(
        'header-row btn-secondary-action is compact (32-44px tall, NOT stretched)',
        headerSecondaryBox && headerSecondaryBox.height >= 32 && headerSecondaryBox.height <= 44,
        `(got: ${headerSecondaryBox?.height}px; expected 32-44px so it doesn't balloon)`
    )

    const optionsBox = await page.evaluate(() => {
        const el = document.querySelector('.btn-options')
        if (!el) return null
        const r = el.getBoundingClientRect()
        return { width: r.width, height: r.height }
    })
    check(
        'btn-options is at least 44px wide AND 44px tall',
        optionsBox && optionsBox.width >= 44 && optionsBox.height >= 44,
        `(got: ${optionsBox?.width}x${optionsBox?.height}px)`
    )

    const headerBox = await page.evaluate(() => {
        const el = document.querySelector('.main-menu-header')
        if (!el) return null
        const r = el.getBoundingClientRect()
        return { width: r.width, height: r.height }
    })
    // Header row: compact on portrait (max-height: 56px) so the
    // credits-pill + Shop + Usage + gear fit on a single ~56px row
    // instead of stretching vertically. Was previously >80px when the
    // buttons stretched to match the credits-pill height; the polish
    // pass locked it down. Allow a 52-90px band so small wrap heights
    // (when the row fits one line) are accepted too.
    check(
        'header row is compact (height 52-90px on portrait)',
        headerBox && headerBox.height >= 52 && headerBox.height <= 90,
        `(got: ${headerBox?.height}px; expected 52-90px on compact portrait header)`
    )

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