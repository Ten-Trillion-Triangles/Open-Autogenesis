// kvisionApp-e2e/probes/diagnose-all-mobile.mjs
//
// Comprehensive mobile-portrait diagnostic for every widget that has a mobile probe.
// Opens iPhone 12 viewport, navigates with skipLogin=true, walks each widget's
// open/close path, dumps DOM measurements + takes screenshots.
// Output: screenshots in /home/cage/Desktop/Workspaces/Autogenesis/screenshots/2026-07-11-mobile-baseline/
//         JSON dump at /home/cage/Desktop/Workspaces/Autogenesis/screenshots/2026-07-11-mobile-baseline/diagnostic.json

import { chromium, devices } from '@playwright/test'
import { mkdir, writeFile } from 'node:fs/promises'
import { join } from 'node:path'

const BASE_URL = 'http://127.0.0.1:8080/index.html?skipLogin=true'
const OUT = '/home/cage/Desktop/Workspaces/Autogenesis/screenshots/2026-07-11-mobile-baseline'
await mkdir(OUT, { recursive: true })

const log = (m) => console.log(`[diag] ${m}`)
const wait = (ms) => new Promise(r => setTimeout(r, ms))
const findings = {}

const browser = await chromium.launch()
const context = await browser.newContext({ ...devices['iPhone 12'] })
const page = await context.newPage()
page.on('pageerror', err => console.error('PAGE-ERROR:', err.message))

async function box(sel) {
    return await page.evaluate((s) => {
        const el = document.querySelector(s)
        if (!el) return null
        const r = el.getBoundingClientRect()
        const cs = getComputedStyle(el)
        return {
            x: Math.round(r.x), y: Math.round(r.y), w: Math.round(r.width), h: Math.round(r.height),
            right: Math.round(r.right), bottom: Math.round(r.bottom),
            display: cs.display, position: cs.position,
            overflow: cs.overflow, overflowY: cs.overflowY,
            scrollW: el.scrollWidth, scrollH: el.scrollHeight,
        }
    }, sel)
}

async function shot(name) {
    const path = join(OUT, `${name}.png`)
    await page.screenshot({ path, fullPage: true })
    log(`captured ${name}.png`)
}

async function viewportInfo() {
    return await page.evaluate(() => ({
        w: window.innerWidth, h: window.innerHeight,
        scrollW: document.documentElement.scrollWidth,
        scrollH: document.documentElement.scrollHeight,
        dpr: window.devicePixelRatio,
        mql: matchMedia('(max-width: 600px)').matches,
    }))
}

async function collectWidget(name, selectors) {
    const out = { viewport: await viewportInfo() }
    for (const [label, sel] of Object.entries(selectors)) {
        out[label] = await box(sel)
    }
    return out
}

