// Lord Maple Tree — Singleplayer Map Icon Observation
// Used as a fallback to observe map icon rotation/redraw behavior since
// simulation mode cannot reach the gameplay UI (server-extend fails to
// decode playerAlias → simulationHumanPlayerNames).

import { chromium } from '@playwright/test'
import { mkdir, writeFile } from 'node:fs/promises'
import { join } from 'node:path'

const BASE_URL = 'http://127.0.0.1:8080/index.html'
const OUT_DIR = '/tmp/lordmaple-simulation-test/screenshots-singleplayer'
const LOG_PATH = '/tmp/lordmaple-simulation-test/singleplayer.log'
const REPORT_PATH = '/tmp/lordmaple-simulation-test/singleplayer-report.json'

await mkdir(OUT_DIR, { recursive: true })

const logLines = []
const log = (msg) => {
    const line = `[${new Date().toISOString().split('T')[1].slice(0,8)}] ${msg}`
    console.log(line)
    logLines.push(line)
}
const wait = (ms) => new Promise(r => setTimeout(r, ms))

const result = {
    mode: 'SINGLEPLAYER',
    purpose: 'Observe map icon rotation/redraw behavior (simulation mode fails at server-extend)',
    mapIcons: { samples: [] },
    currentPlayerLabel: null,
    turnTimerVisible: null,
}

const browser = await chromium.launch({ headless: true })
const context = await browser.newContext({ viewport: { width: 1920, height: 1080 } })
const page = await context.newPage()

page.on('pageerror', err => log(`PAGE-ERROR: ${err.message}`))

async function shot(label) {
    const path = join(OUT_DIR, `${label}.png`)
    await page.screenshot({ path, fullPage: false })
    log(`📸 ${label}.png`)
}

