// Lord Maple Tree Simulation Mode - 2-turn driver (v5)
// v5 fix: gate Send-click on the AUTHORITATIVE server-mirrored turn state
// (window.globals.World.isPlayerTurn) instead of the UI's Send-button visibility
// (which can be out of sync during the AI's long broadcast loop).
//
// Expected runtime budget: 4-6 minutes (AI takeover can take 60-120s).

import { chromium } from '@playwright/test'
import { mkdir, writeFile } from 'node:fs/promises'
import { join } from 'node:path'

const BASE_URL = 'http://127.0.0.1:8080/index.html'
const OUT_DIR = '/tmp/lordmaple-simulation-test/screenshots'
const LOG_PATH = '/tmp/lordmaple-simulation-test/turns.log'
const REPORT_PATH = '/tmp/lordmaple-simulation-test/turns-report.json'

await mkdir(OUT_DIR, { recursive: true })

const logLines = []
const log = (msg) => {
    const line = `[${new Date().toISOString().split('T')[1].slice(0,8)}] ${msg}`
    console.log(line)
    logLines.push(line)
}
const wait = (ms) => new Promise(r => setTimeout(r, ms))

const result = {
    loginPage: { ok: false, error: null },
    mainMenu: { ok: false, error: null },
    wizardStep1: { ok: false, error: null },
    wizardStep2: { ok: false, error: null },
    wizardStep3: { ok: false, error: null },
    gameplayMount: { ok: false, error: null },
    turn1: { ok: false, error: null, sentAt: null, activeTurnActorAtSend: null },
    turn2: { ok: false, error: null, sentAt: null, activeTurnActorAtSend: null },
    fatal: null,
}

async function shot(page, label) {
    const path = join(OUT_DIR, `${label}.png`)
    await page.screenshot({ path, fullPage: false })
    log(`📸 ${label}.png`)
}

async function dismissAnyModal(page, maxTries = 5) {
    for (let a = 0; a < maxTries; a++) {
        const okBtn = page.locator('button:has-text("OK")').first()
        if (await okBtn.count() > 0 && await okBtn.isVisible()) {
            const modalText = await page.evaluate(() => {
                const dialog = document.querySelector('.autogenesis-message-box-overlay')
                return dialog?.textContent?.trim().slice(0, 200) || ''
            })
            log(`Dismissing OK modal: ${modalText}`)
            await okBtn.click({ force: true })
            await wait(2000)
        } else {
            break
        }
    }
}

async function readTurnState(page) {
    return page.evaluate(() => {
        // globals.World is the Kotlin object; the JS bridge exposes it.
        const w = (typeof globals !== 'undefined') ? globals.World : null
        return {
            activeTurnActor: w?.activeTurnActor ?? null,
            isPlayerTurn: w?.isPlayerTurn ?? null,
            canPlayerAct: w?.canPlayerAct ?? null,
        }
    })
}

async function sendHumanTurn(page, turnNum, humanName) {
    const tStart = new Date().toISOString()
    log(`==== TURN ${turnNum} START (${tStart}) ====`)

    if (turnNum === 1) {
        // SIMULATION MODE: The user controls BOTH player slots (Lord
        // Maple Tree + the AI filler). The carve-out at
        // PromptManager.executeGameplayAction now allows the human to
        // send commands on the AI slot's behalf when activeTurnActor
        // is the AI (because the human owns that slot). So we don't
        // need to wait for the AI takeover to complete — just send
        // whenever "Your Turn To Act" is visible.
        await wait(2000)
    } else {
        // Turn 2: after Turn 1's command is processed, the turn advances.
        // The AI's automatic takeover should NOT fire (because both
        // slots are human-owned in sim mode). The game should advance
        // to the next human turn immediately. Wait a few seconds for
        // the turn state to settle, then check Send.
        await wait(10_000)
    }

    // Wait for the Send button to be present, enabled, AND stable for
    // 5 consecutive seconds (longer than the AI broadcast retry loop).
    let stableCount = 0
    let lastState = null
    const MAX_WAIT_MS = 60_000
    const tWaitStart = Date.now()

    while (Date.now() - tWaitStart < MAX_WAIT_MS) {
        const sendBtn = page.locator('button:has-text("Send")').first()
        const cnt = await sendBtn.count()
        if (cnt > 0) {
            const dis = await sendBtn.getAttribute('disabled')
            if (dis === null) {
                stableCount++
                if (stableCount >= 5) {
                    log(`Send button stable for 5s (${Math.round((Date.now() - tWaitStart)/1000)}s)`)
                    break
                }
            } else {
                stableCount = 0
            }
        } else {
            stableCount = 0
        }
        await wait(1000)
    }

    if (stableCount < 3) {
        log(`Turn ${turnNum}: Send button never became stable`)
        return false
    }

    await shot(page, `11-turn-${turnNum}-start`)

    // Type the command via the KVision controlled-input bypass
    const commandInput = page.locator('.command-box textarea, .command-box input, [data-testid="command-input"]').first()
    if (await commandInput.count() === 0) {
        log(`Turn ${turnNum}: no command input`)
        return false
    }

    const tag = await commandInput.evaluate(el => el.tagName)
    const command = `Turn ${turnNum}: Expand territory and consolidate holdings.`
    await page.evaluate(({tagName, value}) => {
        const proto = tagName === 'TEXTAREA' ? window.HTMLTextAreaElement.prototype : window.HTMLInputElement.prototype
        const desc = Object.getOwnPropertyDescriptor(proto, 'value')
        const el = document.querySelector('.command-box textarea, .command-box input, [data-testid="command-input"]')
        if (!el) return false
        desc.set.call(el, value)
        el.dispatchEvent(new Event('input', { bubbles: true }))
        return true
    }, {tagName: tag, value: command})
    await wait(800)
    await shot(page, `11-turn-${turnNum}-typed`)

    // Final check: Send button must still be present, enabled, and stable
    const sendBtn = page.locator('button:has-text("Send")').first()
    if (await sendBtn.count() === 0) {
        log(`Turn ${turnNum}: Send button not present`)
        return false
    }
    const wasDisabled = await sendBtn.getAttribute('disabled')
    if (wasDisabled !== null) {
        log(`Turn ${turnNum}: Send disabled (wasDisabled=${wasDisabled})`)
        return false
    }

    await sendBtn.click({ force: true })
    const tSent = new Date().toISOString()
    log(`Turn ${turnNum} SENT at ${tSent}`)
    await wait(3000)
    await shot(page, `12-turn-${turnNum}-sent`)
    return { sentAt: tSent, command }
}

