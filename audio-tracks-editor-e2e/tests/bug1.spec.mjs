// Bug 1 test: click handlers fire and state mutations must propagate to the DOM
// Pre-fix expected: FAIL — list-container id is lost after first render, so
//   subsequent rebuildAll() calls no-op. Sample data never appears.
// Post-fix expected: PASS — list-container stays findable; sample data appears.

import { test, expect } from '@playwright/test'

test.beforeEach(async ({ page }) => {
    await page.goto('/index.html')
    // Wait for the page to be hydrated
    await expect(page.locator('h1', { hasText: 'Audio Tracks Editor' })).toBeVisible()
})

test('list-container id is preserved across initial render', async ({ page }) => {
    // After the first render, the container must still be findable by id
    const el = page.locator('#list-container')
    await expect(el).toHaveCount(1)
})

test('clicking load sample data populates the track list', async ({ page }) => {
    await page.getByTestId('load-sample-data-link').click()
    // The compiled bundle creates 6 sample tracks: 2 drone, 2 melody, 1 rhythm, 1 harmony
    await expect(page.getByText('music.drone.pad')).toBeVisible()
    await expect(page.getByText('music.melody.theme')).toBeVisible()
    await expect(page.getByText('music.rhythm.drums')).toBeVisible()
})

test('Save button enables after loading sample data (dirty=true)', async ({ page }) => {
    const saveBtn = page.getByTestId('save-button')
    await expect(saveBtn).toBeDisabled()
    await page.getByTestId('load-sample-data-link').click()
    await expect(saveBtn).toBeEnabled()
})

test('clicking + New Track opens the modal', async ({ page }) => {
    await page.getByTestId('new-track-button').click()
    await expect(page.getByTestId('edit-modal')).toBeVisible()
    // The dialog should be open (open attribute or .open property)
    const openAttr = await page.getByTestId('edit-modal').getAttribute('open')
    expect(openAttr).not.toBeNull()
})
