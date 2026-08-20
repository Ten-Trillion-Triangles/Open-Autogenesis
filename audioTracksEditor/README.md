# audioTracksEditor

Standalone Kotlin/JS browser editor for `audioTracks.json` (the file consumed by
the Autogenesis game server's audio system). The editor lets a designer
define `AudioObject` entries, assign each to one of four categories
(`drone` / `melody` / `rhythm` / `harmony`), and save/load the result as
`audioTracks.json`.

This module is pure Kotlin/JS — no KVision, no Compose, no JVM. It uses
just the DOM APIs directly so the build artifact is a single self-contained
JS bundle plus a thin `index.html` shell.

## Run it

Two ways to start the editor in your browser. Both build the production
bundle, serve it on a local port, and (on a desktop session) auto-open the
browser.

### One click — IntelliJ run configuration

Two run configurations are registered in `.idea/runConfigurations/`:

| Name | What it does |
|------|--------------|
| **Audio Tracks Editor** | Builds `:audioTracksEditor:jsBrowserDistribution` if needed, serves the dist on `http://localhost:4174/`, and opens your browser. |
| **Audio Tracks Editor E2E** | Boots the static server in the background and runs the Playwright suite against it. |

Select **Audio Tracks Editor** in the Run/Debug configurations dropdown
(upper-right of the IntelliJ toolbar) and press the green ▶ button. The
first run will rebuild the dist (~55 s); subsequent runs reuse the
existing dist and start the server immediately.

To stop the server, press the red ■ button (or `Ctrl-C` in the terminal
panel).

### One command — `scripts/serve-audio-editor.sh`

From this directory (or any CWD, because the script resolves the repo
root from its own location):

```bash
./scripts/serve-audio-editor.sh
```

Override the port (default `4174`):

```bash
./scripts/serve-audio-editor.sh 9090
```

Headless / CI — same script, no browser auto-open:

```bash
AUDIO_EDITOR_NO_OPEN=1 ./scripts/serve-audio-editor.sh
```

Skip the auto-build step (fail if the dist is missing):

```bash
AUDIO_EDITOR_AUTO_BUILD=0 ./scripts/serve-audio-editor.sh
```

The script is the same one the IntelliJ run config invokes, so behaviour
is identical across both entry points. Env vars: `AUDIO_EDITOR_NO_OPEN`
and `AUDIO_EDITOR_AUTO_BUILD`.

> **Click-to-run:** every `bash` code fence in this README is independently
> clickable in the Fenix-enabled VS Code workspace — click the run
> button (▷) on any fence to launch that one task in the integrated
> terminal. Each task lives in its own fence so a click only fires the
> command you intended, never the whole block.

---

## Build commands

### Build the production webpack bundle (`jsBrowserDistribution`)

Produces under `audioTracksEditor/build/dist/js/productionExecutable/`:

- `index.html` — UI shell
- `audioTracksEditor.js` — the Kotlin/JS bundle
- `styles.css` — dark theme

```bash
../gradlew :audioTracksEditor:jsBrowserDistribution
```

### Run the unit tests (`jsTest`)

Runs the `kotlin.test` suites under
`audioTracksEditor/src/jsTest/kotlin/...` (21 cases covering
`EditorState` and `AudioTracksFileIO`) via karma + headless Chromium.
No display required.

```bash
../gradlew :audioTracksEditor:jsTest
```

### Run the Playwright E2E suite

24 cases total — 9 original plus 15 added during the 2026-06-05 bugfix
session. Lives at `audio-tracks-editor-e2e/tests/`. The
**Audio Tracks Editor E2E** IntelliJ run config runs the full suite.

```bash
cd ../audio-tracks-editor-e2e
npx playwright test --reporter=list
```

To run a single spec file or test name:

```bash
npx playwright test --reporter=list tests/audio-tracks-editor.spec.mjs
npx playwright test --reporter=list -g "load replaces in-memory state"
```

---

## Architecture pointers

- `src/jsMain/kotlin/.../EventHandlers.kt` — application controller (the
  singleton owning `EditorState`, the file picker, and the global click
  delegation)
- `src/jsMain/kotlin/.../Render.kt` — pure renderers (no state mutation
  in render; all mutations go through `Callbacks`)
- `src/jsMain/kotlin/.../EditorState.kt` — immutable state + 9 mutator
  methods
- `src/jsMain/kotlin/.../ModalState.kt` — sealed class for modal lifecycle
- `src/jsMain/kotlin/.../AudioTracksFileIO.kt` — JSON encode/decode wrapper
- `src/jsMain/resources/index.html` — UI shell (4 container divs + 1 dialog)
- `src/jsMain/resources/styles.css` — dark theme
- `scripts/serve-audio-editor.sh` — build + serve + browser-open helper

## Related docs

- [`../README.md`](../README.md) — project root README
- `../docs/maestro/state/2026-06-05-audio-tracks-editor-bugfixes.md` —
  bugfix session report (7 real bugs fixed, 5 investigated as
  non-bugs/design decisions, 24/24 tests passing)
- `../docs/maestro/state/archive/2026-06-04-audio-tracks-editor.md` —
  original implementation archive (design + per-phase summaries)