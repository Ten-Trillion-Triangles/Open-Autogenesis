import { chromium } from 'playwright'
import { setTimeout as sleep } from 'node:timers/promises'
import { mkdirSync, writeFileSync } from 'node:fs'

const ART = '/tmp/ag-softlock-probe'
mkdirSync(ART, { recursive: true })

// Connect to the running Firefox instead of launching a new one.
// Firefox 3073473 is the user's running session.
// Use chromium (playwright) over CDP would not work directly with firefox.
// Better: just launch a new chromium with the same user-data-dir to share state.

const browser = await chromium.launchPersistentContext('/tmp/ag-profile-softlock', {
    headless: true,
    viewport: { width: 1920, height: 1080 },
    args: ['--no-sandbox']
})
const page = await browser.newPage()

const consoleMsgs = []
const pageErrors = []
page.on('console', (m) => consoleMsgs.push({ t: Date.now(), type: m.type(), text: m.text() }))
page.on('pageerror', (e) => pageErrors.push({ t: Date.now(), msg: e.message }))

// First — clear localStorage so we get a clean MapCardThumbnailRenderer state, then
// navigate with skipLogin and seed a local player + map pack via the testMode hook.
await page.goto('http://localhost:8080/?skipLogin=true', { waitUntil: 'load' })
await sleep(4000)

const cta = await page.getByTestId('loading-screen-cta').count()
if (cta > 0) {
    await page.getByTestId('loading-screen-cta').click()
    await sleep(2000)
}

await page.locator('[data-testid="main-menu"]').waitFor({ state: 'visible' })
console.log('MainMenu visible')

// Use the testMode hook to directly load a map pack — this is what real gameplay
// looks like when the user clicks PLAY MAP. We pass the bytes of the existing
// San_Martello.map (or any small map from kvisionApp/build/processedResources).
const mapFiles = []
import { readdirSync, statSync } from 'node:fs'
try {
    const dir = '/home/cage/Desktop/Workspaces/Autogenesis/Autogenesis/kvisionApp/build/processedResources/js/main'
    const candidates = []
    function walk(d) {
        for (const f of readdirSync(d)) {
            const p = d + '/' + f
            const s = statSync(p)
            if (s.isDirectory()) walk(p)
            else if (f.endsWith('.map') || f.endsWith('.png')) candidates.push({ p, size: s.size })
        }
    }
    walk(dir)
    mapFiles.push(...candidates.slice(0, 10))
    console.log('Found map candidates:', mapFiles.slice(0, 5).map(c => `${c.p} (${c.size}b)`).join(', '))
} catch (e) {
    console.log('No map files found: ' + e.message)
}

// Check if window.loadMapForTest exists (testMode)
const testModeReady = await page.evaluate(() => {
    return typeof window.loadMapForTest === 'function' && typeof window.mapViewer !== 'undefined'
})
console.log(`testMode hooks ready: ${testModeReady}`)

// Even if mapViewer isn't on window, we can still drive the UI.
// Try to navigate into a real gameplay screen. First, find a way to start gameplay.
const enterGameplay = await page.evaluate(() => {
    // Look for buttons that start a game
    const allBtns = Array.from(document.querySelectorAll('button'))
    for (const b of allBtns) {
        const txt = b.textContent.trim().toUpperCase()
        if (txt === 'PLAY' || txt === 'START' || txt.includes('NEW GAME') || txt.includes('PLAY MAP')) {
            // Found a play button
            return { found: true, text: txt, visible: b.offsetParent !== null }
        }
    }
    return { found: false }
})
console.log(`Enter gameplay button: ${JSON.stringify(enterGameplay)}`)

// If we don't have a PlayMap path, we can try a different approach: load the map directly into the mapViewer via a programmatic path.
// Check the dev console — does it expose anything?
const debugInfo = await page.evaluate(() => {
    return {
        hasGameplayUI: typeof window.gameplayUI !== 'undefined',
        hasMapViewer: typeof window.mapViewer !== 'undefined',
        bodyClass: document.body.className,
        gameplayClass: !!document.querySelector('.gameplay-ui'),
        mainMenuVisible: !!document.querySelector('[data-testid="main-menu"]'),
        collectionVisible: !!document.querySelector('.collection-overlay, .collection-content, [class*="collection"]'),
        // Find any UI elements that look like map-related widgets
        mapRelated: Array.from(document.querySelectorAll('[class*="map"], [data-testid*="map"]')).slice(0, 10).map(el => el.className || el.tagName)
    }
})
console.log('Debug info:', JSON.stringify(debugInfo, null, 2))

// Most likely: MainMenu has buttons we need to click in order.
// Try: click PLAY → choose "Resume" / "New Game" / collection
const clickedPlayResult = await page.evaluate(() => {
    const results = []
    for (const b of document.querySelectorAll('[data-testid="main-menu"] button')) {
        const txt = b.textContent.trim()
        results.push({ txt, visible: b.offsetParent !== null })
    }
    return results
})
console.log('MainMenu buttons:', JSON.stringify(clickedPlayResult, null, 2))

// Take a screenshot to see current state
await page.screenshot({ path: `${ART}/current-state.png`, fullPage: false })

// Save console + page errors
writeFileSync(`${ART}/console.log`, consoleMsgs.map(m => `[${new Date(m.t).toISOString()}] [${m.type}] ${m.text}`).join('\n'))
writeFileSync(`${ART}/page-errors.log`, pageErrors.map(e => `[${new Date(e.t).toISOString()}] ${e.msg}`).join('\n'))

console.log(`\n=== Total console messages: ${consoleMsgs.length} ===`)
console.log(`=== Page errors: ${pageErrors.length} ===`)

await browser.close()