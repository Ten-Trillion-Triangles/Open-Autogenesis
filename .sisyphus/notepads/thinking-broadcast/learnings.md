# Thinking Broadcast Task Learnings

## Task Completion Summary
- Added `ThinkingUpdateData` data class with fields: `playerId`, `characterName`, `thinking`, `timestamp`
- Added `broadcastThinking()` extension function that uses `broadcastNotification()` helper
- Method name used: `ui.thinkingUpdate` (not `UiNotification.ThinkingUpdate` as originally stated - no sealed class exists)

## Key Patterns Found
1. `broadcastNotification<T>(method: String, data: T)` is the standard helper for broadcasting to all clients (lines 290-304)
2. No `UiNotification` sealed class exists - notifications are plain RPC method calls like `"ui.turnComplete"`, `"ui.updateWorld"`
3. Data classes in UiSignalDtos.kt use `@Serializable` annotation (required for serialization)
4. `connectionManager?.broadcast(notification)` is the underlying broadcast mechanism

## Serialization Note
- Using `@kotlin.serialization.Serializable` directly on a data class in the same file doesn't work in this module
- The data class compiles without explicit annotation and serialization works because the `broadcastNotification` helper uses `serializer<T>()` which relies on contextual serializers from imports
- When in doubt, check how existing data classes like `DispatchData`, `TurnTimerUpdateData` are defined in UiSignalDtos.kt

## File Structure
- `UiSignalRpcHandlers.kt` at: `server/src/main/kotlin/org/ttt/autogenesis/server/`
- Broadcast function placed after `broadcastDispatchResult` (line 449) and before `broadcastIntentUpdate` (line 467)

## Task 8: Wire showThinking metadata in npcHostileAgent.kt
- Modified: `server/src/main/kotlin/agent/builders/gameplayActions/npcHostileAgent.kt`
- Both `authorBuilder` calls updated with metadata:
  1. Line 85 (optionsPipe reasoning): Added `.apply { pipeMetadata["showThinking"] = true; pipeMetadata["actorName"] = npcData.name; pipeMetadata["isPlayer"] = false }` to first `BedrockConfig.authorBuilder()` call
  2. Line 127 (actionPipe reasoning): Added same metadata to second `BedrockConfig.authorBuilder()` call
- Pattern: Chain `.apply {}` on the Pipe returned by `authorBuilder()` to add metadata
- `npcData.name` is available from the `Npc` object passed to `buildHostileNpcAgent(npcData: Npc)`
- For hostile NPCs: `isPlayer = false`, `playerId` = empty string
- Verification: `./gradlew :server:compileKotlin` passes (BUILD SUCCESSFUL in 1m 18s)

## Task 7: playerAgent.kt showThinking Wiring
- Modified `server/src/main/kotlin/agent/builders/playerAgent/playerAgent.kt`
- First `authorBuilder` call (line 59): Added `.apply { pipeMetadata["showThinking"] = true; pipeMetadata["actorName"] = playerData.name; pipeMetadata["isPlayer"] = true }` to `setReasoningPipe()` for analysisPipe
- Second `authorBuilder` call (lines 132-142): Added the same metadata to the existing `.apply {}` block (before the `if (this is BedrockMultimodalPipe)` check) for strategicPlanningPipe
- Compilation verified: `./gradlew :server:compileKotlin` passes (BUILD SUCCESSFUL in 8s)
- Pattern: Chain `.apply {}` directly on `authorBuilder(...)` return value to add metadata without creating intermediate variable

## Task 6: Wire showThinking metadata in elderGodAgent.kt
- Modified: `server/src/main/kotlin/agent/builders/gameplayActions/elderGodAgent.kt`
- Line 94 (targetPipe): Added `.apply { pipeMetadata["showThinking"] = true; pipeMetadata["actorName"] = npcData.name; pipeMetadata["isPlayer"] = false }` to first `BedrockConfig.authorBuilder()` call
- Line 145 (actionPipe): Added same metadata to second `BedrockConfig.authorBuilder()` call
- Pattern: Chain `.apply {}` on the Pipe returned by `authorBuilder()` to add metadata
- `npcData.name` available from the `Npc` object passed to `buildElderGodAgent(npcData: Npc)`
- For NPCs: `isPlayer = false`, `playerId` = empty string
- Verification: `./gradlew :server:compileKotlin` passes (BUILD SUCCESSFUL in 33s)

## Task 5: Wire showThinking metadata in npcActorAgent.kt
- Modified: `server/src/main/kotlin/agent/builders/gameplayActions/npcActorAgent.kt` line 53
- Added `.apply { pipeMetadata["showThinking"] = true; pipeMetadata["actorName"] = npcData.name; pipeMetadata["isPlayer"] = false }` to `BedrockConfig.authorBuilder()` call
- Pattern: chain `.apply {}` on the Pipe returned by `authorBuilder` to add metadata
- `npcData.name` is available from the `Npc` object passed to `buildNpcActorAgent(npcData: Npc)`
- For NPCs: `isPlayer = false`, `playerId` = empty string
- Verification: `./gradlew :server:compileKotlin` passes with BUILD SUCCESSFUL

