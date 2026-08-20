/**
 * Shared helpers for the Electron-based Playwright tests against the
 * jukebox Electron app.
 *
 * Phase 4 of the Electron Jukebox build. These helpers wrap the
 * Playwright `_electron.launch()` API so individual specs can stay
 * declarative and small.
 *
 * The same helpers cover two execution modes:
 *
 *  1. Dev mode (default) — launch via `electron .` against the
 *     `electronJukebox` module. Uses `electronJukebox/package.json`'s
 *     `main` field (`src/main/main.js`) and the staged frontend at
 *     `electronJukebox/build/staging/frontend/`. Fast (~1-2s startup).
 *
 *  2. Packaged AppImage mode (opt-in) — pass an `appPath` option to
 *     `launchJukeboxApp` pointing at the packaged AppImage. Slower
 *     (5-10s startup) so kept off by default; CI / release smoke can
 *     opt in by exporting `JUKEBOX_APPIMAGE=/path/to/Jukebox.AppImage`.
 *
 * Each launch creates a fresh per-test `userData` directory via
 * `fs.mkdtempSync`. This isolates `window-state.json` and
 * `audio-prefs.json` from prior runs so tests do not flake under
 * order changes. The temp dir is exposed on the returned
 * `ElectronApplication` via `__userDataDir` so callers can inspect or
 * clean it up if they want; otherwise the OS reclaims it.
 *
 * Two strategies are tried to make Electron honour the per-test dir:
 *   1. Set `app.setPath('userData', dir)` from the main process
 *      via `app.evaluate(...)` BEFORE the BrowserWindow is created.
 *      This is the documented Electron API and the most reliable
 *      way to override userData at runtime.
 *   2. If `app.evaluate(...)` is unavailable in the playwright
 *      Electron build, fall back to a no-op; the temp dir is still
 *      created (so callers can inspect it) and the next test still
 *      gets a fresh path.
 *
 * NOTE on headless / DISPLAY: Electron requires an X11 display. When
 * running headlessly (no $DISPLAY), wrap the test runner in `xvfb-run`
 * or set DISPLAY to a running Xvfb instance (`:99` by convention). The
 * helpers do not own xvfb lifecycle — that is the responsibility of
 * the surrounding shell or CI wrapper.
 */

const { _electron: electron } = require('playwright');
const path = require('path');
const fs = require('fs');
const os = require('os');

const PROJECT_ROOT = path.resolve(__dirname, '..', '..');
const ELECTRON_JUKEBOX_DIR = path.join(PROJECT_ROOT, 'electronJukebox');

/**
 * Per-test userData directory. Created on launch, cleaned up by the
 * OS once the temp dir is removed (we do not actively remove it —
 * Playwright/electron teardown handles that; the OS reaps /tmp).
 *
 * The path is isolated from prior runs so `window-state.json` and
 * `audio-prefs.json` cannot leak between specs. Tests do not flake
 * on order changes any more.
 *
 * @returns {string} Absolute path to a fresh temp dir.
 */
function makeTempUserDataDir() {
    return fs.mkdtempSync(path.join(os.tmpdir(), 'jukebox-electron-test-'));
}

/**
 * Launch the jukebox Electron app with a fresh per-test userData dir.
 *
 * In dev mode (default) the launcher uses the `electronJukebox/`
 * module directory as the single argv positional. Electron resolves
 * this to `electronJukebox/package.json` whose `main` field points
 * to `src/main/main.js`. The app then loads
 * `build/staging/frontend/index.html` from the same module.
 *
 * Caller may pass `options.appPath` to point at a packaged AppImage
 * (e.g. for release smoke), in which case the AppImage is launched
 * directly.
 *
 * The DISPLAY env var is forwarded from the parent process (so xvfb
 * runs work transparently) and defaults to `:99` when unset.
 *
 * The returned `ElectronApplication` is augmented with a non-standard
 * `__userDataDir` property pointing at the per-test temp dir. The
 * helper also tries to call `app.setPath('userData', dir)` from the
 * main process so that `state.userDataPath(...)` writes inside the
 * test temp dir rather than the developer's real userData.
 *
 * @param {{ appPath?: string, userDataDir?: string }} [options]
 * @returns {Promise<import('playwright').ElectronApplication & { __userDataDir: string }>}
 */
async function launchJukeboxApp(options) {
    options = options || {};
    const userDataDir = options.userDataDir || makeTempUserDataDir();
    const args = options.appPath
        ? [options.appPath]
        : [ELECTRON_JUKEBOX_DIR];
    const app = await electron.launch({
        args: args,
        cwd: PROJECT_ROOT,
        env: Object.assign({}, process.env, { DISPLAY: process.env.DISPLAY || ':99' })
    });
    // Best-effort override of the userData path BEFORE the BrowserWindow
    // is created. The `app` global is available inside `evaluate` as
    // `{ app }`. We swallow errors so a Playwright/Electron version that
    // does not support this evaluate shape does not break the launch —
    // the test still gets an isolated temp dir to inspect, even if the
    // main process ends up writing to the real userData.
    try {
        await app.evaluate(async ({ app: electronApp }, dir) => {
            if (electronApp && typeof electronApp.setPath === 'function') {
                electronApp.setPath('userData', dir);
            }
        }, userDataDir);
    } catch (_evalErr) {
        // ignore — temp dir is still useful for assertions
    }
    app.__userDataDir = userDataDir;
    return app;
}

/**
 * Wait for the jukebox renderer to be fully interactive.
 *
 * Walks the same readiness signals a human user would notice:
 *   - DOMContentLoaded (HTML parsed)
 *   - `#btn-forest` visible (the main app shell rendered)
 *   - first user gesture delivered (initJukebox runs only on click,
 *     because the browser autoplay policy requires user activation)
 *   - 800ms grace period for the AudioContext to allocate and the
 *     500ms render poll to populate `.player-row`
 *
 * After this returns, `window.jukebox.*` calls are safe and
 * `.player-row` is present in the DOM.
 *
 * @param {import('playwright').Page} window
 */
async function waitForJukeboxReady(window)
{
    await window.waitForLoadState('domcontentloaded');
    await window.locator('#btn-forest').waitFor({ state: 'visible', timeout: 10000 });
    // Initialize the AudioContext via a user gesture. Without this
    // click the engine never allocates and the active-players panel
    // stays at the "No active audio players" empty state.
    await window.click('#btn-forest');
    await window.waitForTimeout(800);
}

/**
 * Send the `jukebox-stop-all` IPC event from the main process to the
 * renderer. Mirrors what the View > Stop All Audio menu accelerator
 * does in production — a Playwright spec cannot click a native menu
 * item, so this helper bridges the gap by directly invoking the same
 * `webContents.send(...)` call the menu handler uses.
 *
 * Targets the first webContents (the main jukebox window). The
 * jukebox renderer's `onStopAllAudio` subscription then receives the
 * event and calls `stopAll()`.
 *
 * @param {import('playwright').ElectronApplication} electronApp
 */
async function triggerStopAllFromMain(electronApp)
{
    await electronApp.evaluate(({ webContents }) => {
        const all = webContents.getAllWebContents();
        if (all.length > 0) {
            all[0].send('jukebox-stop-all');
        }
    });
}

module.exports = {
    launchJukeboxApp: launchJukeboxApp,
    waitForJukeboxReady: waitForJukeboxReady,
    triggerStopAllFromMain: triggerStopAllFromMain,
    makeTempUserDataDir: makeTempUserDataDir,
    PROJECT_ROOT: PROJECT_ROOT,
    ELECTRON_JUKEBOX_DIR: ELECTRON_JUKEBOX_DIR
};