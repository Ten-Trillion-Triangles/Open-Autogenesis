# Autogenesis Jukebox

The **jukebox** module is a standalone developer test tool for the Autogenesis
audio system. It renders a browser UI (HTML/CSS/vanilla JS) that drives a
Kotlin/JS `AudioEngine` to play, mix, and visualise audio sources in real
time. The same engine powers the main `kvisionApp` game client, so the
jukebox is the fastest way to verify that an audio change still works
end-to-end.

The jukebox ships in two flavours:

| Form | Module | Audience |
|------|--------|----------|
| **Browser tab** — `python3 -m http.server` over the dist | `:jukebox` | Quick smoke tests, CI |
| **Electron desktop app** — AppImage / NSIS installer | `:electronJukebox` | Long-running developer sessions, audio device routing |

Both flavours share the same Kotlin/JS bundle produced by
`../gradlew :jukebox:packageJukeboxDistribution`.

> **About the paths in this README:** the Gradle wrapper lives at the
> project root, one directory **above** this file — so every `gradlew`
> command below uses `../gradlew`, matching the convention in
> `mapEditor/README.md`. Click-to-run works because Fenix runs the
> command with the current working directory set to where the README
> lives (i.e. `jukebox/`); `../gradlew` then resolves to the project
> root and everything works.

> **Click-to-run:** every `bash` code fence in this README is independently
> clickable in the Fenix-enabled VS Code workspace — click the run button
> (▷) on any fence to launch that one task in the integrated terminal.
> Each task lives in its own fence so a click only fires the command you
> intended, never the whole block.

---

## Browser tasks (`:jukebox`)

### Build & package the browser dist (`packageJukeboxDistribution`)

Builds the Kotlin/JS bundle and copies `index.html` / `app.js` /
`styles.css` + audio assets into `build/dist/js/productionExecutable/`.

```bash
../gradlew :jukebox:packageJukeboxDistribution
```

### Run the jukebox app in your browser (recommended)

One click — builds the dist if it's missing, starts a local HTTP server
against the dist path, and opens the jukebox in your default browser.
Works from any CWD because the script resolves the repo root from its
own location. Press `Ctrl-C` in the terminal to stop the server.

```bash
./scripts/serve-jukebox.sh
```

Override the port (default `8080`):

```bash
./scripts/serve-jukebox.sh 9090
```

Headless / CI — same script, no browser auto-open:

```bash
JUKEBOX_NO_OPEN=1 ./scripts/serve-jukebox.sh
```

Skip the auto-build step (fail if the dist is missing):

```bash
JUKEBOX_AUTO_BUILD=0 ./scripts/serve-jukebox.sh
```

### Start the development server (`jsBrowserDevelopmentRun`)

Launches a webpack dev server on `http://localhost:8080` with hot reload.
The browser autoplay policy still requires a click on `#btn-forest` (or any
track button) before the `AudioContext` allocates.

```bash
../gradlew :jukebox:jsBrowserDevelopmentRun
```

### Build the production webpack bundle (`jsBrowserProductionWebpack`)

Same as `packageJukeboxDistribution` but stops short of the flat-layout
copy step — useful when you want to inspect the raw `productionExecutable`
output without re-staging. **Do not point `python3 -m http.server` at the
intermediate `build/compileSync/.../kotlin/` output** — that directory has
no `index.html` and you'll just see a raw file listing.

```bash
../gradlew :jukebox:jsBrowserProductionWebpack
```

### Run the Kotlin/JS unit tests (`jsTest`)

Runs the `kotlin.test` suites under `jukebox/src/jsTest/kotlin/...`
via karma + headless Chromium. No display required.

```bash
../gradlew :jukebox:jsTest
```

### Run the Playwright smoke suite (`jukebox-100pct.spec.js`)

Drives the rendered UI through every documented audio feature
(play / pause / stop / volume / frequency visualiser / device
selector / state restore). Spec lives at
`tests/playwright/jukebox-100pct.spec.js`.

```bash
npx playwright test ../tests/playwright/jukebox-100pct.spec.js
```

### Run the Electron Playwright suite (`electron-jukebox.spec.js`)

