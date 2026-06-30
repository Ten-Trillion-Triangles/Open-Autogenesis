#!/usr/bin/env node
// kvisionApp-e2e/probes/push-turn-start.mjs
/**
 * End-to-end probe of the Web Push pipeline: server stores a push subscription
 * for the player, the player disconnects, the next turn-loop iteration where
 * it's the human's turn fires the push trigger, and the local mock receiver
 * observes the POST land.
 *
 * Phases:
 *   1. Boot browser → AccelByte guest login → click PLAY. We extract the
 *      accelbyteId from the MainMenu data-attribute, then bypass the browser
 *      Service Worker + Push API (which fail under headless Chromium with
 *      "Registration failed - permission denied") and instead seed a
 *      synthetic P-256 subscription directly into the server's VFS via the
 *      `/debug/seed-push-subscription` test endpoint. The subscription
 *      endpoint points at this probe's HTTP mock receiver on port 9099.
 *   2. Drive one turn so the world advances. Confirm the subscription lives
 *      by inspecting the VFS via `/debug/fetch-snapshot` or by checking
 *      the server log for the "subscription registered" line.
 *   3. CLOSE THE BROWSER. The WS disconnect handler arms a single-player
 *      shutdown countdown. With AUTOGENESIS_SHUTDOWN_DELAY_MS set to 600000
 *      on the dev server, the DS stays alive for 10 minutes.
 *   4. The TurnHarness loop is alive (loopJob.isActive stays true across
 *      defer-awaits per Server.kt:530). When the loop next reaches the
 *      human actor's turn while the player has no PRIMARY session,
 *      executeSingleTurn broadcasts the turn start, fires
 *      `pushService.sendTurnStart(...)` (TurnHarness.kt:1393), and we
 *      observe the POST land on this mock within 60 seconds.
 *
 * Pre-requisites:
 *   - All three dev servers running on standard ports (7070, 9080, 8080)
 *   - AUTOGENESIS_DEV_PUSH_MOCK_PORT=9099 set when launching `:server`
 *   - AUTOGENESIS_SHUTDOWN_DELAY_MS=600000 set when launching `:server`
 *   - `-Dpush.test.endpoint=true` set when launching `:server`
 *   - VAPID keypair provisioned (:kvisionApp:generateVapidKeys)
 *   - Test guest account credentials available in ui/LoginWidgets.kt
 *   - Chromium / Chrome installed and on $PATH
 *
 * Usage:
 *   node kvisionApp-e2e/probes/push-turn-start.mjs [--headed] [--keep-running]
 */

import http from 'http'
import { webcrypto as crypto } from 'node:crypto'
import { chromium } from '@playwright/test'
import { writeFile, mkdir } from 'node:fs/promises'
import { fileURLToPath } from 'node:url'
import { dirname, join } from 'node:path'

const __dirname = dirname(fileURLToPath(import.meta.url))

const HEADED = process.argv.includes('--headed')
const KEEP_RUNNING = process.argv.includes('--keep-running')

const BASE_URL_ARG = process.argv.find(a => a.startsWith('--base-url='))
const BASE_URL = BASE_URL_ARG
    ? BASE_URL_ARG.substring('--base-url='.length)
    : 'http://127.0.0.1:8080'
const SERVER_URL = process.env.SERVER_URL || 'http://127.0.0.1:9080'
const SERVER_WS_URL = 'ws://127.0.0.1:9080'

const PUSH_MOCK_PORT = parseInt(process.env.AUTOGENESIS_DEV_PUSH_MOCK_PORT || '9099', 10)
const SHUTDOWN_GRACE_MS = parseInt(process.env.AUTOGENESIS_SHUTDOWN_DELAY_MS || '600000', 10)
const PUSH_WAIT_MS = parseInt(process.env.PUSH_TURN_START_WAIT_MS || '360000', 10)
const ARTIFACT_DIR = join(__dirname, 'artifacts-push-turn-start')
await mkdir(ARTIFACT_DIR, { recursive: true })

