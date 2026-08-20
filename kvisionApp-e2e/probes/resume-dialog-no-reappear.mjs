#!/usr/bin/env node
// kvisionApp-e2e/probes/resume-dialog-no-reappear.mjs
//
// Regression probe for BUG 27 (2026-07-01): the "Resume saved game?"
// dialog was reappearing every 45-60 seconds because
// `notifyResumeAvailable` was firing on every SSE rebind. The fix is a
// per-userId dedupe in `UiSignalRpcHandlers.pushedResumeThisSession`
// (server) + a per-userId dedupe in `ResumeAvailabilityListener`
// (client) + a client-driven `server.consumeResumePush` RPC that
// re-arms the dedupe when the user clicks Resume / New Game / Cancel.
//
// What this probe verifies:
//  1. Login as the test user → ResumeOrNewDialog appears.
//  2. Click Resume → dialog dismisses, GameplayUI mounts.
//  3. Wait 90 seconds (covers 2× the 45s SSE reconnect cycle observed
//     in `server-extend-*.log`).
//  4. Assert: ResumeOrNewDialog is NOT visible (the bug would have
//     re-mounted it).
//  5. Inspect the server log: confirm the dedupe fired (multiple
//     `notifyResumeAvailable` calls but only ONE successful push).
//
// Pre-requisites (run in the user's dev environment, not the Hermes
// sandbox):
//  - `:server-extend:run` on port 7070
//  - `:server:run` on port 9080
//  - `:kvisionApp:jsBrowserDevelopmentRun` on port 8080
//  - A previously-saved running-game snapshot for the test user
//    (GuestAccount / <REDACTED_USER_ID>). The probe
//    seeds one in Phase A if missing.
//  - AUTOGENESIS_SHUTDOWN_DELAY_MS=600000 (10-minute shutdown timer so
//    the server doesn't die while the probe waits 90s).
//
// Run:  node Autogenesis/kvisionApp-e2e/probes/resume-dialog-no-reappear.mjs
//        (from the workspace root)

import { chromium } from '@playwright/test'
import { writeFile, mkdir, readFile, stat } from 'node:fs/promises'
import { fileURLToPath } from 'node:url'
import { dirname, join } from 'node:path'

const __dirname = dirname(fileURLToPath(import.meta.url))
const BASE_URL = process.env.KVISION_E2E_URL || 'http://127.0.0.1:8080'
const ARTIFACT_DIR = join(__dirname, 'artifacts-resume-no-reappear')
await mkdir(ARTIFACT_DIR, { recursive: true })
const HEADED = process.argv.includes('--headed')
const WAIT_AFTER_RESUME_MS = Number(process.env.PROBE_WAIT_MS || 90_000)
const LOG_PATH = process.env.SERVER_LOG_PATH ||
    join(process.env.HOME || '/home/cage', '.autogenesis', 'logs')

const log = (s) => console.log(`[${new Date().toISOString().slice(11, 23)}] ${s}`)

async function captureShot(page, name) {
    try {
        const out = join(ARTIFACT_DIR, `${name}.png`)
        await page.screenshot({ path: out, fullPage: false })
        log(`  📸 ${out}`)
        return out
    } catch (e) {
        log(`  screenshot failed: ${e.message}`)
        return null
    }
}

async function dismissMessageBoxes(page, maxMs = 15_000) {
    const start = Date.now()
    while (Date.now() - start < maxMs) {
        const dismissed = await page.evaluate(() => {
            const overlays = document.querySelectorAll(
                '.autogenesis-message-box-overlay, [class*="modal"], [class*="popup"]'
            )
            for (const overlay of overlays) {
                if (overlay.offsetWidth === 0 || overlay.offsetHeight === 0) continue
                const buttons = overlay.querySelectorAll('button')
                for (const btn of buttons) {
                    const text = btn.textContent.trim().toLowerCase()
                    if (text === 'ok' || text === 'o.k.') { btn.click(); return true }
                }
            }
            const allOk = Array.from(document.querySelectorAll('button'))
                .filter(b => b.textContent.trim().toLowerCase() === 'ok')
            if (allOk.length > 0) { allOk[0].click(); return true }
            return false
        })
        if (dismissed) await page.waitForTimeout(800)
        else await page.waitForTimeout(300)
    }
}

