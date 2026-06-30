/**
 * Playwright spec for the jukebox Phase 1 fixes.
 *
 * Covers the runtime assertions for:
 *  - B1: live currentTimeMs display
 *  - B2: SFX slider value label
 *  - B3: per-player volume slider shows "0.0 dB" on initial render
 *  - B4: visualizer "freqData unavailable" warn fires at most once per session
 *  - B5: Stop All button clears active players + setInterval
 *  - B6: loop-mode toggle drives the default loop flag on subsequent plays
 *  - A2: window.jukebox.close is a function
 *
 * Usage:
 *   1. Build the jukebox dist:
 *        ./gradlew :jukebox:build
 *   2. Serve the dist:
 *        python3 -m http.server 8782 \
 *          --directory jukebox/build/dist/js/productionExecutable
 *   3. Run with playwright:
 *        npx playwright test tests/playwright/jukebox-100pct.spec.js
 *
 * Pre-req: the page must be allowed to autoplay (the first click triggers
 * initJukebox). The spec is designed to run against the headless browser
 * with `--autoplay-policy=no-user-gesture-required` or to dispatch synthetic
 * clicks before each assertion.
 */

const { test, expect } = require('@playwright/test');

const URL = 'http://localhost:8782/index.html';

test.describe('Jukebox Phase 1 fixes', () => {
    test.beforeEach(async ({ page }) => {
        // Collect console messages for the B4 (warn-once) assertion.
        const consoleMessages = [];
        page.on('console', msg => consoleMessages.push({ type: msg.type(), text: msg.text() }));

        await page.goto(URL);

        // First user gesture to trigger initJukebox.
        await page.click('#btn-forest');
        // Give the engine a moment to allocate the AudioContext.
        await page.waitForTimeout(500);
    });

    test('A2: window.jukebox.close is a function', async ({ page }) => {
        const closeType = await page.evaluate(() => typeof window.jukebox.close);
        expect(closeType).toBe('function');
    });

    test('B1: live currentTimeMs is displayed per active player', async ({ page }) => {
        // The first click (in beforeEach) spawned a Forest player. Its
        // currentTimeMs should be > 0 after a short wait.
        await page.waitForTimeout(2000);
        const timeText = await page.locator('.player-row .time').first().textContent();
        expect(timeText).toMatch(/\d+s/);
        const seconds = parseInt(timeText);
        expect(seconds).toBeGreaterThanOrEqual(1);
    });

    test('B2: SFX volume slider has a value label', async ({ page }) => {
        const label = await page.locator('#sfx-volume-value').textContent();
        expect(label).toMatch(/dB/);
    });

    test('B3: per-player volume slider shows dB on initial render', async ({ page }) => {
        const label = await page.locator('.player-row .volume-value').first().textContent();
        // Per the B3 fix, the freshly-spawned player should show "0.0 dB"
        // (or close to it for any non-unity default volume). The bug was
        // that it showed the raw linear gain (e.g. "1 dB").
        expect(label).toMatch(/-?\d+\.\d dB/);
        // Sanity: the value shouldn't be the raw linear gain (1 dB or 1.0 dB).
        expect(label).not.toMatch(/^1(\.0)? dB$/);
    });

    test('B5: Stop All clears active players', async ({ page }) => {
        // Confirm there is at least one active player from the beforeEach.
        const initialCount = await page.evaluate(() => window.jukebox.jukeboxGetActivePlayers().length);
        expect(initialCount).toBeGreaterThan(0);

        // Click Stop All.
        await page.click('#btn-stop-all');
        await page.waitForTimeout(500);

        // Active players should be empty.
        const afterCount = await page.evaluate(() => window.jukebox.jukeboxGetActivePlayers().length);
        expect(afterCount).toBe(0);

        // The active-players panel should be empty (no rows).
        await expect(page.locator('.player-row')).toHaveCount(0);

        // A subsequent play click works immediately because Stop All does
        // not close the AudioContext — only the active players. The next
        // play starts a new player without needing a re-init gesture.
        await page.click('#btn-forest');
        await page.waitForTimeout(800);
        const rePlayCount = await page.evaluate(() => window.jukebox.jukeboxGetActivePlayers().length);
        expect(rePlayCount).toBeGreaterThan(0);
    });

    test('B6: loop-mode toggle drives the default loop flag on subsequent plays', async ({ page }) => {
        // Toggle the loop-mode button On.
        await page.click('#btn-loop-mode');
        const state = await page.locator('#btn-loop-mode').getAttribute('data-state');
        expect(state).toBe('on');

        // The engine's default loop should now be true.
        const defaultLoop = await page.evaluate(() => window.jukebox.getDefaultLoop());
        expect(defaultLoop).toBe(true);

        // Play a fresh track; loop flag should propagate to the player.
        await page.click('#btn-battle');
        await page.waitForTimeout(1000);

        // The freshly-spawned player should have loop = true.
        const loop = await page.evaluate(() => {
            const players = window.jukebox.jukeboxGetActivePlayers();
            return players[0] && players[0].loop;
        });
        expect(loop).toBe(true);
    });

    test('B4: visualizer warn-once (not per-poll)', async ({ page }) => {
        // Reset the per-session warn flag (it was already set by the
        // beforeEach's first-click). To exercise the warn path, monkey-patch
        // jukeboxGetFrequencyData to return null.
        await page.evaluate(() => {
            window.__freqWarnCount = 0;
            window.jukebox.jukeboxGetFrequencyData = function() {
                window.__freqWarnCount++;
                return null;
            };
            // Reset the per-session flag so the warn can fire at most once
            // more after our patches.
            window.__visualizerWarned = false;
        });

        // Capture new console messages only from this point on.
        const newWarns = [];
        page.on('console', msg => {
            if (msg.type() === 'warning' && /freqData unavailable/.test(msg.text())) {
                newWarns.push(msg.text());
            }
        });

        // Wait for ~3 polls (500ms each).
        await page.waitForTimeout(2000);

        const warnCount = newWarns.length;
        expect(warnCount).toBeLessThanOrEqual(1);
    });
});