Same coverage, but launches the app via `_electron.launch()`. Requires
X11 — wrap in `xvfb-run` or set `DISPLAY=:99` on a running Xvfb
instance. Spec lives at `tests/playwright/electron-jukebox.spec.js`.

```bash
xvfb-run npx playwright test ../tests/playwright/electron-jukebox.spec.js
```

---

## Electron tasks (`:electronJukebox`)

The Electron module is a thin desktop shell. It does not compile any
Kotlin/JS — it stages the same `:jukebox` dist and hands it to
`electron-builder` for packaging.

### Stage the frontend + config (`stageResources`)

Copies `jukebox/build/dist/js/productionExecutable/` into
`electronJukebox/build/staging/frontend/` and writes a placeholder
`build/staging/config/app-config.json`. The Electron `main.js` expects
both to exist at runtime.

```bash
../gradlew :electronJukebox:stageResources
```

### Install Electron + electron-builder (`npmInstall`)

Runs `npm install` against the bundled cache (controlled by
`XDG_CACHE_HOME` / `ELECTRON_CACHE` / `npm_config_cache`). Most other
Electron tasks depend on this implicitly.

```bash
../gradlew :electronJukebox:npmInstall
```

### Package Linux installers (`packageLinux`)

Produces under `electronJukebox/build/electron/`:

- `Autogenesis-Jukebox-linux-<version>.AppImage` — single-file portable
- `Autogenesis-Jukebox-linux-<version>.deb` — Debian/Ubuntu installer
- `linux-unpacked/` — uncompressed dir layout for debugging

```bash
../gradlew :electronJukebox:packageLinux
```

If `chrome-sandbox` fails to start after install, fix its ownership:

```bash
sudo chown root /opt/Autogenesis-Jukebox/chrome-sandbox && sudo chmod 4755 /opt/Autogenesis-Jukebox/chrome-sandbox
```

### Package Windows installers (`packageWindows`)

Produces under `electronJukebox/build/electron/`:

- `Autogenesis-Jukebox-windows-<version>-portable.exe` — no-install
- `Autogenesis-Jukebox-windows-<version>-setup.exe` — NSIS installer
- `win-unpacked/` — uncompressed dir layout for debugging

Must be run on Windows or via a Windows CI runner.

```bash
../gradlew :electronJukebox:packageWindows
```

### Build all Electron artifacts (`packageJukeboxElectronAll`)

Convenience aggregate: runs `packageLinux` + `packageWindows` in one
task. Useful for nightly CI; for local development prefer the
platform-specific task.

```bash
../gradlew :electronJukebox:packageJukeboxElectronAll
```

---

## Running the packaged app

After packaging, the installer is a self-contained runnable. To run the
unpacked dir directly without installing:

```bash
../electronJukebox/build/electron/linux-unpacked/autogenesis-jukebox
```

```bash
../electronJukebox/build/electron/win-unpacked/autogenesis-jukebox.exe
```

The app launches a single `BrowserWindow` loading
`build/staging/frontend/index.html`. Window state (size, position,
last track, volume, mute) persists in
`$XDG_CONFIG_HOME/Autogenesis-Jukebox/window-state.json` on Linux and
`%APPDATA%/Autogenesis-Jukebox/window-state.json` on Windows. Delete
those files to reset.

---

## Architecture pointers

- `scripts/serve-jukebox.sh` — click-to-run helper: builds the dist, starts
  the local server, opens the browser
- `src/jsMain/resources/jukebox/index.html` — UI shell
- `src/jsMain/resources/jukebox/app.js` — UI controller (vanilla JS)
- `src/jsMain/kotlin/.../audio/AudioEngine.kt` — Kotlin/JS engine
- `src/jsMain/kotlin/.../audio/AudioObjectPlayer.kt` — per-source player
- `electronJukebox/src/main/main.js` — Electron main process
- `electronJukebox/src/main/preload.js` — contextBridge IPC surface

## Related docs

- [`../README.md`](../README.md) — project root README
- [`docs/maestro/plans/archive/`](../docs/maestro/plans/archive/) —
  archived design and implementation plans for the jukebox and its
  Electron build