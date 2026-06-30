import { test, expect } from '@playwright/test'
import { promises as fs } from 'fs'
import { tmpdir } from 'os'
import path from 'path'

test.beforeEach(async ({ page }) => {
    await page.goto('/index.html')
    // Wait for the top bar heading to be present, confirming the page loaded
    await expect(page.locator('h1', { hasText: 'Audio Tracks Editor' })).toBeVisible()
})

test('empty state on first load', async ({ page }) => {
    await expect(page.getByTestId('tab-DRONE')).toBeVisible()
    await expect(page.getByTestId('tab-MELODY')).toBeVisible()
    await expect(page.getByTestId('tab-RHYTHM')).toBeVisible()
    await expect(page.getByTestId('tab-HARMONY')).toBeVisible()
    await expect(page.getByText(/no tracks yet/i)).toBeVisible()
    await expect(page.getByTestId('new-track-button')).toBeVisible()
})

test('add a track in the Drone tab', async ({ page }) => {
    await page.getByTestId('new-track-button').click()
    await expect(page.getByTestId('edit-modal')).toBeVisible()
    await page.locator('#f-resourceName').fill('music.drone.test')
    await page.getByTestId('modal-save').click()
    await expect(page.getByText('music.drone.test')).toBeVisible()
})

test('edit a track', async ({ page }) => {
    await page.getByTestId('new-track-button').click()
    await page.locator('#f-resourceName').fill('original.name')
    await page.getByTestId('modal-save').click()
    await expect(page.getByText('original.name')).toBeVisible()

    const editButton = page.getByRole('button', { name: 'Edit' }).first()
    await editButton.click()
    await expect(page.getByTestId('edit-modal')).toBeVisible()
    await page.locator('#f-resourceName').fill('updated.name')
    await page.getByTestId('modal-save').click()
    await expect(page.getByText('updated.name')).toBeVisible()
    await expect(page.getByText('original.name')).not.toBeVisible()
})

test('delete a track', async ({ page }) => {
    await page.getByTestId('new-track-button').click()
    await page.locator('#f-resourceName').fill('to.delete')
    await page.getByTestId('modal-save').click()
    await expect(page.getByText('to.delete')).toBeVisible()

    page.once('dialog', dialog => dialog.accept())
    const deleteButton = page.getByRole('button', { name: 'Delete' }).first()
    await deleteButton.click()
    await expect(page.getByText('to.delete')).not.toBeVisible()
})

test('save downloads audioTracks.json with valid content', async ({ page }) => {
    await page.getByTestId('load-sample-data-link').click()
    await expect(page.getByText('music.drone.pad')).toBeVisible()

    const downloadPromise = page.waitForEvent('download')
    await page.getByTestId('save-button').click()
    const download = await downloadPromise

    expect(download.suggestedFilename()).toBe('audioTracks.json')

    const tmpPath = path.join(tmpdir(), `audioTracks-${Date.now()}.json`)
    await download.saveAs(tmpPath)
    const content = await fs.readFile(tmpPath, 'utf-8')
    const parsed = JSON.parse(content)
    expect(parsed.drone).toBeInstanceOf(Array)
    expect(parsed.drone.length).toBe(2)
    expect(parsed.melody.length).toBe(2)
    expect(parsed.rhythm.length).toBe(1)
    expect(parsed.harmony.length).toBe(1)
})

test('load replaces in-memory state', async ({ page }) => {
    await expect(page.getByText(/no tracks yet/i)).toBeVisible()

    const tmpPath = path.join(tmpdir(), `load-test-${Date.now()}.json`)
    const fixture = {
        drone: [
            {
                id: 'a',
                resourceName: 'loaded.drone',
                channelId: 'Music',
                volume: 1.0,
                panning: 0,
                speed: 1.0,
                loop: false,
                startTimeMs: 0,
                fadeInDurationMs: 0,
                fadeOutDurationMs: 0,
                loopWithTail: false
            }
        ],
        melody: [],
        rhythm: [],
        harmony: []
    }
    await fs.writeFile(tmpPath, JSON.stringify(fixture))
    await page.setInputFiles('[data-testid="load-file-picker-hidden"]', tmpPath)
    await expect(page.getByText('loaded.drone')).toBeVisible()
})

test('invalid JSON load shows error banner and preserves state', async ({ page }) => {
    await page.getByTestId('load-sample-data-link').click()
    await expect(page.getByText('music.drone.pad')).toBeVisible()

    const tmpPath = path.join(tmpdir(), `bad-${Date.now()}.json`)
    await fs.writeFile(tmpPath, '{this is not valid json')
    await page.setInputFiles('[data-testid="load-file-picker-hidden"]', tmpPath)

    await expect(page.locator('#error-banner-container')).toContainText(/could not parse/i)
    await expect(page.getByText('music.drone.pad')).toBeVisible()
})

test('sample data link loads curated tracks', async ({ page }) => {
    await page.getByTestId('load-sample-data-link').click()
    await expect(page.getByText('music.drone.pad')).toBeVisible()
    await expect(page.getByText('music.drone.sub')).toBeVisible()
    await expect(page.getByText('music.melody.theme')).toBeVisible()
    await expect(page.getByText('music.melody.counter')).toBeVisible()
    await expect(page.getByText('music.rhythm.drums')).toBeVisible()
    await page.getByTestId('tab-HARMONY').click()
    await expect(page.getByText('music.harmony.chord')).toBeVisible()
})

test('all 4 categories are accessible via tabs', async ({ page }) => {
    for(const cat of ['DRONE', 'MELODY', 'RHYTHM', 'HARMONY'])
    {
        await page.getByTestId(`tab-${cat}`).click()
        await expect(page.getByTestId('new-track-button')).toBeVisible()
    }
})