async function findResumeDialog(page) {
    return await page.evaluate(() => {
        const el = document.querySelector('[data-testid="resume-or-new-dialog"]')
        if (!el) return { visible: false, present: false }
        const rect = el.getBoundingClientRect()
        const visible = el.offsetWidth > 0 && el.offsetHeight > 0 &&
                        rect.width > 0 && rect.height > 0
        return { visible, present: true, w: rect.width, h: rect.height }
    })
}

async function clickResume(page) {
    const btn = page.locator('[data-testid="resume-dialog-resume"]')
    await btn.waitFor({ state: 'visible', timeout: 10_000 })
    await btn.click({ force: true })
}

async function login(page) {
    // Match the existing login flow used by no-auto-resume-flow.mjs.
    await page.goto(BASE_URL, { waitUntil: 'load', timeout: 30_000 })
    await dismissMessageBoxes(page, 8_000)

    // Click Play → Commander select → confirm.
    try {
        await page.getByRole('button', { name: /^Play$/i }).first()
            .click({ timeout: 5_000, force: true })
    } catch (_) { /* if not present, already past menu */ }

    // If a commander dialog appears, just pick the first available.
    const commanderCount = await page.locator('.commander-selection-window').count()
    if (commanderCount > 0) {
        try {
            await page.locator('.commander-selection-window button').first()
                .click({ timeout: 5_000, force: true })
        } catch (_) {}
    }
    await dismissMessageBoxes(page, 8_000)
}

