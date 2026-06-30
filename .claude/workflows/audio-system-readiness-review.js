export const meta = {
  name: 'audio-system-readiness-review',
  description: 'Deep review of audio system: research basis, implementation correctness, plan compliance, and production readiness',
  phases: [
    { title: 'ResearchAudit', detail: 'Audit all audio research files and docs in the codebase' },
    { title: 'CodeAudit', detail: 'Deep read of every audio implementation file end-to-end' },
    { title: 'PlanCompliance', detail: 'Compare implementation against the maestro plan spec' },
    { title: 'CrossExamination', detail: 'Adversarial examination of each design decision' },
    { title: 'ReadinessVerdict', detail: 'Produce pass/fail readiness verdict with specific evidence' }
  ],
}

const FINDINGS_SCHEMA = {
  type: 'object',
  properties: {
    findings: {
      type: 'array',
      items: {
        type: 'object',
        properties: {
          area: { type: 'string' },
          file: { type: 'string' },
          problem: { type: 'string' },
          severity: { type: 'string' },
          detail: { type: 'string' }
        },
        required: ['area', 'problem', 'severity', 'detail']
      }
    }
  },
  required: ['findings']
}

const VERDICT_SCHEMA = {
  type: 'object',
  properties: {
    verdict: { type: 'string' },
    confidence: { type: 'string' },
    blockers: { type: 'array', items: { type: 'string' } },
    passedCriteria: { type: 'array', items: { type: 'string' } },
    concerns: { type: 'array', items: { type: 'string' } },
    evidence: { type: 'string' }
  },
  required: ['verdict', 'confidence', 'blockers', 'passedCriteria']
}

// ─── PHASE 1: RESEARCH AUDIT ─────────────────────────────────────────────────
phase('ResearchAudit')

const researchFiles = await agent(
  'You are a technical research auditor. Find and read ALL audio research and documentation files in this codebase.\n\n' +
  'Search for files matching patterns:\n' +
  '- md/md-audio*/**/*.md\n' +
  '- docs/audio*.md\n' +
  '- **/audio*.md\n' +
  '- **/research*/**/*.md\n' +
  '- CLAUDE.md sections about audio\n' +
  '- Any audio design docs, ADR, or spec files\n\n' +
  'For each research file found:\n' +
  '1. Read the FULL file\n' +
  '2. Extract all Web Audio API constraints, limits, and requirements\n' +
  '3. Note browser compatibility requirements\n' +
  '4. Extract any timing/fidelity requirements (quantum size, latency, etc.)\n' +
  '5. Note any patterns or anti-patterns documented\n\n' +
  'Return a JSON object:\n' +
  '{ researchFiles: [ { path: "...", keyFindings: ["list"], constraints: ["list"], browserNotes: ["list"] } ],\n' +
  '  topConstraints: ["the 5-10 most important constraints from research"],\n' +
  '  researchCoverage: "complete|incomplete|partial" }',
  {label: 'research-audit', phase: 'ResearchAudit'}
)

// ─── PHASE 2: CODE AUDIT ─────────────────────────────────────────────────────
phase('CodeAudit')

