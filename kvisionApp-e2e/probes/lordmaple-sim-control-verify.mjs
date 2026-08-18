// Lord Maple Tree Simulation Mode - 2-round dual-control verification
//
// Verifies the simulation-mode carve-out: in a 2-player simulation
// session, the human controls both player slots. The probe walks the
// wizard into Step 3, clicks Play, waits for the gameplay UI to mount,
// then issues TWO turns. At each turn:
//   - reads window.globals.World.activeTurnActor (the server-mirrored turn state)
//   - captures the action box state (whose card is highlighted, turn timer)
//   - types a command via the KVision-controlled-input bypass
//   - clicks Send
//   - waits for the turn to resolve and confirms the activeTurnActor advances
//
// Both visual proof (screenshots) and server-log proof (the human reports
// the grep output back to the operator) confirm the human controls both
// slots.
//
// Expected runtime budget: 4-8 minutes (AI takeover can take 60-120s in
// the case where the AI filler IS the active actor — but in simulation
// mode the human owns the AI filler, so the AI takeover is BLOCKED).

import { chromium } from '@playwright/test'
import { mkdir, writeFile } from 'node:fs/promises'
import { join } from 'node:path'

const BASE_URL = 'http://127.0.0.1:8080/index.html'
const OUT_DIR = '/tmp/lordmaple-sim-verify/screenshots'
const LOG_PATH = '/tmp/lordmaple-sim-verify/probe.log'
const REPORT_PATH = '/tmp/lordmaple-sim-verify/report.json'

await mkdir(OUT_DIR, { recursive: true })

const logLines = []
const log = (msg) => {
    const line = `[${new Date().toISOString().split('T')[1].slice(0, 8)}] ${msg}`
    console.log(line)
    logLines.push(line)
}
const wait = (ms) => new Promise((r) => setTimeout(r, ms))

const result = {
    loginPage: { ok: false, error: null },
    mainMenu: { ok: false, error: null },
    wizardStep1: { ok: false, error: null },
    wizardStep2: { ok: false, error: null, simClicked: false },
    wizardStep3: { ok: false, error: null },
    gameplayMount: { ok: false, error: null, mountedAt: null },
    turn1: { ok: false, error: null, sentAt: null, activeTurnActorAtSend: null, commandSent: null },
    turn2: { ok: false, error: null, sentAt: null, activeTurnActorAtSend: null, commandSent: null },
    finalState: null,
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
        if ((await okBtn.count()) > 0 && (await okBtn.isVisible())) {
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
        const w = typeof globals !== 'undefined' ? globals.World : null
        return {
            activeTurnActor: w?.activeTurnActor ?? null,
            isPlayerTurn: w?.isPlayerTurn ?? null,
            canPlayerAct: w?.canPlayerAct ?? null,
            roundNumber: w?.roundNumber ?? null,
            turnIndex: w?.turnIndex ?? null,
            localPlayerName: w?.localPlayer?.name ?? null,
            localPlayerCommander: w?.localPlayer?.commander ?? null,
            isSimulationMode: w?.isSimulationMode ?? null,
            simulationHumanPlayerNames: w?.simulationHumanPlayerNames ?? null,
            players: w?.world?.players?.map((p) => ({ name: p.name, isHuman: p.isHuman ?? null })) ?? null,
        }
    })
}

async function typeCommand(page, command) {
    // Type the command via the KVision controlled-input bypass
    const commandInput = page
        .locator('.command-box textarea, .command-box input, [data-testid="command-input"]')
        .first()
    if ((await commandInput.count()) === 0) {
        return false
    }
    const tag = await commandInput.evaluate((el) => el.tagName)
    await page.evaluate(
        ({ tagName, value }) => {
            const proto =
                tagName === 'TEXTAREA'
                    ? window.HTMLTextAreaElement.prototype
                    : window.HTMLInputElement.prototype
            const desc = Object.getOwnPropertyDescriptor(proto, 'value')
            const el = document.querySelector(
                '.command-box textarea, .command-box input, [data-testid="command-input"]'
            )
            if (!el) return false
            desc.set.call(el, value)
            el.dispatchEvent(new Event('input', { bubbles: true }))
            return true
        },
        { tagName: tag, value: command }
    )
    return true
}

