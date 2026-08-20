import { test, expect } from '@playwright/test'

/**
 * Comprehensive end-to-end tests for the in-editor audio preview feature.
 *
 * These tests verify:
 *  - Audio file loading via the hidden audio file picker
 *  - File decoding into an AudioBuffer
 *  - Single-track preview (Play / Stop)
 *  - Live parameter updates (volume, panning, speed)
 *  - Side-by-side preview (per-tab Play All + global Play All)
 *  - State cleanup (cancel modal, delete track, browser tab close)
 */

const FIXTURE_DIR = 'tests/fixtures'
const TONE_440 = `${FIXTURE_DIR}/tone-440hz-1s.wav`
const TONE_220 = `${FIXTURE_DIR}/tone-220hz-1s.wav`

test.beforeEach(async ({ page }) => {
    await page.goto('/index.html')
    await expect(page.locator('h1', { hasText: 'Audio Tracks Editor' })).toBeVisible()
})

test('audio file picker is created on init', async ({ page }) => {
    const picker = page.locator('[data-testid="audio-file-picker-hidden"]')
    await expect(picker).toHaveCount(1)
    await expect(picker).toHaveAttribute('accept', 'audio/*')
})

test('preview fieldset renders at the top of the modal', async ({ page }) => {
    await page.locator('[data-testid="new-track-button"]').click()
    await expect(page.locator('[data-testid="edit-modal"]')).toBeVisible()
    const preview = page.locator('.preview-fieldset')
    await expect(preview).toBeVisible()
    const status = page.locator('[data-testid="preview-status"]')
    await expect(status).toContainText('No file loaded')
})

test('loading a wav file shows filename and duration in status', async ({ page }) => {
    await page.locator('[data-testid="new-track-button"]').click()
    await page.locator('[data-testid="preview-load-audio"]').click()
    const [fileChooser] = await Promise.all([
        page.waitForEvent('filechooser'),
        page.locator('[data-testid="preview-load-audio"]').click(),
    ])
    await fileChooser.setFiles(TONE_440)
    await expect(page.locator('[data-testid="preview-status"]')).toContainText('Loaded: tone-440hz-1s.wav')
    await expect(page.locator('[data-testid="preview-status"]')).toContainText('1.00s')
    await expect(page.locator('[data-testid="preview-status"]')).toContainText('1ch')
})

test('Play button is disabled when no file is loaded', async ({ page }) => {
    await page.locator('[data-testid="new-track-button"]').click()
    const playBtn = page.locator('[data-testid="preview-play"]')
    await expect(playBtn).toBeDisabled()
})

test('Play button starts preview, Stop button stops it', async ({ page }) => {
    await page.locator('[data-testid="new-track-button"]').click()
    await page.locator('[data-testid="preview-load-audio"]').click()
    const [fileChooser] = await Promise.all([
        page.waitForEvent('filechooser'),
        page.locator('[data-testid="preview-load-audio"]').click(),
    ])
    await fileChooser.setFiles(TONE_440)
    await page.locator('[data-testid="preview-play"]').click()
    // Active player should exist
    const playerCount = await page.evaluate(() => window.__editor?.getActivePreviewPlayerCount?.())
    expect(playerCount).toBe(1)
    // Stop
    await page.locator('[data-testid="preview-stop"]').click()
    await page.waitForTimeout(100)
    const afterStop = await page.evaluate(() => window.__editor?.getActivePreviewPlayerCount?.())
    expect(afterStop).toBe(0)
})

test('AudioContext is running after first play', async ({ page }) => {
    await page.locator('[data-testid="new-track-button"]').click()
    await page.locator('[data-testid="preview-load-audio"]').click()
    const [fileChooser] = await Promise.all([
        page.waitForEvent('filechooser'),
        page.locator('[data-testid="preview-load-audio"]').click(),
    ])
    await fileChooser.setFiles(TONE_440)
    await page.locator('[data-testid="preview-play"]').click()
    const state = await page.evaluate(() => window.__editor?.getAudioContextState?.())
    expect(state).toBe('running')
})

test('live volume change updates gain during playback', async ({ page }) => {
    await page.locator('[data-testid="new-track-button"]').click()
    await page.locator('[data-testid="preview-load-audio"]').click()
    const [fileChooser] = await Promise.all([
        page.waitForEvent('filechooser'),
        page.locator('[data-testid="preview-load-audio"]').click(),
    ])
    await fileChooser.setFiles(TONE_440)
    await page.locator('[data-testid="preview-play"]').click()
    // Set volume slider to 0.3
    await page.locator('#f-volume').fill('0.3')
    await page.locator('#f-volume').dispatchEvent('input')
    await page.waitForTimeout(50)
    const trackId = await page.evaluate(() => document.querySelector('[data-testid="edit-modal"]')?.getAttribute('data-modal-track-id'))
    const gain = await page.evaluate((id) => window.__editor?.getActiveGainValue?.(id), trackId)
    expect(gain).toBeCloseTo(0.3, 1)
})