try {
    log(`navigating to ${BASE_URL} ...`)
    await page.goto(BASE_URL, { waitUntil: 'domcontentloaded' })

    // LoadingScreen
    log('=== LoadingScreen ===')
    await page.waitForSelector('[data-testid="loading-screen-cta"]', { timeout: 15000 })
    await wait(800)
    findings.loading_screen = {
        ...await collectWidget('loading_screen', {
            root: '[data-testid="loading-screen-root"]',
            wordmark: '.loading-screen-wordmark',
            cta: '[data-testid="loading-screen-cta"]',
            footer: '.loading-screen-footer',
            progress: '.loading-screen-progress-track',
        }),
        dataMobileLayout: await page.getAttribute('[data-testid="loading-screen-root"]', 'data-mobile-layout'),
    }
    await shot('01-loading-screen')

    log('clicking LoadingScreen CTA to advance to MainMenu...')
    await page.click('[data-testid="loading-screen-cta"]')

    // MainMenu
    log('=== MainMenu ===')
    await page.waitForSelector('[data-testid="main-menu"]', { timeout: 30000 })
    await wait(800)
    findings.mainmenu = {
        ...await collectWidget('mainmenu', {
            root: '[data-testid="main-menu"]',
            header: '.main-menu-header',
            center: '.main-menu-center',
            bottom: '.main-menu-bottom',
            bottomInnerPanel: '.main-menu-bottom > div',
            play: '.btn-play',
            shop: '.main-menu-header button.btn-secondary-action',
            usage: '.main-menu-header .btn-secondary-action:nth-of-type(2)',
            options: '.btn-options',
            addCredits: '.btn-add-credits',
            friends: '.btn-friends',
            collection: '.main-menu-bottom button.btn-secondary-action',
            newCommander: '.main-menu-bottom .btn-secondary-action:nth-of-type(2)',
            displayName: '.display-name',
            versionText: '.version-text',
        }),
        dataMobileLayout: await page.getAttribute('[data-testid="main-menu"]', 'data-mobile-layout'),
    }
    await shot('02-main-menu')

    // NOTE: No z-index hack needed — the CSS now collapses .main-menu-bottom > div
    // (which contains Friends + inner action panel), so the bottom row doesn't
    // overlap with the header anymore. Shop/Usage/Options in the header are
    // clickable directly.

    // ShopOverlay — click the Shop button in the HEADER (not Collection/New Commander in the bottom row).
    log('=== ShopOverlay ===')
    await page.evaluate(() => {
        const btn = Array.from(document.querySelectorAll('.main-menu-header button'))
            .find(b => b.textContent.trim().includes('Shop'))
        btn?.click()
    })
    await page.waitForSelector('.billing-modal-window-host', { timeout: 5000 }).catch(() => log('Shop modal did not appear'))
    await wait(1500)
    findings.shop = await collectWidget('shop', {
        host: '.billing-modal-window-host',
        dialog: '.billing-modal-window-host .modal-dialog',
        header: '.billing-modal-header',
        body: '.billing-modal-body',
    })
    findings.shop.tabs = await page.evaluate(() => Array.from(document.querySelectorAll('.billing-tab')).map(t => {
        const r = t.getBoundingClientRect()
        return { x: Math.round(r.x), y: Math.round(r.y), w: Math.round(r.width), h: Math.round(r.height), text: t.textContent?.trim() }
    }))
    await shot('03-shop-overlay')
    await page.keyboard.press('Escape')
    await wait(800)

    // UsageOverlay — click the Usage button in the HEADER.
    log('=== UsageOverlay ===')
    await page.locator('.main-menu-header button:has-text("Usage")').first().click({ force: true })
    await page.waitForSelector('.billing-modal-window-host', { timeout: 5000 }).catch(() => log('Usage modal did not appear'))
    await wait(1500)
    findings.usage = await collectWidget('usage', {
        host: '.billing-modal-window-host',
        dialog: '.billing-modal-window-host .modal-dialog',
    })
    findings.usage.meterRows = await page.evaluate(() => Array.from(document.querySelectorAll('.usage-meter-row')).map(m => {
        const r = m.getBoundingClientRect()
        return { x: Math.round(r.x), y: Math.round(r.y), w: Math.round(r.width), h: Math.round(r.height) }
    }))
    findings.usage.tabs = await page.evaluate(() => Array.from(document.querySelectorAll('.billing-tab')).map(t => {
        const r = t.getBoundingClientRect()
        return { x: Math.round(r.x), y: Math.round(r.y), w: Math.round(r.width), h: Math.round(r.height), text: t.textContent?.trim() }
    }))
    await shot('04-usage-overlay')
    await page.keyboard.press('Escape')
    await wait(800)

    // SettingsWidget
    log('=== SettingsWidget ===')
    await page.click('.btn-options', { force: true })
    await wait(2000)
    findings.settings = await collectWidget('settings', {
        root: '[data-testid="settings-widget-root"]',
        window: '.login-widget-window',
    })
    await shot('05-settings-widget')
    const settingsClose = await page.locator('[data-testid="settings-widget-root"] button:has-text("CLOSE")').first()
    if (await settingsClose.count() > 0) await settingsClose.click({ force: true })
    await wait(800)

    // CollectionOverlay
    log('=== CollectionOverlay ===')
    await page.click('button:has-text("Collection")', { force: true })
    await wait(2000)
    findings.collection = await collectWidget('collection', {
        overlay: '.collection-overlay',
        window: '.collection-window',
        content: '.collection-content',
    })
    findings.collection.tabs = await page.evaluate(() => Array.from(document.querySelectorAll('.collection-tab-button')).map(t => {
        const r = t.getBoundingClientRect()
        return { x: Math.round(r.x), y: Math.round(r.y), w: Math.round(r.width), h: Math.round(r.height), text: t.textContent?.trim() }
    }))
    await shot('06-collection-overlay')
    await page.locator('.btn-close-collection').click({ force: true })
    await wait(800)

    // CommanderCreationDialog
    log('=== CommanderCreationDialog ===')
    await page.click('button:has-text("New Commander")', { force: true })
    await wait(2000)
    findings.commander_creation = await collectWidget('commander_creation', {
        root: '[data-testid="commander-creation-root"]',
        overlay: '.commander-creation-overlay',
        dialog: '.commander-creation-dialog',
        play: '.commander-creation-dialog .btn-play',
    })
    findings.commander_creation.textInputs = await page.evaluate(() => Array.from(document.querySelectorAll('.commander-creation-dialog input[type="text"], .commander-creation-dialog textarea')).map(t => {
        const r = t.getBoundingClientRect()
        return { x: Math.round(r.x), y: Math.round(r.y), w: Math.round(r.width), h: Math.round(r.height), placeholder: t.placeholder }
    }))
    await shot('07-commander-creation')
    const ccClose = await page.locator('.commander-creation-dialog button:has-text("Cancel"), .commander-creation-overlay button:has-text("Cancel")').first()
    if (await ccClose.count() > 0) await ccClose.click({ force: true })
    else await page.keyboard.press('Escape')
    await wait(800)

    // Probe-only — these dialogs may not appear under skipLogin but their selectors must exist
    log('=== ResumeOrNewDialog / MessageBox / SurrenderConfirmDialog (probe-only) ===')
    findings.resume_dialog = {
        viewport: await viewportInfo(),
        rootExists: await page.evaluate(() => !!document.querySelector('[data-testid="resume-or-new-dialog-root"]')),
        root: await box('[data-testid="resume-or-new-dialog-root"]'),
    }
    findings.message_box = {
        viewport: await viewportInfo(),
        rootExists: await page.evaluate(() => !!document.querySelector('[data-testid="message-box-root"]')),
        root: await box('[data-testid="message-box-root"]'),
    }
    findings.surrender_dialog = {
        viewport: await viewportInfo(),
        rootExists: await page.evaluate(() => !!document.querySelector('[data-testid="surrender-confirm-dialog-root"]')),
        root: await box('[data-testid="surrender-confirm-dialog-root"]'),
    }

    await shot('99-final-mainmenu')
    await writeFile(join(OUT, 'diagnostic.json'), JSON.stringify(findings, null, 2))
    log(`wrote diagnostic.json with ${Object.keys(findings).length} widget entries`)
} catch (err) {
    console.error('DIAGNOSTIC CRASHED:', err.message)
    await page.screenshot({ path: join(OUT, 'CRASH.png'), fullPage: true }).catch(() => {})
    await writeFile(join(OUT, 'diagnostic-crash.json'), JSON.stringify({ error: err.message, findings }, null, 2))
    process.exit(1)
} finally {
    await browser.close()
}
log('done')
