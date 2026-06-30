/**
 * Electron main process entry point for the Autogenesis Jukebox.
 *
 * Responsibilities:
 *   - Create the BrowserWindow with secure webPreferences.
 *   - Load the staged jukebox frontend (dev: build/staging/frontend,
 *     packaged: process.resourcesPath/frontend).
 *   - Persist window bounds (size, position, maximized) to userData.
 *   - Expose IPC channels for the renderer to read/write window state
 *     and audio device preferences.
 *   - Register the native application menu.
 *   - Defend against external navigation and window.open.
 *
 * This file deliberately avoids any non-Electron / non-Node built-in
 * dependencies to keep the main process small and the security surface
 * obvious.
 */

const { app, BrowserWindow, ipcMain, Menu } = require('electron');
const path = require('path');

const state = require('./state');
const { buildMenu } = require('./menu');

// ----------------------------------------------------------------------
// Module-level state
// ----------------------------------------------------------------------

/** @type {import('electron').BrowserWindow | null} */
let mainWindow = null;

/**
 * Cached window + jukebox UI state. Persisted to
 * `userData/window-state.json`. Keys include the bounds (width, height,
 * x, y, maximized) plus any `jukebox` subtree merged in via the
 * `save-window-state` IPC channel.
 *
 * @type {null | { width: number, height: number, x?: number, y?: number,
 *   maximized: boolean, jukebox?: object }}
 */
let windowState = null;

/**
 * Debounced writer for the window-state file. 500ms debounce coalesces
 * resize/move bursts into a single write. A `.flush()` method is attached
 * by `state.debounce` so we can synchronously drain pending writes on
 * `before-quit` to avoid losing the most recent bounds.
 */
const saveWindowStateDebounced = state.debounce((s) => {
    state.saveJson(state.userDataPath('window-state.json'), s);
}, 500);

// ----------------------------------------------------------------------
// Path resolution
// ----------------------------------------------------------------------

/**
 * Resolve the absolute path to the jukebox frontend `index.html`.
 *
 * In dev (`npm start`), the Gradle `stageJukeboxDist` task copies the
 * jukebox dist into `build/staging/frontend/`. In packaged builds,
 * electron-builder's `extraResources` directive copies the same dir
 * into `<resourcesPath>/frontend/`.
 *
 * @returns {string} Absolute path to index.html.
 */
function resolveFrontendIndex() {
    if (app.isPackaged) {
        return path.join(process.resourcesPath, 'frontend', 'index.html');
    }
    return path.join(__dirname, '..', '..', 'build', 'staging', 'frontend', 'index.html');
}

// ----------------------------------------------------------------------
// Window state IO
// ----------------------------------------------------------------------

/**
 * Load the persisted window state at startup, falling back to a safe
 * default if the file is missing or corrupt.
 *
 * @returns {{ width: number, height: number, x?: number, y?: number,
 *   maximized: boolean, jukebox?: object }}
 */
function loadInitialWindowState() {
    return state.loadJson(state.userDataPath('window-state.json'), {
        width: 1280,
        height: 800,
        maximized: false,
    });
}

/**
 * Capture the current BrowserWindow bounds and schedule a debounced
 * write to disk. Safe to call repeatedly during a resize drag — only the
 * most recent call within the debounce window actually writes.
 */
function persistWindowBounds() {
    if (!mainWindow || mainWindow.isDestroyed()) {
        return;
    }
    const bounds = mainWindow.getBounds();
    windowState = {
        ...(windowState || {}),
        width: bounds.width,
        height: bounds.height,
        x: bounds.x,
        y: bounds.y,
        maximized: mainWindow.isMaximized(),
    };
    saveWindowStateDebounced(windowState);
}

// ----------------------------------------------------------------------
// Window creation
// ----------------------------------------------------------------------

