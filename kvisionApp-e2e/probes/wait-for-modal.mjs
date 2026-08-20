#!/usr/bin/env node
// kvisionApp-e2e/probes/wait-for-modal.mjs
//
// Minimal probe: open browser, do real guest login, click PLAY to start
// a game (creates a snapshot implicitly), close browser. Then open
// browser, do guest login, and JUST WAIT for the ResumeOrNewDialog to
// appear. This isolates the dialog-firing path from all the resume-button
// clicking. Use this to confirm the server-extend push makes it through
// to the modal mount.
//
// Pre-requisites:
//   1. All three dev servers running on standard ports.
//   2. AUongfa834nfa commander present in the test-user's master record.

import { chromium } from '@playwright/test'
import { writeFile, mkdir } from 'node:fs/promises'
import { fileURLToPath } from 'node:url'
import { dirname, join } from 'node:path'

const __dirname = dirname(fileURLToPath(import.meta.url))
const BASE_URL = process.env.KVISION_E2E_URL || 'http://127.0.0.1:8080'
const ARTIFACT_DIR = join(__dirname, 'artifacts-wait-for-modal')
await mkdir(ARTIFACT_DIR, { recursive: true })
const HEADED = process.argv.includes('--headed')

const log = (s) => console.log(`[${new Date().toISOString().slice(11, 23)}] ${s}`)

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

async function dismissMessageBoxes(page)
{
    for (let i = 0; i < 30; i++) {
        const okByText = page.getByRole('button', { name: /^OK$/ })
        if (await okByText.count() > 0 && await okByText.first().isVisible()) {
            try { await okByText.first().click({ timeout: 2_000 }) } catch (_) {}
        }
        await page.waitForTimeout(500)
    }
}