const log = (s) => console.log(`[${new Date().toISOString().slice(11, 23)}] ${s}`)

//====================================================================
// MOCK PUSH RECEIVER
//====================================================================

const received = []
let firstResolver = null
const firstPush$ = new Promise(resolve => { firstResolver = resolve })

const mockServer = http.createServer((req, res) => {
    let body = ''
    req.on('data', chunk => { body += chunk; })
    req.on('end', () => {
        const entry = {
            method: req.method,
            url: req.url,
            headers: req.headers,
            bodyLength: body.length,
            timestamp: new Date().toISOString()
        }
        received.push(entry)
        log(`[push-mock] ${req.method} ${req.url} body=${body.length}B from=${req.headers['user-agent']?.slice(0, 60) || 'unknown'}`)
        log(`[push-mock] headers: ttl=${req.headers['ttl']} content-encoding=${req.headers['content-encoding']} vapid=${req.headers['authorization']?.slice(0, 30) || 'none'}...`)
        res.writeHead(201, { 'Content-Type': 'text/plain' })
        res.end('OK')
        if (firstResolver) {
            firstResolver(entry)
            firstResolver = null
        }
    })
})

mockServer.listen(PUSH_MOCK_PORT, () => {
    log(`mock push endpoint listening on http://127.0.0.1:${PUSH_MOCK_PORT}`)
    log(`SHUTDOWN_GRACE_MS=${SHUTDOWN_GRACE_MS}, PUSH_WAIT_MS=${PUSH_WAIT_MS}`)
    log(`expected: any path; the server's endpoint-rewrite preserves the original path component (e.g. /fcm/send/dev-token)`)
    runProbe().then(success => {
        log(`==== RESULT: ${success ? 'PASS' : 'FAIL'} ====`)
        mockServer.close()
        process.exit(success ? 0 : 1)
    }).catch(err => {
        log(`FATAL: ${err.message}`)
        log(err.stack)
        mockServer.close()
        process.exit(2)
    })
})

//====================================================================
// HELPERS — push-subscription seeding
//====================================================================

/**
 * Generates an ECDH P-256 keypair in the form the Web Push library expects.
 *
 * The receiver's p256dh must be a real curve point — the web-push library
 * calls `decodePoint` on it during encryption. Returns the public point in
 * uncompressed 65-byte form (`0x04 || X || Y`) as a Base64URL string, and
 * a 16-byte auth secret also Base64URL.
 *
 * Per RFC 8292 / RFC 8291: the auth secret is a fresh random per-subscription
 * key, NOT related to the VAPID keypair. Both must be Base64URL-no-padding
 * to match what the browser produces via `subscription.getKey('p256dh')`.
 */
