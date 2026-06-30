export const meta = {
  name: 'jukebox-ship-readiness-v2',
  description: 'Determine if jukebox app is 100% ship-ready for developers to test every aspect of the audio system. Spec review + Playwright runtime verification.',
  phases: [
    { title: 'SpecReview', detail: 'Extract audio system requirements from spec plans' },
    { title: 'CodeInventory', detail: 'Map every audio feature to its implementation' },
    { title: 'BuildVerify', detail: 'Verify jukebox build succeeds' },
    { title: 'RuntimeTest', detail: 'Playwright drives jukebox UI through every feature' },
    { title: 'VisualAudit', detail: 'MiniMax vision analyzes screenshots for UI issues' },
    { title: 'FinalVerdict', detail: '100% readiness verdict with evidence' },
  ],
}

// ─── PHASE 1: Spec Review ─────────────────────────────────────────────────────

phase('SpecReview')

const specRequirements = await agent(`
Read these two spec/plan files and extract EVERY audio-system requirement the jukebox app must satisfy:

1. <absolute-path> (Audio System Implementation Plan)
2. <absolute-path> (Jukebox App Implementation Plan)

For each requirement, capture: requirement_id | description | must_have_for_jukebox (boolean).

Focus on features the jukebox MUST support to be a viable audio test tool:
- Volume hierarchy (global, channel, per-object)
- AudioParam automation (setTargetAtTime, no direct .value writes)
- Signal chain (source → gain → panner → analyser → destination)
- Channel hierarchy (parent/child)
- Pause/resume with position tracking
- Loop with loopStart/loopEnd
- Fade in/out
- AnalyserNode for visualization
- Resource loading (multi-format fallback)
- Buffer caching
- Tick loop
- Mute per channel
- All RPC types (schedulePlay, stop, paramUpdate, channelUpdate, syncState, queryState)
- Server-frame timing vs wall-clock
- Web Audio API: AudioContext, GainNode, StereoPannerNode, AnalyserNode, AudioBufferSourceNode

Return as a structured list.
`, {label: 'extract-specs', phase: 'SpecReview', schema: {
  type: 'object',
  properties: {
    requirements: { type: 'array', items: { type: 'object' } },
    summary: { type: 'string' },
  },
  required: ['requirements', 'summary'],
}})

// ─── PHASE 2: Code Inventory ──────────────────────────────────────────────────

phase('CodeInventory')

const inventory = await agent(`
Read every audio source file in jukebox/src/jsMain/kotlin/ and the UI files in resources/jukebox/.

For each, extract: file | public @JsName functions | features_implemented | issues_found.

Files to cover:
- JukeboxAudioEngine.kt
- JukeboxMain.kt
- audio/AudioEngine.kt
- audio/AudioObjectPlayer.kt
- audio/AudioResourceLoader.kt
- audio/AudioChannelMaster.kt
- audio/AudioChannel.kt
- audio/AudioClientHandlers.kt
- audio/AudioRpcClient.kt
- audio/AudioRpcHandlers.kt
- audio/WebAudioInterfaces.kt
- resources/jukebox/index.html
- resources/jukebox/app.js
- resources/jukebox/styles.css

Compare each file's exports against the spec requirements from the SpecReview phase.
Produce a coverage table: requirement | has_impl | impl_file | status (OK/PARTIAL/MISSING/BROKEN).
`, {label: 'inventory', phase: 'CodeInventory', schema: {
  type: 'object',
  properties: {
    fileSummaries: { type: 'array', items: { type: 'object' } },
    coverageTable: { type: 'array', items: { type: 'object' } },
  },
  required: ['fileSummaries', 'coverageTable'],
}})

// ─── PHASE 3: Build Verify ────────────────────────────────────────────────────

phase('BuildVerify')

