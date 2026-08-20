# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Build, Test, and Development Commands

```bash
# Build all modules
./gradlew build

# Build specific modules
./gradlew :server:build           # Main game server (JVM)
./gradlew :kvisionApp:build      # KVision frontend (Kotlin/JS)
./gradlew :accelbyteSdk:build    # AccelByte TypeScript SDK bindings

# Run tests
./gradlew :server:test           # Server tests only

# Run a single test class (IntelliJ IDEA pattern via Gradle)
./gradlew :server:test --tests "agent.runners.GameplayMultiTargetTest"

# Run the KVision dev server
./gradlew :kvisionApp:jsBrowserDevelopmentRun

# Fallback: serve the committed prod bundle (Node 22+ — see kvisionApp/README.md)
node kvisionApp-e2e/static-server-8080.mjs

# Probe the Web Push pipeline end-to-end (server must be running with the
# right env vars; see kvisionApp-e2e/README.md)
AUTOGENESIS_DEV_PUSH_MOCK_PORT=9099 \
AUTOGENESIS_PUSH_TEST_ENDPOINT=true \
AUTOGENESIS_SHUTDOWN_DELAY_MS=600000 \
  ./gradlew :server:run &
node kvisionApp-e2e/static-server-8080.mjs &
node kvisionApp-e2e/probes/push-turn-start.mjs

# Package Electron app
./gradlew :electronApp:packageLinux
./gradlew :electronApp:packageWindows
```

### Known gotchas (read before editing push code)

- `kvisionApp/src/jsMain/kotlin/org/ttt/autogenesis/kvisionapp/Main.kt` contains
  a DCE keepalive (`val pushServiceKeepalive: Any = PushNotificationService`).
  **Do not delete it** — without it the production webpack bundle tree-shakes
  `PushNotificationService` and the Web Push pipeline is unreachable from the
  browser. Verify after any webpack/kvisionApp change:
  `grep -c PushNotificationService kvisionApp/build/dist/js/productionExecutable/kvisionApp.js`
  must return ≥ 1.
- The VAPID private key at `~/.autogenesis/vapid_private.pem` is **PKCS#8
  format only**; the gradle task emits SEC1 and the server normalizes on
  boot via `openssl pkcs8 -topk8 -nocrypt`. If you provision the file out of
  band, emit it as PKCS#8 (see `server/README.md` § **PEM format requirements**).
- The webpack-cli 6.0.1 + Node 22+ combo fails with `SyntaxError: Identifier 'path'
  has already been declared`. Two CI-friendly workarounds are documented in
  `kvisionApp/README.md` § **Node 22+ webpack-cli gotcha**.
- The Web Push pipeline MUST be exercised under `AUTOGENESIS_PUSH_TEST_ENDPOINT=true`
  + `AUTOGENESIS_DEV_PUSH_MOCK_PORT=<port>` for any local test. Without those, the
  push signature never reaches a local listener and the JVM integration test in
  `:server:test` is the only thing exercising the wire path.
- Headless Chromium cannot subscribe to push notifications — `Notification.requestPermission`
  is rejected even with `playwright.grantPermissions(['notifications'])`. The
  `push-turn-start.mjs` probe compensates by writing the subscription via
  `POST /debug/seed-push-subscription` (gated by `AUTOGENESIS_PUSH_TEST_ENDPOINT`).

See `server/README.md` § **Web Push (VAPID)** and `kvisionApp-e2e/README.md`
for the full setup recipes.


The project uses **Gradle** with a **Kotlin Multiplatform** setup:
- `server/` - JVM game server with AI agent orchestration
- `kvisionApp/` - Browser UI via KVision (Kotlin/JS)
- `accelbyteSdk/` - Kotlin/JS bindings for AccelByte SDK
- `sharedModel/` - Shared data models and RPC bridges
- `electronApp/` - Electron packaging bundling server + frontend

## High-Level Architecture

### Game Turn Execution Flow (`gameplayOrchestrator.kt`)

The server processes player turns through a sequential phase pipeline:

1. **Setup/AI Takeover** - Checks if player is disconnected, generates AI action if needed
2. **Intent Detection** (`buildPlayDetectionAgent`) - Classifies action type (Military, Diplomatic, Research, etc.)
3. **Validation** (`buildValidator`, `buildRailroadAgent`) - Checks action legality
4. **Targeting** (`buildTargetDetectorAgent`) - Resolves targets and detects source locations
5. **Counter-Play** (`handleCounterPlay`) - Prompts defenders for responses, with cascade system
6. **Simulation** (`buildNeoWritingAgent`) - Generates narrative outcome
7. **Assessment** (`buildAssessmentAgent`) - Geopolitical impact analysis
8. **Outcome Analysis** (`buildPassFailAgent`) - Determines success/failure
9. **Mathematical Resolution** (`GameMath.resolveAction`) - Applies stat bonuses/penalties
10. **Refinement** (`buildHardenAgent`/`buildReverseAgent`) - Aligns narrative to math result
11. **Judgement** (`buildJudge`) - Calculates final consequences
12. **Commit** - Saves history, broadcasts state

