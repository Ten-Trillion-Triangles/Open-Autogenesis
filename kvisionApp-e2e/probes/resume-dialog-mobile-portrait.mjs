#!/usr/bin/env node
// kvisionApp-e2e/probes/resume-dialog-mobile-portrait.mjs
//
// Mobile-portrait rendering probe for the ResumeOrNewDialog.
// Drives a Playwright session at iPhone 12 dimensions (390x844) and verifies
// that the @media (max-width: 600px) override in night-mode.css fires
// correctly:
//   - data-mobile-layout="portrait" is set on the root (matchMedia listener)
//   - no horizontal overflow
//   - CANCEL, NEW GAME buttons (btn-secondary-action) are at least 48px tall
//   - RESUME button (btn-primary-action + [data-testid="resume-dialog-resume"])
//     is at least 48px tall and at least 44px wide
//   - three buttons stack vertically on portrait (distinct y-coordinates)
//
// Pre-req: dev server running at http://127.0.0.1:8080 (default kvisionApp
// dev port). Run with:
//   node kvisionApp-e2e/probes/resume-dialog-mobile-portrait.mjs
//
// Usage:
//   node kvisionApp-e2e/probes/resume-dialog-mobile-portrait.mjs [--base-url=http://127.0.0.1:8080]

import { chromium, devices } from '@playwright/test'
import { mkdir } from 'node:fs/promises'
import { fileURLToPath } from 'node:url'
import { dirname, join } from 'node:path'

const __dirname = dirname(fileURLToPath(import.meta.url))

const BASE_URL_ARG = process.argv.find(a => a.startsWith('--base-url='))
const BASE_URL = BASE_URL_ARG
    ? BASE_URL_ARG.substring('--base-url='.length)
    : 'http://127.0.0.1:8080'

const ARTIFACT_DIR = join(__dirname, 'artifacts-resume-dialog-mobile-portrait')
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
    // Wait for the resume dialog root to appear.
    await page.waitForSelector('[data-testid="resume-or-new-dialog"]', { timeout: 15000 })
    // Give the matchMedia listener time to attach and set data-mobile-layout.
    await page.waitForFunction(
        () => document.querySelector('[data-testid="resume-or-new-dialog"]')?.hasAttribute('data-mobile-layout'),
        { timeout: 5000 }
    )

    const root = await page.$('[data-testid="resume-or-new-dialog"]')
    check('resume-or-new-dialog root is mounted', root !== null)

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

    // CANCEL button: btn-secondary-action (first in the hPanel)
    const cancelBox = await page.evaluate(() => {
        const allBtns = Array.from(document.querySelectorAll('[data-testid="resume-or-new-dialog"] .btn-secondary-action'))
        // First btn-secondary-action is Cancel
        const el = allBtns[0]
        if (!el) return null
        const r = el.getBoundingClientRect()
        return { width: r.width, height: r.height, y: r.y }
    })
    check('CANCEL button (btn-secondary-action) is mounted', cancelBox !== null)
    check('CANCEL button >= 48px tall', cancelBox && cancelBox.height >= 48, `(got: ${cancelBox?.height}px)`)

    // NEW GAME button: btn-secondary-action (second in the hPanel)
    const newGameBox = await page.evaluate(() => {
        const allBtns = Array.from(document.querySelectorAll('[data-testid="resume-or-new-dialog"] .btn-secondary-action'))
        // Second btn-secondary-action is New Game
        const el = allBtns[1]
        if (!el) return null
        const r = el.getBoundingClientRect()
        return { width: r.width, height: r.height, y: r.y }
    })
    check('NEW GAME button (btn-secondary-action) is mounted', newGameBox !== null)
    check('NEW GAME button >= 48px tall', newGameBox && newGameBox.height >= 48, `(got: ${newGameBox?.height}px)`)

    // RESUME button: btn-primary-action AND [data-testid="resume-dialog-resume"]
    const resumeBox = await page.evaluate(() => {
        const el = document.querySelector('[data-testid="resume-dialog-resume"]')
        if (!el) return null
        const r = el.getBoundingClientRect()
        return { width: r.width, height: r.height, y: r.y }
    })
    check('RESUME button (btn-primary-action + [data-testid="resume-dialog-resume"]) is mounted', resumeBox !== null)
    check('RESUME button >= 48px tall', resumeBox && resumeBox.height >= 48, `(got: ${resumeBox?.height}px)`)
    check('RESUME button >= 44px wide', resumeBox && resumeBox.width >= 44, `(got: ${resumeBox?.width}px)`)

    // Verify three buttons stack vertically on portrait (distinct y-coordinates, each exceeds previous)
    const stackCheck = await page.evaluate(() => {
        const allBtns = Array.from(document.querySelectorAll('[data-testid="resume-or-new-dialog"] .btn-secondary-action'))
        const resumeEl = document.querySelector('[data-testid="resume-dialog-resume"]')
        if (!resumeEl) return null
        const buttons = [...allBtns, resumeEl].filter(Boolean)
        if (buttons.length < 3) return null
        const rects = buttons.map(el => {
            const r = el.getBoundingClientRect()
            return { y: r.y, x: r.x }
        })
        // Each button's y should be greater than the previous (stacked vertically)
        const stacked = rects[0].y < rects[1].y && rects[1].y < rects[2].y
        return { stacked, rects }
    })
    check('three buttons stack vertically on portrait', stackCheck && stackCheck.stacked === true,
        stackCheck ? `(y-values: ${stackCheck.rects.map(r => r.y.toFixed(1)).join(', ')})` : '')

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