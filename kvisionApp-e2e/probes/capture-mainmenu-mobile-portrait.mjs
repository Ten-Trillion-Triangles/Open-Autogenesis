// kvisionApp-e2e/probes/capture-mainmenu-mobile-portrait.mjs
//
// Captures portrait-mode screenshots of every widget this plan modified.
// Uses ?skipLogin=true to bypass the AccelByte login flow (which would hang
// waiting for the backend that's not available in this static-bundle env).
//
// Pre-req: static-server-8080.mjs running at http://127.0.0.1:8080.
//          Build at kvisionApp/build/dist/js/productionExecutable/ must be
//          fresh (post-this-plan).

import { chromium, devices } from '@playwright/test'
import { mkdir } from 'node:fs/promises'
import { join } from 'node:path'

const BASE_URL = 'http://127.0.0.1:8080/index.html?skipLogin=true'
const OUT_DIR = '/home/cage/Desktop/Workspaces/Autogenesis/screenshots/2026-07-10-mainmenu-mobile'
await mkdir(OUT_DIR, { recursive: true })

const log = (msg) => console.log(`[shot] ${msg}`)
const wait = (ms) => new Promise(r => setTimeout(r, ms))

const browser = await chromium.launch()
const context = await browser.newContext({
    ...devices['iPhone 12'],
})
const page = await context.newPage()

page.on('pageerror', err => console.error('PAGE-ERROR:', err.message))

async function shot(label) {
    const path = join(OUT_DIR, `${label}.png`)
    await page.screenshot({ path, fullPage: true })
    log(`captured ${label}.png`)
}

async function dumpLayout(selector) {
    if (!selector) return null
    return await page.evaluate((sel) => {
        const el = document.querySelector(sel)
        if (!el) return null
        const r = el.getBoundingClientRect()
        return {
            width: Math.round(r.width),
            height: Math.round(r.height),
            layout: el.getAttribute('data-mobile-layout'),
            testid: el.getAttribute('data-testid'),
        }
    }, selector)
}

async function dumpMetrics() {
    return await page.evaluate(() => ({
        viewportWidth: window.innerWidth,
        viewportHeight: window.innerHeight,
        scrollWidth: document.documentElement.scrollWidth,
        scrollHeight: document.documentElement.scrollHeight,
    }))
}

