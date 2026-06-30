// Direct-chromium probe of the audioTracksEditor scenario-tab bug.
//
// We avoid calling window.__editor.getState() because the Kotlin/JS
// production build minifies function names. Instead we observe the bug
// from the DOM: the modal pre-fills (or doesn't pre-fill) the form
// fields when an Edit button is clicked.

import { chromium } from '@playwright/test'

const BASE = process.env.BASE || 'http://127.0.0.1:18080'

async function readModal(page) {
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
            startTimeMs: v('f-startTimeMs'),
            fadeInDurationMs: v('f-fadeInDurationMs'),
            fadeOutDurationMs: v('f-fadeOutDurationMs'),
        }
    })
}

function defaultDraft() {
    // Matches EditorState.newDraftTrack: blank resourceName, channelId=Music,
    // volume=1.0, panning=0, speed=1, loop=false, startTimeMs=0, fades=0.
    return {
        resourceName: '',
        channelId: 'Music',
        volume: '1',
        panning: '0',
        speed: '1',
        loop: false,
        startTimeMs: '0',
        fadeInDurationMs: '0',
        fadeOutDurationMs: '0',
    }
}

function modalsEqual(a, b) {
    return JSON.stringify(a) === JSON.stringify(b)
}

const SCENARIO_TABS = ['MENU', 'START', 'NEMESIS', 'END']
const LAYER_TABS = ['DRONE', 'MELODY', 'RHYTHM', 'HARMONY']
const failures = []
const passes = []

const browser = await chromium.launch({ headless: true })
try {
    const ctx = await browser.newContext()
    const page = await ctx.newPage()
    page.on('pageerror', (e) => console.log('PAGEERROR:', e.message))
    page.on('console', (m) => { if (m.type() === 'error') console.log('CONSOLE.error:', m.text()) })

    await page.goto(BASE + '/index.html', { waitUntil: 'networkidle' })
    await page.locator('h1', { hasText: 'Audio Tracks Editor' }).waitFor()

    // Load sample data so every category has exactly one track.
    await page.getByTestId('load-sample-data-link').click()
    await page.waitForTimeout(200)

    // ---- PHASE 1: open Edit on a sample-data track in every category.
    for (const tab of [...SCENARIO_TABS, ...LAYER_TABS]) {
        await page.getByTestId(`tab-${tab}`).click()
        await page.waitForTimeout(50)
        await page.locator('button[data-action="edit-track"]').first().click()
        await page.getByTestId('edit-modal').waitFor()
        await page.waitForTimeout(50)
        const modal = await readModal(page)
        const draft = defaultDraft()
        const matchesDraft = modalsEqual(modal, draft)
        const verdict = matchesDraft ? 'BLANK_DRAFT' : 'PRE_FILLED_WITH_TRACK'
        console.log(`[PHASE1.${tab}] verdict=${verdict} modal=${JSON.stringify(modal)}`)
        // For every category, opening Edit on the sample track must pre-fill
        // the form. If the modal shows the default draft, findTrackById failed.
        if (matchesDraft) {
            failures.push({
                phase: `open-edit-sample-${tab}`,
                msg: `BUG: opening Edit on the ${tab} sample track shows a blank default draft instead of the saved track values.`,
                modal
            })
        } else {
            passes.push(`open-edit-sample-${tab}: modal pre-filled (resourceName="${modal.resourceName}")`)
        }
        await page.getByTestId('modal-cancel').click()
        await page.locator('#edit-modal').waitFor({ state: 'detached' })
    }

    // ---- PHASE 2: round-trip — create a track with distinctive values,
    // save, reopen, confirm values survive (for both scenario and layer tabs).
    for (const tab of [...SCENARIO_TABS, ...LAYER_TABS.slice(0, 1)]) {
        await page.getByTestId(`tab-${tab}`).click()
        await page.getByTestId('new-track-button').click()
        await page.getByTestId('edit-modal').waitFor()

        const NAME = `probe.${tab.toLowerCase()}.roundtrip`
        const CHANNEL = 'Sfx'
        const VOLUME = '0.42'
        await page.locator('#f-resourceName').fill(NAME)
        await page.locator('#f-channelId').fill(CHANNEL)
        await page.locator('#f-volume').evaluate((el, v) => {
            el.value = v
            el.dispatchEvent(new Event('input', { bubbles: true }))
        }, VOLUME)
        await page.getByTestId('modal-save').click()
        await page.getByText(NAME).waitFor()

        // Find the Edit button for the new track (last in the list).
        const rowCount = await page.locator('button[data-action="edit-track"]').count()
        await page.locator('button[data-action="edit-track"]').nth(rowCount - 1).click()
        await page.getByTestId('edit-modal').waitFor()
        await page.waitForTimeout(50)
        const reopened = await readModal(page)
        console.log(`[PHASE2.${tab}.reopen] modal=${JSON.stringify(reopened)}`)
        const expected = {
            resourceName: NAME, channelId: CHANNEL, volume: VOLUME,
            panning: '0', speed: '1', loop: false,
            startTimeMs: '0', fadeInDurationMs: '0', fadeOutDurationMs: '0',
        }
        if (modalsEqual(reopened, expected)) {
            passes.push(`round-trip-${tab}: save+reopen preserved all values`)
        } else {
            failures.push({
                phase: `round-trip-${tab}`,
                msg: `BUG: ${tab} round-trip lost values. expected=${JSON.stringify(expected)} actual=${JSON.stringify(reopened)}`,
            })
        }
        await page.getByTestId('modal-cancel').click()
        await page.locator('#edit-modal').waitFor({ state: 'detached' })
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
    if (f.modal) console.log('     modal:', JSON.stringify(f.modal))
}
console.log('\n========== VERDICT ==========')
if (failures.length === 0) {
    console.log('NO BUG DETECTED.')
    process.exit(0)
} else {
    const scenarioFailures = failures.filter(f => SCENARIO_TABS.some(t => f.phase.includes(t)))
    const layerFailures = failures.filter(f => LAYER_TABS.some(t => f.phase.includes(t)))
    console.log(`BUG: ${failures.length} failure(s) — ${scenarioFailures.length} in scenario tabs, ${layerFailures.length} in layer tabs.`)
    process.exit(1)
}