// ---------------------------------------------------------------------------
// Electron-context regression: re-run the Phase 1 fixes against the Electron
// Chromium that ships with the Autogenesis Jukebox. The original tests above
// run against the served dist (python -m http.server); these run against the
// same code, but loaded by Electron via main.js / preload.js. The intent is
// to catch regressions where the preload bridge, BrowserWindow webPreferences,
// or the file:// load path silently break a Phase 1 fix.
//
// The describe is serial (one Electron process at a time) because all
// instances share the same userData dir. afterEach closes the process so the
// next test starts clean.
// ---------------------------------------------------------------------------

const { _electron: electron } = require('playwright');
const path = require('path');

const ELECTRON_JUKEBOX_DIR = path.resolve(__dirname, '..', '..', 'electronJukebox');

test.describe.configure({ mode: 'serial' });

test.describe('Jukebox Phase 1 fixes in Electron context', () => {
    /** @type {import('playwright').ElectronApplication} */
    let electronApp;
    /** @type {import('playwright').Page} */
    let window;

    test.beforeEach(async () => {
        electronApp = await electron.launch({
            args: [ELECTRON_JUKEBOX_DIR],
            env: Object.assign({}, process.env, { DISPLAY: process.env.DISPLAY || ':99' })
        });
        window = await electronApp.firstWindow();
        await window.waitForLoadState('domcontentloaded');
        // First user gesture to kick the AudioContext alive, mirroring
        // the original describe's beforeEach.
        await window.click('#btn-forest');
        await window.waitForTimeout(500);
    });

    test.afterEach(async () => {
        if (electronApp) {
            await electronApp.close();
        }
    });

    test('B1 in Electron: live currentTimeMs is displayed per active player', async () => {
        await window.waitForTimeout(1500);
        const timeText = await window.locator('.player-row .time').first().textContent();
        expect(timeText).toMatch(/\d+s/);
    });

    test('B2 in Electron: SFX volume slider has a value label', async () => {
        const label = await window.locator('#sfx-volume-value').textContent();
        expect(label).toMatch(/dB/);
    });

    test('B3 in Electron: per-player volume slider shows dB on initial render', async () => {
        const label = await window.locator('.player-row .volume-value').first().textContent();
        expect(label).toMatch(/-?\d+\.\d dB/);
        // Sanity: pre-fix, this was the raw linear gain ("1 dB" / "1.0 dB").
        expect(label).not.toMatch(/^1(\.0)? dB$/);
    });

    test('B5 in Electron: in-UI Stop All button clears active players', async () => {
        const before = await window.evaluate(() => window.jukebox.jukeboxGetActivePlayers().length);
        expect(before).toBeGreaterThan(0);

        await window.click('#btn-stop-all');
        await window.waitForTimeout(500);

        const after = await window.evaluate(() => window.jukebox.jukeboxGetActivePlayers().length);
        expect(after).toBe(0);
    });

    test('B6 in Electron: loop-mode toggle drives the default loop flag', async () => {
        await window.click('#btn-loop-mode');
        const state = await window.locator('#btn-loop-mode').getAttribute('data-state');
        expect(state).toBe('on');
        const defaultLoop = await window.evaluate(() => window.jukebox.getDefaultLoop());
        expect(defaultLoop).toBe(true);
    });
});