const buildResult = await agent(`
Run: cd . && ./gradlew :jukebox:build
Return the exit code and the list of files at jukebox/build/dist/js/productionExecutable/ and jukebox/build/dist/js/productionExecutable/jukebox/ and jukebox/build/dist/js/productionExecutable/audio/ (if exists).
Also confirm audio files exist at jukebox/src/jsMain/resources/audio/music/ and audio/sfx/.
`, {label: 'build-verify', phase: 'BuildVerify', schema: {
  type: 'object',
  properties: {
    exitCode: { type: 'number' },
    buildOutput: { type: 'string' },
    outputFiles: { type: 'array', items: { type: 'string' } },
    audioFilesExist: { type: 'boolean' },
  },
  required: ['exitCode', 'buildOutput', 'outputFiles', 'audioFilesExist'],
}})

if (buildResult.exitCode !== 0) {
  log('BUILD FAILED — aborting runtime test: ' + buildResult.buildOutput)
  return { earlyExit: true, buildResult, specRequirements, inventory }
}

// ─── PHASE 4: Runtime Test with Playwright ────────────────────────────────────

phase('RuntimeTest')

// Start server
const serveResult = await agent(`
Start a local HTTP server on port 7892 serving: ./jukebox/build/dist/js/productionExecutable/jukebox/
Use: cd to that directory and run: npx --yes serve -p 7892 -L &
Wait 3 seconds, then confirm the server is responding: curl -sI http://localhost:7892
Return whether the server is up and the PID if available.
`, {label: 'start-server', phase: 'RuntimeTest', schema: { type: 'object' }})

await new Promise(r => setTimeout(r, 3000))

const playwrightTest = await agent(`
Use mcp__playwright__ browser tools to verify the jukebox app at http://localhost:7892.

Comprehensive test sequence — exercise every audio feature the jukebox exposes:

INITIALIZATION:
1. Navigate to http://localhost:7892
2. Take a snapshot — describe the page layout in detail
3. Take a screenshot, save to ./jukebox-screenshot.png
4. Get the page title

INIT AUDIO (browser autoplay policy requires user gesture):
5. Click anywhere on the page to trigger the init
6. Wait 2 seconds
7. Take another screenshot to ./jukebox-test-screenshot.png
8. Use mcp__MiniMax__understand_image on jukebox-screenshot.png with prompt: "Analyze this jukebox audio testing app UI. List every UI control visible. Are there any visual issues, broken layouts, or missing elements? Rate the design from 1-10 for developer utility."

MUSIC PLAYBACK:
9. Click the Forest music button
10. Wait 2 seconds
11. Click the Battle music button
12. Wait 1 second
13. Click the Menu music button
14. Wait 1 second

SFX PLAYBACK:
15. Click each SFX button: click, explosion, gunshot, footstep
16. Wait 1 second between each

VOLUME CONTROLS:
17. Move the global volume slider to value 10
18. Move the music channel volume slider to value 5
19. Move the sfx channel volume slider to value 5

CHANNEL MUTE:
20. Click the music mute button
21. Click it again to unmute
22. Click the sfx mute button
23. Click it again to unmute

LOOP AND FADE:
24. Click the loop toggle button (it should change to "On")
25. Type 1000 in the fade-in-ms input
26. Type 500 in the fade-out-ms input

PER-PLAYER CONTROLS (if active players exist):
27. Check the active-players panel — list player IDs visible
28. For any active player, try to interact with their pause/resume/stop buttons
29. For any active player, try to move their per-player volume/pan/speed sliders

VERIFICATION:
30. Get all console messages at error level — list any errors
31. Get all console messages at warning level — list any warnings
32. Check the action log — does it show entries for the interactions?
33. Check the visualizer — are canvases rendering?

Return: {
  pageTitle: "...",
  layoutDescription: "...",
  visualAnalysis: { score: 1-10, issues: [...] },
  musicPlayed: { forest: bool, battle: bool, menu: bool },
  sfxPlayed: { click: bool, explosion: bool, gunshot: bool, footstep: bool },
  volumeControls: { globalChanged: bool, musicChanged: bool, sfxChanged: bool },
  muteToggled: { music: bool, sfx: bool },
  loopToggled: bool,
  fadeInputsSet: { fadeIn: bool, fadeOut: bool },
  perPlayerControls: { attempted: bool, succeeded: bool, errors: [...] },
  consoleErrors: [...],
  consoleWarnings: [...],
  actionLogEntries: int,
  visualizerRendering: bool,
  overall: "PASS" or "PARTIAL" or "FAIL"
}
`, {label: 'runtime-test', phase: 'RuntimeTest', schema: {
  type: 'object',
  properties: {
    pageTitle: { type: 'string' },
    layoutDescription: { type: 'string' },
    visualAnalysis: { type: 'object' },
    musicPlayed: { type: 'object' },
    sfxPlayed: { type: 'object' },
    volumeControls: { type: 'object' },
    muteToggled: { type: 'object' },
    loopToggled: { type: 'boolean' },
    fadeInputsSet: { type: 'object' },
    perPlayerControls: { type: 'object' },
    consoleErrors: { type: 'array', items: { type: 'string' } },
    consoleWarnings: { type: 'array', items: { type: 'string' } },
    actionLogEntries: { type: 'number' },
    visualizerRendering: { type: 'boolean' },
    overall: { type: 'string' },
  },
  required: ['pageTitle', 'musicPlayed', 'sfxPlayed', 'consoleErrors', 'overall'],
}})