function createWindow() {
    const initial = windowState || { width: 1280, height: 800, maximized: false };

    mainWindow = new BrowserWindow({
        width: initial.width,
        height: initial.height,
        x: typeof initial.x === 'number' ? initial.x : undefined,
        y: typeof initial.y === 'number' ? initial.y : undefined,
        backgroundColor: '#0d1117',
        title: 'Autogenesis Jukebox',
        show: false,
        webPreferences: {
            contextIsolation: true,
            nodeIntegration: false,
            sandbox: true,
            webSecurity: true,
            preload: path.join(__dirname, 'preload.js'),
        },
    });

    if (initial.maximized) {
        mainWindow.maximize();
    }

    // Show the window only after `ready-to-show` to avoid the white flash
    // that some Linux compositors produce when the window is first laid
    // out. Showing later is purely cosmetic but improves perceived startup.
    mainWindow.once('ready-to-show', () => {
        if (mainWindow && !mainWindow.isDestroyed()) {
            mainWindow.show();
        }
    });

    mainWindow.loadFile(resolveFrontendIndex());

    // Persist bounds on every meaningful geometry change. Debounce in
    // `saveWindowStateDebounced` coalesces these into a single write.
    mainWindow.on('resize', persistWindowBounds);
    mainWindow.on('move', persistWindowBounds);
    mainWindow.on('maximize', persistWindowBounds);
    mainWindow.on('unmaximize', persistWindowBounds);
    mainWindow.on('close', () => {
        // Capture final bounds synchronously. The debounce schedules the
        // disk write; `before-quit` will flush it.
        persistWindowBounds();
    });
    mainWindow.on('closed', () => {
        mainWindow = null;
    });

    Menu.setApplicationMenu(buildMenu(mainWindow));
    return mainWindow;
}

// ----------------------------------------------------------------------
// IPC handlers
// ----------------------------------------------------------------------

/**
 * Read the persisted default audio device id from
 * `userData/audio-prefs.json`. The main process does NOT enumerate
 * MediaDevices — that is a renderer-only API. The renderer enumerates
 * devices via `navigator.mediaDevices` and calls `set-default-device`
 * with the chosen deviceId. The main process simply persists that id
 * and returns it on `get-audio-devices` so the renderer can re-hydrate
 * its dropdown on startup.
 *
 * @returns {{ defaultDeviceId: string }}
 */
function readAudioPrefs() {
    const prefs = state.loadJson(state.userDataPath('audio-prefs.json'), {
        defaultDeviceId: 'default',
    });
    return {
        defaultDeviceId: typeof prefs.defaultDeviceId === 'string'
            ? prefs.defaultDeviceId
            : 'default',
    };
}