// Fan out deep reads across all implementation files in parallel
const [sharedModelAudit, serverAudit, clientAudit, uiAudit] = await parallel([
  () => agent(
    'You are a deep code auditor. Read every file in sharedModel/src/commonMain/kotlin/org/ttt/autogenesis/audio/ in FULL.\n\n' +
    'Files to read:\n' +
    '- sharedModel/src/commonMain/kotlin/org/ttt/autogenesis/audio/AudioObject.kt\n' +
    '- sharedModel/src/commonMain/kotlin/org/ttt/autogenesis/audio/AudioChannel.kt\n' +
    '- sharedModel/src/commonMain/kotlin/org/ttt/autogenesis/audio/AudioRpc.kt\n\n' +
    'For each file:\n' +
    '1. Report every field, type, default value, and @Serializable status\n' +
    '2. Verify AudioObject has ALL fields: id, resourceName, channelId, volume, panning, speed, loop, startTimeMs, startFrame, startSample, endTimeMs, fadeInDurationMs, fadeOutDurationMs\n' +
    '3. Verify AudioObjectView interface has: id, currentTimeMs, currentFrame, sampleData (FloatArray), isPlaying, isPaused, isEnded, volume, panning, speed, tick()\n' +
    '4. Verify AudioRpc has ALL DTOs: AudioSchedulePlay, AudioQueryState, AudioReportState, AudioChannelState, AudioObjectState, AudioPositionReport, AudioGameTrigger, AudioParamUpdate, AudioStop, AudioChannelUpdate, AudioSyncState\n' +
    '5. Check for any missing fields or wrong types vs the plan spec\n\n' +
    'Return JSON: { filesAudited: [...], fieldReport: {...}, issues: [{file, problem, severity}] }',
    {label: 'audit:sharedmodel', phase: 'CodeAudit'}
  ),
  () => agent(
    'You are a deep code auditor. Read EVERY file in server/src/main/kotlin/org/ttt/autogenesis/server/audio/ in FULL.\n\n' +
    'Files to read:\n' +
    '- server/src/main/kotlin/org/ttt/autogenesis/server/audio/AudioManager.kt\n' +
    '- server/src/main/kotlin/org/ttt/autogenesis/server/audio/AudioRpcHandlers.kt\n\n' +
    'For each file:\n' +
    '1. Report every method signature, visibility, and what it does\n' +
    '2. Verify AudioManager has: channels, playingObjects, globalVolume, clientLastReport, pendingQueries, serverFrameCounter, SERVER_SAMPLE_RATE, SERVER_RENDER_QUANTUM\n' +
    '3. Verify schedulePlay() broadcasts AudioSchedulePlay via PlayerConnectionManager\n' +
    '4. Verify stop() broadcasts AudioStop\n' +
    '5. Verify updateChannel() broadcasts AudioChannelUpdate\n' +
    '6. Verify setGlobalVolume() broadcasts AudioParamUpdate with objectId="" for global\n' +
    '7. Verify buildSyncState() returns AudioSyncState\n' +
    '8. Check ScheduledAudio has ALL fields: id, resourceName, channelId, volume, panning, speed, loop, scheduledStartMs, startTimeMs, startFrame, startSample, endTimeMs, fadeInDurationMs, fadeOutDurationMs\n' +
    '9. Verify AudioRpcHandlers has handlers for: audio.reportPosition, audio.reportState, audio.setChannelVolume, audio.triggerGameAudio\n' +
    '10. Check for any stub TODO comments or empty implementations\n\n' +
    'Return JSON: { filesAudited: [...], methodReport: {...}, issues: [{file, problem, severity, detail}] }',
    {label: 'audit:server', phase: 'CodeAudit'}
  ),
  () => agent(
    'You are a deep code auditor. Read EVERY file in kvisionApp/src/jsMain/kotlin/org/ttt/autogenesis/kvisionapp/audio/ in FULL.\n\n' +
    'Files to read:\n' +
    '- kvisionApp/src/jsMain/kotlin/org/ttt/autogenesis/kvisionapp/audio/AudioEngine.kt\n' +
    '- kvisionApp/src/jsMain/kotlin/org/ttt/autogenesis/kvisionapp/audio/AudioChannelMaster.kt\n' +
    '- kvisionApp/src/jsMain/kotlin/org/ttt/autogenesis/kvisionapp/audio/AudioObjectPlayer.kt\n' +
    '- kvisionApp/src/jsMain/kotlin/org/ttt/autogenesis/kvisionapp/audio/AudioResourceLoader.kt\n' +
    '- kvisionApp/src/jsMain/kotlin/org/ttt/autogenesis/kvisionapp/audio/AudioClientHandlers.kt\n\n' +
    'For each file:\n' +
    '1. AudioEngine: verify signal chain (ctx.destination -> globalGainNode -> channelMaster.gainNode -> player.gainNode -> pannerNode -> output)\n' +
    '2. AudioEngine: verify setVolume/setPanning/setSpeed all use setTargetAtTime/setValueAtTime — NEVER direct .value assignment\n' +
    '3. AudioEngine: verify setGlobalVolume uses setTargetAtTime with hardcoded 0.01 timeConstant\n' +
    '4. AudioEngine: verify scheduleFromServer handles startSample, startFrame, startTimeMs correctly\n' +
    '5. AudioEngine: verify tick() calls player.tick() for all activePlayers\n' +
    '6. AudioChannelMaster: verify effectiveVolume = volume * (parent?.effectiveVolume ?: 1.0f)\n' +
    '7. AudioChannelMaster: verify applyState() uses setTargetAtTime automation\n' +
    '8. AudioObjectPlayer: verify resume() creates new source, reconnects chain, calls start() with pauseOffset\n' +
    '9. AudioObjectPlayer: verify loop=false pre-schedules source.stop() before source.start()\n' +
    '10. AudioObjectPlayer: verify loop=true sets source.loop=true, loopStart, loopEnd\n' +
    '11. AudioObjectPlayer: verify ALL parameter changes use setTargetAtTime/setValueAtTime\n' +
    '12. AudioResourceLoader: verify uses @JsFun dynamicImport — NOT window.fetch\n' +
    '13. AudioClientHandlers: verify handles audio.schedulePlay, audio.paramUpdate, audio.stop, audio.channelUpdate, audio.syncState, audio.queryState\n' +
    '14. Check for any direct .value assignment on AudioParams (gain, pan, playbackRate)\n' +
    '15. Check for any TODO stubs or incomplete implementations\n\n' +
    'Return JSON: { filesAudited: [...], signalChain: "verified|broken", automationCompliance: "pass|fail", issues: [{file, problem, severity, detail}] }',
    {label: 'audit:client', phase: 'CodeAudit'}
  ),
  () => agent(
    'You are a deep code auditor. Read EVERY audio-related file in the UI and integration layer in FULL.\n\n' +
    'Files to read:\n' +
    '- kvisionApp/src/jsMain/kotlin/org/ttt/autogenesis/kvisionapp/ui/audio/AudioSettingsPanel.kt\n' +
    '- kvisionApp/webpack.config.d/audio-chunks.js\n' +
    '- server/src/main/kotlin/org/ttt/autogenesis/server/UiSignalRpcHandlers.kt (audio-related sections)\n' +
    '- server/src/main/kotlin/org/ttt/autogenesis/server/PlayerConnectionManager.kt (audio-related methods)\n' +
    '- kvisionApp/src/jsMain/kotlin/ui/gameplay/networking/UiSignalClientHandlers.kt (audio-related sections)\n\n' +
    'For each file:\n' +
    '1. AudioSettingsPanel: verify AudioRpcClient.setGlobalVolume and setChannelVolume send REAL RPC — not TODO stubs\n' +
    '2. AudioSettingsPanel: verify sliders wired to call AudioRpcClient + update AudioEngine\n' +
    '3. webpack: verify audio-chunks.js has correct asset/resource rule and splitChunks config\n' +
    '4. UiSignalRpcHandlers: verify sendAudioSync called in sendInitialSync\n' +
    '5. PlayerConnectionManager: verify queryClientAudioState sends AudioQueryState and awaits AudioReportState\n' +
    '6. UiSignalClientHandlers: verify handleAudioSyncState buffers when UI not attached, flushes on attach\n' +
    '7. Check for any TODOs, stubs, or incomplete integrations\n\n' +
    'Return JSON: { filesAudited: [...], integrationCompleteness: "complete|partial|incomplete", issues: [{file, problem, severity}] }',
    {label: 'audit:ui-integrations', phase: 'CodeAudit'}
  )
])

