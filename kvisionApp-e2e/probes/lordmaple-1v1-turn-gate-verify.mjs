// Lord Maple Tree — 1v1 vs AI Turn-Gate Verification
//
// Verifies the turn-validation gate in PromptManager.kt:969-1003:
//   - HUMAN's turn → submit command → ACCEPTED (no "Not Your Turn" rejection)
//   - AI's turn → submit command → REJECTED with "Not Your Turn" message box
//
// In 1v1 vs AI mode (GameType.SINGLEPLAYER, AI opponent count = 1):
//   - The human owns ONE slot (their commander, e.g. "Lord Maple Tree")
//   - The AI owns ONE slot (auto-filled)
//   - Turn order alternates between them
//   - The human may only submit on their OWN turn (the gate at PromptManager.kt:969)
//   - During the AI's turn, the human's submitAction must be REJECTED
//   - The counter-play path (game.submitCounterAction) is a SEPARATE RPC that
//     bypasses the gate — that's the "targeted by AI" exception.

import { chromium } from '@playwright/test'
import { mkdir, writeFile } from 'node:fs/promises'
import { readFileSync } from 'node:fs'
import { join } from 'node:path'

const BASE_URL = 'http://127.0.0.1:8080/index.html'
const OUT_DIR = '/tmp/lordmaple-1v1-verify/screenshots'
const LOG_PATH = '/tmp/lordmaple-1v1-verify/probe.log'
const REPORT_PATH = '/tmp/lordmaple-1v1-verify/report.json'
const SRV_LOG = '/tmp/autogenesis-proxy/srv.log'

await mkdir(OUT_DIR, { recursive: true })

const logLines = []
const log = (msg) => {
    const line = `[${new Date().toISOString().split('T')[1].slice(0, 8)}] ${msg}`
    console.log(line)
    logLines.push(line)
}
const wait = (ms) => new Promise((r) => setTimeout(r, ms))

