import { test, expect } from '@playwright/test'
import { readFile } from 'node:fs/promises'
import { fileURLToPath } from 'node:url'
import { dirname, join } from 'node:path'

const FIXTURE_DIR = 'tests/fixtures'

const __dirname = dirname(fileURLToPath(import.meta.url))

/**
 * End-to-end tests for the hover-state border lines on the live
 * gameplay map. The user sees lines drawn between the territory
 * under their cursor and each of its direct neighbors.
 *
 * The Kotlin-side `HoverBorderLineRenderer` is covered by pure
 * unit tests in `kvisionApp/src/jsTest/.../ui/HoverBorderLineRendererTest.kt`.
 * This file is the user-facing integration test: it boots the
 * real production bundle, drives a real `MapViewer` through the
 * `window.mapViewer` test-mode hook, and asserts on the SVG that
 * actually appears under the cursor.
 */

/**
 * Network-failure patterns we ignore in the
 * "no console errors during hover lifecycle" test. The production
 * bundle is loaded without a running server-extend, so the
 * disabled RPC bridges log dozens of `ERR_CONNECTION_REFUSED`,
 * `ERR_FAILED`, and CORS lines. Those are not part of the hover
 * feature's contract.
 */
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

test.beforeEach(async ({ page }) => {
    // Capture only NON-pre-existing errors so the final
    // "no console errors during hover lifecycle" test can assert
    // on the accumulated set.
    const errors = []
    page._capturedErrors = errors
    page.on('pageerror', err => {
        if(!isPreExistingNetworkError(err.message))
        {
            errors.push(`pageerror: ${err.message}`)
        }
    })
    page.on('console', msg => {
        if(msg.type() === 'error')
        {
            const t = msg.text()
            if(!isPreExistingNetworkError(t))
            {
                errors.push(`console.error: ${t}`)
            }
        }
    })

    // Boot the production bundle in test mode. testMode=true
    // (a) skips the MainMenu and mounts a `MapViewer` directly on
    // the app stack, and (b) exposes `window.mapViewer` and the
    // stable `window.loadMapForTest(bytes)` helper for Playwright.
    // skipLogin bypasses the login screen.
    await page.goto('/index.html?skipLogin=true&testMode=true')

    // Click through the loading screen's CTA. The loading screen
    // gates Web Audio behind a user click and also drives the
    // asset-pipeline completion.
    const cta = page.getByTestId('loading-screen-cta')
    await cta.waitFor({ state: 'visible', timeout: 30_000 })
    await cta.click()

    // Wait for the test-mode hooks to be installed by
    // [org.ttt.autogenesis.kvisionapp.Main].
    await page.waitForFunction(() => {
        return typeof window.mapViewer !== 'undefined'
            && window.mapViewer !== null
            && typeof window.loadMapForTest === 'function'
    }, { timeout: 30_000 })
})

/**
 * Boots the fixture map into the live MapViewer and waits until
 * the first territory icon has rendered.
 */
async function loadTinyMap(page)
{
    const mapBytes = await readFile(join(__dirname, 'fixtures', 'tiny-map.map'))

    // The stable `loadMapForTest` helper survives webpack
    // minification (unlike `mapViewer.loadMapPack`, which the
    // production build renames). It internally converts the
    // JS array to a Kotlin ByteArray and runs `loadMapPack` on
    // the MainScope.
    await page.evaluate(async (bytes) => {
        await window.loadMapForTest(bytes)
    }, Array.from(mapBytes))

    // Wait for at least one territory icon to appear.
    await page.locator('[data-testid="territory-icon"]').first().waitFor({ state: 'visible', timeout: 10_000 })
}

/**
 * Returns the count of `<line>` elements inside the borders SVG.
 * Asserts the SVG itself is mounted as a defensive precondition.
 */
async function borderLineCount(page)
{
    const svg = page.locator('[data-testid="map-svg-borders"]')
    await expect(svg).toHaveCount(1)
    return svg.locator('line').count()
}