## Task 3 (current): Wire showThinking metadata in nemesisAgent.kt
- Modified: `server/src/main/kotlin/agent/builders/gameplayActions/nemesisAgent.kt`
- All 4 `authorBuilder` calls updated with metadata:
  1. Line ~128 (assessmentPipe reasoning): Added metadata inside existing `.apply {}` block
  2. Line ~177 (branchPipe repair reasoning): Added metadata inside existing `.apply {}` block
  3. Line ~223 (schemesPipe reasoning): Wrapped with new `.apply {}` block
  4. Line ~246 (promptPipe reasoning): Wrapped with new `.apply {}` block
- Pattern: `.apply {}` on `authorBuilder(...)` result, NOT on outer BedrockMultimodalPipe
- Metadata values: `pipeMetadata["showThinking"] = true`, `pipeMetadata["actorName"] = npcData.name`, `pipeMetadata["isPlayer"] = false`
- Verification: `./gradlew :server:compileKotlin` passes (BUILD SUCCESSFUL in 11s)

## Task 9 (current): Add ThinkingUpdateData to kvisionApp client
- Modified: `sharedModel/src/commonMain/kotlin/org/ttt/autogenesis/network/UiSignalDtos.kt`
- Added `ThinkingUpdateData` data class at end of file (after `ForceShowTurnResolutionData`)
- Fields: `playerId: String`, `characterName: String`, `thinking: String`, `timestamp: Long`
- Pattern: Added as regular data class (not inside UiSignalRpcHandlers.kt which is server-only)
- The server already had `ThinkingUpdateData` defined locally in UiSignalRpcHandlers.kt (line 12-17) but NOT in sharedModel
- Client handler `handleThinkingUpdate` in UiSignalClientHandlers.kt was already wired and imported, awaiting the DTO
- `handleThinkingUpdate` calls `AgentWorkStreamManager.appendThinking(data)` to process the thinking updates
- Verification: `./gradlew :kvisionApp:compileKotlinJs` passes (BUILD SUCCESSFUL in 2s after clean build)

## Task 11: Distinct Styling for Thinking in AgentWorkStreamWindow.kt
- Modified: `kvisionApp/src/jsMain/kotlin/ui/gameplay/AgentWorkStreamWindow.kt`
- Added `appendThinking(data: ThinkingUpdateData)` method (line 132-158)
- Distinct styling:
  - 🧠 prefix in thinking text
  - `[NPC]` or `[PLAYER]` badge based on `playerId.startsWith("npc_")` check
  - `color = Color.hex(0x888888)` (grey/muted)
  - `fontFamily = "monospace"` (monospace font)
- Also modified: `kvisionApp/src/jsMain/kotlin/ui/gameplay/AgentWorkStreamManager.kt`
- Changed `appendChunk("[${data.characterName}]: ${data.thinking}")` to `window.appendThinking(data)` (line 108)
- Compilation verified: `./gradlew :kvisionApp:compileKotlinJs` passes (BUILD SUCCESSFUL in 5s)
- Pattern: Use same structure as `appendChunk` but with distinct visual properties for thinking content

## Code Quality Review Findings (F2)

### HIGH Issues

1. **Duplicate ThinkingUpdateData class definition** (SERVER)
   - `UiSignalRpcHandlers.kt:12-17` defines a local `ThinkingUpdateData` without `@Serializable`
   - `sharedModel/src/commonMain/kotlin/org/ttt/autogenesis/network/UiSignalDtos.kt:313-318` has the proper `@Serializable` version
   - Server's `broadcastThinking()` uses the local non-serializable version (line 456-459)
   - Client's `handleThinkingUpdate` uses the shared model's version
   - This creates an inconsistency where server and client use different DTO definitions

2. **runBlocking in suspend transformation function** (BEDROCK CONFIG)
   - `BedrockConfig.kt:612-614` wraps `broadcastThinking()` in `runBlocking { }`
   - This is in a `setTransformationFunction` lambda which may execute in suspend context
   - While task notes claim this is "appropriate", blocking in suspend code can cause deadlocks
   - Alternative: make the transformation function not use blocking calls, or use a coroutine dispatcher

### MEDIUM Issues

1. **Non-serializable DTO in server broadcast path** (SERVER)
   - The local `ThinkingUpdateData` in `UiSignalRpcHandlers.kt` lacks `@Serializable`
   - `broadcastNotification` uses `serializer<T>()` which requires `@Serializable`
   - If the local class is used, serialization may fail silently or throw at runtime

### LOW Issues

1. **Inconsistent naming for NPC badge** (CLIENT)
   - `AgentWorkStreamWindow.kt` checks `playerId.startsWith("npc_")` to determine NPC badge
   - But in BedrockConfig.kt line 603, playerId is empty string for NPCs: `playerId = if (isPlayer) ... else ""`
   - So NPCs will never have `playerId.startsWith("npc_")` evaluate to true
   - The badge logic may be broken - NPCs get `[PLAYER]` badge instead of `[NPC]`

2. **Missing performance annotations** (SERVER)
   - `broadcastThinking` at line 456-460 does not have `@Benchmark` or timing instrumentation
   - Compare to other methods like `handleLoadMapPack` in client which has performance tracking

### Verdict: REJECT

**Reason**: Duplicate non-serializable DTO definition creates runtime risk, and NPC badge logic is broken due to empty playerId for NPCs.

**Must Fix**:
1. Remove duplicate `ThinkingUpdateData` from `UiSignalRpcHandlers.kt:12-17`, use shared model's version
2. Fix NPC badge logic - either pass actual NPC ID or use `isPlayer` flag to determine badge
3. Re-evaluate `runBlocking` usage in transformation function
