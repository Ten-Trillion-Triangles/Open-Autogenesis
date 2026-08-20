# Plan: Thinking Broadcast Feature

## TL;DR

> **Quick Summary**: Surface the reasoning thinking from NPC and player agents' role-play reasoning pipes to all connected players via a new `ui.thinkingUpdate` broadcast notification, displayed in the AgentWorkStream window.
>
> **Deliverables**:
> - Server-side: `UiSignalRpcHandlers.broadcastThinking()`, completed transformation function in `authorBuilder`
> - Agent wiring: `showThinking` + `actorName` + `isPlayer` metadata set on 4 NPC agents + 1 player agent
> - Client-side: `handleThinkingUpdate()` handler + `AgentWorkStreamManager.appendThinking()` + distinct UI rendering
>
> **Estimated Effort**: Medium
> **Parallel Execution**: YES — server changes (Phases 1-5) can run alongside client changes (Phase 6)
> **Critical Path**: Phase 3 (transformation function) → Phase 4-5 (agent wiring) → Phase 6 (client handler)

---

## Context

### Original Request
Complete the `setTransformationFunction` block started at `BedrockConfig.authorBuilder()` lines 598-605. The function extracts `problemView`, `thinking`, and `solution` from `MethodActorResponse` but does nothing with them. The goal is to broadcast NPC/player reasoning thinking to all game clients for display in the AgentWorkStream window.

### What Was Already Started
The transformation function skeleton exists at `BedrockConfig.kt:578-607`:
- Checks `parentPipe.pipeMetadata["showThinking"]` flag
- Parses `MethodActorResponse` from pipe output
- Extracts `problemView`, `thinking`, `solution` fields
- Has a `/** Finish the logic here... */` TODO instead of actual broadcast logic

### Key Design Decisions Made

1. **Broadcast to ALL players** — use `PlayerConnectionManager.broadcast()` which iterates all sessions and sends to everyone. No per-player threading needed.

2. **`showThinking` is opt-in per agent** — NOT set automatically in `authorBuilder`. Each NPC/player agent must explicitly set `pipeMetadata["showThinking"] = true`. This gives agents fine-grained control.

3. **No `connectionId` threading** — the broadcast goes to all players always. `connectionId` is not needed.

4. **`chatAgent` excluded** — per user direction, `chatAgent` is not included in this feature.

5. **Utility agents (zetaReasoning/nordoldTrable)** — do NOT set `showThinking`. These are internal reasoning engines, not game actors whose thinking should be surfaced.

---

## Agent Scope

### NPC Agents — will surface thinking (4 agents)

| Agent | File | `authorBuilder` calls |
|-------|------|----------------------|
| `nemesisAgent` | `gameplayActions/nemesisAgent.kt` | 4 calls (lines 128, 177, 223, 246) |
| `npcHostileAgent` | `gameplayActions/npcHostileAgent.kt` | 2 calls (lines 85, 128) |
| `npcActorAgent` | `gameplayActions/npcActorAgent.kt` | 1 call (line 53) |
| `elderGodAgent` | `gameplayActions/elderGodAgent.kt` | 2 calls (lines 94, 145) |

### Player Agent — will surface thinking (1 agent)

| Agent | File | `authorBuilder` calls |
|-------|------|----------------------|
| `playerAgent` | `playerAgent/playerAgent.kt` | 2 calls (lines 59, 132) |

### Excluded

- **`chatAgent`** — per user direction, excluded
- **Utility agents** (judge, validator, railroadAgent, ValidatorPipeAgent, passFailAgent, writerAgent, newcharacterscan) — internal reasoning personas, not game actors

---

## Work Objectives

### Core Objective
When an NPC or player agent with `showThinking = true` produces a `MethodActorResponse` from its role-play reasoning pipe, broadcast the thinking to ALL game clients via a new `ui.thinkingUpdate` notification for display in the AgentWorkStream window.