try {
    log('navigating with skipLogin=true...')
    await page.goto(BASE_URL, { waitUntil: 'domcontentloaded' })

    // LoadingScreen is rendered first regardless. Click CTA to advance.
    log('waiting for LoadingScreen CTA...')
    await page.waitForSelector('[data-testid="loading-screen-cta"]', { timeout: 15000 })
    await wait(500)
    const loadingInfo = await dumpLayout('[data-testid="loading-screen-root"]')
    log(`loading-screen: ${JSON.stringify(loadingInfo)}`)
    await shot('01-loading-screen')
    await page.click('[data-testid="loading-screen-cta"]')
    log('clicked CTA')

    // After CTA click, MainMenu mounts directly (skipLogin bypasses login screen).
    log('waiting for MainMenu...')
    await page.waitForSelector('[data-testid="main-menu"]', { timeout: 30000 })
    await page.waitForFunction(
        () => document.querySelector('[data-testid="main-menu"]')?.hasAttribute('data-mobile-layout'),
        { timeout: 5000 }
    )
    await wait(800)  // Let layout settle
    const mmInfo = await dumpLayout('[data-testid="main-menu"]')
    log(`main-menu: ${JSON.stringify(mmInfo)}`)
    await shot('02-main-menu')

    const mainMenuMetrics = await page.evaluate(() => {
        const grab = (sel) => {
            const el = document.querySelector(sel)
            if (!el) return null
            const r = el.getBoundingClientRect()
            return { w: Math.round(r.width), h: Math.round(r.height) }
        }
        return {
            play: grab('.btn-play'),
            secondary: grab('.main-menu .btn-secondary-action'),
            options: grab('.btn-options'),
            addCredits: grab('.btn-add-credits'),
            friends: grab('.btn-friends'),
            header: grab('.main-menu-header'),
            center: grab('.main-menu-center'),
            bottom: grab('.main-menu-bottom'),
            metrics: {
                viewportW: window.innerWidth,
                viewportH: window.innerHeight,
                scrollW: document.documentElement.scrollWidth,
            },
        }
    })
    log(`main-menu metrics: ${JSON.stringify(mainMenuMetrics)}`)

    // ShopOverlay
    log('opening Shop...')
    await page.click('button:has-text("Shop")')
    await wait(2000)
    const shopInfo = await dumpLayout('.billing-modal-window-host')
    log(`shop-overlay: ${JSON.stringify(shopInfo)}`)
    await shot('03-shop-overlay')
    const shopMetrics = await page.evaluate(() => {
        const tabs = Array.from(document.querySelectorAll('.billing-tab')).map(t => {
            const r = t.getBoundingClientRect()
            return { w: Math.round(r.width), h: Math.round(r.height), text: t.textContent?.trim() }
        })
        const dialog = document.querySelector('.billing-modal-window-host .modal-dialog')
        const dr = dialog?.getBoundingClientRect()
        return {
            tabs,
            dialog: dr ? { w: Math.round(dr.width), h: Math.round(dr.height) } : null,
            modalMetrics: {
                viewportW: window.innerWidth,
                scrollW: document.documentElement.scrollWidth,
            },
        }
    })
    log(`shop metrics: ${JSON.stringify(shopMetrics)}`)
    await page.keyboard.press('Escape')
    await wait(800)

    // UsageOverlay
    log('opening Usage...')
    await page.click('button:has-text("Usage")')
    await wait(2000)
    const usageInfo = await dumpLayout('.billing-modal-window-host')
    log(`usage-overlay: ${JSON.stringify(usageInfo)}`)
    await shot('04-usage-overlay')
    const usageMetrics = await page.evaluate(() => {
        const meters = document.querySelectorAll('.usage-meter-row').length
        const tabs = Array.from(document.querySelectorAll('.usage-tab-strip .billing-tab, .billing-tab')).map(t => {
            const r = t.getBoundingClientRect()
            return { w: Math.round(r.width), h: Math.round(r.height), text: t.textContent?.trim() }
        })
        return { meterRowCount: meters, tabs }
    })
    log(`usage metrics: ${JSON.stringify(usageMetrics)}`)
    await page.keyboard.press('Escape')
    await wait(800)

    // SettingsWidget
    log('opening Settings...')
    await page.click('.btn-options')
    await wait(2000)
    const settingsInfo = await dumpLayout('[data-testid="settings-widget-root"]')
    log(`settings-widget: ${JSON.stringify(settingsInfo)}`)
    await shot('05-settings-widget')
    // SettingsWidget is a SimplePanel (no Modal escape handler); click its CLOSE button.
    await page.locator('[data-testid="settings-widget-root"] button:has-text("CLOSE")').click({ force: true })
    await wait(800)

    // CollectionOverlay
    log('opening Collection...')
    await page.click('button:has-text("Collection")')
    await wait(2000)
    const collInfo = await dumpLayout('.collection-overlay')
    log(`collection-overlay: ${JSON.stringify(collInfo)}`)
    await shot('06-collection-overlay')
    const collMetrics = await page.evaluate(() => {
        const tabs = Array.from(document.querySelectorAll('.collection-tab-button')).map(t => {
            const r = t.getBoundingClientRect()
            return { w: Math.round(r.width), h: Math.round(r.height), active: t.classList.contains('collection-tab-button-active') }
        })
        const overlay = document.querySelector('.collection-overlay')
        const or = overlay?.getBoundingClientRect()
        return {
            tabs,
            overlay: or ? { w: Math.round(or.width), h: Math.round(or.height) } : null,
        }
    })
    log(`collection metrics: ${JSON.stringify(collMetrics)}`)
    // CollectionOverlay has an icon-X close button (.btn-close-collection).
    await page.locator('.btn-close-collection').click({ force: true })
    await wait(800)

    // CommanderCreationDialog
    log('opening New Commander...')
    await page.click('button:has-text("New Commander")')
    await wait(2000)
    const createInfo = await dumpLayout('[data-testid="commander-creation-root"]')
    log(`commander-creation: ${JSON.stringify(createInfo)}`)
    await shot('07-commander-creation')
    const createMetrics = await page.evaluate(() => {
        const grab = (sel) => {
            const el = document.querySelector(sel)
            if (!el) return null
            const r = el.getBoundingClientRect()
            return { w: Math.round(r.width), h: Math.round(r.height) }
        }
        return {
            input: grab('.commander-creation-dialog input[type="text"]'),
            back: grab('.commander-creation-dialog .btn-secondary-action'),
            create: grab('.commander-creation-dialog .btn-play'),
            dialog: grab('.commander-creation-dialog'),
        }
    })
    log(`commander-creation metrics: ${JSON.stringify(createMetrics)}`)
    // CommanderCreationDialog is a SimplePanel; click BACK button to close.
    await page.locator('[data-testid="commander-creation-root"] button:has-text("BACK")').click({ force: true })
    await wait(800)

    // PLAY flow → CommanderSelectionDialog (resume dialog may appear if WS pushes it)
    log('clicking PLAY...')
    await page.click('.btn-play')
    await wait(3000)
    const onScreen = await page.evaluate(() => ({
        resume: !!document.querySelector('[data-testid="resume-or-new-dialog"]'),
        commanderSelection: !!document.querySelector('[data-testid="commander-selection-root"]'),
        messageBox: !!document.querySelector('[data-testid="autogenesis-message-box-overlay"]'),
    }))
    log(`after PLAY: ${JSON.stringify(onScreen)}`)

    if (onScreen.resume) {
        await shot('08-resume-or-new-dialog')
        const resumeMetrics = await page.evaluate(() => {
            const btns = Array.from(document.querySelectorAll('[data-testid="resume-or-new-dialog"] button')).map(b => {
                const r = b.getBoundingClientRect()
                return { w: Math.round(r.width), h: Math.round(r.height), text: b.textContent?.trim() }
            })
            const dialog = document.querySelector('[data-testid="resume-or-new-dialog"]')
            const dr = dialog?.getBoundingClientRect()
            return {
                buttons: btns,
                dialog: dr ? { w: Math.round(dr.width), h: Math.round(dr.height) } : null,
            }
        })
        log(`resume-or-new metrics: ${JSON.stringify(resumeMetrics)}`)
        await page.locator('[data-testid="resume-or-new-dialog"] button:has-text("Cancel")').first().click({ force: true })
        await wait(800)
    }
    if (onScreen.commanderSelection) {
        await shot('09-commander-selection')
        const selMetrics = await page.evaluate(() => {
            const cards = Array.from(document.querySelectorAll('.commander-selection-card')).map(c => {
                const r = c.getBoundingClientRect()
                return { w: Math.round(r.width), h: Math.round(r.height) }
            })
            return { cardCount: cards.length, cards }
        })
        log(`commander-selection metrics: ${JSON.stringify(selMetrics)}`)
        await page.keyboard.press('Escape')
        await wait(800)
    }
    if (onScreen.messageBox) {
        await shot('10-message-box')
        const mbMetrics = await page.evaluate(() => {
            const dialog = document.querySelector('.autogenesis-message-box-content')
            const dr = dialog?.getBoundingClientRect()
            const btns = Array.from(document.querySelectorAll('.autogenesis-message-box-content button')).map(b => {
                const r = b.getBoundingClientRect()
                return { w: Math.round(r.width), h: Math.round(r.height), text: b.textContent?.trim() }
            })
            return {
                content: dr ? { w: Math.round(dr.width), h: Math.round(dr.height) } : null,
                buttons: btns,
            }
        })
        log(`message-box metrics: ${JSON.stringify(mbMetrics)}`)
    }

    // Final clean MainMenu shot.
    // Bug 2026-07-11: page.goto(BASE_URL) does NOT remount the SPA — KVision
    // virtual DOM keeps the CommanderCreationDialog mounted from the prior
    // step, so the "final" shot was the same dialog frame. Force a hard reload
    // by navigating away first (about:blank) so the new context is a clean
    // boot, then wait for loading + main-menu in sequence.
    log('final MainMenu shot...')
    await page.goto('about:blank', { waitUntil: 'domcontentloaded' })
    await page.goto(BASE_URL, { waitUntil: 'domcontentloaded' })
    await page.waitForSelector('[data-testid="loading-screen-cta"]', { timeout: 15000 })
    await page.click('[data-testid="loading-screen-cta"]')
    await page.waitForSelector('[data-testid="main-menu"]', { timeout: 30000 })
    await page.waitForFunction(
        () => document.querySelector('[data-testid="main-menu"]')?.hasAttribute('data-mobile-layout'),
        { timeout: 5000 }
    )
    await wait(800)
    await shot('99-main-menu-final')

    log(`done — screenshots in ${OUT_DIR}`)
}
catch (err) {
    console.error('CAPTURE FAILED:', err.message)
    console.error(err.stack)
    await shot('CRASH-STATE').catch(() => {})
    process.exit(1)
}
finally {
    await browser.close()
}