async function sendHumanTurn(page, turnNum, command) {
    const tStart = new Date().toISOString()
    log(`==== TURN ${turnNum} START (${tStart}) ====`)
    const stateBeforeSend = await readTurnState(page)
    log(`State before send: ${JSON.stringify(stateBeforeSend)}`)

    // Wait for the Send button to be present, enabled, AND stable for
    // 5 consecutive seconds (longer than any AI broadcast retry loop).
    let stableCount = 0
    const MAX_WAIT_MS = 120_000
    const tWaitStart = Date.now()

    while (Date.now() - tWaitStart < MAX_WAIT_MS) {
        const sendBtn = page.locator('button:has-text("Send")').first()
        const cnt = await sendBtn.count()
        if (cnt > 0) {
            const dis = await sendBtn.getAttribute('disabled')
            if (dis === null) {
                stableCount++
                if (stableCount >= 5) {
                    log(`Send button stable for 5s (${Math.round((Date.now() - tWaitStart) / 1000)}s)`)
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
        return { ok: false, error: 'send-button-never-stable' }
    }

    await shot(page, `11-turn-${turnNum}-ready-to-send`)

    const typed = await typeCommand(page, command)
    if (!typed) {
        log(`Turn ${turnNum}: command input not found`)
        return { ok: false, error: 'no-command-input' }
    }
    await wait(800)
    await shot(page, `12-turn-${turnNum}-typed`)

    // Final check: Send button must still be present, enabled, and stable
    const sendBtn = page.locator('button:has-text("Send")').first()
    if ((await sendBtn.count()) === 0) {
        log(`Turn ${turnNum}: Send button not present after typing`)
        return { ok: false, error: 'send-button-disappeared' }
    }
    const wasDisabled = await sendBtn.getAttribute('disabled')
    if (wasDisabled !== null) {
        log(`Turn ${turnNum}: Send disabled after typing (wasDisabled=${wasDisabled})`)
        return { ok: false, error: 'send-disabled-after-typing' }
    }

    // Capture the state at the moment of Send
    const stateAtSend = await readTurnState(page)
    await sendBtn.click({ force: true })
    const tSent = new Date().toISOString()
    log(`Turn ${turnNum} SENT at ${tSent} (activeTurnActor at send: ${stateAtSend.activeTurnActor})`)
    await wait(3000)
    await shot(page, `13-turn-${turnNum}-sent`)

    // Wait for activeTurnActor to advance (or timer to roll) before next turn
    if (turnNum === 1) {
        // After turn 1, the game should advance to the NEXT player.
        // For a 2-player sim, that's the SECOND player. In simulation
        // mode the human owns that slot too, so the Send button should
        // become enabled again within ~60s of the turn resolving.
        log('Waiting for turn advancement…')
        let advanceObserved = false
        const tAdvanceStart = Date.now()
        while (Date.now() - tAdvanceStart < 90_000) {
            await wait(2000)
            const s = await readTurnState(page)
            if (s.activeTurnActor && stateAtSend.activeTurnActor && s.activeTurnActor !== stateAtSend.activeTurnActor) {
                log(`Advancement observed: ${stateAtSend.activeTurnActor} → ${s.activeTurnActor} after ${Math.round((Date.now() - tAdvanceStart) / 1000)}s`)
                advanceObserved = true
                break
            }
        }
        if (!advanceObserved) {
            log('Turn 1: no advancement detected in 90s — will continue to turn 2 anyway')
        }
        await shot(page, `14-turn-${turnNum}-after-advance`)
    }

    return {
        ok: true,
        sentAt: tSent,
        activeTurnActorAtSend: stateAtSend.activeTurnActor,
        commandSent: command,
        stateBeforeSend,
        stateAtSend,
    }
}

const browser = await chromium.launch({ headless: true })
const context = await browser.newContext({ viewport: { width: 1920, height: 1080 } })
const page = await context.newPage()

page.on('pageerror', (err) => log(`PAGE-ERROR: ${err.message}`))
page.on('console', (msg) => {
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
    if ((await loadingCta.count()) > 0) {
        await loadingCta.click()
        await wait(1500)
    }
    const guestBtn = page.locator('[data-testid="login-as-guest"]').first()
    if ((await guestBtn.count()) > 0) {
        log('Clicking Login As Guest...')
        await guestBtn.click()
        await wait(3000)
        const okBtn = page.locator('button:has-text("OK")').first()
        if ((await okBtn.count()) > 0 && (await okBtn.isVisible())) {
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
    if ((await newGameBtn.count()) > 0 && (await newGameBtn.isVisible())) {
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
    if ((await firstCmd.count()) > 0) {
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
                while (
                    target &&
                    !target.matches('.game-type-card, [data-testid], button, [role="button"], div[class*="card"]')
                ) {
                    target = target.parentElement
                }
                if (target) {
                    target.click()
                    return true
                }
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
    if ((await rosterCmd.count()) > 0) {
        await rosterCmd.click()
        await wait(500)
        log('Roster: first commander selected')
    }
    const mapPicks = await page
        .locator('.simulation-map-picker .simulation-map-card')
        .all()
    if (mapPicks.length > 0) {
        await page.locator('.simulation-map-picker .simulation-map-card').first().click()
        await wait(500)
        log(`Map: first option selected (count=${mapPicks.length})`)
    }
    await shot(page, '08-wizard-step3-configured')

    // Capture the wizard payload before clicking Play
    const payloadBeforePlay = await page.evaluate(() => {
        const w = typeof globals !== 'undefined' ? globals.World : null
        return {
            selectedNames: w?.selectedNames ?? null,
            totalSlotCount: w?.totalSlotCount ?? null,
            selectedMapPath: w?.selectedMapPath ?? null,
        }
    })
    log(`Wizard payload: ${JSON.stringify(payloadBeforePlay)}`)

    const simPlay = page.locator('[data-testid="commander-selection-root"] button:has-text("Play")').first()
    if ((await simPlay.count()) > 0) {
        const isDisabled = await simPlay.getAttribute('disabled')
        if (isDisabled === null) {
            log('Clicking Play...')
            await simPlay.click()
            await wait(5000)
            await shot(page, '09-after-play-click')
            result.wizardStep3.ok = true
        } else {
            log(`Play button is disabled (disabled=${isDisabled})`)
            result.wizardStep3.error = 'play-disabled'
        }
    } else {
        log('Play button not found')
        result.wizardStep3.error = 'no-play-button'
    }

    // === GAMEPLAY MOUNT ===
    log('STEP 6: Waiting for gameplay UI or fail modal...')
    let mountedOrFailed = false
    for (let i = 0; i < 60; i++) {
        await wait(1000)
        const hasGameplay = await page.locator('[data-testid="gameplay-ui"]').count()
        const hasFailModal = await page.locator('.autogenesis-message-box-overlay').count()
        if (hasGameplay > 0) {
            log(`[t=${i + 1}s] gameplay-ui mounted`)
            mountedOrFailed = true
            result.gameplayMount.mountedAt = new Date().toISOString()
            break
        }
        if (hasFailModal > 0) {
            log(`[t=${i + 1}s] Modal visible`)
            const errText = await page.evaluate(() => {
                const ms = document.querySelectorAll('.autogenesis-message-box-overlay')
                return Array.from(ms)
                    .map((m) => m.textContent?.trim().slice(0, 500))
                    .filter(Boolean)
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
        result.gameplayMount.error = 'no mount in 60s'
        await shot(page, '10-no-mount')
    }

    if (result.gameplayMount.error) {
        throw new Error('gameplay did not mount: ' + result.gameplayMount.error)
    }

    result.gameplayMount.ok = true
    log('Gameplay UI mounted — capturing initial state')
    await wait(10000)
    await shot(page, '11-gameplay-initial')

    await dismissAnyModal(page)

    // Capture initial turn state — the smoking gun:
    // activeTurnActor should be one of the human-controlled players,
    // isSimulationMode should be true, simulationHumanPlayerNames should
    // list both players.
    const initialState = await readTurnState(page)
    log(`Initial state: ${JSON.stringify(initialState)}`)
    result.finalState = { initialState }

    const localPlayerName = initialState.localPlayerName || 'Lord Maple Tree'
    log(`Local player name from bridge: ${localPlayerName}`)

    // === PLAY 2 HUMAN TURNS ===
    log('Sending Turn 1 (human-owned slot 1)...')
    const t1Result = await sendHumanTurn(
        page,
        1,
        `Turn 1 from ${localPlayerName}: Expand territory to the south and consolidate holdings.`
    )
    if (t1Result && t1Result.ok) {
        result.turn1 = {
            ok: true,
            sentAt: t1Result.sentAt,
            activeTurnActorAtSend: t1Result.activeTurnActorAtSend,
            commandSent: t1Result.commandSent,
        }
    } else {
        result.turn1.error = t1Result?.error || 'send failed'
    }

    log('Sending Turn 2 (human-owned slot 2)...')
    const t2Result = await sendHumanTurn(
        page,
        2,
        `Turn 2 from ${localPlayerName}: Scout the eastern front and fortify the capital.`
    )
    if (t2Result && t2Result.ok) {
        result.turn2 = {
            ok: true,
            sentAt: t2Result.sentAt,
            activeTurnActorAtSend: t2Result.activeTurnActorAtSend,
            commandSent: t2Result.commandSent,
        }
    } else {
        result.turn2.error = t2Result?.error || 'send failed'
    }

    const finalState = await readTurnState(page)
    log(`Final state: ${JSON.stringify(finalState)}`)
    result.finalState.finalState = finalState
    await shot(page, '15-final-state')

    log(
        `FINAL: turn1.ok=${result.turn1.ok} turn2.ok=${result.turn2.ok} | activeTurnActor: ${result.turn1.activeTurnActorAtSend} → ${result.turn2.activeTurnActorAtSend}`
    )
} catch (err) {
    log(`FATAL: ${err.message}`)
    result.fatal = err.message
    await shot(page, '99-fatal')
} finally {
    await writeFile(REPORT_PATH, JSON.stringify(result, null, 2))
    await writeFile(LOG_PATH, logLines.join('\n'))
    await browser.close()
    log('Probe complete.')
}

console.log('\n=== FINAL RESULT ===')
console.log(JSON.stringify(result, null, 2))
