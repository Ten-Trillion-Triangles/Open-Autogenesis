#!/usr/bin/env node
// hermes-verify-multi-viewport-20260712.mjs
//
// AD-HOC: probe the MainMenu header layout at 5 portrait viewports
// (320, 375, 390x664, 390x844, 430) and assert:
//   - gear.right <= viewport.width (no clip)
//   - scrollW <= viewport.width (no horizontal overflow)
//   - header-row btn-secondary-action height is compact (32-44px)
//
// Run after applying night-mode.css edits. Replaces the earlier
// _multi-viewport-shot.mjs scratch script.

import { chromium } from '@playwright/test'
import { mkdir } from 'node:fs/promises'
import { join } from 'node:path'

const BASE_URL = 'http://127.0.0.1:8080/index.html?skipLogin=true'
const OUT_DIR = '/home/cage/Desktop/Workspaces/Autogenesis/screenshots/2026-07-12-mainmenu-mobile-fix'
await mkdir(OUT_DIR, { recursive: true })

const VIEWPORTS = [
    { name: '320x568-iphone-se-1st',  w: 320, h: 568 },
    { name: '375x667-iphone-se-3rd',  w: 375, h: 667 },
    { name: '390x664-iphone-12-mini',  w: 390, h: 664 },
    { name: '390x844-iphone-12',       w: 390, h: 844 },
    { name: '430x932-iphone-14-pro-max', w: 430, h: 932 },
]

const FAILURES = []
function check(name, condition, detail = '')
{
    if (condition) console.log(`PASS: ${name}`)
    else { console.log(`FAIL: ${name} ${detail}`); FAILURES.push(name) }
}

const browser = await chromium.launch()

for (const v of VIEWPORTS) {
    const context = await browser.newContext({
        viewport: { width: v.w, height: v.h },
        deviceScaleFactor: 3,
        isMobile: true,
        hasTouch: true,
    })
    const page = await context.newPage()
    try {
        await page.goto(BASE_URL, { waitUntil: 'domcontentloaded' })
        await page.waitForSelector('[data-testid="loading-screen-cta"]', { timeout: 15000 })
        await page.click('[data-testid="loading-screen-cta"]')
        await page.waitForSelector('[data-testid="main-menu"]', { timeout: 30000 })
        await page.waitForFunction(
            () => document.querySelector('[data-testid="main-menu"]')?.hasAttribute('data-mobile-layout'),
            { timeout: 5000 }
        )
        await new Promise(r => setTimeout(r, 800))

        const m = await page.evaluate(() => {
            const grab = (sel) => {
                const el = document.querySelector(sel)
                if (!el) return null
                const r = el.getBoundingClientRect()
                return { w: Math.round(r.width), h: Math.round(r.height), left: Math.round(r.left), right: Math.round(r.right), top: Math.round(r.top) }
            }
            const grabAll = (sel) => Array.from(document.querySelectorAll(sel)).map(el => {
                const r = el.getBoundingClientRect()
                return { w: Math.round(r.width), h: Math.round(r.height), left: Math.round(r.left), right: Math.round(r.right) }
            })
            const creditsEl = document.querySelector('.credits-container')
            const creditsHidden = (() => {
                if (!creditsEl) return true
                const cs = window.getComputedStyle(creditsEl)
                return cs.display === 'none'
            })()
            const wordmarkEl = document.querySelector('#kvapp')
            const bgInfo = (() => {
                if (!wordmarkEl) return null
                const cs = window.getComputedStyle(wordmarkEl)
                return { position: cs.backgroundPosition, size: cs.backgroundSize }
            })()
            return {
                viewport: { w: window.innerWidth, h: window.innerHeight },
                scrollW: document.documentElement.scrollWidth,
                header: grab('.main-menu-header'),
                gear: grab('.btn-options'),
                creditsPill: grab('.credits-container'),
                creditsHidden,
                headerSecondaries: grabAll('.main-menu-header .btn-secondary-action'),
                play: grab('.btn-play'),
                bottomRowSibling: (() => {
                    // COLLECTION is the first .btn-secondary-action inside .main-menu-bottom
                    const el = document.querySelector('.main-menu-bottom .btn-secondary-action')
                    if (!el) return null
                    const r = el.getBoundingClientRect()
                    return { left: Math.round(r.left), right: Math.round(r.right), w: Math.round(r.width), h: Math.round(r.height) }
                })(),
                bg: bgInfo,
            }
        })

        console.log(`\n[${v.name} ${v.w}x${v.h}]`)
        console.log(`  bg=${m.bg?.position}  scrollW=${m.scrollW}  gear.right=${m.gear?.right}`)
        console.log(`  credits=${m.creditsPill?.w}x${m.creditsPill?.h}  secondaries=${m.headerSecondaries.length}`)

        check(`[${v.name}] no horizontal overflow`,
              m.scrollW <= m.viewport.w + 1,
              `(scrollW=${m.scrollW}, viewportW=${m.viewport.w})`)
        check(`[${v.name}] gear visible (not clipped)`,
              m.gear && m.gear.right <= m.viewport.w + 1,
              `(gear.right=${m.gear?.right}, viewportW=${m.viewport.w})`)
        check(`[${v.name}] gear has positive width`,
              m.gear && m.gear.w > 0,
              `(gear.w=${m.gear?.w})`)
        check(`[${v.name}] gear widened to at least 44px (mobile tap target)`,
              m.gear && m.gear.w >= 44 && m.gear.w <= 48,
              `(gear.w=${m.gear?.w})`)
        check(`[${v.name}] credits-pill is hidden (display:none)`,
              m.creditsHidden === true,
              `(creditsHidden=${m.creditsHidden})`)
        /* gear.right should be at viewport.w - header_padding_right (28px) -
           right-cluster internal right padding (~15px) ≈ viewport.w - 43. */
        check(`[${v.name}] gear pinned to right side of header panel`,
              m.gear && Math.abs(m.gear.right - (m.viewport.w - 43)) <= 8,
              `(gear.right=${m.gear?.right}, expected ≈ viewport.w-43=${m.viewport.w - 43})`)
        check(`[${v.name}] PLAY left-aligned with bottom-row siblings`,
              Math.abs(m.play.left - m.bottomRowSibling.left) <= 2,
              `(play.left=${m.play.left}, sibling.left=${m.bottomRowSibling.left}, delta=${m.play.left - m.bottomRowSibling.left})`)
        m.headerSecondaries.forEach((s, i) => {
            check(`[${v.name}] header-secondary #${i} compact (32-44px tall)`,
                  s.h >= 32 && s.h <= 44,
                  `(got: ${s.h}px)`)
        })

        await page.screenshot({ path: join(OUT_DIR, `mainmenu-${v.name}.png`), fullPage: false })
    } catch (err) {
        console.error(`[${v.name}] PROBE CRASHED: ${err.message}`)
        FAILURES.push(`[${v.name}] crashed: ${err.message}`)
    } finally {
        await context.close()
    }
}

await browser.close()

if (FAILURES.length > 0) {
    console.error(`\n${FAILURES.length} check(s) failed:\n  - ${FAILURES.join('\n  - ')}`)
    process.exit(1)
}
console.log('\nAll multi-viewport checks passed.')