// ─── PHASE 3: PLAN COMPLIANCE ────────────────────────────────────────────────
phase('PlanCompliance')

const planCompliance = await agent(
  'You are a compliance auditor. Read the audio system plan and compare it against the actual implementation.\n\n' +
  'Plan file: ~/.claude/plans/squishy-sparking-clover.md\n\n' +
  'Read the plan in full, then for each phase (1-7) verify:\n\n' +
  'PHASE 1 (Shared Model):\n' +
  '- AudioObject has ALL specified fields with correct types and defaults\n' +
  '- AudioObjectView has all specified members\n' +
  '- AudioChannel has parentId, volume, muted\n' +
  '- ALL AudioRpc DTOs present with correct field sets\n\n' +
  'PHASE 2 (JVM AudioManager):\n' +
  '- All 5 tracking structures present (channels, playingObjects, globalVolume, etc.)\n' +
  '- SERVER_SAMPLE_RATE = 44100f, SERVER_RENDER_QUANTUM = 128\n' +
  '- schedulePlay/stop/updateChannel/setGlobalVolume all broadcast correctly\n' +
  '- buildSyncState returns AudioSyncState with all fields\n' +
  '- ScheduledAudio has all fields\n' +
  '- AudioRpcHandlers has all 4 handlers\n\n' +
  'PHASE 3 (Browser AudioEngine):\n' +
  '- AudioEngine object with all specified members\n' +
  '- Signal chain correct (destination -> global -> channel -> player -> panner)\n' +
  '- ALL parameter changes use AudioParam automation (never direct .value)\n' +
  '- scheduleFromServer handles all 3 reference modes correctly\n' +
  '- loop=false pre-schedules stop(), loop=true uses loopStart/loopEnd\n' +
  '- AudioClientHandlers has all 6 handlers\n\n' +
  'PHASE 4 (Webpack):\n' +
  '- audio-chunks.js has asset/resource rule for ogg/mp3/wav\n' +
  '- splitChunks.cacheGroups.audio configured\n' +
  '- Audio resource directories exist with placeholder files\n\n' +
  'PHASE 5 (Settings UI):\n' +
  '- AudioSettingsPanel has global + per-channel sliders\n' +
  '- AudioRpcClient has real RPC implementation (not TODOs)\n\n' +
  'PHASE 6 (Sync Integration):\n' +
  '- sendAudioSync in sendInitialSync\n' +
  '- queryClientAudioState method exists and works\n' +
  '- handleAudioSyncState buffers and flushes correctly\n\n' +
  'PHASE 7 (Unit Tests):\n' +
  '- AudioManagerTest has 5 tests covering all specified cases\n' +
  '- AudioRpcTest has 4 tests covering all specified cases\n' +
  '- Tests actually call real methods (not direct state mutation)\n\n' +
  'Return JSON: { phaseReports: [{phase, status, complianceScore, missing: [], extra: []}], overallCompliance: "full|partial|non-compliant" }',
  {label: 'plan-compliance', phase: 'PlanCompliance'}
)