try {
    log('Navigating...')
    await page.goto(BASE_URL, { waitUntil: 'domcontentloaded' })
    await wait(800)

    const loadingCta = page.locator('[data-testid="loading-screen-cta"]')
    if (await loadingCta.count() > 0) {
        await loadingCta.click()
        await wait(1500)
    }

    const guestBtn = page.locator('[data-testid="login-as-guest"]')
    if (await guestBtn.count() > 0) {
        await guestBtn.click()
        await wait(3000)
        const okBtn = page.locator('button:has-text("OK")').first()
        if (await okBtn.count() > 0) { await okBtn.click(); await wait(2000) }
    }

    await page.waitForSelector('[data-testid="main-menu"]', { timeout: 30000 })
    await wait(1500)

    const newGameBtn = page.locator('button:has-text("NEW GAME")').first()
    if (await newGameBtn.count() > 0 && await newGameBtn.isVisible()) {
        await newGameBtn.click()
        await wait(1500)
    }

    await page.click('.btn-play')
    await wait(1500)

    const firstCmd = page.locator('.commander-selection-card').first()
    if (await firstCmd.count() > 0) await firstCmd.click()
    await wait(800)

    const nextBtn = page.locator('button:has-text("Next")').first()
    if (await nextBtn.count() > 0 && await nextBtn.isVisible()) await nextBtn.click()
    await wait(1500)

    await shot('01-step2-default')

    const dialogPlay = page.locator('[data-testid="commander-selection-root"] button:has-text("Play")').first()
    if (await dialogPlay.count() > 0 && await dialogPlay.isVisible()) {
        await dialogPlay.click()
        await wait(3000)
    }

    await page.waitForSelector('[data-testid="gameplay-ui"]', { timeout: 30000 })
    await wait(5000)

    // Dismiss "Match Ready" modal (and any other OK modals)
    let modalIterations = 0
    while (modalIterations < 5) {
        const okBtn = page.locator('button:has-text("OK")').first()
        if (await okBtn.count() > 0 && await okBtn.isVisible()) {
            await okBtn.click()
            await wait(1000)
            modalIterations++
        } else {
            break
        }
    }
    await wait(2000)
    await shot('02-gameplay-initial')
    log('Gameplay UI mounted (singleplayer mode) - Match Ready dismissed')

    // Click GO TO MAP to reveal the map
    const goToMapBtn = page.locator('button:has-text("GO TO MAP"), button:has-text("Go To Map"), button:has-text("Map")').first()
    if (await goToMapBtn.count() > 0 && await goToMapBtn.isVisible()) {
        await goToMapBtn.click()
        await wait(3000)
        await shot('02b-map-opened')
        log('GO TO MAP clicked')
    // Try IGNORE button on hostile event modal
    const ignoreBtn = page.locator('button:has-text("IGNORE")').first()
    if (await ignoreBtn.count() > 0 && await ignoreBtn.isVisible()) {
        await ignoreBtn.click()
        await wait(2000)
        await shot('02c-hostile-ignored')
        log('IGNORE button clicked')
    }
    }

    // Check if [data-testid="gameplay-ui"] is actually visible
    const uiCheck = await page.evaluate(() => {
        const root = document.querySelector('[data-testid="gameplay-ui"]')
        if (!root) return { found: false }
        const r = root.getBoundingClientRect()
        return {
            found: true,
            visible: r.width > 0 && r.height > 0,
            width: r.width,
            height: r.height,
            className: root.className,
        }
    })
    log(`Gameplay UI state: ${JSON.stringify(uiCheck)}`)
    result.gameplayUI = uiCheck

    // Observe map icons over 60s
    for (let i = 0; i < 6; i++) {
        await wait(10000)
        const snapshot = await page.evaluate(() => {
            const map = document.querySelector('[data-testid="gameplay-ui"] .map-viewer-container, [data-testid="gameplay-ui"] .map-canvas, [data-testid="gameplay-ui"] svg.map')
            if (!map) {
                const fallback = document.querySelector('[data-testid="gameplay-ui"]')
                if (!fallback) return null
                const allIcons = Array.from(fallback.querySelectorAll('[data-icon-id], .map-icon, .commander-marker, .pin, .player-pin, .territory-marker, .pin-icon, [data-territory-id]'))
                return {
                    foundMapContainer: false,
                    foundFallback: true,
                    iconCount: allIcons.length,
                    sampleIcons: allIcons.slice(0, 5).map(el => ({
                        transform: el.style.transform || getComputedStyle(el).transform,
                        rotation: el.style.rotate || el.getAttribute('rotate'),
                        className: el.className,
                        dataIconId: el.getAttribute('data-icon-id'),
                        dataAttr: el.getAttribute('data-territory-id'),
                    })),
                }
            }
            const icons = Array.from(map.querySelectorAll('[data-icon-id], .map-icon, .commander-marker, .pin, .player-pin, .territory-marker'))
            return {
                foundMapContainer: true,
                iconCount: icons.length,
                sampleIcons: icons.slice(0, 5).map(el => ({
                    transform: el.style.transform || getComputedStyle(el).transform,
                    rotation: el.style.rotate || el.getAttribute('rotate'),
                    className: el.className,
                    dataIconId: el.getAttribute('data-icon-id'),
                })),
            }
        })
        result.mapIcons.samples.push({ t: (i + 1) * 10, snapshot })
        log(`[t=${(i+1)*10}s] ${JSON.stringify(snapshot).slice(0, 400)}`)
        await shot(`03-map-t${(i+1)*10}s`)
    }

    const playerInfo = await page.evaluate(() => {
        const root = document.querySelector('[data-testid="gameplay-ui"]')
        const body = root ? root.textContent : document.body.textContent
        return {
            hasTurnTimer: !!document.querySelector('[data-testid="gameplay-ui"] .turn-timer, [data-testid="gameplay-ui"] .turn-clock, [data-testid="gameplay-ui"] [class*="timer"]'),
            currentPlayerText: ((body?.match(/Your Turn[^.\n]+/g) || [])[0]) || 'unknown',
            bodySnippet: body?.slice(0, 400),
        }
    })
    result.currentPlayerLabel = playerInfo.currentPlayerText
    result.turnTimerVisible = playerInfo.hasTurnTimer
    log(`Current player: ${playerInfo.currentPlayerText}`)
    log(`Turn timer visible: ${playerInfo.hasTurnTimer}`)

    await shot('99-final')

} catch (err) {
    log(`FATAL: ${err.message}`)
    result.fatal = err.message
} finally {
    await writeFile(REPORT_PATH, JSON.stringify(result, null, 2))
    await writeFile(LOG_PATH, logLines.join('\n'))
    await browser.close()
    log('Done.')
}

console.log('\n=== FINAL RESULT ===')
console.log(JSON.stringify(result, null, 2))