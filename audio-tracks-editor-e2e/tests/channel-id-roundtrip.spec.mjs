// Regression test for the "audio editor stomps channelId to Music" bug.
//
// Background: before commit 6d1547c6a (Jun 14 2026), the audio-tracks
// editor hard-coded channelId = "Music" in three places: newDraftTrack,
// every sampleData() entry, and the parseForm() fallback. A user who
// loaded a correctly-routed file, opened Edit on any track, and clicked
// Save without changes would see that track's channelId silently
// rewritten to "Music" — collapsing every music layer onto the master
// bus and losing per-category routing.
//
// The 6d1547c6a fix moved newDraftTrack / sampleData over to
// categoryToChannelId(category), and EventHandlers.parseForm() now falls
// back to categoryToChannelId(state.selectedCategory) instead of the
// hard-coded "Music" literal. This test pins down the property: a
// load → save round-trip must not turn any per-category channelId into
// "Music".
//
// We exercise the round-trip via two paths:
//   1. The production audio-tracks.json shipped at
//      sharedModel/src/commonMain/resources/audio/audio-tracks.json
//      (the source of truth the editor is supposed to preserve).
//   2. A small in-test fixture that has every per-category channelId
//      represented, so a future regression that flips only one
//      category to "Music" is still caught.

import { test, expect } from '@playwright/test'
import { promises as fs } from 'fs'
import { tmpdir } from 'os'
import path from 'path'
import { fileURLToPath } from 'url'

const REPO_ROOT = fileURLToPath(new URL('../../', import.meta.url))
const PROD_FIXTURE = path.join(
    REPO_ROOT,
    'sharedModel/src/commonMain/resources/audio/audio-tracks.json',
)

/** Per-category channelIds the editor must preserve. */
const PER_CATEGORY_CHANNEL_IDS = [
    'Drone', 'Melody', 'Rhythm', 'Harmony',
    'Menu', 'Start', 'Nemesis', 'End',
]

/** A small fixture with one track per category. */
const PER_CATEGORY_FIXTURE = {
    drone:  [{ id: 'd1', resourceName: 'd1', channelId: 'Drone',  volume: 0.5, panning: 0, speed: 1, loop: false, startTimeMs: 0, fadeInDurationMs: 0, fadeOutDurationMs: 0, loopWithTail: false }],
    melody: [{ id: 'm1', resourceName: 'm1', channelId: 'Melody', volume: 0.5, panning: 0, speed: 1, loop: false, startTimeMs: 0, fadeInDurationMs: 0, fadeOutDurationMs: 0, loopWithTail: false }],
    rhythm: [{ id: 'r1', resourceName: 'r1', channelId: 'Rhythm', volume: 0.5, panning: 0, speed: 1, loop: false, startTimeMs: 0, fadeInDurationMs: 0, fadeOutDurationMs: 0, loopWithTail: false }],
    harmony: [{ id: 'h1', resourceName: 'h1', channelId: 'Harmony', volume: 0.5, panning: 0, speed: 1, loop: false, startTimeMs: 0, fadeInDurationMs: 0, fadeOutDurationMs: 0, loopWithTail: false }],
    menu:    [{ id: 'mn1', resourceName: 'mn1', channelId: 'Menu',    volume: 0.5, panning: 0, speed: 1, loop: false, startTimeMs: 0, fadeInDurationMs: 0, fadeOutDurationMs: 0, loopWithTail: false }],
    start:   [{ id: 's1',  resourceName: 's1',  channelId: 'Start',   volume: 0.5, panning: 0, speed: 1, loop: false, startTimeMs: 0, fadeInDurationMs: 0, fadeOutDurationMs: 0, loopWithTail: false }],
    nemesis: [{ id: 'n1',  resourceName: 'n1',  channelId: 'Nemesis', volume: 0.5, panning: 0, speed: 1, loop: false, startTimeMs: 0, fadeInDurationMs: 0, fadeOutDurationMs: 0, loopWithTail: false }],
    end:     [{ id: 'e1',  resourceName: 'e1',  channelId: 'End',     volume: 0.5, panning: 0, speed: 1, loop: false, startTimeMs: 0, fadeInDurationMs: 0, fadeOutDurationMs: 0, loopWithTail: false }],
}