async function main()
{
    log(`base URL: ${BASE_URL}, headed: ${HEADED}`)
    const browser = await chromium.launch({ headless: !HEADED })

    // PHASE 1 — start a game so a snapshot exists
    log('==== PHASE 1: seed a snapshot ====')
    const ctx1 = await browser.newContext({ viewport: { width: 1280, height: 800 } })
    const page1 = await ctx1.newPage()
    page1.on('console', m => console.log(`  [phase1 ${m.type()}] ${m.text().slice(0, 200)}`))

    await page1.goto(`${BASE_URL}/index.html`)
    await page1.getByTestId('loading-screen-cta').click()
    await page1.locator('.login-widget-window').waitFor({ state: 'visible', timeout: 30_000 })
    await page1.getByTestId('login-as-guest').click()
    await dismissMessageBoxes(page1)
    await page1.locator('[data-testid="main-menu"]').waitFor({ state: 'visible', timeout: 60_000 })
    log('  MainMenu mounted')

    // If a ResumeOrNewDialog from a prior run is in the way (it intercepts
    // pointer events), click "New Game" to dismiss it and start a fresh game.
    const existingDialog = page1.locator('[data-testid="resume-or-new-dialog"]')
    if (await existingDialog.count() > 0 && await existingDialog.first().isVisible()) {
        log('  found stale ResumeOrNewDialog from a prior run; clicking New Game to dismiss')
        await page1.locator('[data-testid="resume-or-new-dialog"] button:has-text("New Game")').first().click({ timeout: 5_000 }).catch(() => {})
        await page1.waitForTimeout(1_000)
    }

    // Click PLAY, click existing commander, click Next, click Play.
    // Use force:true to bypass any stale overlay (e.g. an outer
    // commander-selection-overlay that's a sibling, not the actual blocking
    // Click PLAY, click existing commander, click Next, click Play
    await page1.locator('.btn.btn-play').click({ force: true, timeout: 10_000 })
    log('  clicked PLAY (force)')

    // Pushes can pop up a fresh ResumeOrNewDialog right after PLAY click.
    // Dismiss any pending ones before clicking into the CommanderSelectionDialog.
    for (let i = 0; i < 5; i++) {
        const dialog = page1.locator('[data-testid="resume-or-new-dialog"]')
        if (await dialog.count() > 0 && await dialog.first().isVisible()) {
            log(`  dismissing ResumeOrNewDialog (iteration ${i})`)
            await page1.locator('[data-testid="resume-or-new-dialog"] button:has-text("New Game")').first().click({ timeout: 5_000, force: true }).catch(() => {})
            await page1.waitForTimeout(1_000)
        }
    }
    await dumpDomSnapshot(page1, 'phase1-after-play-click')
    await page1.waitForFunction(() =>
        document.querySelector('[data-testid="gameplay-ui"]') ||
        document.querySelector('.commander-selection-window')
    , { timeout: 30_000 }).catch(() => {})
    await page1.locator('text=/AUongfa834nfa/').first().click({ timeout: 5_000, force: true }).catch(() => {})
    await page1.getByRole('button', { name: /^Next$/ }).first().click({ timeout: 5_000, force: true }).catch(() => {})
    await page1.getByRole('button', { name: /^Play$/ }).first().click({ timeout: 5_000, force: true }).catch(() => {})
    await dismissMessageBoxes(page1)
    await page1.waitForFunction(() => !!document.querySelector('[data-testid="gameplay-ui"]'), { timeout: 30_000 }).catch(() => {})
    log('  GameplayUI mounted; letting one turn run')
    await page1.waitForTimeout(8_000)
    log('  closing phase 1 browser')
    await page1.close()
    await ctx1.close()

    // Wait for the snapshot to be written + server to settle
    log('  waiting 15s for snapshot to land')
    await new Promise(r => setTimeout(r, 15_000))

    // PHASE 2 — open fresh browser, login, watch for modal
    log('==== PHASE 2: open fresh browser and wait for ResumeOrNewDialog ====')
    const ctx2 = await browser.newContext({ viewport: { width: 1280, height: 800 } })
    const page2 = await ctx2.newPage()
    const phase2Console = []
    page2.on('console', m => {
        const text = m.text()
        phase2Console.push(`[${m.type()}] ${text}`)
        // Also print live so we can see if the listener fires
        if (text.includes('ResumeAvailability') || text.includes('resume') || text.includes('Resume') ||
            text.includes('client.resume') || text.includes('mountResumeDialog') ||
            text.includes('dialog callbacks') || text.includes('notification received') ||
            text.includes('queueing') || text.includes('pending payload'))
            console.log(`  >>> [phase2 ${m.type()}] ${text}`)
    })

    await page2.goto(`${BASE_URL}/index.html`)
    // Hook the WS to log incoming frames
    await page2.addInitScript(() => {
        const origWS = window.WebSocket
        window.__wsFrames = []
        window.WebSocket = function(...args) {
            const ws = new origWS(...args)
            const origAdd = ws.addEventListener.bind(ws)
            ws.addEventListener = function(type, fn, opts) {
                if (type === 'message') {
                    const wrapped = (event) => {
                        try {
                            const data = typeof event.data === 'string' ? event.data : '<binary>'
                            window.__wsFrames.push({ url: args[0], data: data.slice(0, 500), ts: Date.now() })
                        } catch(_) {}
                        return fn(event)
                    }
                    return origAdd(type, wrapped, opts)
                }
                return origAdd(type, fn, opts)
            }
            return ws
        }
        Object.assign(window.WebSocket, origWS)
    })
    await page2.reload()  // apply addInitScript
    await page2.goto(`${BASE_URL}/index.html`)
    await page2.getByTestId('loading-screen-cta').click()
    await page2.locator('.login-widget-window').waitFor({ state: 'visible', timeout: 30_000 })
    await page2.getByTestId('login-as-guest').click()
    await dismissMessageBoxes(page2)
    await page2.locator('[data-testid="main-menu"]').waitFor({ state: 'visible', timeout: 60_000 })
    log('  MainMenu mounted; waiting 30s for modal')

    const dialogAppeared = await page2.waitForFunction(() => {
        const byTestId = document.querySelector('[data-testid="resume-or-new-dialog"]')
        if (byTestId) return { found: 'testid', text: byTestId.textContent.slice(0, 200) }
        if (document.body.textContent.includes('Saved game found')) {
            return { found: 'text', text: document.body.textContent.slice(0, 200) }
        }
        return false
    }, { timeout: 30_000 }).then(h => h.jsonValue()).catch(e => ({ error: e.message }))

    log(`  modal check: ${JSON.stringify(dialogAppeared)}`)
    await dumpDomSnapshot(page2, 'phase2-final')

    // Print WS frames received
    const wsFrames = await page2.evaluate(() => window.__wsFrames || [])
    log(`  WS frames received on phase 2 (${wsFrames.length}):`)
    for (const f of wsFrames.slice(0, 20)) {
        log(`    [${new Date(f.ts).toISOString().slice(11, 23)}] ${f.url} :: ${f.data.slice(0, 200)}`)
    }

    // Print all resume-related phase2 console messages
    const resumeMsgs = phase2Console.filter(c =>
        c.includes('resume') || c.includes('Resume') || c.includes('notification') ||
        c.includes('client.resume') || c.includes('ResumeAvailability') ||
        c.includes('mountResumeDialog') || c.includes('dialog callbacks')
    )
    log(`  resume-related phase2 console messages (${resumeMsgs.length}):`)
    for (const m of resumeMsgs.slice(0, 30)) log(`    ${m}`)

    await browser.close()
    process.exit(dialogAppeared && !dialogAppeared.error ? 0 : 1)
}

main().catch(e => {
    console.error('probe crashed:', e)
    process.exit(2)
})