// Kill the server
await agent('Kill the process listening on port 7892. Run: lsof -ti:7892 | xargs -r kill -9 2>/dev/null; echo done', {label: 'kill-server', phase: 'RuntimeTest'})

// ─── PHASE 5: Visual Audit ────────────────────────────────────────────────────

phase('VisualAudit')

const visualAudit = await agent(`
Use mcp__MiniMax__understand_image to analyze both jukebox screenshots:
- ./jukebox-screenshot.png
- ./jukebox-test-screenshot.png

For each, prompt: "This is a developer audio testing tool (jukebox). Rate the UI quality:
1. Layout clarity (1-10)
2. Control completeness (1-10) — does it have music buttons, SFX buttons, volume/panning/speed sliders, active players, visualizer, action log?
3. Visual consistency (1-10)
4. Developer-friendliness (1-10)
5. Any visible issues: misalignment, broken elements, missing controls, text overflow

For each screenshot, return: {layoutScore, controlScore, consistencyScore, devScore, issues: [...]}
`, {label: 'visual-audit', phase: 'VisualAudit', schema: {
  type: 'object',
  properties: {
    initialScreenshot: { type: 'object' },
    afterClickScreenshot: { type: 'object' },
    overallVisualVerdict: { type: 'string' },
  },
  required: ['initialScreenshot', 'afterClickScreenshot', 'overallVisualVerdict'],
}})

// ─── PHASE 6: Final Verdict ───────────────────────────────────────────────────

phase('FinalVerdict')

const verdict = await agent(`
You are a senior reviewer. Produce the final ship-readiness verdict for the jukebox app.

Inputs from prior phases:
- Spec requirements (SpecReview)
- Code inventory coverage table (CodeInventory)
- Build result (BuildVerify)
- Playwright runtime test results (RuntimeTest)
- Visual audit scores (VisualAudit)

The question: Can a developer use this jukebox app to test EVERY aspect of the audio system end-to-end?

Determine:
1. Is the jukebox 100% correct? If not, what percentage of features work?
2. List every spec requirement and whether the jukebox satisfies it at runtime
3. List every console error from runtime testing
4. List every visual issue
5. Final verdict: SHIP or NO-SHIP

Return:
{
  percentCorrect: 0-100,
  requirementMatrix: [{requirement, runtimeStatus, evidence}],
  runtimeIssues: [{severity, description, evidence}],
  visualIssues: [...],
  shipReady: true/false,
  verdict: "one paragraph final assessment"
}
`, {label: 'final-verdict', phase: 'FinalVerdict', schema: {
  type: 'object',
  properties: {
    percentCorrect: { type: 'number' },
    requirementMatrix: { type: 'array' },
    runtimeIssues: { type: 'array' },
    visualIssues: { type: 'array' },
    shipReady: { type: 'boolean' },
    verdict: { type: 'string' },
  },
  required: ['percentCorrect', 'requirementMatrix', 'runtimeIssues', 'visualIssues', 'shipReady', 'verdict'],
}})

return {
  specRequirements,
  inventory,
  buildResult,
  playwrightTest,
  visualAudit,
  verdict,
}