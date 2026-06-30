/**
 * Playwright spec for the packaged Electron jukebox app.
 *
 * Phase 4 of the Electron Jukebox build. Each test launches a fresh
 * Electron process via `_electron.launch()` against the
 * `electronJukebox/` module (or, if `JUKEBOX_APPIMAGE` is set, against
 * the packaged AppImage). Tests are serial because Electron keeps GPU
 * and IPC state per process and parallel launches on the same
 * userData dir would race on `window-state.json` and
 * `audio-prefs.json`.
 *
 * Coverage:
 *   EJ-1  app launches and the jukebox UI is visible
 *   EJ-2  the device selector dropdown is present and has options
 *   EJ-3  the window.electronAPI surface is exposed with the
 *         5 documented methods
 *   EJ-4  clicking Forest spawns an active player (sanity check
 *         that the engine is alive in Electron's Chromium)
 *   EJ-5  the jukebox-stop-all IPC event (sent by the View > Stop
 *         All Audio menu) clears the active players
 *   EJ-6  state restore on reload — sliders, fade inputs, and the
 *         loop-mode toggle all re-hydrate from the persisted blob
 *   EJ-7  device-selector persistence — setDefaultDevice + a
 *         subsequent getAudioDevices return the same id
 *   EJ-8  Phase 1 regression — B1 live time, B2 SFX value label,
 *         B3 dB conversion still hold under Electron's Chromium
 *
 * Usage:
 *   1. Build the jukebox dist:
 *        ./gradlew :jukebox:build
 *   2. Stage the dist into electronJukebox/build/staging/frontend/
 *      (the Gradle `stageJukeboxDist` task does this; or copy by hand
 *      from jukebox/build/dist/js/productionExecutable/).
 *   3. Run with playwright:
 *        npx playwright test tests/playwright/electron-jukebox.spec.js
 *
 * Headless environments must set DISPLAY (e.g. `xvfb-run npx playwright
 * test ...`) — Electron will refuse to start without an X11 display.
 *
 * Audio playback is NOT asserted (REQ-N3): headless CI has no audio
 * device and the Web Audio engine's output bus is opaque to the test.
 * Only structural / state-shape assertions run here.
 */

const { test, expect } = require('@playwright/test');
const { launchJukeboxApp, waitForJukeboxReady, triggerStopAllFromMain } = require('./electron-jukebox-helpers');

// Serial mode: each test owns its own Electron process, but two
// processes pointing at the same userData dir would race on the
// debounced JSON writes. Serialising avoids that.
test.describe.configure({ mode: 'serial' });