### Concrete Deliverables
- `UiSignalRpcHandlers.broadcastThinking(ThinkingUpdateData)` — server-side broadcast function
- `authorBuilder` transformation function body completed — parses `MethodActorResponse`, builds thinking string, calls broadcast
- 5 agents (4 NPC + 1 player) with `showThinking`, `actorName`, `isPlayer` metadata set on their reasoning pipes
- `UiSignalClientHandlers.handleThinkingUpdate()` — client-side handler
- `AgentWorkStreamManager.appendThinking()` + distinct UI rendering in `AgentWorkStreamWindow`

### Must Have
- Thinking broadcast reaches all connected clients
- UI distinguishes thinking from pipeline output (distinct style: `🧠` prefix, grey/monospace)
- Normal pipe output unchanged when `showThinking = false`

### Must NOT Have
- No per-player targeting — always broadcast to all
- No `chatAgent` changes
- No utility agent changes (zetaReasoning/nordoldTrable agents)
- No breaking of existing pipe output when `showThinking` flag is absent

---

## Verification Strategy

> **ZERO HUMAN INTERVENTION** — ALL verification is agent-executed.

- **Frontend/UI**: Use Playwright — navigate to AgentWorkStreamWindow, trigger a nemesis turn, assert `🧠` appears in DOM with correct source name
- **Backend**: Use Bash — curl the server health endpoint, verify no startup errors
- **QA Scenarios** (see each phase's QA section)

---

## Execution Strategy

```
PHASE 1 (Server - foundation):
├── Task 1: Add ThinkingUpdateData + broadcastThinking() to UiSignalRpcHandlers
└── Task 2: Complete transformation function body in authorBuilder

PHASE 2 (Server - NPC agents, parallel):
├── Task 3: Wire metadata in nemesisAgent (4 calls)
├── Task 4: Wire metadata in npcHostileAgent (2 calls)
├── Task 5: Wire metadata in npcActorAgent (1 call)
└── Task 6: Wire metadata in elderGodAgent (2 calls)

PHASE 3 (Server - player agent):
└── Task 7: Wire metadata in playerAgent (2 calls)

PHASE 4 (Client - handler):
├── Task 8: Add handleThinkingUpdate() to UiSignalClientHandlers
└── Task 9: Add ThinkingUpdateData to kvisionApp

PHASE 5 (Client - display):
├── Task 10: Add appendThinking() to AgentWorkStreamManager
└── Task 11: Render thinking with distinct style in AgentWorkStreamWindow

PHASE 6 (Verification):
└── Task 12: Verify thinking appears in AgentWorkStreamWindow for all players

Critical Path: Task 1 → Task 2 → Tasks 3-7 → Tasks 8-11 → Task 12
Parallel Speedup: ~60% faster than sequential
Max Concurrent: 4 (NPC agents in Phase 2)
```

---

## TODOs

- [x] 1. **Add ThinkingUpdateData and broadcastThinking() to UiSignalRpcHandlers**

  **What to do**:
  - Add to `server/src/main/kotlin/org/ttt/autogenesis/server/UiSignalRpcHandlers.kt`:
    ```kotlin
    @kotlinx.serialization.Serializable
    data class ThinkingUpdateData(
        val thinking: String,
        val sourceName: String,   // NPC name or player character name
        val sourceType: String,   // "NPC" or "PLAYER"
        val timestamp: Long = System.currentTimeMillis()
    )

    suspend fun broadcastThinking(data: ThinkingUpdateData) {
        val payload = RpcJson.encodeToJsonElement(ThinkingUpdateData.serializer(), data)
        val notification = RpcMessage.Notification("ui.thinkingUpdate", payload)
        connectionManager?.broadcast(notification)
    }
    ```
  - No `RpcMethod` registration — this is broadcast-only (server→client)

  **Must NOT do**:
  - Do not add an `RpcMethod` annotation — this is one-way notification, not a request/response

  **Recommended Agent Profile**:
  - **Category**: `ultrabrain`
  - **Skills**: []
  - Reason: Low-risk DTO addition and broadcast helper — straightforward

  **Parallelization**:
  - **Can Run In Parallel**: YES
  - **Parallel Group**: Phase 1 (with Task 2)
  - **Blocks**: Task 2 (transformation function needs this import)
  - **Blocked By**: None

  **References**:
  - `server/src/main/kotlin/org/ttt/autogenesis/server/UiSignalRpcHandlers.kt:262-282` — existing `sendAgentWorkStream()` pattern to follow for broadcast
  - `server/src/main/kotlin/org/ttt/autogenesis/server/ActionHistoryRpcHandlers.kt:122-148` — `broadcastTurnComplete()` as reference for `connectionManager?.broadcast()` usage
  - `sharedModel/src/commonMain/kotlin/org/ttt/autogenesis/network/RpcModels.kt:84-87` — `RpcMessage.Notification` structure

  **Acceptance Criteria**:
  - [ ] `ThinkingUpdateData` data class compiles and is serializable
  - [ ] `broadcastThinking()` compiles and type-checks
  - [ ] `./gradlew :server:compile` → PASS (no errors)

  **QA Scenarios**:

  \`\`\`
  Scenario: Server compiles with new ThinkingUpdateData and broadcastThinking
    Tool: Bash
    Preconditions: Server module compiles cleanly before changes
    Steps:
      1. ./gradlew :server:compile 2>&1 | tail -20
    Expected Result: BUILD SUCCESSFUL — no errors
    Failure Indicators: "unresolved reference", "data class without serializer"
    Evidence: .sisyphus/evidence/task-1-compile.txt
  \`\`\`

  **Commit**: YES (group with Task 2)
  - Message: `feat(thinking): add ThinkingUpdateData and broadcastThinking() to UiSignalRpcHandlers`
  - Files: `server/src/main/kotlin/org/ttt/autogenesis/server/UiSignalRpcHandlers.kt`

---

- [x] 2. **Complete transformation function body in authorBuilder**

  **What to do**:
  - In `server/src/main/kotlin/globals/BedrockConfig.kt`, replace the TODO at lines 598-605 with:
    ```kotlin
    val thinkingText = buildString {
        append("🧠 [$problemView]\n")
        append(thinking)
        append("\n➡️ $solution")
    }

    kotlinx.coroutines.runBlocking {
        val sourceName = parentPipe.pipeMetadata["actorName"] as? String ?: "Unknown"
        val sourceType = if ((parentPipe.pipeMetadata["isPlayer"] as? Boolean) == true) "PLAYER" else "NPC"
        UiSignalRpcHandlers.broadcastThinking(
            ThinkingUpdateData(
                thinking = thinkingText,
                sourceName = sourceName,
                sourceType = sourceType
            )
        )
    }
    ```
  - Add imports: `org.ttt.autogenesis.server.UiSignalRpcHandlers`, `org.ttt.autogenesis.network.RpcMessage`
  - Keep the `return@setTransformationFunction pipeContent` at the end (pass-through)
  - Keep the `isDefault()` guard check at line 592 — do not broadcast empty responses

  **Must NOT do**:
  - Do NOT set `showThinking` automatically — that is the calling agent's responsibility
  - Do NOT broadcast if `reasoningResponse.isDefault()` returns true

  **Recommended Agent Profile**:
  - **Category**: `ultrabrain`
  - **Skills**: []
  - Reason: Low-risk transformation completion — follows existing pattern

  **Parallelization**:
  - **Can Run In Parallel**: YES
  - **Parallel Group**: Phase 1 (with Task 1)
  - **Blocks**: Tasks 3-11 (all downstream agent and client work depends on transformation function)
  - **Blocked By**: None

  **References**:
  - `server/src/main/kotlin/globals/BedrockConfig.kt:578-607` — existing transformation function context
  - `TPipe/src/main/kotlin/Structs/ModelReasoning.kt:358-390` — `MethodActorResponse` structure with `unravel()` method showing expected fields

  **Acceptance Criteria**:
  - [ ] Transformation function compiles
  - [ ] `reasoningResponse.isDefault()` check preserved
  - [ ] `return@setTransformationFunction pipeContent` pass-through preserved
  - [ ] `./gradlew :server:compile` → PASS

  **QA Scenarios**:

  \`\`\`
  Scenario: Server compiles with completed transformation function
    Tool: Bash
    Preconditions: Server compiles cleanly before changes
    Steps:
      1. ./gradlew :server:compile 2>&1 | tail -20
    Expected Result: BUILD SUCCESSFUL
    Failure Indicators: "unresolved reference" for UiSignalRpcHandlers or ThinkingUpdateData
    Evidence: .sisyphus/evidence/task-2-compile.txt
  \`\`\`

  **Commit**: YES (group with Task 1)
  - Message: `feat(thinking): complete authorBuilder transformation function with broadcast call`
  - Files: `server/src/main/kotlin/globals/BedrockConfig.kt`

---

- [x] 3. **Wire metadata in nemesisAgent — 4 authorBuilder calls**
- [x] 4. **Wire metadata in npcHostileAgent — 2 authorBuilder calls**
- [x] 5. **Wire metadata in npcActorAgent — 1 authorBuilder call**
- [x] 6. **Wire metadata in elderGodAgent — 2 authorBuilder calls**
- [x] 7. **Wire metadata in playerAgent — 2 authorBuilder calls**

  **What to do**:
  - In `server/src/main/kotlin/agent/builders/playerAgent/playerAgent.kt`
  - After each `setReasoningPipe(BedrockConfig.authorBuilder(...))` call (lines 59, 132), add:
    ```kotlin
    .also { it.pipeMetadata["showThinking"] = true }
    .also { it.pipeMetadata["actorName"] = characterName }  // or whatever the player character name variable is
    .also { it.pipeMetadata["isPlayer"] = true }  // <-- key difference from NPC agents
    ```
  - Set `isPlayer = true` (not `false` like NPC agents)
  - `actorName` should be the player character's name

  **Must NOT do**:
  - Do not set `isPlayer = false` — this is a player agent

  **Recommended Agent Profile**:
  - **Category**: `quick`
  - **Skills**: []

  **Parallelization**:
  - **Can Run In Parallel**: YES (Phase 3)
  - **Blocked By**: Tasks 1, 2

  **References**:
  - `server/src/main/kotlin/agent/builders/playerAgent/playerAgent.kt:59, 132` — the 2 call sites
  - `characterName` or equivalent player name variable is in scope at both call sites

  **Acceptance Criteria**:
  - [ ] Both `authorBuilder` calls in `playerAgent` have metadata set with `isPlayer = true`
  - [ ] `./gradlew :server:compile` → PASS

  **QA Scenarios**:

  \`\`\`
  Scenario: playerAgent compiles with showThinking and isPlayer metadata
    Tool: Bash
    Preconditions: Server compiles cleanly before changes
    Steps:
      1. ./gradlew :server:compile 2>&1 | tail -20
    Expected Result: BUILD SUCCESSFUL
    Evidence: .sisyphus/evidence/task-7-compile.txt
  \`\`\`

  **Commit**: YES (group with Tasks 3, 4, 5, 6)

---

- [x] 8. **Add handleThinkingUpdate() to UiSignalClientHandlers**

  **What to do**:
  - In `kvisionApp/src/jsMain/kotlin/ui/gameplay/networking/UiSignalClientHandlers.kt`
  - Add handler method:
    ```kotlin
    @RpcMethod(name = "ui.thinkingUpdate", direction = RpcDirection.CLIENT)
    suspend fun handleThinkingUpdate(_ctx: RpcCallContext, data: ThinkingUpdateData) {
        AgentWorkStreamManager.appendThinking(data)
    }
    ```
  - This follows the same pattern as existing handlers (`handleTurnComplete`, `handleGeopoliticsUpdate`, `handleAgentWorkStream`)
  - The KSP processor will auto-generate the registration function

  **Must NOT do**:
  - Do not add to `Main.kt` manually — KSP auto-generates registration

  **Recommended Agent Profile**:
  - **Category**: `ultrabrain`
  - **Skills**: []
  - Reason: Follows established client-side RPC handler pattern

  **Parallelization**:
  - **Can Run In Parallel**: YES (Phase 4 with Task 9)
  - **Blocked By**: Tasks 1, 2 (server-side must compile)

  **References**:
  - `kvisionApp/src/jsMain/kotlin/ui/gameplay/networking/UiSignalClientHandlers.kt` — existing handlers (e.g., `handleTurnComplete` at ~line 42)
  - Pattern: `@RpcMethod(name = "ui.xxx", direction = RpcDirection.CLIENT)` + suspend function

  **Acceptance Criteria**:
  - [ ] `handleThinkingUpdate()` method added with correct annotation
  - [ ] `./gradlew :kvisionApp:compile` → PASS

  **QA Scenarios**:

  \`\`\`
  Scenario: kvisionApp compiles with handleThinkingUpdate handler
    Tool: Bash
    Preconditions: kvisionApp compiles cleanly before changes
    Steps:
      1. ./gradlew :kvisionApp:compile 2>&1 | tail -20
    Expected Result: BUILD SUCCESSFUL
    Failure Indicators: "unresolved reference" to ThinkingUpdateData or AgentWorkStreamManager
    Evidence: .sisyphus/evidence/task-8-compile.txt
  \`\`\`

  **Commit**: YES (group with Task 9)
  - Message: `feat(thinking): add handleThinkingUpdate() client RPC handler`
  - Files: `kvisionApp/src/jsMain/kotlin/ui/gameplay/networking/UiSignalClientHandlers.kt`

---

- [x] 9. **Add ThinkingUpdateData to kvisionApp**

  **What to do**:
  - Add `ThinkingUpdateData` data class to `kvisionApp/src/jsMain/kotlin/ui/gameplay/networking/` (or wherever UiSignalDtos are defined if they exist there)
  - Must match the server-side structure exactly:
    ```kotlin
    @kotlinx.serialization.Serializable
    data class ThinkingUpdateData(
        val thinking: String,
        val sourceName: String,
        val sourceType: String,
        val timestamp: Long
    )
    ```

  **Must NOT do**:
  - Do not change field names or types — must match server exactly for serialization

  **Recommended Agent Profile**:
  - **Category**: `quick`
  - **Skills**: []
  - Reason: Simple data class duplication — straightforward

  **Parallelization**:
  - **Can Run In Parallel**: YES (Phase 4 with Task 8)
  - **Blocked By**: Tasks 1, 2

  **References**:
  - Look in `kvisionApp/src/jsMain/kotlin/ui/gameplay/networking/` for existing DTOs (e.g., `UiSignalDtos.kt` or similar)
  - `server/src/main/kotlin/org/ttt/autogenesis/server/UiSignalRpcHandlers.kt:ThinkingUpdateData` — source of truth

  **Acceptance Criteria**:
  - [ ] `ThinkingUpdateData` data class exists in kvisionApp with matching fields
  - [ ] `./gradlew :kvisionApp:compile` → PASS

  **QA Scenarios**:

  \`\`\`
  Scenario: kvisionApp has ThinkingUpdateData and compiles
    Tool: Bash
    Preconditions: kvisionApp compiles cleanly before changes
    Steps:
      1. ./gradlew :kvisionApp:compile 2>&1 | tail -20
    Expected Result: BUILD SUCCESSFUL
    Evidence: .sisyphus/evidence/task-9-compile.txt
  \`\`\`

  **Commit**: YES (group with Task 8)

---

- [x] 10. **Add appendThinking() to AgentWorkStreamManager**

  **What to do**:
  - In `kvisionApp/src/jsMain/kotlin/ui/gameplay/AgentWorkStreamManager.kt`
  - Add method:
    ```kotlin
    fun appendThinking(data: ThinkingUpdateData) {
        MainScope().launch {
            // Route to open window if stream is active
            openWindow()?.appendThinking(data)
        }
    }

    // Also add to AgentWorkStreamWindow:
    fun appendThinking(data: ThinkingUpdateData) {
        // Format thinking with distinct styling: 🧠 prefix, grey background, monospace
        val thinkingText = buildString {
            append("🧠 [${data.sourceType}] ${data.sourceName}\n")
            append(data.thinking)
        }
        appendChunk(thinkingText)  // reuse existing chunk append mechanism
    }
    ```
  - Or add a separate buffer for thinking distinct from pipeline output

  **Must NOT do**:
  - Do not break existing `appendChunk()` behavior for pipeline output

  **Recommended Agent Profile**:
  - **Category**: `ultrabrain`
  - **Skills**: []
  - Reason: Follows existing `AgentWorkStreamManager.openStream()` pattern

  **Parallelization**:
  - **Can Run In Parallel**: YES (Phase 5 with Task 11)
  - **Blocked By**: Tasks 8, 9

  **References**:
  - `kvisionApp/src/jsMain/kotlin/ui/gameplay/AgentWorkStreamManager.kt` — existing `appendChunk()` and `openStream()` patterns
  - `kvisionApp/src/jsMain/kotlin/ui/gameplay/AgentWorkStreamWindow.kt` — existing `appendChunk()` method

  **Acceptance Criteria**:
  - [ ] `appendThinking()` method added to AgentWorkStreamManager
  - [ ] `./gradlew :kvisionApp:compile` → PASS

  **QA Scenarios**:

  \`\`\`
  Scenario: AgentWorkStreamManager has appendThinking and compiles
    Tool: Bash
    Preconditions: kvisionApp compiles cleanly before changes
    Steps:
      1. ./gradlew :kvisionApp:compile 2>&1 | tail -20
    Expected Result: BUILD SUCCESSFUL
    Evidence: .sisyphus/evidence/task-10-compile.txt
  \`\`\`

  **Commit**: YES (group with Task 11)
  - Message: `feat(thinking): add appendThinking() to AgentWorkStreamManager and AgentWorkStreamWindow`
  - Files: `kvisionApp/src/jsMain/kotlin/ui/gameplay/AgentWorkStreamManager.kt`, `kvisionApp/src/jsMain/kotlin/ui/gameplay/AgentWorkStreamWindow.kt`

---

- [x] 11. **Render thinking with distinct style in AgentWorkStreamWindow**

  **What to do**:
  - In `kvisionApp/src/jsMain/kotlin/ui/gameplay/AgentWorkStreamWindow.kt`
  - When rendering thinking from `appendThinking()`:
    - Prefix: `🧠` emoji
    - Source badge: `[NPC]` or `[PLAYER]` in sourceType
    - Font: monospace
    - Color: grey or muted (not bright like pipeline output)
    - Background: subtle grey background tint
  - Different styling from regular pipeline output chunks

  **Must NOT do**:
  - Do not alter existing `appendChunk()` rendering for pipeline output
  - Do not remove the `🧠` prefix or change the monospace requirement

  **Recommended Agent Profile**:
  - **Category**: `visual-engineering`
  - **Skills**: []
  - Reason: UI styling changes — visual engineering

  **Parallelization**:
  - **Can Run In Parallel**: YES (Phase 5 with Task 10)
  - **Blocked By**: Tasks 8, 9

  **References**:
  - `kvisionApp/src/jsMain/kotlin/ui/gameplay/AgentWorkStreamWindow.kt` — existing `appendChunk()` rendering logic
  - CSS classes from night-mode.css or similar for styling reference

  **Acceptance Criteria**:
  - [ ] Thinking rendered with `🧠` prefix, monospace font, grey/muted color
  - [ ] `[NPC]` or `[PLAYER]` badge visible in rendering
  - [ ] `./gradlew :kvisionApp:compile` → PASS

  **QA Scenarios**:

  \`\`\`
  Scenario: kvisionApp compiles with thinking display style
    Tool: Bash
    Preconditions: kvisionApp compiles cleanly before changes
    Steps:
      1. ./gradlew :kvisionApp:compile 2>&1 | tail -20
    Expected Result: BUILD SUCCESSFUL
    Evidence: .sisyphus/evidence/task-11-compile.txt
  \`\`\`

  **Commit**: YES (group with Task 10)

---

- [x] 12. **Verify thinking appears in AgentWorkStreamWindow for all players**

  **What to do**:
  - Start a game session with a nemesis or hostile NPC active
  - Trigger a turn that causes the NPC to use its reasoning pipe
  - Verify via Playwright or manual check that thinking appears in AgentWorkStreamWindow

  **Must NOT do**:
  - Do not skip this verification — required to confirm feature works end-to-end

  **Recommended Agent Profile**:
  - **Category**: `unspecified-high`
  - **Skills**: []
  - Reason: End-to-end verification — requires running server + client

  **Parallelization**:
  - **Can Run In Parallel**: NO — final verification wave
  - **Blocked By**: Tasks 1-11 all complete

  **References**:
  - `server/src/main/kotlin/agent/builders/gameplayActions/nemesisAgent.kt` — where to trigger a nemesis turn
  - `kvisionApp/src/jsMain/kotlin/ui/gameplay/AgentWorkStreamWindow.kt` — where to observe output

  **Acceptance Criteria**:
  - [ ] When a nemesis NPC takes a turn, thinking appears in AgentWorkStreamWindow
  - [ ] Thinking shows `🧠` prefix, NPC name, and content
  - [ ] All connected clients see the thinking broadcast

  **QA Scenarios**:

  \`\`\`
  Scenario: Nemesis turn triggers thinking display in AgentWorkStreamWindow
    Tool: Playwright
    Preconditions: Server running with active game session, AgentWorkStreamWindow open
    Steps:
      1. Navigate to game client UI
      2. Open AgentWorkStreamWindow via "Work Stream" button
      3. Trigger a nemesis turn (or wait for one)
      4. Wait 3 seconds for broadcast to arrive
      5. Query DOM for ".agent-work-stream-window" text content
    Expected Result: Text contains "🧠" prefix and "[NPC] nemesisName" and reasoning content
    Failure Indicators: No "🧠" found, thinking not visible, window not receiving data
    Evidence: .sisyphus/evidence/task-12-e2e.txt

  Scenario: Pass-through works when showThinking is false
    Tool: Bash
    Preconditions: Server running, normal pipeline output active
    Steps:
      1. Trigger a utility agent turn (judge, validator) that does NOT set showThinking
      2. Verify normal pipeline output is unchanged
    Expected Result: Pipeline output is normal, no thinking broadcast
    Failure Indicators: Pipeline output corrupted, extra "🧠" entries in stream
    Evidence: .sisyphus/evidence/task-12-passthrough.txt
  \`\`\`

  **Commit**: NO

---

## Final Verification Wave

- [x] F1. Plan Compliance Audit — `oracle` — APPROVED (1 issue fixed: removed duplicate non-serializable ThinkingUpdateData)
- [x] F2. Code Quality Review — `oracle` — APPROVED (2 issues fixed: isPlayer badge logic, runBlocking kept as-is)
- [x] F3. Real Manual QA — `general` — APPROVED (end-to-end path verified: trigger → broadcast → receive → render)
- [x] F4. Scope Fidelity Check — `general` — APPROVED (original request satisfied, scope boundaries maintained)

---

## Commit Strategy

Grouped commits to minimize noise:

**Commit 1** (Tasks 1-2, server foundation):
```
feat(thinking): add ThinkingUpdateData, broadcastThinking, and completed transformation function
```

**Commit 2** (Tasks 3-7, server agent wiring):
```
feat(thinking): wire showThinking metadata on NPC and player reasoning pipes
```

**Commit 3** (Tasks 8-9, client handler + DTO):
```
feat(thinking): add handleThinkingUpdate() client RPC handler and ThinkingUpdateData
```

**Commit 4** (Tasks 10-11, client display):
```
feat(thinking): add appendThinking() and distinct rendering in AgentWorkStream window
```

---

## Success Criteria

- `./gradlew :server:compile` → BUILD SUCCESSFUL
- `./gradlew :kvisionApp:compile` → BUILD SUCCESSFUL
- `./gradlew :server:test` → all tests pass
- When a nemesis NPC or player agent with `showThinking = true` produces reasoning output, ALL connected clients receive the `ui.thinkingUpdate` notification
- AgentWorkStreamWindow displays thinking with `🧠` prefix, monospace font, grey color, and `[NPC]`/`[PLAYER]` badge
- When `showThinking` is not set, pipe output is unchanged (pass-through)
- `chatAgent` — no changes
- Utility agents (judge, validator, etc.) — no changes
