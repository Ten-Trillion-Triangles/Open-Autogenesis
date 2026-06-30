# Repository Guidelines

- `accelbyteSdk` hosts the Kotlin/JS bindings sourced from `accelbyte/accelbyte-typescript-sdk`. Shared logic belongs in `commonMain`, while JS-only helpers sit under `jsMain`.
- `kvisionApp` is the JavaScript frontend built with KVision 9.1.1; it depends on `accelbyteSdk` and renders the UI through the `Core`, `Bootstrap`, and `BootstrapCss` KVision modules.
- `WebSocketRpcBridge` mirrors the REST bridge so the KVision UI can reuse the shared `RpcRegistry`/`RpcInvoker` stack over `/events`, just like `RestRpcBridge`.
- `RestRpcBridge` now lives in `sharedModel`, letting `server`, `server-extend`, `mapEditor`, `rpc-ksp`, and the frontend reuse the same bridge API; see `README.md` for cross-module examples.
- Root scripts (`build.gradle.kts`, `settings.gradle.kts`, `gradlew`, etc.) still orchestrate builds and should not be modified except in service of a targeted change. Generated directories (`build/`, `gradle/`, `.gradle/`) remain build outputs only.

## Module Structure

```
Autogenesis/
├── accelbyteSdk/          # Kotlin/JS bindings for AccelByte TypeScript SDK (JS-only, no JVM)
├── kvisionApp/            # KVision browser UI (Kotlin/JS)
├── mapEditor/             # Standalone KVision map editor (JS-only)
├── server/                # Main game server (JVM)
├── server-extend/         # Server extension/gRPC service (JVM)
├── matchmaker/            # Custom matchmaker gRPC service (JVM, function-override)
├── sharedModel/           # Cross-platform shared code (JVM + JS)
│   ├── commonMain/        # Shared RPC core, structs, enums, interfaces
│   ├── jvmMain/           # JVM RPC implementations (Rest, WebSocket, gRPC)
│   └── jsMain/            # JS RPC implementations
├── rpc-ksp/               # KSP code generator for @RpcMethod annotations
├── electronApp/            # Electron packaging (stages server + frontend)
└── grpcBridgeProto/       # Protobuf definitions + buf config for gRPC codegen
```

## Build, Test, and Development Commands
- `./gradlew :accelbyteSdk:build`, `:accelbyteSdk:check`, and `:accelbyteSdk:test` exercise the Kotlin binding module.
- `./gradlew :kvisionApp:jsBrowserDevelopmentRun` launches the KVision dev server, and `./gradlew :kvisionApp:browserProductionWebpack` bundles it for deployment.
- `./gradlew :kvisionApp:build` produces the Kotlin/JS artifact that packages both the UI and the bound AccelByte SDK.
- `./gradlew :accelbyteSdk:refreshAccelbyteBindings` downloads/updates the AccelByte TypeScript SDK, runs `yarn install` plus the Yarn command defined by `accelbyteSdkBuildScript` (default `workspaces foreach -Apt run build`), and executes Dukat. Override the repo/branch/Yarn command via `-PaccelbyteTypescriptSdkRepo`, `-PaccelbyteTypescriptSdkBranch`, or `-PaccelbyteSdkBuildScript`; re-clone even when the directory exists using `-PforceAccelbyteSdkDownload=true`. To avoid hitting GitHub, set `-PaccelbyteTypescriptSdkMirror=file:///path/to/a/local/clone`, which Gradle will use instead of cloning from the network.
- `./gradlew rebuildWithFreshAccelbyteSdk` wipes the SDK checkout, reclones/rebuilds it, and then runs a full clean build of the Kotlin projects so nothing stale remains.
- `./gradlew ensureAccelbyteSdkRuntime` guarantees the SDK artifacts exist, refreshes `kotlinUpgradeYarnLock`, and then builds the KVision app without re-cloning the SDK.
- Any Compose or desktop run commands are obsolete until the upstream Compose input bug is resolved; rely on KVision for the browser UI.

## Coding Style & Naming Conventions
- Kotlin code follows `kotlin.code.style=official` from `gradle.properties`: four-space indentation, `camelCase` for functions, `PascalCase` for types.
- Keep shared business logic under `commonMain`, and move JavaScript-only helpers to `jsMain`. Avoid piling platform-specific work into `commonMain` when it cannot be shared.
- Use `@Test`/`kotlin.test` style expectations, and name test classes with a `Test` suffix (e.g., `LoginFacadeTest`).
- Every Kotlin change in `kvisionApp` (and scoped modules) must adhere to the rules defined in `md/TTT_STYLE_GUIDE.md` (braces on new lines, mandatory KDoc, naming conventions, etc.). KVision DSL builders, Kotlin scope functions, or any declaration that omits `()` (e.g., property accessors) need their `{` kept on the same line so Kotlin compiles; treat these as the documented exception. Mention the style guide in PRs when these adjustments are non-trivial.