const result = {
    mode: 'SINGLEPLAYER_1v1_AI',
    purpose: 'Verify turn-validation gate: accept on own turn, reject on AI turn',
    loginPage: { ok: false, error: null },
    mainMenu: { ok: false, error: null },
    wizardStep1: { ok: false, error: null },
    wizardStep2: { ok: false, error: null, singlePlayerClicked: false, aiOpponentCount: null },
    gameplayMount: { ok: false, error: null, mountedAt: null },
    turnActor: { human: null, ai: null },
    paths: {
        pathA_onHumanTurn: { ok: false, error: null, sentAt: null, commandSent: null, rejectionReceived: null },
        pathB_onAiTurn: { ok: false, error: null, sentAt: null, commandSent: null, rejectionReceived: null, rejectionMessage: null },
    },
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

async function typeCommand(page, command) {
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

async function waitForTurnActor(targetActor, timeoutMs = 420_000) {
    // Read the server log file to detect turn changes. The JS bridge does
    // NOT expose World.activeTurnActor reliably so we cannot rely on
    // page.evaluate. The server log emits
    //   `TurnHarness.executeSingleTurn: Resolved actor='X' (round=N, turnOrderIndex=K)`
    // AND
    //   `TurnHarness.bootstrapTurnOrderIfNeeded: Set initial activeTurnActor to 'X'`
    // whenever a turn begins. We poll the log for the latest Resolved actor
    // and compare against targetActor.
    let lastActor = null
    let lastSize = 0
    const tStart = Date.now()
    while (Date.now() - tStart < timeoutMs) {
        try {
            const stat = await import('node:fs').then((m) => m.statSync(SRV_LOG))
            if (stat.size > lastSize) {
                const fd = await import('node:fs').then((m) => m.openSync(SRV_LOG, 'r'))
                const buf = Buffer.alloc(stat.size - lastSize)
                await import('node:fs').then((m) => m.readSync(fd, buf, 0, buf.length, lastSize))
                await import('node:fs').then((m) => m.closeSync(fd))
                const newContent = buf.toString('utf8')
                const resolvedMatches = [...newContent.matchAll(/Resolved actor='([^']+)'/g)]
                if (resolvedMatches.length > 0) {
                    const lastResolved = resolvedMatches[resolvedMatches.length - 1][1]
                    if (lastResolved !== lastActor) {
                        log(`waitForTurnActor: server log emitted Resolved actor='${lastResolved}'`)
                        lastActor = lastResolved
                    }
                }
                const bootstrapMatches = [...newContent.matchAll(/Set initial activeTurnActor to '([^']+)'/g)]
                if (bootstrapMatches.length > 0) {
                    const lastBootstrap = bootstrapMatches[bootstrapMatches.length - 1][1]
                    if (lastBootstrap !== lastActor) {
                        log(`waitForTurnActor: server log emitted bootstrap activeTurnActor='${lastBootstrap}'`)
                        lastActor = lastBootstrap
                    }
                }
                lastSize = stat.size
            }
        } catch (e) {
            log(`waitForTurnActor: log read error: ${e.message}`)
        }
        if (lastActor === targetActor) {
            log(`waitForTurnActor: ${targetActor} is now active (after ${Math.round((Date.now() - tStart) / 1000)}s)`)
            return { ok: true, lastActor }
        }
        await wait(1000)
    }
    log(`waitForTurnActor: TIMED OUT waiting for ${targetActor} after ${Math.round(timeoutMs / 1000)}s (lastActor=${lastActor})`)
    return { ok: false, error: 'timeout', lastActor }
}

async function submitOnHumanTurn(page, humanName, command) {
    const sendBtn = page.locator('button:has-text("Send")').first()
    let stableCount = 0
    const MAX_WAIT_MS = 120_000
    const tWaitStart = Date.now()
    while (Date.now() - tWaitStart < MAX_WAIT_MS) {
        const cnt = await sendBtn.count()
        if (cnt > 0) {
            const dis = await sendBtn.getAttribute('disabled')
            if (dis === null) {
                stableCount++
                if (stableCount >= 3) {
                    log(`Send button stable for 3s`)
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
        return { ok: false, error: 'send-button-never-stable' }
    }
    const typed = await typeCommand(page, command)
    if (!typed) {
        return { ok: false, error: 'no-command-input' }
    }
    await wait(800)
    await sendBtn.click({ force: true })
    const tSent = new Date().toISOString()
    log(`PATH A SENT at ${tSent}`)
    await wait(3000)
    return { ok: true, sentAt: tSent }
}

async function trySubmitDuringCurrentTurn(page, command) {
    const sendBtn = page.locator('button:has-text("Send")').first()
    if ((await sendBtn.count()) === 0) {
        return { ok: false, error: 'no-send-button', rejectionReceived: null, rejectionMessage: null }
    }
    const sendDisabled = await sendBtn.getAttribute('disabled')
    if (sendDisabled !== null) {
        log(`trySubmitDuringCurrentTurn: Send button is disabled (cannot attempt submit)`)
        return { ok: false, error: 'send-disabled', rejectionReceived: null, rejectionMessage: null }
    }
    const modalBefore = await page.evaluate(() => {
        const m = document.querySelector('.autogenesis-message-box-overlay')
        return m?.textContent?.trim().slice(0, 500) || null
    })
    const typed = await typeCommand(page, command)
    if (!typed) {
        return { ok: false, error: 'no-command-input', rejectionReceived: null, rejectionMessage: null }
    }
    await wait(500)
    await sendBtn.click({ force: true })
    const tSent = new Date().toISOString()
    await wait(2500)
    const modalAfter = await page.evaluate(() => {
        const m = document.querySelector('.autogenesis-message-box-overlay')
        if (!m) return null
        return m.textContent?.trim().slice(0, 500) || ''
    })
    const rejectionReceived = !!modalAfter && (
        modalAfter.toLowerCase().includes('not your turn') ||
        modalAfter.toLowerCase().includes('please wait')
    )
    return {
        ok: true,
        sentAt: tSent,
        modalBefore,
        modalAfter,
        rejectionReceived,
        rejectionMessage: rejectionReceived ? modalAfter : null,
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
    result.mainMenu.ok = true

    // === WIZARD STEP 1 ===
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

    // === WIZARD STEP 2 — SINGLE PLAYER (default) ===
    log('STEP 4: Step 2 — SINGLE PLAYER (default)...')
    await wait(800)
    await shot(page, '06-wizard-step2')

    const singlePlayerClicked = await page.evaluate(() => {
        const els = document.querySelectorAll('[data-testid="commander-selection-root"] *')
        for (const el of els) {
            const ownText = el.childNodes[0]?.textContent?.trim() || ''
            if (ownText === 'Single Player' || ownText === 'Play vs. AI locally') {
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
    log(`Single Player clicked: ${singlePlayerClicked}`)
    result.wizardStep2.singlePlayerClicked = singlePlayerClicked
    result.wizardStep2.aiOpponentCount = 1
    result.wizardStep2.ok = singlePlayerClicked

    const playBtn = page.locator('[data-testid="commander-selection-root"] button:has-text("Play")').first()
    if ((await playBtn.count()) > 0) {
        const isDisabled = await playBtn.getAttribute('disabled')
        if (isDisabled === null) {
            log('Clicking Play (Step 2 — Single Player)...')
            await playBtn.click()
            await wait(5000)
            await shot(page, '09-after-play-click')
        } else {
            log(`Play button is disabled (disabled=${isDisabled})`)
            result.wizardStep2.error = 'play-disabled'
        }
    } else {
        log('Play button not found')
        result.wizardStep2.error = 'no-play-button'
    }

    // === GAMEPLAY MOUNT ===
    log('STEP 5: Waiting for gameplay UI...')
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
    }
    if (result.gameplayMount.error) {
        throw new Error('gameplay did not mount: ' + result.gameplayMount.error)
    }
    result.gameplayMount.ok = true
    await wait(8000)
    await shot(page, '11-gameplay-initial')
    await dismissAnyModal(page)

    // === IDENTIFY PLAYERS from server log ===
    const humanName = 'Lord Maple Tree'
    result.turnActor.human = humanName
    let aiFillerName = null
    try {
        const srvLog = readFileSync(SRV_LOG, 'utf8')
        const m1 = srvLog.match(/Finalizing turn setup for 2 players: Lord Maple Tree, ([^\n]+)/)
        const m2 = srvLog.match(/activePlayers=\[Lord Maple Tree, ([^\]]+)\]/)
        const m = m1 || m2
        if (m) aiFillerName = m[1].trim()
    } catch (e) {
        log(`Could not read AI name from srv.log: ${e.message}`)
    }
    result.turnActor.ai = aiFillerName
    log(`Human: ${humanName} | AI: ${aiFillerName}`)

    if (!aiFillerName) {
        throw new Error('AI filler name not identified from server log')
    }

    // === PATH A: SUBMIT ON HUMAN'S TURN (should be ACCEPTED) ===
    log('=== PATH A: Submit on HUMAN turn (expect ACCEPTED) ===')
    const humanTurnWait = await waitForTurnActor(humanName, 420_000)
    if (!humanTurnWait.ok) {
        log(`PATH A: never reached human turn: ${humanTurnWait.error}`)
        result.paths.pathA_onHumanTurn.error = 'human-turn-never-arrived'
    } else {
        await wait(3000) // let the UI settle
        await shot(page, '12-pathA-human-turn-active')
        const pathAResult = await submitOnHumanTurn(
            page,
            humanName,
            `Path A from ${humanName}: Submitting on own turn — should be ACCEPTED.`
        )
        if (pathAResult.ok) {
            result.paths.pathA_onHumanTurn.ok = true
            result.paths.pathA_onHumanTurn.sentAt = pathAResult.sentAt
            result.paths.pathA_onHumanTurn.commandSent = `Path A from ${humanName}: ACCEPTED`
            await wait(3000)
            const modalAfterPathA = await page.evaluate(() => {
                const m = document.querySelector('.autogenesis-message-box-overlay')
                return m?.textContent?.trim().slice(0, 500) || null
            })
            if (modalAfterPathA && (
                modalAfterPathA.toLowerCase().includes('not your turn') ||
                modalAfterPathA.toLowerCase().includes('please wait')
            )) {
                log(`PATH A: UNEXPECTED REJECTION: ${modalAfterPathA}`)
                result.paths.pathA_onHumanTurn.rejectionReceived = true
                result.paths.pathA_onHumanTurn.rejectionMessage = modalAfterPathA
            } else {
                log(`PATH A: turn processed normally, no rejection modal. (Modal after: ${modalAfterPathA})`)
                result.paths.pathA_onHumanTurn.rejectionReceived = false
            }
            await shot(page, '13-pathA-after-send')
        } else {
            result.paths.pathA_onHumanTurn.error = pathAResult.error
        }
    }

    // === PATH B: ATTEMPT SUBMIT DURING AI'S TURN (expect REJECTED) ===
    log('=== PATH B: Attempt submit during AI turn (expect REJECTED with "Not Your Turn") ===')
    const aiTurnWait = await waitForTurnActor(aiFillerName, 420_000)
    if (!aiTurnWait.ok) {
        log(`PATH B: never reached AI turn: ${aiTurnWait.error}`)
        result.paths.pathB_onAiTurn.error = 'ai-turn-never-arrived'
    } else {
        await wait(4000) // let the UI settle
        await shot(page, '14-pathB-ai-turn-active')
        const pathBResult = await trySubmitDuringCurrentTurn(
            page,
            `Path B from ${humanName}: Attempting during ${aiFillerName}'s turn — should be REJECTED.`
        )
        result.paths.pathB_onAiTurn.sentAt = pathBResult.sentAt
        result.paths.pathB_onAiTurn.commandSent = `Path B from ${humanName}: attempt on AI turn`
        result.paths.pathB_onAiTurn.rejectionReceived = pathBResult.rejectionReceived
        result.paths.pathB_onAiTurn.rejectionMessage = pathBResult.rejectionMessage
        result.paths.pathB_onAiTurn.ok = pathBResult.ok && pathBResult.rejectionReceived
        if (pathBResult.rejectionReceived) {
            log(`PATH B: REJECTION RECEIVED — modal: ${pathBResult.rejectionMessage}`)
        } else {
            log(`PATH B: NO REJECTION (modal before: ${pathBResult.modalBefore}, modal after: ${pathBResult.modalAfter})`)
        }
        await shot(page, '15-pathB-after-attempt')
        if (pathBResult.rejectionReceived) {
            await dismissAnyModal(page)
        }
    }

    await shot(page, '16-final-state')

    log(
        `FINAL: pathA.accepted=${result.paths.pathA_onHumanTurn.ok && !result.paths.pathA_onHumanTurn.rejectionReceived} | pathB.rejected=${result.paths.pathB_onAiTurn.rejectionReceived}`
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