### Agent System Architecture

Agents are built via builder functions that return configured `Pipeline` objects:

- **`agent/builders/validateAction/`** - Action validation pipeline builders
  - `buildTargetDetectorAgent` - Detects target entities and source territories
  - `buildValidator` - Checks action legality
  - `buildDefensiveValidator` - Validates counter-play responses
- **`agent/builders/writingAgent/`** - Narrative generation
  - `buildNeoWritingAgent` - Main story simulation
  - `buildResponseRefinementAgent` - Refines counter-play prose
- **`agent/builders/judgeOutcome/`** - Outcome resolution
  - `buildAssessmentAgent` - Geopolitical assessment
  - `buildJudge` - Final stat/consequence calculation
  - `buildPassFailAgent` - Success/failure determination
- **`agent/runners/`** - Orchestration coordinators
  - `gameplayOrchestrator.kt` - Main turn execution coordinator
  - `npcOrchestrator.kt` - NPC turn execution

### Source Location Detection & Path Interception

**Already implemented** in `agent/math/SourceLocationResolver.kt`:

- `detectSourceLocation()` - Extracts or optimizes source territories from player action
- `validateSourceToTargetPaths()` - BFS pathfinding to detect hostile territory crossings

**Path interception flow** in `gameplayOrchestrator.kt` (lines 577-598):
1. Source territories are detected from player action text
2. `validateSourceToTargetPaths()` computes BFS paths and returns `playersOnPath`
3. If any other player's territory lies on the attack path, they are added to `targetType.targets`
4. `handleCounterPlay` then prompts those players for counter-play responses
5. The cascade system propagates: defender → their target → counter-players of that target

### Counter-Play Cascade System (`handleCounterPlay`)

- `CascadeState` data class tracks attacker, action, targets, and cascade depth
- Iterative batch processing by depth level
- Defenders with insufficient points (`<50`) are skipped
- Responses are validated, refined, and aggregated
- Cycle detection prevents infinite loops (A→B→A pattern)

### TPipe Dependency

The project depends on an external `TPipe` library for LLM pipeline orchestration:
- Located via `findTPipeWithFallback()` in `settings.gradle.kts` (searches up to 4 directories up)
- Must be present as a sibling directory: `../TPipe/TPipe/build.gradle.kts`
- Provides `BedrockMultimodalPipe`, `Pipeline`, `ContextBank` abstractions

### RPC Layer

Three transport options coexist, all sharing the same `RpcRegistry`/`RpcInvoker` contract:
- **REST** (`RestRpcBridge` in `sharedModel`) - Default for browser client
- **WebSocket** (`WebSocketRpcBridge`) - Real-time events on `/events`
- **gRPC** - Binary protocol on port `9092` (server-extend)

## Key File Locations

| Component | Path |
|-----------|------|
| Main game orchestrator | `server/src/main/kotlin/agent/runners/gameplayOrchestrator.kt` |
| Target detection | `server/src/main/kotlin/agent/builders/validateAction/targetDetectorAgent.kt` |
| Source location resolver | `server/src/main/kotlin/agent/math/SourceLocationResolver.kt` |
| Counter-play handler | `server/src/main/kotlin/agent/runners/gameplayOrchestrator.kt` (handleCounterPlay) |
| Game math resolution | `server/src/main/kotlin/agent/math/GameMath.kt` |
| World state | `server/src/main/kotlin/gameState/WorldManager.kt` |
| Response manager | `server/src/main/kotlin/agent/managers/GameResponseManager.kt` |
| Logging system | `sharedModel/src/commonMain/kotlin/org/ttt/autogenesis/logging/` |

## Kotlin Code Formatting Rules

This project follows the **TTT Kotlin Style Guide** (`md/TTT_STYLE_GUIDE.md`). Critical rules:

### Bracing
- **With parentheses** (`fun`, `class`, `if`, `for`, `when`): `{` on the next line
  ```kotlin
  fun myFunction(param: String)
  {
      // body
  }
  
  if(condition)
  {
      // ...
  }
  ```
- **Without parentheses** (property getters, `init`, `companion object`): `{` on same line
  ```kotlin
  val property get() {
      return value
  }
  ```
- **DSL/scope functions** (`apply`, `map { }`, widget trees): always `{` on same line

### Documentation
- Every **public** and **private** function needs KDoc with `@param`, `@return`
- Complex inline logic needs comments explaining **why**, not what

### Naming
- No snake_case for in-house code — use `camelCase`
- Variables must be descriptive (no `tmp`, `x`, `result`)

### Spacing
- `if(value)` not `if (value)` — no space after control keyword
- Type adjacent to name: `val count: Int`

### When Rules Conflict
Document exceptions inline when compiler requirements force non-standard formatting.

## Style Notes

- Kotlin 4-space indentation, `camelCase` functions, `PascalCase` types
- Logging via `Logger` class with `LogCategory` - never raw `console.*`
- All agents use `enablePipeTimeout` (3min, 5 retries) to handle LLM stalls
- KSP regenerates on every compile for `@RpcMethod` annotations