import { test, expect } from '@playwright/test'
import { promises as fs } from 'fs'
import { tmpdir } from 'os'
import path from 'path'

/**
 * End-to-end tests for the four new scenario tabs in the audio tracks
 * editor: MENU, START, NEMESIS, END.
 *
 * These tabs cover the scenario-level tracks the music-selector
 * pipeline picks under specific game conditions. The tests verify:
 *
 *  1. All eight tabs render (4 layer + 4 scenario).
 *  2. A track can be added in each scenario tab.
 *  3. The full editor state, including the new scenario tracks, can
 *     be saved to a JSON file and reloaded.
 */

test.beforeEach(async ({ page }) => {
    await page.goto('/index.html')
    await expect(page.locator('h1', { hasText: 'Audio Tracks Editor' })).toBeVisible()
})

test('all eight tabs render in spec order', async ({ page }) => {
    // Layer tabs
    await expect(page.getByTestId('tab-DRONE')).toBeVisible()
    await expect(page.getByTestId('tab-MELODY')).toBeVisible()
    await expect(page.getByTestId('tab-RHYTHM')).toBeVisible()
    await expect(page.getByTestId('tab-HARMONY')).toBeVisible()
    // New scenario tabs
    await expect(page.getByTestId('tab-MENU')).toBeVisible()
    await expect(page.getByTestId('tab-START')).toBeVisible()
    await expect(page.getByTestId('tab-NEMESIS')).toBeVisible()
    await expect(page.getByTestId('tab-END')).toBeVisible()
})

test('scenario tab names appear in the strip in the documented order', async ({ page }) => {
    // The spec order is: MENU, START, NEMESIS, END, DRONE, MELODY, RHYTHM, HARMONY.
    // We assert by reading the data-testid attributes of every visible
    // tab button in DOM order.
    const tabIds = await page.locator('.tab-strip .tab').evaluateAll(els =>
        els.map(el => el.getAttribute('data-testid'))
    )
    expect(tabIds).toEqual([
        'tab-MENU', 'tab-START', 'tab-NEMESIS', 'tab-END',
        'tab-DRONE', 'tab-MELODY', 'tab-RHYTHM', 'tab-HARMONY'
    ])
})

test('add a track in the MENU tab', async ({ page }) => {
    await page.getByTestId('tab-MENU').click()
    await page.getByTestId('new-track-button').click()
    await expect(page.getByTestId('edit-modal')).toBeVisible()
    await page.locator('#f-resourceName').fill('music.menu.theme')
    await page.getByTestId('modal-save').click()
    await expect(page.getByText('music.menu.theme')).toBeVisible()
})

test('add a track in the START tab', async ({ page }) => {
    await page.getByTestId('tab-START').click()
    await page.getByTestId('new-track-button').click()
    await expect(page.getByTestId('edit-modal')).toBeVisible()
    await page.locator('#f-resourceName').fill('music.start.initial')
    await page.getByTestId('modal-save').click()
    await expect(page.getByText('music.start.initial')).toBeVisible()
})

test('add a track in the NEMESIS tab', async ({ page }) => {
    await page.getByTestId('tab-NEMESIS').click()
    await page.getByTestId('new-track-button').click()
    await expect(page.getByTestId('edit-modal')).toBeVisible()
    await page.locator('#f-resourceName').fill('music.nemesis.theme')
    await page.getByTestId('modal-save').click()
    await expect(page.getByText('music.nemesis.theme')).toBeVisible()
})

test('add a track in the END tab', async ({ page }) => {
    await page.getByTestId('tab-END').click()
    await page.getByTestId('new-track-button').click()
    await expect(page.getByTestId('edit-modal')).toBeVisible()
    await page.locator('#f-resourceName').fill('music.end.terminal')
    await page.getByTestId('modal-save').click()
    await expect(page.getByText('music.end.terminal')).toBeVisible()
})

test('tracks added in one scenario tab are not visible in another scenario tab', async ({ page }) => {
    // Add in MENU
    await page.getByTestId('tab-MENU').click()
    await page.getByTestId('new-track-button').click()
    await page.locator('#f-resourceName').fill('menu.only')
    await page.getByTestId('modal-save').click()
    await expect(page.getByText('menu.only')).toBeVisible()

    // Switch to START — the menu track must not be listed
    await page.getByTestId('tab-START').click()
    await expect(page.getByText('menu.only')).not.toBeVisible()
    await expect(page.getByText(/no tracks yet/i)).toBeVisible()
})