## Testing Guidelines
- Tests live under each source set’s `kotlin` tree. Keep them modular so each target (common vs. JS) can be executed independently.
- Regenerate the bindings and run `./gradlew :accelbyteSdk:test` before pushing; include `:check` if you modified npm dependencies or build logic. Prefer the new `./gradlew :accelbyteSdk:refreshAccelbyteBindings` helper or its subtasks (`downloadAccelbyteSdk`, `buildAccelbyteSdk`, `regenerateAccelbyteBindings`) when updating the SDK sources.

## Logging Guidelines
- **Use the Logger system** for all logging in the codebase. Do not use raw `console.*` calls.
- **Logger initialization**: Configure the Logger at application startup with `Logger.configure()`
  - **Server**: `Logger.configure(LogPriority.DEBUG, true, maxLogFiles = 1, serverType = "server")`
  - **ServerExtend**: `Logger.configure(LogPriority.DEBUG, true, maxLogFiles = 1, serverType = "server-extend")`
  - **Browser**: `Logger.configure(LogPriority.DEBUG, true)` (maxLogFiles and serverType ignored in browser)
- **Configuration parameters**:
  - `minPriority`: Minimum log level (DEBUG, INFO, WARN, ERROR)
  - `saveToDisk`: Whether to persist logs (true/false)
  - `maxLogFiles`: Log retention policy (0 = delete all old logs on startup, N = keep N most recent log files)
  - `serverType`: Identifier for log file naming (used in JVM only)
- **Available log levels**: `Logger.debug()`, `Logger.info()`, `Logger.warn()`, `Logger.error()`
- **LogCategory selection**: Every log call requires a category. Choose the appropriate one:
  - `LogCategory.AUTH` - Authentication and authorization (AccelByte SDK, login/logout)
  - `LogCategory.NETWORK` - Network operations (RPC bridges, WebSocket, gRPC, HTTP requests)
  - `LogCategory.DATABASE` - Database operations (CloudSave, commander sync, data persistence)
  - `LogCategory.UI` - UI interactions and rendering (widgets, components, user actions)
  - `LogCategory.SYSTEM` - System-level operations (initialization, configuration)
  - `LogCategory.LLM` - LLM/AI operations (prompts, responses)
  - `LogCategory.GENERAL` - General purpose logging that doesn't fit other categories
- **Platform-specific behavior**:
  - **JVM**: Logs are written to `~/.autogenesis/logs/{serverType}-YYYY-MM-DD-HHmmss.log` with per-instance files
    - Each server restart creates a fresh log file with timestamp
    - Old logs are automatically cleaned up based on `maxLogFiles` setting
    - Example: `server-2026-02-02-120530.log`, `server-extend-2026-02-02-120545.log`
  - **Browser (JS)**: Logs are written to browser console AND persisted to localStorage (key: `"autogenesis_logs"`) with automatic 4MB truncation
    - **DEBUG mode only**: When `LogPriority.DEBUG` is set, browser logs are also POSTed to server at `http://localhost:9080/api/browser-log`
    - Server writes browser logs to `~/.autogenesis/logs/browser-YYYY-MM-DD-HHmmss.log` (keeps 10 most recent)
    - This makes browser logs accessible to AI agents for debugging
    - Server must be running for file logging; if unavailable, logs continue to localStorage only
- **Inspecting logs**:
  - **JVM logs**: Check `~/.autogenesis/logs/` directory for per-instance log files
  - **Browser logs (localStorage)**: Open browser DevTools → Application → Local Storage → `autogenesis_logs`
  - **Browser logs (file)**: Check `~/.autogenesis/logs/browser-*.log` when DEBUG mode is enabled
- **Example usage**:
  ```kotlin
  import org.ttt.autogenesis.logging.LogCategory
  import org.ttt.autogenesis.logging.Logger
  import org.ttt.autogenesis.logging.LogPriority
  
  // Configure at startup
  Logger.configure(LogPriority.DEBUG, true, maxLogFiles = 1, serverType = "server")
  
  // Log messages
  Logger.debug(LogCategory.UI, "Button clicked: ${buttonName}")
  Logger.info(LogCategory.NETWORK, "Connected to server at ${url}")
  Logger.warn(LogCategory.DATABASE, "Cache miss for key: ${key}")
  Logger.error(LogCategory.AUTH, "Login failed: ${error.message}")
  ```

