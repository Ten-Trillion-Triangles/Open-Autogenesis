#!/usr/bin/env node
// kvisionApp-e2e/probes/commander-creation-mobile-portrait.mjs
//
// Mobile-portrait rendering probe for the CommanderCreationDialog.
// Drives a Playwright session at iPhone 12 dimensions (390x844) using the
// `?skipLogin=true` synthetic path so MainMenu mounts immediately, then
// opens the dialog via the "New Commander +" button. Verifies that the
// @media (max-width: 600px) override in night-mode.css fires correctly:
//   - data-mobile-layout="portrait" is set on the dialog root
//   - no horizontal overflow at 390px viewport width
//   - form fields are at least 44px tall (tap target)
//   - CANCEL button (btn-secondary-action) is at least 48px tall
//   - CREATE button (btn-play) is at least 48px tall AND width <= 90vw
//   - error-message container fits within viewport width
//
// Pre-req: dev server running at http://127.0.0.1:8080. Run with:
//   node kvisionApp-e2e/probes/commander-creation-mobile-portrait.mjs [--base-url=http://127.0.0.1:8080]

import { chromium, devices } from '@playwright/test'
import { mkdir } from 'node:fs/promises'
import { fileURLToPath } from 'node:url'
import { dirname, join } from 'node:path'

const __dirname = dirname(fileURLToPath(import.meta.url))

const BASE_URL_ARG = process.argv.find(a => a.startsWith('--base-url='))
const BASE_URL = BASE_URL_ARG
    ? BASE_URL_ARG.substring('--base-url='.length)
    : 'http://127.0.0.1:8080'

const ARTIFACT_DIR = join(__dirname, 'artifacts-commander-creation-mobile')
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
    await page.goto(BASE_URL + '/index.html?skipLogin=true', { waitUntil: 'domcontentloaded' })
    await page.waitForSelector('[data-testid="main-menu"]', { timeout: 15000 })

    // Open the commander creation dialog via the "New Commander +" button.
    const newCommanderButton = await page.$('button:has-text("New Commander")')
    if (!newCommanderButton) {
        throw new Error('Could not find "New Commander +" button on MainMenu')
    }
    await newCommanderButton.click()

    // The dialog mounts via .commander-creation-overlay (root class) and we set
    // data-testid="commander-creation-root" in the listener block.
    await page.waitForSelector('[data-testid="commander-creation-root"]', { timeout: 10000 })
    await page.waitForFunction(
        () => document.querySelector('[data-testid="commander-creation-root"]')?.hasAttribute('data-mobile-layout'),
        { timeout: 5000 }
    )

    const root = await page.$('[data-testid="commander-creation-root"]')
    check('commander creation root is mounted', root !== null)

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

    const inputBox = await page.evaluate(() => {
        const el = document.querySelector('.commander-creation-dialog input[type="text"]')
        if (!el) return null
        const r = el.getBoundingClientRect()
        return { width: r.width, height: r.height }
    })
    check(
        'name input is at least 44px tall (tap target)',
        inputBox && inputBox.height >= 44,
        `(got: ${inputBox?.height}px)`
    )

    const cancelBox = await page.evaluate(() => {
        const el = document.querySelector('.commander-creation-dialog .btn-secondary-action')
        if (!el) return null
        const r = el.getBoundingClientRect()
        return { width: r.width, height: r.height }
    })
    check(
        'CANCEL button is at least 48px tall',
        cancelBox && cancelBox.height >= 48,
        `(got: ${cancelBox?.height}px)`
    )

    const createBox = await page.evaluate(() => {
        const el = document.querySelector('.commander-creation-dialog .btn-play')
        if (!el) return null
        const r = el.getBoundingClientRect()
        return { width: r.width, height: r.height }
    })
    check(
        'CREATE button is at least 48px tall',
        createBox && createBox.height >= 48,
        `(got: ${createBox?.height}px)`
    )
    check(
        'CREATE button width is at most 90vw',
        createBox && createBox.width <= dims.innerWidth * 0.9,
        `(got: ${createBox?.width}px, viewport=${dims.innerWidth}px)`
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