function registerIpcHandlers() {
    /**
     * Returns the persisted default device id. The renderer should call
     * `navigator.mediaDevices.enumerateDevices()` to get the full list and
     * use the returned id as a hint for the initial selection.
     *
     * Unified envelope: `{ ok: true, data: { defaultDeviceId } }` on
     * success, `{ ok: false, error: string }` on failure.
     *
     * @returns {Promise<{ ok: true, data: { defaultDeviceId: string } } | { ok: false, error: string }>}
     */
    ipcMain.handle('get-audio-devices', async () => {
        try {
            return { ok: true, data: readAudioPrefs() };
        } catch (err) {
            return {
                ok: false,
                error: err && err.message ? err.message : String(err),
            };
        }
    });

    /**
     * Persist the user-selected output deviceId. The deviceId is opaque
     * to the main process — it is whatever the renderer got from
     * `enumerateDevices()`.
     *
     * Unified envelope: `{ ok: true }` on success, `{ ok: false, error: string }`
     * on failure.
     *
     * @param {import('electron').IpcMainInvokeEvent} _event
     * @param {string} deviceId
     * @returns {Promise<{ ok: boolean, error?: string }>}
     */
    ipcMain.handle('set-default-device', async (_event, deviceId) => {
        if (typeof deviceId !== 'string' || deviceId.length === 0) {
            return { ok: false, error: 'deviceId must be a non-empty string' };
        }
        const result = state.saveJson(state.userDataPath('audio-prefs.json'), {
            defaultDeviceId: deviceId,
        });
        return { ok: !!result.success, error: result.error };
    });

    /**
     * Returns the current window + jukebox UI state. Used by the renderer
     * on startup to re-hydrate UI state (selected device, last-played
     * track, volume sliders, etc).
     *
     * Unified envelope: `{ ok: true, data: stateObject }` on success,
     * `{ ok: false, error: string }` on failure. The state object is
     * nested under `data`.
     *
     * @returns {Promise<{ ok: true, data: object } | { ok: false, error: string }>}
     */
    ipcMain.handle('get-window-state', async () => {
        try {
            const stateObj = windowState || { width: 1280, height: 800, maximized: false };
            return { ok: true, data: stateObj };
        } catch (err) {
            return {
                ok: false,
                error: err && err.message ? err.message : String(err),
            };
        }
    });

    /**
     * Merge the renderer's jukebox UI state into the persisted window
     * state. Size/position keys are still owned by the main process; the
     * renderer supplies the `jukebox` subtree. The merged object is
     * debounced to disk.
     *
     * Unified envelope: `{ ok: true }` on success, `{ ok: false, error: string }`
     * on failure.
     *
     * @param {import('electron').IpcMainInvokeEvent} _event
     * @param {object} partialState Subtree to merge into the persisted state.
     * @returns {Promise<{ ok: boolean, error?: string }>}
     */
    ipcMain.handle('save-window-state', async (_event, partialState) => {
        try {
            if (!partialState || typeof partialState !== 'object') {
                return { ok: false, error: 'partialState must be an object' };
            }
            windowState = { ...(windowState || {}), ...partialState };
            saveWindowStateDebounced(windowState);
            return { ok: true };
        } catch (err) {
            const message = err && err.message ? err.message : String(err);
            return { ok: false, error: message };
        }
    });
}

// ----------------------------------------------------------------------
// Security: navigation and window.open
// ----------------------------------------------------------------------

function registerSecurityHandlers() {
    // Block new window.open() requests unconditionally. The jukebox UI does
    // not open child windows; if a future feature needs to, switch this to
    // a same-origin allowlist.
    app.on('web-contents-created', (_event, contents) => {
        contents.setWindowOpenHandler(() => ({ action: 'deny' }));
    });

    // Block in-page navigation away from the local file:// origin. The
    // jukebox is a standalone desktop app with no remote network needs;
    // any `window.location = ...` to a non-file URL is treated as hostile.
    app.on('web-contents-created', (_event, contents) => {
        contents.on('will-navigate', (event, url) => {
            try {
                const parsed = new URL(url);
                if (parsed.protocol !== 'file:') {
                    event.preventDefault();
                }
            } catch (_parseErr) {
                event.preventDefault();
            }
        });
        // Same guard for `window.location = url` redirects.
        contents.on('will-redirect', (event, url) => {
            try {
                const parsed = new URL(url);
                if (parsed.protocol !== 'file:') {
                    event.preventDefault();
                }
            } catch (_parseErr) {
                event.preventDefault();
            }
        });
    });
}

// ----------------------------------------------------------------------
// Lifecycle
// ----------------------------------------------------------------------

app.whenReady().then(() => {
    try {
        windowState = loadInitialWindowState();
    } catch (_err) {
        // State IO failure should not prevent the app from starting; fall
        // back to the defaults baked into loadInitialWindowState.
        windowState = { width: 1280, height: 800, maximized: false };
    }
    registerSecurityHandlers();
    registerIpcHandlers();
    createWindow();

    app.on('activate', () => {
        // macOS dock reactivation: re-create the window if all are closed.
        if (BrowserWindow.getAllWindows().length === 0) {
            createWindow();
        }
    });
});

app.on('window-all-closed', () => {
    // Drain any pending debounced writes before quitting so a fast close
    // does not lose the last resize/move state.
    saveWindowStateDebounced.flush();
    if (process.platform !== 'darwin') {
        app.quit();
    }
});

app.on('before-quit', () => {
    saveWindowStateDebounced.flush();
});
