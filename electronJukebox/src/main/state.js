/**
 * Pure-Node state IO helpers for the jukebox Electron main process.
 *
 * The `loadJson`, `saveJson`, and `debounce` helpers in this module are
 * intentionally Electron-free so they can be exercised in plain Node
 * during unit tests. The `userDataPath` helper does need `app.getPath`
 * and therefore lazy-requires `electron`; it is only valid inside an
 * Electron main process (after `app.whenReady()`).
 */

const fs = require('fs');
const path = require('path');

// ----------------------------------------------------------------------
// JSON file IO
// ----------------------------------------------------------------------

/**
 * Read a JSON file and return its parsed contents, or `fallback` on any error.
 *
 * All failure modes — missing file, empty file, permission denied, invalid
 * JSON — collapse to `fallback` so callers do not have to defend against
 * partial state files. Corrupt state is treated the same as missing state.
 *
 * @param {string} filepath Absolute or relative path to a JSON file.
 * @param {*} fallback Value to return if the file cannot be read or parsed.
 * @returns {*} Parsed JSON value, or `fallback` on any failure.
 */
function loadJson(filepath, fallback) {
    try {
        const raw = fs.readFileSync(filepath, 'utf8');
        if (raw.length === 0) {
            return fallback;
        }
        return JSON.parse(raw);
    } catch (_err) {
        // ENOENT, EACCES, SyntaxError, etc. — all fall back silently.
        return fallback;
    }
}

/**
 * Write a JSON file atomically using a write-then-rename strategy.
 *
 * The data is first serialized to a temp file at `filepath + '.tmp'`, then
 * `fs.renameSync` swaps it into place. This avoids the classic partial-write
 * failure mode where a crash mid-write leaves the file truncated.
 *
 * Parent directories are created with `recursive: true` so callers can pass
 * deep paths (e.g. `<userData>/subdir/state.json`) without pre-creating them.
 *
 * @param {string} filepath Target path for the JSON file.
 * @param {*} data Serializable value (will be JSON.stringified with 2-space indent).
 * @returns {{ success: boolean, error?: string }} Result descriptor.
 */
function saveJson(filepath, data) {
    const tmpPath = filepath + '.tmp';
    try {
        const dir = path.dirname(filepath);
        fs.mkdirSync(dir, { recursive: true });
        fs.writeFileSync(tmpPath, JSON.stringify(data, null, 2), 'utf8');
        fs.renameSync(tmpPath, filepath);
        return { success: true };
    } catch (err) {
        // Best-effort cleanup of the temp file; do not let a leftover tmp mask
        // the original error message.
        try {
            fs.unlinkSync(tmpPath);
        } catch (_cleanupErr) {
            // ignore — original error is what matters
        }
        const message = err && err.message ? err.message : String(err);
        return { success: false, error: message };
    }
}

// ----------------------------------------------------------------------
// Debounce
// ----------------------------------------------------------------------

/**
 * Wrap a function in trailing-edge debounce.
 *
 * Each call to the wrapped function cancels any pending invocation and
 * schedules a new one `ms` milliseconds in the future. A `.flush()` method
 * is attached so callers can synchronously run the latest pending call
 * during shutdown (e.g. `before-quit`) to avoid losing unflushed writes.
 *
 * @template {(...args: any[]) => any} F
 * @param {F} fn The function to debounce.
 * @param {number} ms Delay in milliseconds.
 * @returns {F & { flush: () => void, cancel: () => void }} Wrapped function.
 */
function debounce(fn, ms) {
    let timer = null;
    /** @type {any[] | null} */
    let pendingArgs = null;

    const wrapped = function (...args) {
        pendingArgs = args;
        if (timer !== null) {
            clearTimeout(timer);
        }
        timer = setTimeout(() => {
            timer = null;
            if (pendingArgs !== null) {
                const callArgs = pendingArgs;
                pendingArgs = null;
                fn.apply(undefined, callArgs);
            }
        }, ms);
    };

    wrapped.flush = function () {
        if (timer !== null) {
            clearTimeout(timer);
            timer = null;
        }
        if (pendingArgs !== null) {
            const callArgs = pendingArgs;
            pendingArgs = null;
            fn.apply(undefined, callArgs);
        }
    };

    wrapped.cancel = function () {
        if (timer !== null) {
            clearTimeout(timer);
            timer = null;
        }
        pendingArgs = null;
    };

    return wrapped;
}

// ----------------------------------------------------------------------
// userData path helper
// ----------------------------------------------------------------------

/**
 * Build an absolute path inside Electron's per-user data directory.
 *
 * This lazy-requires `electron` because the rest of the module is designed
 * to load in plain Node. This function is only meaningful inside an Electron
 * main process (after `app.whenReady()` has resolved). Calling it from a
 * plain-Node context throws an explicit error rather than silently producing
 * a broken path.
 *
 * @param {string} filename File name (not a path) inside userData.
 * @returns {string} Absolute path: `<userData>/<filename>`.
 */
function userDataPath(filename) {
    // Lazy require: keep the rest of this module usable in plain Node, where
    // `require('electron')` returns a path string (not the API).
    const { app } = require('electron');
    if (!app || typeof app.getPath !== 'function') {
        throw new Error('userDataPath() must be called inside an Electron main process');
    }
    return path.join(app.getPath('userData'), filename);
}

module.exports = {
    loadJson,
    saveJson,
    debounce,
    userDataPath,
};