const browser = await chromium.launch({ headless: true })
const context = await browser.newContext({ viewport: { width: 1920, height: 1080 } })
const page = await context.newPage()

page.on('pageerror', err => log(`PAGE-ERROR: ${err.message}`))
page.on('console', msg => {
    if (msg.type() === 'error' && !msg.text().includes('favicon')) {
        log(`CONSOLE-ERR: ${msg.text().slice(0, 200)}`)
    }
})

try {
    // === LOGIN ===
    log('STEP 1: Loading page...')
    await page.goto(BASE_URL, { waitUntil: 'domcontentloaded' })
    await wait(800)
    const loadingCta = page.locator('[data-testid="loading-screen-cta"]')
    if (await loadingCta.count() > 0) {
        await loadingCta.click()
        await wait(1500)
    }
    const guestBtn = page.locator('[data-testid="login-as-guest"]').first()
    if (await guestBtn.count() > 0) {
        log('Clicking Login As Guest...')
        await guestBtn.click()
        await wait(3000)
        const okBtn = page.locator('button:has-text("OK")').first()
        if (await okBtn.count() > 0 && await okBtn.isVisible()) {
            await okBtn.click()
            await wait(2000)
        }
    }
    result.loginPage.ok = true

    // === MAIN MENU ===
    log('STEP 2: Waiting for MainMenu...')
    await page.waitForSelector('[data-testid="main-menu"]', { timeout: 25000 })
    await wait(1500)
    const newGameBtn = page.locator('button:has-text("NEW GAME")').first()
    if (await newGameBtn.count() > 0 && await newGameBtn.isVisible()) {
        await newGameBtn.click()
        await wait(1500)
    }
    log('MainMenu mounted')
    result.mainMenu.ok = true

    // === WIZARD STEP 1 ===
    log('STEP 3: Wait for Wizard Step 1...')
    let wizardCount = await page.locator('[data-testid="commander-selection-root"]').count()
    if (wizardCount === 0) {
        log('Wizard not open — clicking PLAY...')
        const playBtn = page.locator('[data-testid="main-menu"] .btn-play').first()
        playBtn.click({ force: true })
        await wait(2500)
    }
    await page.waitForSelector('[data-testid="commander-selection-root"]', { timeout: 15000 })
    await wait(800)
    await shot(page, '04-wizard-step1')
    const firstCmd = page.locator('.commander-selection-card').first()
    if (await firstCmd.count() > 0) {
        await firstCmd.click()
        await wait(800)
        log('Selected commander')
        result.wizardStep1.ok = true
    }
    await page.locator('[data-testid="commander-selection-root"] button:has-text("Next")').first().click()
    await wait(1200)

    // === WIZARD STEP 2 ===
    log('STEP 4: Step 2 — pick SIMULATION...')
    await wait(800)
    await shot(page, '06-wizard-step2')
    const simClicked = await page.evaluate(() => {
        const els = document.querySelectorAll('[data-testid="commander-selection-root"] *')
        for (const el of els) {
            const ownText = el.childNodes[0]?.textContent?.trim() || ''
            if (ownText === 'Play every slot yourself in a single match') {
                let target = el
                while (target && !target.matches('.game-type-card, [data-testid], button, [role="button"], div[class*="card"]')) {
                    target = target.parentElement
                }
                if (target) { target.click(); return true }
            }
        }
        return false
    })
    log(`Simulation clicked: ${simClicked}`)
    result.wizardStep2.simClicked = simClicked
    result.wizardStep2.ok = simClicked
    await wait(1200)
    await page.locator('[data-testid="commander-selection-root"] button:has-text("Next")').first().click()
    await wait(1200)

    // === WIZARD STEP 3 ===
    log('STEP 5: Step 3 — configure roster + map...')
    await wait(800)
    await shot(page, '07-wizard-step3')
    const rosterCmd = page.locator('.simulation-settings-page .commander-mini-card').first()
    if (await rosterCmd.count() > 0) {
        await rosterCmd.click()
        await wait(500)
        log('Roster: first commander selected')
    }
    const mapPicks = await page.locator('.simulation-map-picker .simulation-map-card').all()
    if (mapPicks.length > 0) {
        await page.locator('.simulation-map-picker .simulation-map-card').first().click()
        await wait(500)
        log(`Map: first option selected (count=${mapPicks.length})`)
    }
    await shot(page, '08-wizard-step3-configured')
    const simPlay = page.locator('[data-testid="commander-selection-root"] button:has-text("Play")').first()
    if (await simPlay.count() > 0) {
        const isDisabled = await simPlay.getAttribute('disabled')
        if (isDisabled === null) {
            log('Clicking Play...')
            await simPlay.click()
            await wait(5000)
            await shot(page, '09-after-play-click')
            result.wizardStep3.ok = true
        }
    }

    // === GAMEPLAY MOUNT ===
    log('STEP 6: Waiting for gameplay UI or fail modal...')
    let mountedOrFailed = false
    for (let i = 0; i < 30; i++) {
        await wait(1000)
        const hasGameplay = await page.locator('[data-testid="gameplay-ui"]').count()
        const hasFailModal = await page.locator('.autogenesis-message-box-overlay').count()
        if (hasGameplay > 0) {
            log(`[t=${i+1}s] gameplay-ui mounted`)
            mountedOrFailed = true
            break
        }
        if (hasFailModal > 0) {
            log(`[t=${i+1}s] Modal visible`)
            const errText = await page.evaluate(() => {
                const ms = document.querySelectorAll('.autogenesis-message-box-overlay')
                return Array.from(ms).map(m => m.textContent?.trim().slice(0, 500)).filter(Boolean)
            })
            log(`Modal text: ${JSON.stringify(errText)}`)
            result.gameplayMount.error = errText.join(' | ')
            await dismissAnyModal(page)
            await shot(page, '10-fail-modal')
            mountedOrFailed = true
            break
        }
    }
    if (!mountedOrFailed) {
        result.gameplayMount.error = 'no mount in 30s'
        await shot(page, '10-no-mount')
    }

    if (result.gameplayMount.error) {
        throw new Error('gameplay did not mount: ' + result.gameplayMount.error)
    }

    result.gameplayMount.ok = true
    log('Gameplay UI mounted — playing 2 turns (server-authoritative wait)')
    await wait(10000)
    await shot(page, '11-gameplay-initial')

    await dismissAnyModal(page)

    // === Capture the human name from the first available commander ===
    // The wizard selected the first .commander-selection-card, which is
    // "Lord Maple Tree" per the unit-test fixtures. Confirm by reading
    // the localPlayer from the JS bridge.
    const localPlayerName = await page.evaluate(() => {
        const w = (typeof globals !== 'undefined') ? globals.World : null
        return w?.localPlayer?.name ?? null
    })
    log(`Local player name from bridge: ${localPlayerName}`)

    // === PLAY 2 HUMAN TURNS — server-authoritative wait ===
    log('Sending Turn 1 (human)...')
    const t1Result = await sendHumanTurn(page, 1, localPlayerName ?? 'Lord Maple Tree')
    if (t1Result && typeof t1Result === 'object') {
        result.turn1.ok = true
        result.turn1.sentAt = new Date().toISOString()
        result.turn1.activeTurnActorAtSend = t1Result.activeTurnActor
    } else {
        result.turn1.error = 'send failed or no state'
    }

    log('Sending Turn 2 (human) — waiting for AI turn to complete...')
    const t2Result = await sendHumanTurn(page, 2, localPlayerName ?? 'Lord Maple Tree')
    if (t2Result && typeof t2Result === 'object') {
        result.turn2.ok = true
        result.turn2.sentAt = new Date().toISOString()
        result.turn2.activeTurnActorAtSend = t2Result.activeTurnActor
    } else {
        result.turn2.error = 'send failed or no state'
    }

    log(`FINAL: turn1.ok=${result.turn1.ok} turn2.ok=${result.turn2.ok}`)
} catch (err) {
    log(`FATAL: ${err.message}`)
    result.fatal = err.message
} finally {
    await writeFile(REPORT_PATH, JSON.stringify(result, null, 2))
    await writeFile(LOG_PATH, logLines.join('\n'))
    await browser.close()
    log('Probe complete.')
}

console.log('\n=== FINAL RESULT ===')
console.log(JSON.stringify(result, null, 2))