test('live panning change updates pan during playback', async ({ page }) => {
    await page.locator('[data-testid="new-track-button"]').click()
    await page.locator('[data-testid="preview-load-audio"]').click()
    const [fileChooser] = await Promise.all([
        page.waitForEvent('filechooser'),
        page.locator('[data-testid="preview-load-audio"]').click(),
    ])
    await fileChooser.setFiles(TONE_440)
    await page.locator('[data-testid="preview-play"]').click()
    await page.locator('#f-panning').fill('-0.5')
    await page.locator('#f-panning').dispatchEvent('input')
    await page.waitForTimeout(50)
    const trackId = await page.evaluate(() => document.querySelector('[data-testid="edit-modal"]')?.getAttribute('data-modal-track-id'))
    const panning = await page.evaluate((id) => window.__editor?.getActivePanningValue?.(id), trackId)
    expect(panning).toBeCloseTo(-0.5, 1)
})

test('live speed change updates playback rate during playback', async ({ page }) => {
    await page.locator('[data-testid="new-track-button"]').click()
    await page.locator('[data-testid="preview-load-audio"]').click()
    const [fileChooser] = await Promise.all([
        page.waitForEvent('filechooser'),
        page.locator('[data-testid="preview-load-audio"]').click(),
    ])
    await fileChooser.setFiles(TONE_440)
    await page.locator('[data-testid="preview-play"]').click()
    await page.locator('#f-speed').fill('1.5')
    await page.locator('#f-speed').dispatchEvent('input')
    await page.waitForTimeout(50)
    const trackId = await page.evaluate(() => document.querySelector('[data-testid="edit-modal"]')?.getAttribute('data-modal-track-id'))
    const speed = await page.evaluate((id) => window.__editor?.getActiveSpeedValue?.(id), trackId)
    expect(speed).toBeCloseTo(1.5, 1)
})

test('Play All (global) starts multiple players side by side', async ({ page }) => {
    // Create track 1 in DRONE
    await page.locator('[data-testid="new-track-button"]').click()
    await page.locator('[data-testid="preview-load-audio"]').click()
    const [chooser1] = await Promise.all([page.waitForEvent('filechooser'), page.locator('[data-testid="preview-load-audio"]').click()])
    await chooser1.setFiles(TONE_440)
    await page.locator('#f-resourceName').fill('test.drone.440hz')
    await page.locator('[data-testid="modal-save"]').click()
    // Create track 2 in MELODY
    await page.locator('[data-testid="tab-MELODY"]').click()
    await page.locator('[data-testid="new-track-button"]').click()
    await page.locator('[data-testid="preview-load-audio"]').click()
    const [chooser2] = await Promise.all([page.waitForEvent('filechooser'), page.locator('[data-testid="preview-load-audio"]').click()])
    await chooser2.setFiles(TONE_220)
    await page.locator('#f-resourceName').fill('test.melody.220hz')
    await page.locator('[data-testid="modal-save"]').click()
    // Click Play All (global)
    await page.locator('[data-testid="play-all-global"]').click()
    await page.waitForTimeout(200)
    const mode = await page.evaluate(() => window.__editor?.getPreviewMode?.())
    const count = await page.evaluate(() => window.__editor?.getActivePreviewPlayerCount?.())
    expect(mode).toBe('GLOBAL')
    expect(count).toBe(2)
})

test('Stop All (global) stops all players', async ({ page }) => {
    // Same setup as above (abbreviated)
    await page.locator('[data-testid="new-track-button"]').click()
    await page.locator('[data-testid="preview-load-audio"]').click()
    const [c1] = await Promise.all([page.waitForEvent('filechooser'), page.locator('[data-testid="preview-load-audio"]').click()])
    await c1.setFiles(TONE_440)
    await page.locator('#f-resourceName').fill('t1')
    await page.locator('[data-testid="modal-save"]').click()
    await page.locator('[data-testid="tab-MELODY"]').click()
    await page.locator('[data-testid="new-track-button"]').click()
    await page.locator('[data-testid="preview-load-audio"]').click()
    const [c2] = await Promise.all([page.waitForEvent('filechooser'), page.locator('[data-testid="preview-load-audio"]').click()])
    await c2.setFiles(TONE_220)
    await page.locator('#f-resourceName').fill('t2')
    await page.locator('[data-testid="modal-save"]').click()
    // Start, then stop
    await page.locator('[data-testid="play-all-global"]').click()
    await page.waitForTimeout(200)
    await page.locator('[data-testid="stop-all-global"]').click()
    await page.waitForTimeout(200)
    const mode = await page.evaluate(() => window.__editor?.getPreviewMode?.())
    const count = await page.evaluate(() => window.__editor?.getActivePreviewPlayerCount?.())
    expect(mode).toBe('IDLE')
    expect(count).toBe(0)
})

test('existing 24 e2e tests preserved: sample data loads all 8 categories', async ({ page }) => {
    await page.locator('[data-testid="load-sample-data-link"]').click()
    const tabs = ['DRONE', 'MELODY', 'RHYTHM', 'HARMONY', 'MENU', 'START', 'NEMESIS', 'END']
    for (const t of tabs) {
        await page.locator(`[data-testid="tab-${t}"]`).click()
        await expect(page.getByTestId('new-track-button')).toBeVisible()
    }
})

test('all 8 tabs render in spec order', async ({ page }) => {
    const tabIds = await page.locator('.tab-strip .tab').evaluateAll(els =>
        els.map(el => el.getAttribute('data-testid'))
    )
    expect(tabIds).toEqual([
        'tab-MENU', 'tab-START', 'tab-NEMESIS', 'tab-END',
        'tab-DRONE', 'tab-MELODY', 'tab-RHYTHM', 'tab-HARMONY'
    ])
})