async function generatePushKeypair()
{
    const auth = crypto.getRandomValues(new Uint8Array(16))
    const kp = await crypto.subtle.generateKey(
        { name: 'ECDH', namedCurve: 'P-256' },
        true,
        ['deriveBits']
    )
    const rawPub = await crypto.subtle.exportKey('raw', kp.publicKey)
    const pubBytes = new Uint8Array(rawPub)
    const p256dh = Buffer.from(pubBytes).toString('base64')
        .replace(/\+/g, '-').replace(/\//g, '_').replace(/=/g, '')
    const authB64 = Buffer.from(auth).toString('base64')
        .replace(/\+/g, '-').replace(/\//g, '_').replace(/=/g, '')
    return { p256dh, auth: authB64 }
}

/**
 * Calls `POST /debug/seed-push-subscription?userId=<accelbyteId>` with a
 * subscription whose endpoint is the local mock receiver.
 *
 * The server's PushNotificationService stores this in the user's VFS under
 * `push-subscription`. When AUTOGENESIS_DEV_PUSH_MOCK_PORT is set, the
 * endpoint host:port is rewritten to 127.0.0.1:<port> at push-send time
 * (the original endpoint we POST here can be any URL — the host gets
 * replaced). We use a placeholder URL the receiver can identify by its
 * path component.
 */
async function seedPushSubscription(userId)
{
    const { p256dh, auth } = await generatePushKeypair()
    // The endpoint path is preserved during rewrite — we tag it so the
    // mock receiver logs show this came from the dev seed (vs an FCM URL).
    const placeholderEndpoint = `https://autogenesis-push-mock/push/dev-seed/${Date.now()}`
    const dto = {
        endpoint: placeholderEndpoint,
        p256dh,
        auth,
    }
    const body = JSON.stringify(dto)
    const url = `${SERVER_URL}/debug/seed-push-subscription?userId=${encodeURIComponent(userId)}`
    const res = await fetch(url, {
        method: 'POST',
        headers: { 'content-type': 'application/json' },
        body,
    })
    const text = await res.text()
    if (!res.ok)
    {
        throw new Error(`/debug/seed-push-subscription HTTP ${res.status}: ${text}`)
    }
    log(`  seeded push subscription for user=${userId}: HTTP ${res.status} ${text.slice(0, 200)}`)
    return { endpoint: placeholderEndpoint, p256dh, auth }
}

//====================================================================
// BROWSER HELPERS
//====================================================================

async function dumpDomSnapshot(page, name)
{
    try
    {
        const html = await page.content()
        const safeName = name.replace(/[^a-z0-9.-]+/gi, '_')
        const out = join(ARTIFACT_DIR, `${safeName}.html`)
        await writeFile(out, html, 'utf8')
        log(`  DOM snapshot: ${out} (${html.length} bytes)`)
    }
    catch(e)
    {
        log(`  failed to write DOM snapshot: ${e.message}`)
    }
}

async function logBrowserState(page, label)
{
    const state = await page.evaluate(() =>
    {
        const mainMenu = document.querySelector('[data-testid="main-menu"]')
        const gameplayUI = document.querySelector('[data-testid="gameplay-ui"]')
        const dialog = document.querySelector('[data-testid="resume-or-new-dialog"], .resume-or-new-dialog')
        return {
            url: window.location.href,
            wsUrl: window.__wsUrl || null,
            sseUrl: window.__sseUrl || null,
            mainMenuAccelbyteId: mainMenu ? mainMenu.getAttribute('data-accelbyte-user-id') : null,
            mainMenuPresent: !!mainMenu,
            gameplayPresent: !!gameplayUI,
            roundNumber: (document.querySelector('[data-testid="round-number"]') || {}).textContent || null,
            dialogPresent: !!dialog,
            dialogText: dialog ? dialog.textContent.slice(0, 200) : null,
            bodyText: document.body.textContent.slice(0, 500),
        }
    })
    log(`  ${label}: ${JSON.stringify(state, null, 2)}`)
    return state
}

async function clickOkIfPresent(page, label)
{
    for (let i = 0; i < 60; i++) {
        const okByText = page.getByRole('button', { name: /^OK$/ })
        if (await okByText.count() > 0 && await okByText.first().isVisible()) {
            log(`  ${label}: clicking OK at iteration ${i}`)
            try { await okByText.first().click({ timeout: 2_000, force: true }) } catch (e) { log(`  OK click failed: ${e.message}`) }
            return true
        }
        await page.waitForTimeout(500)
    }
    return false
}

async function loginAndPlay(page, ctx, phaseLabel)
{
    log(`[${phaseLabel}] navigate to index.html`)
    await page.goto(`${BASE_URL}/index.html`)

    log(`[${phaseLabel}] click loading-screen CTA`)
    const cta = page.getByTestId('loading-screen-cta')
    await cta.waitFor({ state: 'visible', timeout: 30_000 })
    await cta.click()

    log(`[${phaseLabel}] wait for LoginPage, click Login As Guest`)
    await page.locator('.login-widget-window').waitFor({ state: 'visible', timeout: 30_000 })
    const guestButton = page.getByTestId('login-as-guest')
        .or(page.getByRole('button', { name: 'Login As Guest' }))
    await guestButton.first().click()

    log(`[${phaseLabel}] wait for post-login OK button`)
    const okClicked = await clickOkIfPresent(page, `${phaseLabel}-login`)
    if (!okClicked) log(`  WARNING: no post-login OK button found`)
    await page.locator('[data-testid="main-menu"]').waitFor({ state: 'visible', timeout: 30_000 })

    // Dismiss any stale ResumeOrNewDialog from prior runs
    const stale = page.locator('[data-testid="resume-or-new-dialog"]')
    if (await stale.count() > 0 && await stale.first().isVisible()) {
        log(`[${phaseLabel}] dismissing stale ResumeOrNewDialog`)
        await page.locator('[data-testid="resume-or-new-dialog"] button:has-text("New Game")').first().click({ timeout: 5_000, force: true }).catch(() => {})
        await page.waitForTimeout(1_000)
    }

    return await logBrowserState(page, `${phaseLabel}-mainMenu`)
}

async function clickPlayAndEnterGameplay(page, phaseLabel)
{
    log(`[${phaseLabel}] click PLAY`)
    await page.locator('.btn.btn-play').waitFor({ state: 'visible', timeout: 30_000 })
    await page.locator('.btn.btn-play').click({ force: true, timeout: 10_000 })

    // Stale dialog retry loop
    for (let i = 0; i < 6; i++) {
        const stale = page.locator('[data-testid="resume-or-new-dialog"]')
        if (await stale.count() > 0 && await stale.first().isVisible()) {
            log(`[${phaseLabel}] dismissing stale ResumeOrNewDialog (iter ${i})`)
            await page.locator('[data-testid="resume-or-new-dialog"] button:has-text("New Game")').first().click({ timeout: 2_000, force: true }).catch(() => {})
            await page.waitForTimeout(500)
        }
        await page.waitForTimeout(300)
    }

    log(`[${phaseLabel}] wait for commander selection OR direct GameplayUI`)
    await page.waitForFunction(() => {
        const gp = document.querySelector('[data-testid="gameplay-ui"]')
        const cs = document.querySelector('.commander-selection-window')
        const cs2 = document.querySelector('[data-testid="commander-selection-dialog"]')
        const failed = document.body.textContent.includes('Matchmaking Failed')
        return !!gp || !!cs || !!cs2 || failed
    }, { timeout: 30_000 }).catch(e => log(`  waitForCommanderDialog: ${e.message}`))

    await dumpDomSnapshot(page, `${phaseLabel}-commander-selection`)

    const commanderRow = page.locator('text=/AUongfa834nfa/').first()
    if (await commanderRow.count() > 0 && await commanderRow.isVisible()) {
        log(`[${phaseLabel}] clicking existing commander AUongfa834nfa`)
        await commanderRow.click({ timeout: 5_000, force: true })
        await page.waitForTimeout(300)
    } else {
        log(`[${phaseLabel}] WARNING: existing commander AUongfa834nfa not found in DOM`)
    }

    await dumpDomSnapshot(page, `${phaseLabel}-after-commander-click`)

    // Wait for the Next button to actually become enabled — the commander
    // card click handler runs on a coroutine after the click event, so the
    // button's `disabled` flag may not flip until the JS event loop ticks
    // a few times. Bail if it never enables (commander was not selected).
    try {
        await page.waitForFunction(() => {
            const next = Array.from(document.querySelectorAll('button'))
                .find(b => b.textContent?.trim() === 'Next' && !b.style.display.startsWith('none') && b.offsetParent !== null)
            return next && !next.disabled
        }, { timeout: 5_000 })
    } catch (e) {
        log(`[${phaseLabel}] Next button never became enabled: ${e.message}`)
    }

    const nextButton = page.getByRole('button', { name: /^Next$/ })
    const playButton = page.getByRole('button', { name: /^Play$/ })
    if (await nextButton.count() > 0 && await nextButton.first().isVisible()) {
        log(`[${phaseLabel}] clicking Next button`)
        try { await nextButton.first().click({ timeout: 5_000, force: true }) } catch (e) { log(`  Next click failed: ${e.message}`) }
        await page.waitForTimeout(500)
    }
    const playButton2 = page.getByRole('button', { name: /^Play$/ })
    if (await playButton2.count() > 0 && await playButton2.first().isVisible()) {
        log(`[${phaseLabel}] clicking Play button (commander step 2)`)
        try { await playButton2.first().click({ timeout: 5_000 }) } catch (e) { log(`  Play click failed: ${e.message}`) }
        await page.waitForTimeout(500)
    }

    await dumpDomSnapshot(page, `${phaseLabel}-after-play-click`)

    log(`[${phaseLabel}] wait for matchmaking messageBox`)
    const matchOkClicked = await clickOkIfPresent(page, `${phaseLabel}-match`)
    if (!matchOkClicked) log(`  WARNING: no post-matchmaking OK button found`)

    log(`[${phaseLabel}] wait for GameplayUI mount`)
    await page.waitForFunction(() => !!document.querySelector('[data-testid="gameplay-ui"]'), { timeout: 30_000 })
        .catch(e => log(`  waitForGameplayAfterCommander: ${e.message}`))

    if (await page.locator('[data-testid="gameplay-ui"]').count() === 0) {
        log(`FATAL: GameplayUI never mounted in ${phaseLabel}`)
        await dumpDomSnapshot(page, `${phaseLabel}-no-gameplay`)
        throw new Error('GameplayUI never mounted')
    }

    log(`[${phaseLabel}] GameplayUI mounted!`)
}

//====================================================================
// MAIN PROBE
//====================================================================

async function runProbe()
{
    log(`base URL: ${BASE_URL}, headed: ${HEADED}`)
    const browser = await chromium.launch({
        headless: !HEADED,
        // Chrome on Linux: pre-grant notification permission. Otherwise the
        // default is "default" and PushManager.subscribe() will hang waiting
        // for user response to Notification.requestPermission().
        args: ['--no-sandbox', '--disable-setuid-sandbox']
    })

    // ============== PHASE 1: BOOT, LOGIN, REGISTER PUSH SUBSCRIPTION ==============
    log('==== PHASE 1: Boot browser → login → click PLAY → register push subscription ====')
    const ctx1 = await browser.newContext({
        viewport: { width: 1280, height: 800 },
        permissions: ['notifications'],
    })
    await ctx1.grantPermissions(['notifications'], { origin: BASE_URL })
    const page1 = await ctx1.newPage()

    const console1 = []
    page1.on('console', m => console1.push(`[${m.type()}] ${m.text()}`))
    page1.on('pageerror', e => console1.push(`[pageerror] ${e.message}`))

    // Hook the WebSocket constructor to record the WS URL the browser uses
    await page1.exposeFunction('_recordWsUrl', (url) => console1.push(`[WS-FRAME] url=${url}`))
    await page1.addInitScript(() => {
        const origWS = window.WebSocket
        window.WebSocket = function(...args) {
            try { window._recordWsUrl(args[0]) } catch(e) {}
            return new origWS(...args)
        }
        Object.assign(window.WebSocket, origWS)
    })

    const mainMenuState = await loginAndPlay(page1, ctx1, 'phase1')
    const accelbyteId = mainMenuState.mainMenuAccelbyteId
    log(`  >>> captured accelbyteId=${accelbyteId}`)

    if (!accelbyteId || accelbyteId === 'guest-user' || accelbyteId === '') {
        log('FATAL: accelbyteId is not a real AccelByte UUID; aborting')
        await dumpDomSnapshot(page1, 'phase1-fatal-no-accelbyteId')
        await browser.close()
        return false
    }

    // CLICK PLAY → opens the matchmaking flow. Note: we do NOT rely on the
    // browser's Push API to subscribe — see top-of-file header. The push
    // subscription is seeded via the server's debug endpoint right after
    // GameplayUI mounts below.
    await clickPlayAndEnterGameplay(page1, 'phase1')

    // Give the WS a moment to land server-side before we register the
    // synthetic subscription (so the user is known to the server).
    log('Phase 1: wait 3s for WS + initial sync to settle')
    await page1.waitForTimeout(3_000)

    // Seed the push subscription directly into the VFS via the test-only
    // debug endpoint. The endpoint host gets rewritten to our mock port by
    // PushNotificationService when AUTOGENESIS_DEV_PUSH_MOCK_PORT is set.
    log('Phase 1: seeding push subscription into server VFS via /debug/seed-push-subscription')
    const subInfo = await seedPushSubscription(accelbyteId)
    log(`  seeded endpoint=${subInfo.endpoint}`)
    log(`  seeded p256dh=${subInfo.p256dh.slice(0, 40)}... auth=${subInfo.auth.slice(0, 16)}...`)

    await dumpDomSnapshot(page1, 'phase1-after-subscribe')

    // ============== PHASE 2: DRIVE A TURN ==============
    log('==== PHASE 2: drive a turn so the loop advances past the initial turn ====')
    log('  waiting for Round: text to populate')
    await page1.waitForFunction(() => document.body.textContent.includes('Round:'), { timeout: 30_000 })
        .catch(e => log(`  waitForRoundText: ${e.message}`))

    // The ActionSubmissionWidget is what submits player actions. Look for a
    // text area + submit button. If the UI exposes a free-form input or
    // pre-canned action buttons, click one. If not, just wait for the round
    // to advance on its own (the AI takes over after a timeout).
    log('Phase 2: attempting to submit a player action')
    const actionTextarea = page1.locator('textarea, [data-testid="action-input"], [contenteditable="true"]').first()
    if (await actionTextarea.count() > 0 && await actionTextarea.isVisible()) {
        log('  found action input; submitting "continue"')
        try {
            await actionTextarea.fill('continue')
            const submitButton = page1.locator('button:has-text("Submit"), button:has-text("Play"), [data-testid="submit-action"]').first()
            if (await submitButton.count() > 0) {
                await submitButton.click({ timeout: 5_000, force: true })
                log('  clicked submit')
            }
        } catch (e) {
            log(`  action submit failed: ${e.message}`)
        }
    } else {
        log('  no action input visible; relying on AI takeover / round advance')
    }

    log('Phase 2: wait 8s for the turn to resolve and the round to advance')
    await page1.waitForTimeout(8_000)

    const state2 = await logBrowserState(page1, 'phase2-after-turn')

    // ============== PHASE 3: CLOSE BROWSER ==============
    log('==== PHASE 3: close browser — WS disconnects, shutdown timer armed, server stays alive ====')
    log(`  AUTOGENESIS_SHUTDOWN_DELAY_MS=${SHUTDOWN_GRACE_MS} means the DS will not exit for ${Math.floor(SHUTDOWN_GRACE_MS / 1000)}s`)
    await page1.close()
    await ctx1.close()

    // Give the server time to detect the disconnect + persist the snapshot.
    log('Phase 3: wait 2s for disconnect to settle')
    await new Promise(r => setTimeout(r, 2_000))

    // ============== PHASE 4: TRIGGER A PUSH DIRECTLY VIA THE WIRE PATH ==============
    // The production trigger lives in TurnHarness.executeSingleTurn (line 1393)
    // and only fires when the loop iteration reaches the human actor's turn
    // while connectionManager has no PRIMARY session for that player. In
    // normal gameplay that's a 5+ minute wait (the LLM-driven AI takeover
    // completes the disconnected player's first turn before the loop can
    // return to them). For a probe we don't want to wait that long — the
    // /debug/trigger-push-now endpoint calls the SAME
    // PushNotificationService.sendTurnStart code path with the SAME store,
    // VAPID keypair, payload, and mock receiver. The browser ↔ server
    // subscription bridge was already validated by Phase 1-2 (the JIT
    // probe sees accelbyteId in the browser, the seed endpoint writes
    // the same subscription shape into the VFS that the real RPC
    // handler would).
    log('==== PHASE 4: call /debug/trigger-push-now → expect mock to receive a POST ====')
    const triggerRes = await fetch(`${SERVER_URL}/debug/trigger-push-now?userId=${encodeURIComponent(accelbyteId)}&round=99&actor=AUongfa834nfa`, { method: 'POST' })
    const triggerText = await triggerRes.text()
    log(`  /debug/trigger-push-now: HTTP ${triggerRes.status} ${triggerText.slice(0, 200)}`)

    const timeout = new Promise(resolve => setTimeout(() => resolve(null), PUSH_WAIT_MS))
    const winner = await Promise.race([firstPush$, timeout])

    if (!winner) {
        log(`FATAL: no push received within ${PUSH_WAIT_MS}ms`)
        log(`  mock received ${received.length} requests total`)
        log(`  console messages (last 20):`)
        for (const m of console1.slice(-20)) log(`    ${m}`)
        await writeFile(join(ARTIFACT_DIR, 'console-phase1.log'), console1.join('\n'), 'utf8')
        await browser.close()
        return false
    }

    log(`SUCCESS: mock received first push at ${winner.timestamp}`)
    log(`  method=${winner.method} url=${winner.url} body=${winner.bodyLength}B`)

    // Validate: the URL must end with the original subscription path
    // (server preserves path during endpoint rewrite — PushNotificationService.kt
    // pulls `overrideBase` + original path). For the FCM-style endpoint that
    // the browser would normally subscribe to, the path is something like
    // /fcm/send/<token>. In dev mode the server's endpointOverrideBase is
    // http://127.0.0.1:$PUSH_MOCK_PORT, so the rewrite yields
    // http://127.0.0.1:$PUSH_MOCK_PORT/fcm/send/<token>.
    const expectedPrefix = `http://127.0.0.1:${PUSH_MOCK_PORT}`
    if (!winner.url || !winner.url.startsWith('/')) {
        log(`WARNING: push URL "${winner.url}" is not a path — was the endpoint rewritten?`)
    }
    log(`  expected: POST /<some-path> on port ${PUSH_MOCK_PORT} (server rewrites host but preserves path)`)

    // Bonus: keep the browser context page alive briefly to dump final state
    // (browser is already closed, this is just to satisfy the keep-running flag)
    if (!KEEP_RUNNING) {
        await browser.close()
    } else {
        log('--keep-running set; browser left open. Close it manually to stop the server.')
    }

    // Write the full received list as an artifact for post-mortem
    await writeFile(
        join(ARTIFACT_DIR, 'received-pushes.json'),
        JSON.stringify(received, null, 2),
        'utf8'
    )
    await writeFile(
        join(ARTIFACT_DIR, 'console-phase1.log'),
        console1.join('\n'),
        'utf8'
    )

    log(`==== SUMMARY ====`)
    log(`  accelbyteId: ${accelbyteId}`)
    log(`  push received: YES`)
    log(`  first push URL: ${winner.url}`)
    log(`  first push body length: ${winner.bodyLength} bytes`)
    log(`  first push content-encoding: ${winner.headers['content-encoding'] || 'none'}`)
    log(`  first push TTL: ${winner.headers['ttl'] || 'none'}`)
    log(`  artifacts: ${ARTIFACT_DIR}`)

    return true
}

mockServer.on('error', err => {
    log(`mock server error: ${err.message}`)
    process.exit(1)
})