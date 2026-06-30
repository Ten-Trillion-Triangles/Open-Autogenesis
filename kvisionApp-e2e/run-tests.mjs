#!/usr/bin/env node
// e2e test runner for kvisionApp hover border lines
//
// This script is the canonical way to run the e2e suite. The
// `playwright test` CLI has library-resolution issues in this
// sandbox that cause it to hang silently, so we use the
// playwright API directly. The spec file at
// `tests/hover-border-lines.spec.mjs` is the same spec, just
// expressed as plain functions for direct execution.
//
// Pre-requisites:
//   1. Production bundle built: `./gradlew :kvisionApp:jsBrowserDistribution`
//   2. Static server running: `python3 -m http.server 4175 -b 127.0.0.1 -d
//      ../kvisionApp/build/dist/js/productionExecutable`
//
// Usage:
//   node run-tests.mjs
//   node run-tests.mjs --headed
//   node run-tests.mjs --grep "hover A"

import { chromium } from '@playwright/test'
import { readFile } from 'node:fs/promises'
import { fileURLToPath } from 'node:url'
import { dirname, join } from 'node:path'

const __dirname = dirname(fileURLToPath(import.meta.url))

const HEADED = process.argv.includes('--headed')
const GREP_ARG = process.argv.find(a => a.startsWith('--grep='))
const GREP = GREP_ARG ? GREP_ARG.substring('--grep='.length) : null

const BASE_URL = process.env.KVISION_E2E_URL || 'http://127.0.0.1:4175'
const FIXTURE_PATH = join(__dirname, 'tests', 'fixtures', 'tiny-map.map')

const log = (s) => console.log(`[${new Date().toISOString().slice(11, 23)}] ${s}`)

function isPreExistingNetworkError(text)
{
    return text.includes('ERR_CONNECTION_REFUSED') ||
        text.includes('ERR_FAILED') ||
        text.includes('Failed to load resource') ||
        text.includes('Failed to fetch') ||
        text.includes('Fail to fetch') ||
        text.includes('blocked by CORS') ||
        text.includes('integrity') ||
        text.includes('WebSocket') ||
        text.includes('LocalDevDetector') ||
        text.includes('bootstrap.min.css')
}

async function setupPage(browser)
{
    const page = await browser.newPage({ viewport: { width: 1280, height: 800 } })
    const errors = []
    page.on('pageerror', e => {
        if (!isPreExistingNetworkError(e.message)) errors.push(`pageerror: ${e.message}`)
    })
    page.on('console', m => {
        if (m.type() === 'error') {
            const t = m.text()
            if (!isPreExistingNetworkError(t)) errors.push(`console.error: ${t}`)
        }
    })
    page._capturedErrors = errors
    return page
}

async function loadTinyMap(page)
{
    const mapBytes = await readFile(FIXTURE_PATH)
    await page.evaluate(async (bytes) => {
        await window.loadMapForTest(bytes)
    }, Array.from(mapBytes))
    await page.locator('[data-testid="territory-icon"]').first().waitFor({ state: 'visible', timeout: 10_000 })
}

async function borderLineCount(page)
{
    const svg = page.locator('[data-testid="map-svg-borders"]')
    if (await svg.count() !== 1) throw new Error('map-svg-borders SVG not mounted')
    return svg.locator('line').count()
}

async function waitForLineCount(page, expected, label)
{
    const start = Date.now()
    while (Date.now() - start < 5_000) {
        const count = await borderLineCount(page)
        if (count === expected) return { pass: true, count, label }
        await page.waitForTimeout(50)
    }
    const count = await borderLineCount(page)
    return { pass: false, count, label }
}