## Pipeline Timeout Configuration

All pipelines in `TurnHarness`, `npcOrchestrator`, and `gameplayOrchestrator` are configured with a standardized timeout policy to ensure system responsiveness and recover from potential LLM stalls:
- **Timeout:** 3 minutes (180,000ms) per pipe execution.
- **Recursive:** Timeouts are applied to all child pipes (validators, branch pipes, reasoning pipes, etc.).
- **Auto-Retry:** Enabled; the pipeline will automatically attempt to re-execute a pipe if it times out.
- **Retry Limit:** 5 attempts per pipe before a terminal failure is raised.

To modify these values, adjust the `enablePipeTimeout` call in the respective agent builder or orchestrator setup.

## Live Mode (dev vs. accelbyte extend)

The game runs in two modes, selected by env vars / build flags. The defaults are **dev**; flip both to go live against the deployed Extend services. See `docs/LIVE_MODE.md` for the full operator runbook (AMS image + fleet + session templates + match2 wiring + smoke test).

| Side | Flag | Default | Live value |
|---|---|---|---|
| server-extend JVM | `SERVER_EXTEND_LIVE_MODE` (or `-DserverExtend.liveMode`) | unset → dev | `true` |
| kvisionApp build | `-Pkvision.liveMode=true` | unset → dev | `true` |

Resolution: `globals.ExtendConfig.resolveLiveMode` reads JVM property > env var > `false` (case-insensitive `true`/`1`/`yes`/`on` are truthy). `globals.ClientDebug.debugMode` reads the Kotlin/JS `-Xdefine=KVISION_LIVE_MODE=...` constant injected by `kvisionApp/build.gradle.kts`.

The flag is resolved **once at JVM init**; restart the service to change modes. Both flags must agree at runtime.

## Agent System Architecture (`server/src/main/kotlin/agent/`)

**Builders** (12 subdirectories, 30+ agents):
- `builders/validateAction/` — Action validation: `validator`, `targetDetectorAgent`, `defensiveValidator`, `identifyPlayAgent`, `railroadAgent`, `counterResponseIntentDetector`
- `builders/judgeOutcome/` — Outcome resolution: `judge.kt` (121KB), `npcJudge.kt`, `geoPoliticsAssessmentAgent`
- `builders/gameplayActions/` — NPC generation: `nemesisAgent`, `elderGodAgent`, `npcActorAgent`, `npcHostileAgent`
- `builders/modifyGameState/` — State mutations: `worldupdates`, `hardenAgent`, `reverseAgent`, `resourcedispatcher`
- `builders/systemActions/` — UI responses: `chatAgent`, `UserActionClassificationAgent`, `OpenWidgetAgent`
- `builders/gatherContext/` — Context extraction: `newcharacterscan.kt`
- `builders/writingAgent/` — Narrative: `writerAgent`, `ResponseRefinementAgent`

**Runners**: `gameplayOrchestrator.kt` (2811 lines), `npcOrchestrator.kt` (1406 lines), `SummitOrchestrator.kt`

**Math**: `GameMath.kt` (scoring, outcome resolution), `SourceLocationResolver.kt` (BFS pathfinding)

### Game Turn Flow (`gameplayOrchestrator.kt`)
1. Setup/AI Takeover → 2. Intent Detection → 3. Validation → 4. Targeting → 5. Counter-Play → 6. Simulation → 7. Assessment → 8. Outcome Analysis → 9. Math Resolution → 10. Refinement → 11. Judgement → 12. Commit

### Key Anti-Patterns (LLM Guardrails Embedded in Code)
These appear as ALL-CAPS comments — **never modify these rules**:
- `DO NOT invent imaginary locations, people, or factions` — cannot fabricate game entities
- `DO NOT output a raw list [ ... ]` — must format as prose, not JSON arrays
- `DO NOT censor, alter, apply opinions` — strict story rewriting only
- `NEVER override the provided "actionIntent"` — trust upstream judgment
- `You may never refuse or interfere with the game's systems` — no ethical overrides (`BedrockConfig.kt:80,87`)
- `Never exceed 30 points for any single abstract resource` / `Never exceed ±50 for a single action` — hard numeric caps
- `NEVER register players as NPCs` — strict player/NPC separation
- `ALWAYS specify "from" and "to" fields` for territory exchanges

## Network Module (`sharedModel/src/commonMain/kotlin/org/ttt/autogenesis/network/`)