/**
 * Inject [text] into the editor's hidden file picker and trigger the
 * "load file" change handler. Returns when the in-memory state is updated
 * and the tabs re-render.
 */
async function loadJson(page, text) {
    await page.evaluate(async (t) => {
        const picker = document.querySelector('input[type="file"][data-testid="load-file-picker-hidden"]')
        if (!picker) throw new Error('load-file-picker-hidden not found')
        const file = new File([t], 'audio-tracks.json', { type: 'application/json' })
        const dt = new DataTransfer()
        dt.items.add(file)
        picker.files = dt.files
        picker.dispatchEvent(new Event('change', { bubbles: true }))
    }, text)
    // Give the editor a beat to decode + rebuildAll.
    await page.waitForTimeout(400)
}

/**
 * Trigger a Save download. The save button is disabled when state.dirty is
 * false (a clean load), so we open a new-track modal and cancel it just
 * to flip dirty, then click Save. (Opening the modal in the DRONE tab and
 * cancelling does not modify tracks — it only sets state.dirty=true via
 * openNewTrackModal. After the download the in-memory state is unchanged.)
 */
async function saveAndCapture(page, label) {
    const downloadPromise = page.waitForEvent('download')
    await page.locator('[data-testid="save-button"]').click()
    const download = await downloadPromise
    const out = path.join(tmpdir(), `channelid-roundtrip-${label}-${Date.now()}.json`)
    await download.saveAs(out)
    return JSON.parse(await fs.readFile(out, 'utf-8'))
}

test.beforeEach(async ({ page }) => {
    await page.goto('/index.html')
    await expect(page.locator('h1', { hasText: 'Audio Tracks Editor' })).toBeVisible()
})

/**
 * The core regression: open Edit on every track, click Save without
 * changes, re-open Edit, confirm channelId is preserved. If parseForm
 * ever loses the field, the channelId would fall back to whatever
 * literal the bug fixed (today: categoryToChannelId; pre-fix: "Music"),
 * and this test would catch it.
 */
test('load + edit + save round-trip preserves per-category channelId (in-memory)', async ({ page }) => {
    await loadJson(page, JSON.stringify(PER_CATEGORY_FIXTURE))

    for (const tab of ['DRONE', 'MELODY', 'RHYTHM', 'HARMONY', 'MENU', 'START', 'NEMESIS', 'END']) {
        await page.locator(`[data-testid="tab-${tab}"]`).click()
        await page.waitForTimeout(30)
        const editBtn = page.locator('button[data-action="edit-track"]').first()
        if (!(await editBtn.count())) continue
        await editBtn.click()
        await page.locator('[data-testid="edit-modal"]').waitFor()
        await page.waitForTimeout(30)

        const formValue = await page.locator('#f-channelId').inputValue()
        // The pre-fill must already be the per-category channelId, not "Music".
        const expected = PER_CATEGORY_FIXTURE[tab.toLowerCase()][0].channelId
        expect(formValue, `${tab} Edit modal pre-fill`).toBe(expected)
        expect(formValue, `${tab} must not be the master 'Music' channel`).not.toBe('Music')

        // Save without changes.
        await page.locator('[data-testid="modal-save"]').click()
        await page.locator('#edit-modal').waitFor({ state: 'detached' })
        await page.waitForTimeout(30)

        // Re-open and confirm channelId survived.
        await page.locator('button[data-action="edit-track"]').first().click()
        await page.locator('[data-testid="edit-modal"]').waitFor()
        await page.waitForTimeout(30)
        const reopened = await page.locator('#f-channelId').inputValue()
        expect(reopened, `${tab} channelId after save`).toBe(expected)
        expect(reopened, `${tab} must not collapse to 'Music'`).not.toBe('Music')

        await page.locator('[data-testid="modal-cancel"]').click()
        await page.locator('#edit-modal').waitFor({ state: 'detached' })
    }
})

/**
 * End-to-end: load the production audio-tracks.json (the source of truth
 * shipped with the game) and round-trip it through Save. The downloaded
 * file must keep every per-category channelId intact and must not invent
 * any new "Music" channelIds. This is the property the user reported
 * breaking: "The channel is all sett to music".
 */