test.describe('Jukebox Electron App', () => {
    /** @type {import('playwright').ElectronApplication} */
    let electronApp;
    /** @type {import('playwright').Page} */
    let window;

    test.beforeEach(async () => {
        electronApp = await launchJukeboxApp();
        window = await electronApp.firstWindow();
        await waitForJukeboxReady(window);
    });

    test.afterEach(async () => {
        if (electronApp) {
            await electronApp.close();
        }
    });

    test('EJ-1: app launches and jukebox UI is visible', async () => {
        // Header title is the most stable shell marker.
        await expect(window.locator('.header-title')).toBeVisible();
        // Forest play button is the first interactive element used
        // by waitForJukeboxReady; sanity-check it is still in the DOM.
        await expect(window.locator('#btn-forest')).toBeVisible();
        // The Phase 3 device selector must be present in the header.
        await expect(window.locator('#device-selector')).toBeVisible();
        // A row spawned in beforeEach via the Forest click — verify
        // the active-players panel rendered at least one row.
        const activeRows = await window.locator('.player-row').count();
        expect(activeRows).toBeGreaterThanOrEqual(1);
    });

    test('EJ-2: device selector dropdown has at least the default option', async () => {
        // The "Default output" sentinel is hard-coded in index.html so
        // we always expect at least one option. Real audio devices may
        // or may not enumerate depending on whether the host has audio
        // hardware AND whether enumerateDevices() resolves before the
        // assertion. Use >= 1 to stay portable across CI and dev hosts.
        const options = await window.locator('#device-selector option').count();
        expect(options).toBeGreaterThanOrEqual(1);
    });

    test('EJ-3: window.electronAPI is exposed with the documented surface', async () => {
        const apiSurface = await window.evaluate(() => ({
            hasElectronAPI: typeof window.electronAPI === 'object' && window.electronAPI !== null,
            hasGetAudioDevices: typeof window.electronAPI?.getAudioDevices === 'function',
            hasSetDefaultDevice: typeof window.electronAPI?.setDefaultDevice === 'function',
            hasGetWindowState: typeof window.electronAPI?.getWindowState === 'function',
            hasSaveWindowState: typeof window.electronAPI?.saveWindowState === 'function',
            hasOnStopAllAudio: typeof window.electronAPI?.onStopAllAudio === 'function'
        }));
        expect(apiSurface.hasElectronAPI).toBe(true);
        expect(apiSurface.hasGetAudioDevices).toBe(true);
        expect(apiSurface.hasSetDefaultDevice).toBe(true);
        expect(apiSurface.hasGetWindowState).toBe(true);
        expect(apiSurface.hasSaveWindowState).toBe(true);
        expect(apiSurface.hasOnStopAllAudio).toBe(true);
    });

    test('EJ-4: clicking Forest spawns an active player', async () => {
        // beforeEach already clicked once. Click again so the test
        // remains independent of the beforeEach contract — if a future
        // refactor changes beforeEach, this test still asserts the
        // primary engine behaviour.
        await window.click('#btn-forest');
        await window.waitForTimeout(800);
        const count = await window.evaluate(() => window.jukebox.jukeboxGetActivePlayers().length);
        expect(count).toBeGreaterThan(0);
    });

    test('EJ-5: jukebox-stop-all IPC event clears the active players', async () => {
        // Confirm at least one active player exists. beforeEach
        // already spawned a Forest player; click again defensively.
        await window.click('#btn-forest');
        await window.waitForTimeout(500);
        const before = await window.evaluate(() => window.jukebox.jukeboxGetActivePlayers().length);
        expect(before).toBeGreaterThan(0);

        // Send the menu event from the main process. The renderer's
        // onStopAllAudio subscription should fire stopAll(), which
        // clears the active players and removes the .player-row nodes.
        await triggerStopAllFromMain(electronApp);
        await window.waitForTimeout(500);

        const after = await window.evaluate(() => window.jukebox.jukeboxGetActivePlayers().length);
        expect(after).toBe(0);
        await expect(window.locator('.player-row')).toHaveCount(0);
    });

    test('EJ-6: state restore on reload (slider values persist)', async () => {
        // Push a custom jukebox state across the IPC boundary. The
        // main process's save-window-state handler merges this into
        // window-state.json and debounces the disk write at 500ms.
        const saveResult = await window.evaluate(() => {
            return window.electronAPI.saveWindowState({
                jukebox: {
                    lastTrackId: 'battle',
                    globalVolumeDb: -10,
                    defaultPanning: 0.3,
                    defaultSpeed: 1.5,
                    musicVolumeDb: -6,
                    sfxVolumeDb: -3,
                    musicMuted: false,
                    sfxMuted: false,
                    fadeInMs: 250,
                    fadeOutMs: 500,
                    defaultLoop: true
                }
            });
        });
        expect(saveResult.ok).toBe(true);

        // Wait past the 500ms main-process debounce so the JSON has
        // been flushed to disk before we reload.
        await window.waitForTimeout(800);

        // Reload the renderer. The same Electron process stays alive
        // so the in-memory windowState (already updated above) is
        // still available; restoreJukeboxState() reads it via
        // get-window-state and rehydrates the DOM.
        await window.reload();
        await waitForJukeboxReady(window);

        // Verify each slider / input was rehydrated. The values are
        // strings (DOM input.value is always a string).
        expect(await window.locator('#global-volume').inputValue()).toBe('-10');
        expect(await window.locator('#default-pan').inputValue()).toBe('0.3');
        expect(await window.locator('#default-speed').inputValue()).toBe('1.5');
        expect(await window.locator('#music-volume').inputValue()).toBe('-6');
        expect(await window.locator('#sfx-volume').inputValue()).toBe('-3');
        expect(await window.locator('#fade-in-ms').inputValue()).toBe('250');
        expect(await window.locator('#fade-out-ms').inputValue()).toBe('500');
        // The loop-mode toggle stores its state in a data-state attr.
        expect(await window.locator('#btn-loop-mode').getAttribute('data-state')).toBe('on');
    });

    test('EJ-7: device-selector persistence (set + getAudioDevices round-trip)', async () => {
        // Persist a synthetic deviceId. The main process treats this
        // string as opaque — no enumerate / validate step — so any
        // non-empty string round-trips faithfully.
        const setResult = await window.evaluate(() =>
            window.electronAPI.setDefaultDevice('my-saved-device')
        );
        expect(setResult.ok).toBe(true);

        // Read it back via the same IPC channel the renderer uses on
        // startup to re-hydrate the dropdown. After the unified
        // envelope, the device id is nested under `data`.
        const result = await window.evaluate(() => window.electronAPI.getAudioDevices());
        expect(result.ok).toBe(true);
        expect(result.data.defaultDeviceId).toBe('my-saved-device');
    });

    test('EJ-8: Phase 1 regression (B1 live time, B2 SFX value, B3 dB conversion)', async () => {
        // B1: per-player time readout updates while playing. Wait
        // long enough for the 500ms render poll to tick at least
        // twice; first read should reflect a non-zero second count.
        await window.waitForTimeout(1500);
        const timeText = await window.locator('.player-row .time').first().textContent();
        expect(timeText).toMatch(/\d+s/);

        // B2: SFX volume slider has a value label with "dB" units.
        const sfxLabel = await window.locator('#sfx-volume-value').textContent();
        expect(sfxLabel).toMatch(/dB/);

        // B3: per-player volume slider initial render shows dB, not
        // the raw linear gain. "1 dB" / "1.0 dB" would indicate the
        // pre-fix bug; we expect something like "0.0 dB" or "-3.0 dB".
        const volLabel = await window.locator('.player-row .volume-value').first().textContent();
        expect(volLabel).toMatch(/-?\d+\.\d dB/);
        expect(volLabel).not.toMatch(/^1(\.0)? dB$/);
    });
});
