# Resource Dispatcher → World/ActionHistory Plan

## Goals & Success Criteria
- Update `server/src/main/kotlin/agent/builders/resourcedispatcher.kt` so the Bedrock pipeline’s JSON output actually mutates the shared `WorldManager` resources and emits the appropriate `ActionHistory` events (`GameEventType.RESOURCE`/`RESOURCE_GRANT`) instead of leaving interpretation to callers.
- Reuse the existing `WorldManager` helpers (mutex, `recordActionHistoryEventUnlocked`, and resource logging patterns from `newcharacterscan.kt`) so resource changes are atomic, logged, and scored consistently.
- Ensure the dispatcher can resolve the active turn player (similar to the judge helpers) so resource updates tie to the correct `Player` and turn number; log warnings when no player context exists.
- Cover the new behavior with unit tests under `server/src/test/kotlin/org/ttt/autogenesis/gameState`, referencing the existing `WorldManagerJudgeTest` as a template.
- Keep shared conventions: bracing style per `md/TTT_STYLE_GUIDE.md`, avoid touching root Gradle files, and document any new helper functions in AGENTS/plan comments if needed.

## Current Status
- `resourcedispatcher.kt` defines `ResourceArray` and a single `dispatchPipe` that only declares the LLM prompts; there is no transformation or world mutation yet (lines 9-40).
- `WorldManager` already provides `recordActionHistoryEventUnlocked` plus the new `applyJudgeResults` helper that logs `ActionHistory` events, so adding resource dispatch logic can follow that mutex pattern (`server/src/main/kotlin/gameState/WorldManager.kt`:18-132).
- `ActionHistory` resource metadata lives in `sharedModel/src/commonMain/kotlin/structs/ui/ActionHistory.kt`, and existing agents like `newcharacterscan.kt` (around lines 569-613) show the expected logging pattern for resources/NPC introductions.
- No existing plan addresses wiring this dispatcher yet, so we will extend the ActionHistory implementation plan in practice with these steps.

## Tasks
1. **Interpret and model the JSON output from the dispatcher pipe**
   - Define a data class (e.g., `ResourceAdjustment`) representing a single grant/removal (name + granted/destroyed flag).
   - Update `ResourceArray` to contain a list of adjustments instead of one `Resource`.
   - In `buildResourceDispatcher()`, add a `.setTransformationFunction { ... }` that deserializes the JSON, resolves the current player (use `WorldManager.playerStats` + turn tracking), and passes the adjustments to a new helper.
2. **Hook into `WorldManager` to apply resource changes safely**
   - In `WorldManager`, add `applyResourceAdjustments(playerName, adjustments, turnNumber, timestampMillis)` with the same mutexed pattern as `applyJudgeResults`, logging `ActionHistory` events for each grant/destroy using `ResourceEventMetadata`.
   - Reuse `guessResourceType` (maybe the one added earlier) to classify names and record events with `GameEventType.RESOURCE_GRANT`/`RESOURCE`.
   - Record the resulting events with `recordActionHistoryEventUnlocked`.
3. **Log `ActionHistory` entries and ensure scoring/consumption**
   - After applying adjustments, make sure the dispatcher or helper logs `PlayerOutcome` if needed, or at least ensures the `ActionHistory` entries exist so `ActionHistoryProcessor` handles them (similar to `ScoreManager`).
   - Add tests covering resource additions/removals to confirm `WorldManager.actionHistoryLog` grows appropriately and `player.resources` matches the adjustments.
4. **Testing and verification**
   - Create `server/src/test/kotlin/org/ttt/autogenesis/gameState/WorldManagerResourceDispatchTest.kt` verifying success/failure, resource deduplication, and logging counts.
   - Run `./gradlew :server:test` after implementation.

## Dependencies & Risks
- Accurate JSON output is critical; the dispatcher must parse LLM output reliably and guard against missing or malformed data (similar to the judge plan’s validators).
- The new helper must align with existing resource classification enums (`enums.ResourceType`) to keep scoring consistent.
- Concurrency risk exists, so always acquire `worldMutex` before mutating shared player/resource state.

## Testing & Verification
- Unit tests that call `WorldManager.applyResourceAdjustments` with synthetic adjustments and assert resource lists and `ActionHistory` entries.
- Ensure tests cover both granting and destroying resources, plus the case where the player already has the resource.
- Run `./gradlew :server:test` and document the command in the final summary.

## Rollout & Monitoring
- Once merged, monitor `WorldManager` logs for warnings about unknown players or invalid adjustments; add telemetry if needed.
- The UI will automatically consume history events through the existing RPC handlers, so no new UI work is required unless new event types are introduced.

## Communication
- Add a note to `ActionHistory_Implementation_Plan.md` (or related documentation) that the resource dispatcher now logs `RESOURCE`/`RESOURCE_GRANT` events via `WorldManager`.
- Mention the new test file and helper functions in the final update.

### Discovery Commands
- `sed -n '1,220p' server/src/main/kotlin/agent/builders/resourcedispatcher.kt`
- `sed -n '1,240p' server/src/main/kotlin/gameState/WorldManager.kt`
- `sed -n '520,640p' server/src/main/kotlin/agent/builders/newcharacterscan.kt`