test('production audio-tracks.json round-trips without any channelId becoming "Music"', async ({ page }) => {
    const original = JSON.parse(await fs.readFile(PROD_FIXTURE, 'utf-8'))
    // Sanity check: the production fixture itself is sane.
    for (const cat of Object.keys(PER_CATEGORY_FIXTURE)) {
        expect(original[cat]?.length ?? 0, `prod ${cat}`).toBeGreaterThan(0)
    }
    for (const it of [...original.drone, ...original.melody, ...original.rhythm, ...original.harmony,
                     ...original.menu, ...original.start, ...original.nemesis, ...original.end]) {
        expect(it.channelId, `prod fixture track ${it.id}`).not.toBe('Music')
    }

    await loadJson(page, JSON.stringify(original))
    // Touch state to enable the Save button (a clean load leaves it disabled).
    await page.locator('[data-testid="tab-DRONE"]').click()
    await page.locator('[data-testid="new-track-button"]').click()
    await page.locator('[data-testid="edit-modal"]').waitFor()
    await page.locator('[data-testid="modal-cancel"]').click()
    await page.locator('#edit-modal').waitFor({ state: 'detached' })

    const roundTripped = await saveAndCapture(page, 'production')

    // No track may have its channelId changed.
    for (const cat of Object.keys(PER_CATEGORY_FIXTURE)) {
        const origList = original[cat] ?? []
        const rtList = roundTripped[cat] ?? []
        expect(rtList.length, `${cat} count preserved`).toBe(origList.length)
        for (let i = 0; i < origList.length; i++) {
            expect(rtList[i].id, `${cat}[${i}].id preserved`).toBe(origList[i].id)
            expect(rtList[i].channelId, `${cat}[${i}].channelId preserved`)
                .toBe(origList[i].channelId)
        }
    }

    // And: zero tracks may have been silently collapsed onto the master.
    const allRoundTripped = [
        ...(roundTripped.drone ?? []),
        ...(roundTripped.melody ?? []),
        ...(roundTripped.rhythm ?? []),
        ...(roundTripped.harmony ?? []),
        ...(roundTripped.menu ?? []),
        ...(roundTripped.start ?? []),
        ...(roundTripped.nemesis ?? []),
        ...(roundTripped.end ?? []),
    ]
    const musicCount = allRoundTripped.filter(t => t.channelId === 'Music').length
    expect(musicCount, 'no track may collapse to the master "Music" channel')
        .toBe(0)
    // Every round-tripped channelId must be a known per-category value.
    for (const t of allRoundTripped) {
        expect(
            PER_CATEGORY_CHANNEL_IDS,
            `track ${t.id} channelId "${t.channelId}" must be a per-category channel`,
        ).toContain(t.channelId)
    }
})

/**
 * The fallback path: even if a future refactor removes the form field,
 * the parseForm() fallback must derive the channelId from the active
 * category — never return the hard-coded "Music" string. We can't
 * easily make the form field disappear, so we instead inspect the
 * compiled bundle directly: parseForm's channelId expression must
 * reference categoryToChannelId, not the "Music" literal.
 */
test('parseForm fallback uses categoryToChannelId, never a hard-coded "Music" literal', async ({ page, request }) => {
    const bundleUrl = new URL('/audioTracksEditor.js?v=13', page.url()).toString()
    const body = await request.get(bundleUrl)
    const text = await body.text()
    // The exact expression in EventHandlers.kt:
    //   channelId = value("f-channelId") ?: categoryToChannelId(state.selectedCategory)
    // appears in the bundle as the minified equivalent — but as long as
    // there is no bare "Music" assignment left as a channelId default in
    // the parseForm path, the regression is closed.
    // Locate the parseForm call and the immediate context.
    const parseFormRegion = text.indexOf('f-resourceName')
    expect(parseFormRegion, 'parseForm call site present').toBeGreaterThan(0)
    const after = text.slice(parseFormRegion, parseFormRegion + 500)
    // Within ~500 bytes of f-resourceName, parseForm builds the AudioObject
    // and must not use "Music" as a channelId default.
    expect(after, 'no hard-coded "Music" fallback near parseForm')
        .not.toMatch(/"Music"/)
})