test('load sample data populates all eight categories with tracks', async ({ page }) => {
    await page.getByTestId('load-sample-data-link').click()

    // Layer tracks (4 categories)
    await expect(page.getByText('music.drone.pad')).toBeVisible()
    // Scenario tracks (4 categories — switch tabs to verify)
    await page.getByTestId('tab-MENU').click()
    await expect(page.getByText('music.menu.theme')).toBeVisible()
    await page.getByTestId('tab-START').click()
    await expect(page.getByText('music.start.initial')).toBeVisible()
    await page.getByTestId('tab-NEMESIS').click()
    await expect(page.getByText('music.nemesis.theme')).toBeVisible()
    await page.getByTestId('tab-END').click()
    await expect(page.getByText('music.end.terminal')).toBeVisible()
})

test('save then load round-trips scenario tracks', async ({ page }) => {
    // Add one track in every scenario tab
    const tracks = [
        { tab: 'MENU', name: 'rt.menu' },
        { tab: 'START', name: 'rt.start' },
        { tab: 'NEMESIS', name: 'rt.nemesis' },
        { tab: 'END', name: 'rt.end' }
    ]
    for (const t of tracks) {
        await page.getByTestId(`tab-${t.tab}`).click()
        await page.getByTestId('new-track-button').click()
        await page.locator('#f-resourceName').fill(t.name)
        await page.getByTestId('modal-save').click()
        await expect(page.getByText(t.name)).toBeVisible()
    }

    // Save the file
    const downloadPromise = page.waitForEvent('download')
    await page.getByTestId('save-button').click()
    const download = await downloadPromise
    expect(download.suggestedFilename()).toBe('audioTracks.json')
    const tmpPath = path.join(tmpdir(), `audioTracks-scenario-${Date.now()}.json`)
    await download.saveAs(tmpPath)
    const content = await fs.readFile(tmpPath, 'utf-8')
    const parsed = JSON.parse(content)

    // The file must carry the new scenario fields with the tracks we added
    expect(parsed.menu).toBeInstanceOf(Array)
    expect(parsed.start).toBeInstanceOf(Array)
    expect(parsed.nemesis).toBeInstanceOf(Array)
    expect(parsed.end).toBeInstanceOf(Array)
    expect(parsed.menu.length).toBe(1)
    expect(parsed.start.length).toBe(1)
    expect(parsed.nemesis.length).toBe(1)
    expect(parsed.end.length).toBe(1)
    expect(parsed.menu[0].resourceName).toBe('rt.menu')
    expect(parsed.start[0].resourceName).toBe('rt.start')
    expect(parsed.nemesis[0].resourceName).toBe('rt.nemesis')
    expect(parsed.end[0].resourceName).toBe('rt.end')
})

test('old-format 4-field JSON loads with empty new fields', async ({ page }) => {
    // A file with only the four original layer fields — written by the
    // previous version of the editor. The loader must accept it and
    // default the new scenario fields to empty.
    const oldPath = path.join(tmpdir(), `audioTracks-old-${Date.now()}.json`)
    await fs.writeFile(oldPath, JSON.stringify({
        drone: [],
        melody: [],
        rhythm: [],
        harmony: []
    }), 'utf-8')

    // Trigger the hidden file picker via the Load button
    const fileChooserPromise = page.waitForEvent('filechooser')
    await page.getByTestId('load-button').click()
    const fileChooser = await fileChooserPromise
    await fileChooser.setFiles(oldPath)

    // All tabs must still render and show "no tracks yet"
    for (const tab of ['MENU', 'START', 'NEMESIS', 'END', 'DRONE', 'MELODY', 'RHYTHM', 'HARMONY']) {
        await page.getByTestId(`tab-${tab}`).click()
        await expect(page.getByText(/no tracks yet/i)).toBeVisible()
    }
})

// ---------------------------------------------------------------------------
// Regression: scenario-tab Edit modal must pre-fill the saved track
// (regression for the bug where findTrackById() only looked at
// drone/melody/rhythm/harmony and fell through to a blank draft for
// menu/start/nemesis/end).
// ---------------------------------------------------------------------------

const SCENARIO_REGRESSION_TABS = ['MENU', 'START', 'NEMESIS', 'END']
const SCENARIO_SAMPLE = {
    MENU:    { name: 'music.menu.theme',   volume: '0.8' },
    START:   { name: 'music.start.initial', volume: '1'   },
    NEMESIS: { name: 'music.nemesis.theme', volume: '0.9' },
    END:     { name: 'music.end.terminal',  volume: '1'   },
}

