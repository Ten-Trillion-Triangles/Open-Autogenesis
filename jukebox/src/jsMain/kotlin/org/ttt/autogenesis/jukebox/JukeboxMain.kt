package org.ttt.autogenesis.jukebox

import kotlinx.browser.window

/**
 * Bridge from the Kotlin/JS module scope to the browser's `window` object.
 *
 * In `binaries.executable()` mode, Kotlin/JS IR only runs the `fun main()`
 * entry point — top-level vals are NOT evaluated at module load. This main
 * function is the engine of the bridge: it runs as soon as the bundle
 * evaluates, populating `window.jukebox` with the JukeboxAudioEngine object
 * so the web UI can reach the audio engine without any further wiring.
 *
 * The companion webpack `output.library = { name: "JukeboxExports", type: "window" }`
 * config (set in webpack.config.d/jukebox-ui.js) also assigns the module's
 * @JsExport'd exports to `window.JukeboxExports`, but the simpler
 * `window.jukebox.X` form is what `app.js` uses.
 */
fun main() {
    window.asDynamic().jukebox = JukeboxAudioEngine
    console.info("Jukebox: window.jukebox = JukeboxAudioEngine (standalone mode — no server connection)")
}
