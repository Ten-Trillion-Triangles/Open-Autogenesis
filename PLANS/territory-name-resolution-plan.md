# Territory Name Resolution Plan

## Goals & Success Criteria
- Add shared helpers that normalize territory names so any `String` input (trimmed + lowercase) can locate the matching `Territory` from **the full world map**, a **Player**'s tracked tiles (starting and captured), or an **Npc**'s captured territory despite casing differences.
- Keep the logic in `sharedModel` so both the server and `kvisionApp` code can consume it without platform-specific duplication; document new helpers with KDoc per `md/TTT_STYLE_GUIDE.md`/AGENTS conventions.
- Verify the new helpers through shared-model tests so downstream code can rely on deterministic resolution when wiring gamestate updates.
- Ensure existing build orchestration (root Gradle/Kotlin scripts) stays untouched unless strictly required for this scoped change.

## Current Status
- `Territory`, `Player`, `Npc`, and `World` data classes only hold raw properties; no helper exists to search by name or normalize casing.
- `globals.World` exposes the `World` snapshot, a single `localPlayer`, and the `availableCommanders` list but still lacks search helpers.
- `sharedModel` already has test entry points such as `:sharedModel:allTests` (plus `:sharedModel:jsTest`/`:sharedModel:jvmTest`) that can host new unit tests for `World`/`Player`/`Npc` behaviors.

## Tasks
1. **Normalize territory identity**
   - Introduce a new utility file (e.g., `TerritoryLookup.kt`) under `sharedModel/src/commonMain/kotlin/structs` that defines a `String.normalizeTerritoryName()` helper and a `Territory.matchesName(name: String)` extension.
   - Document each helper with KDoc to explain normalization and why the comparison is case/whitespace insensitive (per TTT style).
   - Owner: Dev, ETA: this sprint, Dependencies: AGENTS instructions + TTT style.
2. **Expose search helpers on containers**
   - Add extension functions for `Collection<Territory>`, `World`, `Player`, and `Npc` that return the first matching territory by normalized name (including `Player.startingTile` and `capturedTerritory`).
   - Cover both `MutableList` and `List` cases or use generic `Collection` to maximize reuse.
   - Ensure these helpers live in the shared module so the KVision UI can call them without extra bridging code.
3. **Update surface area for gamestate resolution**
   - Consider adding bridging entry points (e.g., `World.findTerritoryByName`, `Player.trackTerritoryByName`, `Npc.capturesTerritoryByName`) so future gamestate updates can look up cards by name without direct list iteration.
   - Keep bracing/spacing consistent with TTT style (opening braces on new line for regular functions, KDoc for each public helper, inline comments only if necessary).
4. **Write dedicated tests**
   - Create a new `TerritoryResolutionTest` under `sharedModel/src/commonTest/kotlin/org/…` (follow existing test package structure) verifying: world search works, player search hits starting/captured tiles, NPC search matches captured territory, and each respects casing differences.
   - Run `./gradlew :sharedModel:allTests` (or target-specific `:sharedModel:jsTest`/`:sharedModel:jvmTest` if only one platform is affected) locally to prove the helpers and their tests pass.
5. **Optional: expose helper usage to `kvisionApp`**
   - If direct calls are needed, update `kvisionApp` code where gamestate updates happen to leverage the new helpers instead of manual name comparisons (evaluate whether the existing call sites already iterate over `worldData.mapTiles`).
   - Document intention in plan; implement only if immediate need confirmed.

## Dependencies & Risks
- Root-level tooling and `build.gradle.kts` must remain untouched per AGENTS guidelines; the plan avoids touching them unless a missing dependency forces a change.
- Risk: players/NPCs might hold duplicate names; helpers should return the first match but also highlight (via KDoc) that names must be unique to avoid ambiguity.
- Risk: new helpers rely on `String.lowercase()` which respects locale; if territory names contain locale-sensitive characters, normalize strategy might need revisit (future follow-up).

## Testing & Verification
- Add unit tests in `sharedModel/src/commonTest/kotlin/org/…` verifying normalization logic and search paths for world/player/npc.
- Run `./gradlew :sharedModel:allTests` (and optionally `:sharedModel:jsTest`/`:sharedModel:jvmTest` if the helpers target a specific platform) once helpers and tests are added (document these commands in final summary).
- Confirm `kvisionApp` builds implicitly rely on sharedModel so no additional Kotlin/JS-specific tests are required for this task.

## Rollout & Monitoring
- Merge the helpers once tests pass and ensure any consumers (e.g., `kvisionApp` gamestate mutation code) start calling the normalized lookup instead of manual loops.
- Monitor the next `:kvisionApp:build` run for regressions (if the UI uses the new helpers, the production bundle should stay green).

## Communication
- Share the new plan with the team via this document and mention that the helper functions live in `sharedModel/src/commonMain/kotlin/structs/TerritoryLookup.kt`.
- Highlight the need to keep territory names unique to avoid ambiguous matches when another developer uses these helpers.

### Commands Used for Discovery
- `rg -n "data class World" -n` (found `World` definition in `sharedModel`).
- `sed -n '1,200p' sharedModel/src/commonMain/kotlin/structs/{Territory,Player,Npc}.kt` (examined data classes for existing fields).
- `sed -n '1,200p' kvisionApp/src/jsMain/kotlin/globals/World.kt` (checked how the UI exposes `World`).
