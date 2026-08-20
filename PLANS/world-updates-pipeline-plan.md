# World Updates Pipeline Implementation Plan

## Goals & Success Criteria
- Turn the current `worldUpdatesPipeline()` (server/src/main/kotlin/agent/builders/worldupdates.kt) from a prompt-only stub into a producer-consumer that ingests `UniverseChanges` returned by the LLM and applies them to the shared `WorldManager.world`.
- Ensure every change discovered by the pipe (map tiles removed, new physics/magic) is represented as structured data, applied under `WorldManager.worldMutex`, and recorded as an `ActionHistory` event so scoring/ActionHistoryProcessor receive the narrative.
- Reuse existing helpers (`WorldManager.recordActionHistoryEventUnlocked`, `sharedModel/ui/ActionHistory.kt` metadata) and shared conventions (AGENTS style guide, timestamp/locking utilities) while avoiding duplicated logic.
- Cover the new helpers with unit tests under `server/src/test/kotlin/org/ttt/autogenesis/gameState`, ensuring world mutation logic and action history logging behave deterministically.

## Current Status
- `worldUpdatesPipeline()` currently creates a single `physicsChangesAndMapTilesRemovedPipe` that prompts (previous turn + world info) for `UniverseChanges` but never validates or acts on the generated JSON (`worldupdates.kt`:13‑44).
- There is no data class describing the “before/after” pairings or the type of world change—only `UniverseChanges` with a `comprehensiveListOfChanges` string list.
- `WorldManager` already hosts logging/mutation helper patterns for judge and resource adjustments (`WorldManager.applyJudgeResults`, `applyResourceAdjustments`, etc.). `ActionHistory` event types exist for world rules/story updates (`GameEventType.WORLD_RULE`, `GameEventType.STORY`), which we can reuse to capture this pipeline’s semantic changes.

## Tasks
1. **Define a structured schema for universe changes**
   - Introduce `gameState/UniverseChange.kt` with entries like `enum ChangeType { TERRAIN_REMOVED, PHYSICS_ADDED, MAGIC_DISCOVERED }` and fields for `before`, `after`, `details`.
   - Update `UniverseChanges` to carry a list of `UniverseChange`.
   - Add JSON extraction helpers (`com.TTT.Util.extractJson`) similar to the other pipelines.
2. **Extend `worldUpdatesPipeline()` with validation/transformation**
   - Add `.setValidatorFunction { ... }` that ensures the LLM output deserializes into `UniverseChanges`.
   - Add `.setTransformationFunction { ... }` that:
     * Deserializes the JSON.
     * Retrieves `Turn`/player context (use `WorldManager.resolveCurrentTurnPlayerName()` or similar).
     * Calls a new `WorldManager.applyUniverseChanges(...)` helper with the list of changes, turn number, and timestamp (use `TimeProvider.nowMillis()`).
3. **Implement `WorldManager.applyUniverseChanges`**
   - Acquire `worldMutex`, iterate changes, mutate `world.mapTiles`/`world` rules accordingly (e.g., remove tiles whose names match, set `isDestroyed`, update `resource`/`obstacle` states).
   - For physics/magic changes, adjust `World` metadata (e.g., maintain `world.rules` list or a new `MutableList<String>` describing phenomena) so the LLM context stays consistent.
   - Log each modification via `ActionHistory` with `GameEventType.WORLD_RULE` (or `STORY` if narrative). Use metadata structures (e.g., `WorldRuleMetadata`) and the existing `logResourceHistoryEvent` pattern for history insertion.
4. **Testing & verification**
   - Add `WorldManagerWorldUpdatesTest` verifying that `applyUniverseChanges` removes tiles, logs history events, and updates world rule lists correctly.
   - Run `./gradlew :server:test` and document results.

## Dependencies & Risks
- Parsing LLM output reliably is critical; add defensive validation and log warnings for malformed responses.
- Removing map tiles or altering world physics must consider existing references (players owning territories). Use normalized lookups to avoid unintended NPEs.
- `ActionHistory` must continue to represent these changes consistently; update `ActionHistoryProcessor` if new metadata fields are required (consult ActionHistory plan).

## Testing & Verification
- Unit tests that apply sample `UniverseChanges`, assert territory removal, and verify action history logs use `GameEventType.WORLD_RULE`.
- Run `./gradlew :server:test` post-implementation.

## Rollout & Monitoring
- Once merged, watch `WorldManager.history`/`actionHistoryLog` to ensure these world events produce stable scoring updates and not duplicate entries.
- If additional world change agents appear later, extend this helper to centralize world metadata adjustments.

## Communication
- Add a note at the bottom of `PLANS/world-updates-pipeline-plan.md` summarizing test results and referencing the new helper file for future maintainers.

### Discovery Commands
- `sed -n '1,200p' server/src/main/kotlin/agent/builders/worldupdates.kt`
- `sed -n '1,200p' server/src/main/kotlin/gameState/WorldManager.kt`

### Implementation Notes
- `server/src/main/kotlin/gameState/WorldManager.kt` now exposes `applyUniverseChanges` and builds `ActionHistory` events; the schema lives in `server/src/main/kotlin/gameState/UniverseChange.kt`.
- Tests executed via `./gradlew :server:test` (pass) to confirm tile/removal logic and history logging.