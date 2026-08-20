// Probe for the "preview action wipes in-flight form values" bug.
//
// The bug is that several preview-related handlers force a full modal
// re-render (`lastRenderedModal = null` + `rebuildAll(clearError=true)`).
// The re-render rebuilds the form from STATE, not from the DOM, so any
// unsaved edits the user typed are destroyed.
//
// We trigger the bug via the always-available "Play All" button on the
// current tab (preview-play-all-category), which hits the same code
// path as the per-track Play/Stop/Clear/PlayAllGlobal handlers.

import { chromium } from '@playwright/test'

const BASE = process.env.BASE || 'http://127.0.0.1:18080'

async function readForm(page) {
    return page.evaluate(() => {
        const v = (id) => {
            const el = document.getElementById(id)
            return el && 'value' in el ? el.value : null
        }
        const c = (id) => {
            const el = document.getElementById(id)
            return el && 'checked' in el ? el.checked : null
        }
        return {
            resourceName: v('f-resourceName'),
            channelId: v('f-channelId'),
            volume: v('f-volume'),
            panning: v('f-panning'),
            speed: v('f-speed'),
            loop: c('f-loop'),
        }
    })
}

const failures = []
const passes = []

const browser = await chromium.launch({ headless: true })
try {
    const ctx = await browser.newContext()
    const page = await ctx.newPage()
    page.on('pageerror', (e) => console.log('PAGEERROR:', e.message))

    await page.goto(BASE + '/index.html', { waitUntil: 'networkidle' })
    await page.locator('h1', { hasText: 'Audio Tracks Editor' }).waitFor()

    // ---- Phase A: DRONE tab, Play All Category wipes form.
    await page.getByTestId('load-sample-data-link').click()
    await page.getByTestId('tab-DRONE').click()
    await page.locator('button[data-action="edit-track"]').first().click()
    await page.getByTestId('edit-modal').waitFor()

    const ORIGINAL = await readForm(page)
    console.log('[PHASE-A.original]', JSON.stringify(ORIGINAL))

    const EDITED_NAME = 'edited.before.playall.drone'
    const EDITED_CHANNEL = 'Sfx'
    const EDITED_VOLUME = '0.55'
    const EDITED_LOOP = true
    await page.locator('#f-resourceName').fill(EDITED_NAME)
    await page.locator('#f-channelId').fill(EDITED_CHANNEL)
    await page.locator('#f-volume').evaluate((el, v) => {
        el.value = v
        el.dispatchEvent(new Event('input', { bubbles: true }))
    }, EDITED_VOLUME)
    await page.locator('#f-loop').check()

    const AFTER_EDIT = await readForm(page)
    console.log('[PHASE-A.after-edit]', JSON.stringify(AFTER_EDIT))

    // The "▶ Play All" button on the tab header hits preview-play-all-category,
    // which forces lastRenderedModal=null and rebuildAll(clearError=true).
    // That's the same code path as the per-track Play/Stop handlers.
    await page.getByTestId('play-all-category').evaluate(b => b.click())
    await page.waitForTimeout(150)
    const AFTER_PLAYALL = await readForm(page)
    console.log('[PHASE-A.after-playall]', JSON.stringify(AFTER_PLAYALL))

    const matchesEdited =
        AFTER_PLAYALL.resourceName === EDITED_NAME &&
        AFTER_PLAYALL.channelId === EDITED_CHANNEL &&
        AFTER_PLAYALL.volume === EDITED_VOLUME &&
        AFTER_PLAYALL.loop === EDITED_LOOP
    const matchesOriginal = JSON.stringify(AFTER_PLAYALL) === JSON.stringify(ORIGINAL)
    if (matchesEdited) {
        passes.push('phase-A: form preserved across preview-play-all-category')
    } else {
        failures.push({
            phase: 'phase-A-drone-playall',
            msg: `BUG: clicking "Play All" wiped the in-flight form values. ` +
                 `${matchesOriginal ? 'Form was reset to the original (pre-edit) state values.'
                                    : 'Form is in an unexpected intermediate state.'}`,
            original: ORIGINAL,
            afterEdit: AFTER_EDIT,
            afterPlayall: AFTER_PLAYALL,
            expectedEdits: { resourceName: EDITED_NAME, channelId: EDITED_CHANNEL, volume: EDITED_VOLUME, loop: EDITED_LOOP }
        })
    }

    // Stop the side-by-side preview that Play All started so it doesn't
    // leak into the next phase.
    const stopAllVisible = await page.getByTestId('stop-all-category').isVisible().catch(() => false)
    if (stopAllVisible) {
        await page.getByTestId('stop-all-category').evaluate(b => b.click())
        await page.waitForTimeout(100)
    }

    // ---- Phase B: MENU tab — same probe, also covers the scenario path.
    await page.getByTestId('modal-cancel').click()
    await page.locator('#edit-modal').waitFor({ state: 'detached' })

    await page.getByTestId('tab-MENU').click()
    await page.locator('button[data-action="edit-track"]').first().click()
    await page.getByTestId('edit-modal').waitFor()

    const ORIGINAL_B = await readForm(page)
    console.log('[PHASE-B.original]', JSON.stringify(ORIGINAL_B))

    const EDITED_NAME_B = 'edited.before.playall.menu'
    const EDITED_VOLUME_B = '0.77'
    await page.locator('#f-resourceName').fill(EDITED_NAME_B)
    await page.locator('#f-volume').evaluate((el, v) => {
        el.value = v
        el.dispatchEvent(new Event('input', { bubbles: true }))
    }, EDITED_VOLUME_B)

    await page.getByTestId('play-all-category').evaluate(b => b.click())
    await page.waitForTimeout(150)
    const AFTER_PLAYALL_B = await readForm(page)
    console.log('[PHASE-B.after-playall]', JSON.stringify(AFTER_PLAYALL_B))
    if (AFTER_PLAYALL_B.resourceName === EDITED_NAME_B && AFTER_PLAYALL_B.volume === EDITED_VOLUME_B) {
        passes.push('phase-B: form preserved across preview-play-all-category on MENU')
    } else {
        failures.push({
            phase: 'phase-B-menu-playall',
            msg: `BUG: clicking "Play All" on MENU wiped the in-flight form.`,
            original: ORIGINAL_B, after: AFTER_PLAYALL_B,
            expected: { resourceName: EDITED_NAME_B, volume: EDITED_VOLUME_B }
        })
    }
    const stopAllB = await page.getByTestId('stop-all-category').isVisible().catch(() => false)
    if (stopAllB) {
        await page.getByTestId('stop-all-category').evaluate(b => b.click())
        await page.waitForTimeout(100)
    }

    // ---- Phase C: Global Play All (the top-bar button) — same code path.
    await page.getByTestId('modal-cancel').click()
    await page.locator('#edit-modal').waitFor({ state: 'detached' })

    await page.getByTestId('tab-DRONE').click()
    await page.locator('button[data-action="edit-track"]').first().click()
    await page.getByTestId('edit-modal').waitFor()
    const ORIGINAL_C = await readForm(page)
    console.log('[PHASE-C.original]', JSON.stringify(ORIGINAL_C))

    const EDITED_NAME_C = 'edited.before.playallglobal'
    await page.locator('#f-resourceName').fill(EDITED_NAME_C)
    await page.locator('#f-volume').evaluate((el, v) => {
        el.value = v
        el.dispatchEvent(new Event('input', { bubbles: true }))
    }, '0.11')

    await page.getByTestId('play-all-global').evaluate(b => b.click())
    await page.waitForTimeout(150)
    const AFTER_PLAYALLG = await readForm(page)
    console.log('[PHASE-C.after-playallglobal]', JSON.stringify(AFTER_PLAYALLG))
    if (AFTER_PLAYALLG.resourceName === EDITED_NAME_C && AFTER_PLAYALLG.volume === '0.11') {
        passes.push('phase-C: form preserved across preview-play-all-global')
    } else {
        failures.push({
            phase: 'phase-C-global-playall',
            msg: `BUG: clicking "Play All (global)" wiped the in-flight form.`,
            original: ORIGINAL_C, after: AFTER_PLAYALLG,
            expected: { resourceName: EDITED_NAME_C, volume: '0.11' }
        })
    }
    const stopAllC = await page.getByTestId('stop-all-global').isVisible().catch(() => false)
    if (stopAllC) {
        await page.getByTestId('stop-all-global').evaluate(b => b.click())
        await page.waitForTimeout(100)
    }

} finally {
    await browser.close()
}

console.log('\n========== PROBE SUMMARY ==========')
console.log(`PASSES (${passes.length}):`)
for (const p of passes) console.log('  ✓', p)
console.log(`\nFAILURES (${failures.length}):`)
for (const f of failures) {
    console.log('  ✗', f.phase, '—', f.msg)
    if (f.afterPlayall) console.log('     afterPlayall:', JSON.stringify(f.afterPlayall))
    if (f.after) console.log('     after:', JSON.stringify(f.after))
    if (f.expected) console.log('     expected:', JSON.stringify(f.expected))
    if (f.expectedEdits) console.log('     expectedEdits:', JSON.stringify(f.expectedEdits))
}
console.log('\n========== VERDICT ==========')
if (failures.length === 0) {
    console.log('NO BUG DETECTED.')
    process.exit(0)
} else {
    console.log(`BUG CONFIRMED. ${failures.length} failure(s).`)
    process.exit(1)
}