test('hovering a pin draws exactly its neighbors lines', async ({ page }) => {
    await loadTinyMap(page)

    const pinA = page.locator('[data-testid="territory-icon"][data-territory-name="A"]')
    await expect(pinA).toHaveCount(1)
    await pinA.hover()

    // A connects to B, C, D → 3 lines.
    await expect.poll(async () => await borderLineCount(page), { timeout: 5_000 }).toBe(3)

    await page.screenshot({ path: 'artifacts/hover-A.png', fullPage: true })
})

test('mouseleave clears all lines', async ({ page }) => {
    await loadTinyMap(page)

    const pinA = page.locator('[data-testid="territory-icon"][data-territory-name="A"]')
    await pinA.hover()
    await expect.poll(async () => await borderLineCount(page), { timeout: 5_000 }).toBe(3)

    // Move the cursor away from any pin. The map viewer covers
    // the viewport, so the off-corner is the safest "definitely
    // no pin" coordinate.
    await page.mouse.move(2, 2)
    await expect.poll(async () => await borderLineCount(page), { timeout: 5_000 }).toBe(0)

    await page.screenshot({ path: 'artifacts/hover-cleared.png', fullPage: true })
})

test('hovering a different pin swaps the lines (no accumulation)', async ({ page }) => {
    await loadTinyMap(page)

    const pinA = page.locator('[data-testid="territory-icon"][data-territory-name="A"]')
    const pinB = page.locator('[data-testid="territory-icon"][data-territory-name="B"]')
    const pinC = page.locator('[data-testid="territory-icon"][data-territory-name="C"]')
    const pinD = page.locator('[data-testid="territory-icon"][data-territory-name="D"]')

    // A → 3 lines
    await pinA.hover()
    await expect.poll(async () => await borderLineCount(page), { timeout: 5_000 }).toBe(3)

    // Move off, then B → 1 line (only A)
    await page.mouse.move(2, 2)
    await expect.poll(async () => await borderLineCount(page), { timeout: 5_000 }).toBe(0)
    await pinB.hover()
    await expect.poll(async () => await borderLineCount(page), { timeout: 5_000 }).toBe(1)

    // Move off, then C → 1 line (only A)
    await page.mouse.move(2, 2)
    await expect.poll(async () => await borderLineCount(page), { timeout: 5_000 }).toBe(0)
    await pinC.hover()
    await expect.poll(async () => await borderLineCount(page), { timeout: 5_000 }).toBe(1)

    // Move off, then D → 1 line (only A)
    await page.mouse.move(2, 2)
    await expect.poll(async () => await borderLineCount(page), { timeout: 5_000 }).toBe(0)
    await pinD.hover()
    await expect.poll(async () => await borderLineCount(page), { timeout: 5_000 }).toBe(1)
})

test('hovering an isolated pin draws no lines', async ({ page }) => {
    await loadTinyMap(page)

    const pinE = page.locator('[data-testid="territory-icon"][data-territory-name="E"]')
    await expect(pinE).toHaveCount(1)
    await pinE.hover()

    // Give the hover callback a moment to run.
    await page.waitForTimeout(200)
    expect(await borderLineCount(page)).toBe(0)
})

test('no console errors during hover lifecycle', async ({ page }) => {
    await loadTinyMap(page)

    const pinA = page.locator('[data-testid="territory-icon"][data-territory-name="A"]')
    const pinB = page.locator('[data-testid="territory-icon"][data-territory-name="B"]')

    await pinA.hover()
    await expect.poll(async () => await borderLineCount(page), { timeout: 5_000 }).toBe(3)

    await page.mouse.move(2, 2)
    await expect.poll(async () => await borderLineCount(page), { timeout: 5_000 }).toBe(0)

    await pinB.hover()
    await expect.poll(async () => await borderLineCount(page), { timeout: 5_000 }).toBe(1)

    await page.mouse.move(2, 2)
    await expect.poll(async () => await borderLineCount(page), { timeout: 5_000 }).toBe(0)

    // Allow any deferred console.error / pageerror to flush.
    await page.waitForTimeout(500)

    const errs = page._capturedErrors || []
    expect(errs, `unexpected page/console errors:\n${errs.join('\n')}`).toEqual([])
})