name: Audio System Readiness Audit
task_complexity: complex

phases:
  - id: research_audit
    name: Research Audit
    agent: librarian
    blocked_by: []
    parallel: false
    description: "Find all audio research documents in the codebase (md/, docs/, plans/, audio/ directories). Extract Web Audio API constraints, browser compatibility notes, API coverage requirements, and any design decisions documented in research. Produce a research summary that will be used to verify the implementation covers all documented requirements."

  - id: code_audit
    name: End-to-End Code Audit
    agent: librarian
    blocked_by: [research_audit]
    parallel: true
    description: "Fan out 4 agents in parallel to read every audio file in the codebase. Read files in full (not summarized) across all layers: client-side (kvisionApp), server-side, shared. Cover: AudioEngine.kt, AudioContextManager.kt, AudioObjectPlayer.kt, AudioSettingsPanel.kt, AudioRpcClient.kt, AudioRpcHandlers.kt, AudioChannel.kt, AudioBus.kt, and any other audio files found. Each agent reads a subset; together they read everything. Extract: every method signature, every state field, every RPC call, every audio node wiring, every automation function, every event handler. Produce a comprehensive code inventory that documents what every piece does."

  - id: plan_compliance
    name: Plan Compliance Check
    agent: librarian
    blocked_by: [code_audit]
    parallel: false
    description: "Read the Maestro plan file at plans/squishy-sparking-clover.md (or similar audio plan in plans/). Read the AudioEngine.kt and all audio implementation files. For each requirement in the plan, determine if the implementation satisfies it. List any gaps, deviations, or missing features. Read the code directly — do not summarize away details. Flag anything where the code does not match the plan specification."

  - id: cross_examination
    name: Adversarial Cross-Examination
    agent: critic
    blocked_by: [plan_compliance]
    parallel: true
    description: "Using the research summary, code inventory, and plan compliance report as input, run adversarial checks across: (1) Signal chain completeness — every audio object should have source→processing→destination chain, (2) Automation compliance — are AudioParam automation calls (setTargetAtTime, linearRampToValueAtTime, etc.) used correctly per Web Audio API spec, (3) Loop/cycle safety — does the audio graph have any unintended feedback loops or detached nodes, (4) Webpack config — is the Web Audio API imported via webpack and not via script tags, (5) Buffering — are AudioBuffers properly decoded, cached, and shared, (6) Parent chain wiring — does AudioContextManager correctly maintain the parent-child node relationship, (7) Test quality — do AudioEngine tests actually verify audio output vs. only testing mock state, (8) RPC contract — do AudioRpcClient and AudioRpcHandlers agree on the same method names and payload shapes. For each check, produce a finding with severity (critical/major/minor) and concrete evidence."

  - id: readiness_verdict
    name: Readiness Verdict
    agent: critic
    blocked_by: [cross_examination]
    parallel: false
    description: "Senior technical reviewer. Based on all prior phase outputs, produce a structured verdict: APPROVED, CONDITIONALLY_APPROVED, or REJECTED for moving the audio system to testing and game use. For each category: (a) Core audio engine correctness, (b) RPC bridge reliability, (c) Signal chain robustness, (d) Automation accuracy, (e) Test coverage adequacy, (f) Browser compatibility. List specific evidence for each. If APPROVED: state readiness for testing. If CONDITIONALLY_APPROVED: list required pre-conditions before testing. If REJECTED: list critical blockers preventing testing."