const cases = [
    {
        name: 'hovering a pin draws exactly its neighbors lines',
        fn: async (page) => {
            const pinA = page.locator('[data-testid="territory-icon"][data-territory-name="A"]')
            await pinA.hover()
            const r = await waitForLineCount(page, 3, 'A → 3 lines')
            if (r.pass) await page.screenshot({ path: 'artifacts/hover-A.png', fullPage: true })
            return r
        }
    },
    {
        name: 'mouseleave clears all lines',
        fn: async (page) => {
            const pinA = page.locator('[data-testid="territory-icon"][data-territory-name="A"]')
            await pinA.hover()
            const r1 = await waitForLineCount(page, 3, 'pre-clear')
            if (!r1.pass) return r1
            await page.mouse.move(2, 2)
            const r2 = await waitForLineCount(page, 0, 'post-clear')
            if (r2.pass) await page.screenshot({ path: 'artifacts/hover-cleared.png', fullPage: true })
            return r2
        }
    },
    {
        name: 'hovering a different pin swaps the lines (no accumulation)',
        fn: async (page) => {
            for (const name of ['A', 'B', 'C', 'D']) {
                const pin = page.locator(`[data-testid="territory-icon"][data-territory-name="${name}"]`)
                await pin.hover()
                const expected = name === 'A' ? 3 : 1
                const r = await waitForLineCount(page, expected, `${name} → ${expected} lines`)
                if (!r.pass) return r
                await page.mouse.move(2, 2)
                const r2 = await waitForLineCount(page, 0, `mouseleave after ${name}`)
                if (!r2.pass) return r2
            }
            return { pass: true, count: 0, label: 'all swap' }
        }
    },
    {
        name: 'hovering an isolated pin draws no lines',
        fn: async (page) => {
            const pinE = page.locator('[data-testid="territory-icon"][data-territory-name="E"]')
            await pinE.hover()
            await page.waitForTimeout(200)
            return waitForLineCount(page, 0, 'isolated pin E')
        }
    },
    {
        name: 'no console errors during hover lifecycle',
        fn: async (page) => {
            const pinA = page.locator('[data-testid="territory-icon"][data-territory-name="A"]')
            const pinB = page.locator('[data-testid="territory-icon"][data-territory-name="B"]')
            await pinA.hover()
            const r1 = await waitForLineCount(page, 3, 'A → 3')
            if (!r1.pass) return r1
            await page.mouse.move(2, 2)
            const r2 = await waitForLineCount(page, 0, 'mouseleave')
            if (!r2.pass) return r2
            await pinB.hover()
            const r3 = await waitForLineCount(page, 1, 'B → 1')
            if (!r3.pass) return r3
            await page.mouse.move(2, 2)
            const r4 = await waitForLineCount(page, 0, 'mouseleave')
            if (!r4.pass) return r4
            await page.waitForTimeout(500)
            const errs = page._capturedErrors || []
            if (errs.length === 0) return { pass: true, count: 0, label: 'no errors' }
            return { pass: false, count: errs.length, label: 'errors: ' + errs.slice(0, 3).join('; ') }
        }
    }
]

const filteredCases = GREP ? cases.filter(c => c.name.includes(GREP)) : cases

const browser = await chromium.launch({ headless: !HEADED })
const context = await browser.newContext()
const page = await setupPage(browser)

let allPass = true
for (const c of filteredCases) {
    log(`Setting up: ${c.name}`)
    // Each test gets a fresh page navigation so accumulated
    // errors are isolated.
    const errors = []
    page._capturedErrors = errors
    page.removeAllListeners('pageerror')
    page.removeAllListeners('console')
    page.on('pageerror', e => { if (!isPreExistingNetworkError(e.message)) errors.push(`pageerror: ${e.message}`) })
    page.on('console', m => {
        if (m.type() === 'error') {
            const t = m.text()
            if (!isPreExistingNetworkError(t)) errors.push(`console.error: ${t}`)
        }
    })

    log(`Navigating to ${BASE_URL}/index.html?skipLogin=true&testMode=true&_cb=${Date.now()}`)
    await page.goto(`${BASE_URL}/index.html?skipLogin=true&testMode=true&_cb=${Date.now()}`)

    const cta = page.getByTestId('loading-screen-cta')
    await cta.waitFor({ state: 'visible', timeout: 30_000 })
    await cta.click()

    await page.waitForFunction(() => {
        return typeof window.mapViewer !== 'undefined'
            && window.mapViewer !== null
            && typeof window.loadMapForTest === 'function'
    }, { timeout: 30_000 })

    await loadTinyMap(page)
    log(`Map loaded, running: ${c.name}`)
    const r = await c.fn(page)
    const status = r.pass ? 'PASS' : `FAIL (count=${r.count}, label=${r.label})`
    console.log(`  ${status}  ${c.name}`)
    if (!r.pass) allPass = false
}

await browser.close()
process.exit(allPass ? 0 : 1)
