# Judge Pipeline Results → World State Plan
>
## Goals & Success Criteria
- Document how the existing `judge` pipeline orchestrated by TPipe determines action success, and ensure we leverage the same reasoning stack to update the shared world model.
- Extend `WorldManager` with a reusable function that consumes the judge’s `Results`/`Victory?` outputs, mutates player resources and territory ownership, and records the corresponding `ActionHistory` events so downstream scoring, persistence, and UI replay stay in sync.
- Identify the key touch points where ActionHistory events must be emitted (judge success/failure, resource grants/losses, territory gains/losses, player outcomes) and plan their insertion around the LLM-driven pipeline flow. All new helpers must live in `server/src/main/kotlin` code and respect the AGENTS/TTP style conventions.
- Ensure the change is verifiable via targeted unit tests (e.g., `WorldManager` mutation coverage and `ActionHistory` meta validation) and keep the UI/ScoreManager integration untouched beyond consuming the recorded events.

## Current Status
- `server/src/main/kotlin/agent/builders/judge.kt` currently constructs a two-stage TPipe `Pipeline` (pass/fail → gains/losses). Each pipe is a `BedrockMultimodalPipe` configured with system prompts, JSON schemas (`Victory?` and `Results`), reasoning builders, and validators. There is no authoring logic yet that reads those outputs and mutates `WorldManager`.
- TPipe (examined at `<TPipe-repo>/TPipe`) is the core LLM workflow framework. Its `Pipeline` class sequences pipes sharing context windows/global context, and each pipe operates over `MultimodalContent` with tracing, token/accounting, and `ContextBank` mini-banks for cross-stage persistence. `buildNewCharacterScanPipeline` and similar pipelines demonstrate how context bank lorebooks and transformation functions orchestrate multi-stage story tasks.
- `WorldManager` already exposes synchronized state (`world`, `history`, `actionHistoryLog`, mutex). Action history events recorded today are consumed by `ActionHistoryRpcHandlers`/`HistoryParser`/`ScoreManager`. `newcharacterscan.kt` shows how helper functions log resources/ NPC introductions through `WorldManager.recordActionHistoryEventUnlocked`.

## Tasks
1. **Capture judge outputs and route them into the game state.**
   - Define a new `WorldManager.applyJudgeResults(playerName: String, wasSuccessful: Boolean, results: Results, turnNumber: Int, timestamp: Long)` helper (and any supporting data conversions) that:
     * Applies `assetsGained/assetsLost` to the appropriate player’s `resources`.
     * Applies `territoryGained/territoryLost` to `world.mapTiles` using the normalized name helpers we already built (`sharedModel` solution).
     * Records `ActionHistory` events for the judge evaluation (`GameEventType.JUDGE`), each resource change (`RESOURCE`/`RESOURCE_GRANT`), and each territory change (`TERRITORY`). Include a `PlayerOutcomeMetadata` event for the overall result so `ScoreManager` knows the turn outcome.
   - Keep all world mutations and ActionHistory recordings behind `worldMutex`.
2. **Wire the `judge` pipeline invocation to this new helper.**
   - Identify (or create) the pipeline caller that executes `buildJudge()` (based on existing agent orchestrators such as the `newcharacterscan` pipeline). If none exists, document the necessary scaffolding (probably the higher-level orchestration that runs the LLM turn resolution).
   - After the pipeline returns its `Results`, call `WorldManager.applyJudgeResults(...)` with the current player, derived turn number, and timestamp. If the pipeline is meant to be reused across runners (e.g., in the yet-to-be-implemented agent runner), ensure the helper sits close to the `Pipeline.execute(...)` invocation.
3. **Treat ActionHistory as the single source of truth for rule enforcement.**
   - Extend `ActionHistory` metadata creation logic by reusing the existing metadata classes (`JudgeEventMetadata`, `ResourceEventMetadata`, `TerritoryEventMetadata`, etc.) so the new events are compatible with `ActionHistoryProcessor`.
   - Add a guard to `WorldManager` so duplicates aren’t recorded when calling from replays/tests.
4. **Smoke-test and verify.**
   - Add unit tests (e.g., under `server/src/test/kotlin` or `server/src/commonTest` depending on module setup) that:
     * Feed synthetic `Results` into `WorldManager.applyJudgeResults` and assert player resources + territory metadata and recorded ActionHistory event list.
     * Validate the new ActionHistory metadata surfaces through `ActionHistoryRpcHandlers`/`HistoryParser` (maybe via small helper using `ActionHistoryProcessor`).
   - Run targeted builds (`./gradlew :server:build` or the appropriate incremental tasks) after tests are added.

## Dependencies & Risks
- Relying on `Results` asset/territory strings matching existing `Resource`/`Territory` names; we may need normalization or fallback logs.
- `judge` pipeline is configured via TPipe’s `BedrockMultimodalPipe`; ensure any data produced is serialized/deserialized using `extractJson` before handing off to `WorldManager`. We must document how context (page keys, player stats) flows into eventual scoring to avoid misalignment with future pipelines.
- ActionHistory already has a plan for UI/Score integration; ensure we reuse the same metadata classes to keep scoring consistent. Any new event type (e.g., `PLAYER_OUTCOME`) must have validation logic and be expected by `ActionHistoryProcessor`.
- Because world mutations happen while pipelines run, we must keep world mutex acquisition consistent to avoid concurrency issues.

## Testing & Verification
- Unit tests for `WorldManager` applying judge results plus verifying action history entries exist with correct metadata (use `ActionHistory.validate()`).
- `ActionHistoryProcessor` integration test to confirm new judge events increment scoring counters as expected (use synthetic `ActionHistoryBatch`).
- Run `./gradlew :server:build` (or targeted `:server:test` tasks) to make sure server compiles and tests pass after implementing new helper(s).

## Rollout & Monitoring
- Once helper is in place, ensure `ActionHistoryRpcHandlers.processTurnComplete` still processes the new events correctly (no code changes likely needed) and that `ScoreManager` picks up judge-driven scoring deltas for victory/loss.
- Keep an eye on the `WorldManager.actionHistoryLog` size to ensure we never record duplicate events each turn (maybe add dedupe by timestamp+player if necessary).

## Communication
- Document the new `WorldManager.applyJudgeResults` API in server READMEs/comment blocks.
- Mention in the ActionHistory plan (if still tracked) that judge-fueled events now generate `PlayerOutcome`/`Judge`/`Resource`/`Territory` entries, so UI/historical traces can show them.

## Discovery Commands
- `sed -n '1,200p' server/src/main/kotlin/agent/builders/judge.kt`
- `sed -n '1,220p' server/src/main/kotlin/gameState/WorldManager.kt`
- `sed -n '1,220p' server/src/main/kotlin/agent/builders/newcharacterscan.kt`
- `sed -n '1,200p' <TPipe-repo>/TPipe/docs/core-concepts/pipeline-class.md`