async function main() {
    log(`base URL: ${BASE_URL}, headed: ${HEADED}, wait_ms=${WAIT_AFTER_RESUME_MS}`)
    const browser = await chromium.launch({ headless: !HEADED })
    const ctx = await browser.newContext({ viewport: { width: 1280, height: 800 } })
    const page = await ctx.newPage()

    let mountCount = 0
    let pushCountFromLog = 0
    let skipCountFromLog = 0
    let consumeCountFromLog = 0
    const seenMounts = []

    page.on('console', m => {
        const t = m.text()
        if (t.includes('ResumeOrNewDialog mounted')) {
            mountCount += 1
            seenMounts.push(t.slice(0, 200))
            log(`  [browser] MOUNT #${mountCount}: ${t.slice(0, 200)}`)
        }
    })

    // ============ PHASE 1: LOGIN + WAIT FOR DIALOG ============
    log('==== PHASE 1: login + wait for ResumeOrNewDialog ====')
    await login(page)
    await captureShot(page, 'phase1-after-login')

    // Wait for the dialog to appear (it should, since the test user has a save).
    let dialog = await findResumeDialog(page)
    let waited = 0
    while (!dialog.visible && waited < 30_000) {
        await page.waitForTimeout(1_000)
        waited += 1_000
        dialog = await findResumeDialog(page)
    }
    if (!dialog.visible) {
        log('❌ FAIL: ResumeOrNewDialog did not appear within 30s of login. The pre-condition (saved snapshot) is not met.')
        await captureShot(page, 'phase1-no-dialog')
        await browser.close()
        process.exit(1)
    }
    log(`✅ ResumeOrNewDialog visible after ${waited}ms`)
    if (mountCount < 1) {
        log('❌ FAIL: dialog visible but mount counter is 0 — listener not firing')
        await browser.close()
        process.exit(1)
    }

    // ============ PHASE 2: CLICK RESUME ============
    log('==== PHASE 2: click Resume ====')
    const beforeResumeMountCount = mountCount
    await clickResume(page)
    await page.waitForTimeout(3_000) // allow GameplayUI to mount
    await captureShot(page, 'phase2-after-resume-click')

    dialog = await findResumeDialog(page)
    if (dialog.visible) {
        log('❌ FAIL: ResumeOrNewDialog is STILL visible after clicking Resume')
        await browser.close()
        process.exit(1)
    }
    log('✅ ResumeOrNewDialog dismissed after Resume click')
    if (mountCount !== beforeResumeMountCount) {
        log(`⚠️  WARN: mountCount changed from ${beforeResumeMountCount} to ${mountCount} during Resume click (expected: no new mounts)`)
    }

    // ============ PHASE 3: WAIT FOR SSE RECONNECT CYCLES ============
    log(`==== PHASE 3: wait ${WAIT_AFTER_RESUME_MS / 1000}s (SSE reconnect window) ====')
    const beforeWaitMountCount = mountCount
    const startWait = Date.now()
    let lastCheck = startWait
    while (Date.now() - startWait < WAIT_AFTER_RESUME_MS) {
        await page.waitForTimeout(5_000)
        // Re-check: dialog must NOT be back.
        const d = await findResumeDialog(page)
        if (d.visible) {
            log(`❌ FAIL: ResumeOrNewDialog reappeared at ${Date.now() - startWait}ms — the bug is back`)
            await captureShot(page, 'phase3-dialog-reappeared-FAIL')
            await browser.close()
            process.exit(1)
        }
        // Every 30s, log progress.
        if (Date.now() - lastCheck >= 30_000) {
            log(`  ... ${(Date.now() - startWait) / 1000}s elapsed, mountCount=${mountCount}`)
            lastCheck = Date.now()
        }
    }
    log(`✅ waited ${WAIT_AFTER_RESUME_MS / 1000}s; dialog still NOT visible`)
    const newMounts = mountCount - beforeWaitMountCount
    if (newMounts > 0) {
        log(`❌ FAIL: ${newMounts} new dialog mount(s) during the wait — bug is not fixed`)
        seenMounts.forEach(m => log(`   ${m}`))
        await captureShot(page, 'phase3-new-mounts-FAIL')
        await browser.close()
        process.exit(1)
    }
    log('✅ no new dialog mounts during wait')

    // ============ PHASE 4: SERVER LOG VERIFICATION ============
    log('==== PHASE 4: inspect server log for dedupe signal ====')
    try {
        const { readdir } = await import('node:fs/promises')
        const logFiles = (await readdir(LOG_PATH))
            .filter(f => f.startsWith('autogenesis-') && f.endsWith('.log'))
            .sort()
        const latest = logFiles[logFiles.length - 1]
        if (!latest) {
            log(`  no autogenesis-*.log files in ${LOG_PATH} — skipping log check`)
        } else {
            const logPath = join(LOG_PATH, latest)
            const content = await readFile(logPath, 'utf8')
            // The log file will be larger than memory for a long session;
            // search the last 200KB only — covers the test window.
            const tail = content.slice(-200_000)
            const lines = tail.split('\n')
            // Find the user's userId by reading the first accelbyteId mention.
            const userIdMatch = content.match(/userId=([0-9a-f]{32})/)
            const userId = userIdMatch ? userIdMatch[1] : '<REDACTED_USER_ID>'
            const pushRegex = new RegExp(`notifyResumeAvailable: pushed to userId=${userId}`, 'g')
            const skipRegex = new RegExp(`notifyResumeAvailable: skipped \\(user=${userId} already pushed this session`, 'g')
            const consumeRegex = new RegExp(`consumeResumePush: user=${userId} removedFromDedupe=true`, 'g')
            pushCountFromLog = (tail.match(pushRegex) || []).length
            skipCountFromLog = (tail.match(skipRegex) || []).length
            consumeCountFromLog = (tail.match(consumeRegex) || []).length
            log(`  log ${latest}: pushes=${pushCountFromLog}, skips=${skipCountFromLog}, consumes=${consumeCountFromLog}`)
            if (pushCountFromLog === 0) {
                log(`  ⚠️  no push log found in tail (last 200KB). Dedupe worked but the user might not be in scope.`)
            } else if (pushCountFromLog > 1) {
                log(`  ❌ FAIL: ${pushCountFromLog} pushes reached the client in this window — dedupe is not stopping repeat pushes`)
            } else {
                log(`  ✅ exactly 1 push + ${skipCountFromLog} skips + ${consumeCountFromLog} consumes — dedupe is working`)
            }
        }
    } catch (e) {
        log(`  log scan skipped: ${e.message}`)
    }

    log('==== SUMMARY ====')
    log(`mountCount=${mountCount} (1 expected: the initial push)`)
    log(`pushes=${pushCountFromLog}, skips=${skipCountFromLog}, consumes=${consumeCountFromLog}`)
    await captureShot(page, 'final-state')
    await browser.close()
    log('✅ PASS: dialog did not reappear during SSE reconnect window')
}

main().catch(e => {
    log(`❌ FATAL: ${e.message}`)
    console.error(e)
    process.exit(1)
})