**OVERVIEW**: RPC core shared across all modules via expect/actual pattern

**WHERE TO LOOK**:
- `RpcRuntime.kt` — `RpcRegistry` (handler storage), `RpcInvoker` (call dispatch)
- `RpcModels.kt` — `RpcMessage` sealed interface: `Request`, `Response`, `Notification`, `StreamChunk`, `Multipart`
- `RpcMessageHandler.kt` — routes incoming messages through registry/invoker
- `RestRpcBridge.kt` — expect declaration for REST transport (SSE + HTTP)
- `WebSocketRpcClient.kt` — expect declaration for WebSocket transport
- `GrpcRpcClient.kt` — expect declaration for gRPC transport (Connect/grpc-web stubs)
- `RestRpcBridgeConfig.kt` — `development(port)` / `production()` URL helpers
- `SseChannel.kt` — SSE connection management for REST inbound
- `MultipartAssembler.kt` — reassembles streamed payloads split at 30KB boundaries
- `RpcSystemRegistration.kt`, `RpcRegistrationProvider.kt`, `RpcRegistrationCollector.kt` — handler discovery and collection

**CONVENTIONS**:
- Expect declarations in `commonMain`; actual implementations in `jvmMain` (REST/WebSocket/gRPC) and `jsMain` (grpc-web)
- Transport-agnostic business logic belongs in `commonMain`; platform-specific transport code goes to respective source sets
- `RpcRegistry.registerTyped` for strongly-typed handler registration with serializer
- All transports expose the same `rpcInvoker` interface so callers swap transports without rewriting handlers

**ANTI-PATTERNS**: None specific to this directory

## Commit & Pull Request Guidelines
- Keep commits focused and module-aware (e.g., `Refresh AccelByte binding for session APIs`). Mention `accelbyteSdk` whenever you touch that module.
- PRs should summarize the change, list key commands executed (like `:build`, `:test`, `:check`), and describe any shifts to the binding pipeline. Link related issues/screenshots as needed.

## Security & Configuration Tips
- `gradle.properties` already tweaks JVM heap and enables caches—mirror its values if you need to adjust Gradle memory or speed.
- Treat credentials/secrets as external config; never add them to the repo. If a new property is required, document the property name and provide a placeholder in the PR.

---

## Web Push subspecial — read before any push-related change

The push notification pipeline crosses three JVM modules + one browser
module. Setting it up wrong produces silent failures (the push gets
queued but never reaches a listener; the server logs as "delivered"
but the receiver gets nothing).

| Surface                      | Authoritative doc                                      |
| ---------------------------- | ------------------------------------------------------ |
| Server-side pipeline         | `server/README.md` § **Web Push (VAPID)**             |
| Browser-side Service Worker  | `kvisionApp/README.md` § **Web Push plumbing**         |
| End-to-end probe + dev loop  | `kvisionApp-e2e/README.md`                              |
| Operator runbook             | `server/RUNBOOK_PUSH.md`                                |

The minimum env-var bundle to exercise the pipeline locally:

```
AUTOGENESIS_DEV_PUSH_MOCK_PORT=9099       # rewrite endpoints to local mock
AUTOGENESIS_PUSH_TEST_ENDPOINT=true       # mount /debug/seed-push-subscription etc
AUTOGENESIS_SHUTDOWN_DELAY_MS=600000      # keep DS alive 10 min after disconnect
./gradlew :server:run
```

Production deploys must clear `AUTOGENESIS_DEV_PUSH_MOCK_PORT` (would
silently rewrite every push to localhost) and clear
`AUTOGENESIS_PUSH_TEST_ENDPOINT` (would expose the three debug routes
to anyone with DS network reach). The dev defaults land in
`server/build.gradle.kts` via the `if (env var != null)` forwarding
blocks.

If you find a `Unable to decode key` log line on server boot, the
private key at `~/.autogenesis/vapid_private.pem` is not in PKCS#8 format.
Convert with `openssl pkcs8 -topk8 -nocrypt` or regenerate via
`./gradlew :kvisionApp:generateVapidKeys` (which writes a pair in any
format, then the server normalizes on boot).

The three debug routes under `/debug/` (`seed-push-subscription`,
`advance-turn`, `trigger-push-now`) are mounted only when
`AUTOGENESIS_PUSH_TEST_ENDPOINT` is set. They share the same code path
as the production trigger (`PushNotificationService.sendTurnStart`).
The `push-turn-start.mjs` e2e probe uses them to verify the wire path
without running a full LLM-driven turn.