async function readEditModalFields(page) {
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

for (const tab of SCENARIO_REGRESSION_TABS) {
    test(`scenario-tab regression: opening Edit on the ${tab} sample track pre-fills the modal`, async ({ page }) => {
        await page.getByTestId('load-sample-data-link').click()
        await page.getByTestId(`tab-${tab}`).click()
        const sample = SCENARIO_SAMPLE[tab]
        await expect(page.getByText(sample.name)).toBeVisible()

        await page.locator('button[data-action="edit-track"]').first().click()
        await expect(page.getByTestId('edit-modal')).toBeVisible()

        const fields = await readEditModalFields(page)
        // The modal must pre-fill the saved track — if findTrackById misses
        // the scenario category, fields.resourceName will be '' and
        // fields.volume will be '1' (the default-draft values).
        expect(fields.resourceName, `${tab} modal resourceName must equal saved track`).toBe(sample.name)
        expect(fields.volume, `${tab} modal volume must equal saved track volume`).toBe(sample.volume)

        await page.getByTestId('modal-cancel').click()
    })

    test(`scenario-tab regression: create+save+reopen round-trips values on ${tab}`, async ({ page }) => {
        await page.getByTestId(`tab-${tab}`).click()
        await page.getByTestId('new-track-button').click()
        await expect(page.getByTestId('edit-modal')).toBeVisible()

        const NAME = `rt.${tab.toLowerCase()}.scenario.regression`
        const CHANNEL = 'Sfx'
        const VOLUME = '0.42'
        await page.locator('#f-resourceName').fill(NAME)
        await page.locator('#f-channelId').fill(CHANNEL)
        await page.locator('#f-volume').evaluate((el, v) => {
            el.value = v
            el.dispatchEvent(new Event('input', { bubbles: true }))
        }, VOLUME)
        await page.getByTestId('modal-save').click()
        await expect(page.getByText(NAME)).toBeVisible()

        // Reopen the new track (it is the last in the list).
        const rowCount = await page.locator('button[data-action="edit-track"]').count()
        await page.locator('button[data-action="edit-track"]').nth(rowCount - 1).click()
        await expect(page.getByTestId('edit-modal')).toBeVisible()

        const fields = await readEditModalFields(page)
        expect(fields.resourceName, `${tab} round-trip resourceName`).toBe(NAME)
        expect(fields.channelId, `${tab} round-trip channelId`).toBe(CHANNEL)
        expect(fields.volume, `${tab} round-trip volume`).toBe(VOLUME)

        await page.getByTestId('modal-cancel').click()
    })
}

// ---------------------------------------------------------------------------
// Regression: scenario-tab Edit modal must pre-fill the saved track
// (regression for the bug where findTrackById() only looked at
// drone/melody/rhythm/harmony and fell through to a blank draft for
// menu/start/nemesis/end).
// ---------------------------------------------------------------------------

const SCENARIO_REGRESSION_TABS = ['MENU', 'START', 'NEMESIS', 'END']
const SCENARIO_SAMPLE = {
    MENU:    { name: 'music.menu.theme',    volume: '0.8' },
    START:   { name: 'music.start.initial', volume: '1'   },
    NEMESIS: { name: 'music.nemesis.theme', volume: '0.9' },
    END:     { name: 'music.end.terminal',  volume: '1'   },
}

async function readEditModalFields(page) {
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

for (const tab of SCENARIO_REGRESSION_TABS) {
    test(`scenario-tab regression: opening Edit on the ${tab} sample track pre-fills the modal`, async ({ page }) => {
        await page.getByTestId('load-sample-data-link').click()
        await page.getByTestId(`tab-${tab}`).click()
        const sample = SCENARIO_SAMPLE[tab]
        await expect(page.getByText(sample.name)).toBeVisible()

        await page.locator('button[data-action="edit-track"]').first().click()
        await expect(page.getByTestId('edit-modal')).toBeVisible()

        const fields = await readEditModalFields(page)
        // The modal must pre-fill the saved track — if findTrackById misses
        // the scenario category, fields.resourceName will be '' and
        // fields.volume will be '1' (the default-draft values).
        expect(fields.resourceName, `${tab} modal resourceName must equal saved track`).toBe(sample.name)
        expect(fields.volume, `${tab} modal volume must equal saved track volume`).toBe(sample.volume)

        await page.getByTestId('modal-cancel').click()
    })

    test(`scenario-tab regression: create+save+reopen round-trips values on ${tab}`, async ({ page }) => {
        await page.getByTestId(`tab-${tab}`).click()
        await page.getByTestId('new-track-button').click()
        await expect(page.getByTestId('edit-modal')).toBeVisible()

        const NAME = `rt.${tab.toLowerCase()}.scenario.regression`
        const CHANNEL = 'Sfx'
        const VOLUME = '0.42'
        await page.locator('#f-resourceName').fill(NAME)
        await page.locator('#f-channelId').fill(CHANNEL)
        await page.locator('#f-volume').evaluate((el, v) => {
            el.value = v
            el.dispatchEvent(new Event('input', { bubbles: true }))
        }, VOLUME)
        await page.getByTestId('modal-save').click()
        await expect(page.getByText(NAME)).toBeVisible()

        // Reopen the new track (it is the last in the list).
        const rowCount = await page.locator('button[data-action="edit-track"]').count()
        await page.locator('button[data-action="edit-track"]').nth(rowCount - 1).click()
        await expect(page.getByTestId('edit-modal')).toBeVisible()

        const fields = await readEditModalFields(page)
        expect(fields.resourceName, `${tab} round-trip resourceName`).toBe(NAME)
        expect(fields.channelId, `${tab} round-trip channelId`).toBe(CHANNEL)
        expect(fields.volume, `${tab} round-trip volume`).toBe(VOLUME)

        await page.getByTestId('modal-cancel').click()
    })
}

// ---------------------------------------------------------------------------
// Regression: preview actions (Play/Stop/Clear/PlayAll/StopAll) must NOT
// wipe the user's in-flight form values.
//
// The bug was that several preview handlers forced a full modal re-render
// (`lastRenderedModal = null` + `rebuildAll(clearError=true)`). The re-render
// rebuilt the form from STATE, not from the DOM, so any unsaved edits the
// user typed were destroyed.
//
// The fix toggles the Play/Stop/Clear button visibility in place via DOM
// updates and no longer forces a re-render.
// ---------------------------------------------------------------------------

async function readEditModalFieldsV2(page) {
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

const SCENARIO_REGRESSION_TABS_FOR_PREVIEW = ['MENU', 'START', 'NEMESIS', 'END', 'DRONE']

for (const tab of SCENARIO_REGRESSION_TABS_FOR_PREVIEW) {
    test(`preview regression: in-flight edits survive a "Play All" click on ${tab}`, async ({ page }) => {
        // Always-available "Play All (category)" hits the same code path as
        // the per-track Play/Stop handlers; we use it because the per-track
        // buttons are gated on a loaded audio buffer.
        await page.getByTestId('load-sample-data-link').click()
        await page.getByTestId(`tab-${tab}`).click()
        await page.locator('button[data-action="edit-track"]').first().click()
        await expect(page.getByTestId('edit-modal')).toBeVisible()

        const NAME = `preview.rt.${tab.toLowerCase()}`
        const CHANNEL = 'Sfx'
        const VOLUME = '0.55'
        await page.locator('#f-resourceName').fill(NAME)
        await page.locator('#f-channelId').fill(CHANNEL)
        await page.locator('#f-volume').evaluate((el, v) => {
            el.value = v
            el.dispatchEvent(new Event('input', { bubbles: true }))
        }, VOLUME)
        await page.locator('#f-loop').check()

        // Bypass the dialog backdrop so the click actually reaches the
        // "Play All" button.
        await page.getByTestId('play-all-category').evaluate(b => b.click())

        // Give the in-place update a tick to settle.
        await page.waitForTimeout(50)

        const fields = await readEditModalFieldsV2(page)
        expect(fields.resourceName, `${tab} resourceName must survive preview click`).toBe(NAME)
        expect(fields.channelId, `${tab} channelId must survive preview click`).toBe(CHANNEL)
        expect(fields.volume, `${tab} volume must survive preview click`).toBe(VOLUME)
        expect(fields.loop, `${tab} loop must survive preview click`).toBe(true)

        // Stop the side-by-side preview so it doesn't leak across tests.
        const stopVisible = await page.getByTestId('stop-all-category').isVisible().catch(() => false)
        if (stopVisible) {
            await page.getByTestId('stop-all-category').evaluate(b => b.click())
            await page.waitForTimeout(50)
        }
        await page.getByTestId('modal-cancel').click()
        await expect(page.getByTestId('edit-modal')).toBeHidden()
    })
}

test('preview regression: in-flight edits survive "Play All (global)"', async ({ page }) => {
    await page.getByTestId('load-sample-data-link').click()
    await page.getByTestId('tab-DRONE').click()
    await page.locator('button[data-action="edit-track"]').first().click()
    await expect(page.getByTestId('edit-modal')).toBeVisible()

    const NAME = 'preview.rt.global'
    await page.locator('#f-resourceName').fill(NAME)
    await page.locator('#f-volume').evaluate((el, v) => {
        el.value = v
        el.dispatchEvent(new Event('input', { bubbles: true }))
    }, '0.33')

    await page.getByTestId('play-all-global').evaluate(b => b.click())
    await page.waitForTimeout(50)

    const fields = await readEditModalFieldsV2(page)
    expect(fields.resourceName).toBe(NAME)
    expect(fields.volume).toBe('0.33')

    const stopVisible = await page.getByTestId('stop-all-global').isVisible().catch(() => false)
    if (stopVisible) {
        await page.getByTestId('stop-all-global').evaluate(b => b.click())
        await page.waitForTimeout(50)
    }
    await page.getByTestId('modal-cancel').click()
    await expect(page.getByTestId('edit-modal')).toBeHidden()
})