// ─── PHASE 4: CROSS-EXAMINATION ─────────────────────────────────────────────
phase('CrossExamination')

// Collect all issues from phases 1-3
const allIssues = [
  ...(sharedModelAudit.issues || []),
  ...(serverAudit.issues || []),
  ...(clientAudit.issues || []),
  ...(uiAudit.issues || [])
]

if (allIssues.length === 0) {
  log('No issues found in initial audit — cross-examination will verify correctness')
}

const crossExamFindings = await agent(
  'You are a hostile adversarial cross-examiner. You have the results of a full code audit. Your job is to stress-test every design decision and implementation claim.\n\n' +
  'AUDIO SYSTEM CONTEXT:\n' +
  '- Browser game using Web Audio API (AudioContext, AudioBufferSourceNode, GainNode, StereoPannerNode, AnalyserNode)\n' +
  '- Kotlin Multiplatform: JVM server + KVision/JS browser client\n' +
  '- Server schedules audio, clients execute playback, server tracks authoritative state\n' +
  '- Render quantum: 128 samples (~2.9ms at 44.1kHz)\n' +
  '- Signal chain: ctx.destination -> globalGain -> channelMaster.gain -> player.gain -> panner -> output\n\n' +
  'AUDIT ISSUES FOUND:\n' +
  JSON.stringify(allIssues.filter(Boolean), null, 2) + '\n\n' +
  'Cross-examine these specific claims:\n' +
  '1. Does the signal chain actually match the spec? Read AudioEngine.kt and verify every connect() call\n' +
  '2. Is AudioParam automation truly complete? grep all AudioObjectPlayer and AudioEngine for .value assignments on gain/pan/playbackRate\n' +
  '3. Does scheduleFromServer handle all three modes (startSample/startFrame/startTimeMs) with correct math?\n' +
  '4. Is the loop=false pre-schedule of source.stop() actually placed BEFORE source.start()?\n' +
  '5. Does AudioResourceLoader truly use webpack dynamic import, not window.fetch?\n' +
  '6. Do AudioRpcClient methods actually call RestRpcBridge.rpcInvoker — or is it another stub?\n' +
  '7. Does handleAudioSyncState actually buffer when gameplayUI is null?\n' +
  '8. Is the parent chain in AudioChannelMaster actually wired — or always null?\n' +
  '9. Do the tests actually call schedulePlay() and stop(), or do they bypass with direct mutation?\n' +
  '10. Is there any scenario where a user gesture is required before AudioContext can be created (browser autoplay policy)?\n\n' +
  'For each claim, read the actual code and confirm or refute. Do not assume.\n\n' +
  'Return JSON: { confirmedIssues: [{area, file, problem, severity, codeEvidence}], refutedClaims: [{claim, whyItIsWrong}], designQuestions: [{question, whyItMatters}] }',
  {label: 'cross-exam', phase: 'CrossExamination', schema: FINDINGS_SCHEMA}
)

// ─── PHASE 5: READINESS VERDICT ─────────────────────────────────────────────
phase('ReadinessVerdict')

const allFindings = [
  ...(allIssues || []),
  ...(crossExamFindings.confirmedIssues || [])
]

const criticalBlockers = allFindings.filter(f => f.severity === 'critical')
const majorIssues = allFindings.filter(f => f.severity === 'major')
const minorIssues = allFindings.filter(f => f.severity === 'minor')

const verdictResult = await agent(
  'You are a senior technical评审专家. Produce the final readiness verdict for the audio system.\n\n' +
  'RESEARCH CONSTRAINTS FROM PHASE 1:\n' +
  JSON.stringify(researchFiles.topConstraints || [], null, 2) + '\n\n' +
  'SHARED MODEL AUDIT:\n' +
  JSON.stringify(sharedModelAudit.fieldReport || {}, null, 2) + '\n\n' +
  'SERVER AUDIT:\n' +
  JSON.stringify(serverAudit.methodReport || {}, null, 2) + '\n\n' +
  'CLIENT AUDIT:\n' +
  'Signal chain: ' + (clientAudit.signalChain || 'unknown') + '\n' +
  'Automation compliance: ' + (clientAudit.automationCompliance || 'unknown') + '\n\n' +
  'PLAN COMPLIANCE:\n' +
  'Overall: ' + (planCompliance.overallCompliance || 'unknown') + '\n\n' +
  'CROSS-EXAMINATION CONFIRMED ISSUES:\n' +
  JSON.stringify(crossExamFindings.confirmedIssues || [], null, 2) + '\n\n' +
  'CRITICAL BLOCKERS: ' + criticalBlockers.length + '\n' +
  'MAJOR ISSUES: ' + majorIssues.length + '\n' +
  'MINOR ISSUES: ' + minorIssues.length + '\n\n' +
  'Produce a verdict answering:\n' +
  '1. Is the audio system ready to move forward to testing/use in the game?\n' +
  '2. What specific things MUST be fixed before it can be used?\n' +
  '3. What is good enough to ship with minor issues?\n' +
  '4. What evidence supports the verdict?\n\n' +
  'Consider: This is for a real game. Audio stutters, clicks, wrong timing, or broken volume controls will ruin player experience. But partial implementation that mostly works can be improved iteratively.\n\n' +
  'Return JSON: { verdict: "APPROVED|CONDITIONALLY_APPROVED|REJECTED", confidence: "high|medium|low", blockers: ["must fix before use"], passedCriteria: ["confirmed working"], concerns: ["would be nice to fix"], evidence: "summary of why verdict is justified" }',
  {label: 'verdict', phase: 'ReadinessVerdict', schema: VERDICT_SCHEMA}
)

return {
  researchFiles: researchFiles.researchFiles,
  planCompliance: planCompliance,
  crossExamFindings: crossExamFindings,
  verdict: verdictResult,
  criticalBlockers: criticalBlockers.map(f => ({ area: f.area, file: f.file, problem: f.problem, detail: f.detail })),
  majorIssues: majorIssues.map(f => ({ area: f.area, file: f.file, problem: f.problem, detail: f.detail })),
  minorIssues: minorIssues.map(f => ({ area: f.area, file: f.file, problem: f.problem, detail: f.detail })),
  allConfirmedIssues: allFindings
}