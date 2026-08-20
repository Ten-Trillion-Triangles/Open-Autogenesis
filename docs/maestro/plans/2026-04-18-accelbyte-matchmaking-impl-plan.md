---
title: "AccelByte Matchmaking Live Implementation"
design_ref: "docs/maestro/plans/2026-04-18-accelbyte-matchmaking-investigation-design.md"
created: "2026-04-18"
status: "draft"
total_phases: 4
estimated_files: 5
task_complexity: "medium"
---

# AccelByte Matchmaking Live Implementation Plan

## Plan Overview

- **Total phases**: 4
- **Agents involved**: `coder` (x4 sequential phases), `code-reviewer` (x1)
- **Estimated effort**: Fix 1 typo + implement 3-phase live matchmaking proxy in server-extend; all phases are sequential due to tight dependency ordering

## Dependency Graph

```
Phase 1 (typo fix)
    │
    ▼
Phase 2 (invokeMatchMaking live path)
    │
    ▼
Phase 3 (server extraction)
    │
    ▼
Phase 4 (browser call path)
    │
    ▼
Code Review
```

## Execution Strategy

| Stage | Phases | Execution | Agent Count | Notes |
|-------|--------|-----------|-------------|-------|
| 1     | Phase 1 | Sequential | 1 | Foundation: typo fix |
| 2     | Phase 2 | Sequential | 1 | Core: live matchmaking orchestration |
| 3     | Phase 3 | Sequential | 1 | Server info extraction wiring |
| 4     | Phase 4 | Sequential | 1 | Browser client call path |
| 5     | Review  | Sequential | 1 | Code review gate |

---

## Phase 1: Fix `colddownInSec` Typo

### Objective
Rename the misspelled `colddownInSec` field to `cooldownInSec` in `MatchTicketRequest` to match the AccelByte API parameter name, while maintaining backward compatibility.

### Agent: `coder`
### Parallel: No

### Files to Modify

- `accelbyteSdk/src/commonMain/kotlin/org/ttt/autogenesis/accelbyte/models/MatchmakingModels.kt` — Fix field name typo; add deprecated alias or keep `colddownInSec` as a constructor parameter with default that maps to `cooldownInSec`

### Implementation Details

The AccelByte API expects `cooldownInSec` (not `colddownInSec`). Since this model is used by callers, the safest approach is:

1. Rename the constructor parameter from `colddownInSec` to `cooldownInSec`
2. Add a deprecated `colddownInSec` constructor parameter with a default of `null`, and internally map it to `cooldownInSec` if provided (for backward compat)
3. Update `toJson()` to emit `cooldownInSec` as the key

```kotlin
data class MatchTicketRequest(
    val matchPool: String,
    val cooldownInSec: Int? = null,        // corrected name
    @Deprecated("Use cooldownInSec instead", ReplaceWith("cooldownInSec"))
    val colddownInSec: Int? = null,        // backward-compat alias
    val params: Json = json()
) : AccelByteRequest {
    override fun toJson(): Json {
        val effectiveCooldown = cooldownInSec ?: colddownInSec
        return json("matchPool" to matchPool, "cooldownInSec" to effectiveCooldown, "params" to params)
    }
}
```

### Validation

- Build: `./gradlew :accelbyteSdk:build` completes without errors
- No usages of `colddownInSec` as a named parameter should break (deprecated param allows old callers)

### Dependencies

- Blocked by: None
- Blocks: Phase 2 (no code dependency, but semantic — all later phases use the fixed model)

---

## Phase 2: Implement `invokeMatchMaking` Live Path

### Objective
Replace the `//todo:` stub in `ServerConnector.invokeMatchMaking` with a working implementation that: (1) creates an AccelByte match ticket, (2) polls until matched, (3) extracts the game session ID, (4) fetches server IP/port, and (5) returns a `GameTicket`.

### Agent: `coder`
### Parallel: No

### Files to Modify

- `server-extend/src/main/kotlin/matchmaking/ServerConnector.kt` — Implement live path in `invokeMatchMaking`

### Implementation Details

**ServerConnector currently has two stubs:**
- Line 116–118: `requestGame` returns `false` in live mode
- Line 138: `invokeMatchMaking` returns `GameTicket("", "")` in live mode

**New internal function** (add to `ServerConnector`):

```kotlin
/**
 * Executes the full live matchmaking flow:
 * 1. Create match ticket via MatchmakingFacade
 * 2. Poll MatchTicketDetails every 3s until status == MATCHED or timeout (30s)
 * 3. Extract matchId from matched ticket
 * 4. Fetch GameSessionDetailResponse via SessionBrowserFacade
 * 5. Extract ip:port from dsInformation.server
 * 6. Return GameTicket(sessionId=matchId, serverUrl=ip:port)
 */
private suspend fun executeLiveMatchmaking(
    matchPool: String,
    namespace: String,
    accelByteId: String
): GameTicket {
    // 1. Create ticket
    val ticketRequest = MatchTicketRequest(matchPool = matchPool)
    val ticketResponse = matchmakingFacade.createMatchTicket(ticketRequest).await()
    val ticketId = ticketResponse.ticketId

    // 2. Poll until matched (3s interval, 30s timeout)
    val deadline = System.currentTimeMillis() + 30_000
    while (System.currentTimeMillis() < deadline) {
        delay(3_000)
        val details = matchmakingFacade.getMatchTicket(ticketId).await()
        when (details.status) {
            "MATCHED" -> {
                val matchId = details.matchId ?: return GameTicket("", "")
                // 3. Fetch game session for server info
                val session = sessionBrowserFacade.getGameSessionDetails(matchId).await()
                val server = session.dsInformation.server
                val ip = server?.ip ?: return GameTicket("", "")
                val port = server.port ?: 9080
                return GameTicket(matchId, "$ip:$port")
            }
            "CANCELLED", "EXPIRED" -> return GameTicket("", "")
            else -> { /* continue polling */ }
        }
    }
    // Timeout — cancel ticket
    matchmakingFacade.deleteMatchTicket(ticketId).await()
    return GameTicket("", "")
}
```

**Wire into `invokeMatchMaking`:**
```kotlin
@RpcMethod("server.extend.invokeMatchMaking")
suspend fun invokeMatchMaking(context: RpcCallContext): GameTicket {
    if (ExtendConfig.debugMode) {
        return GameTicket("", "127.0.0.1:9080")
    }
    // live mode
    val namespace = ExtendConfig.namespace
    val accelByteId = "" // extracted from context or passed as param
    return executeLiveMatchmaking("default", namespace, accelByteId)
}
```

**Required imports** (ServerConnector currently lacks `MatchmakingFacade` / `SessionBrowserFacade` import — those need to be added via the existing AccelByte SDK facade layer):
- `org.ttt.autogenesis.accelbyte.facades.MatchmakingFacade`
- `org.ttt.autogenesis.accelbyte.facades.SessionBrowserFacade`

**Important**: The `server-extend` module already has a configured `AccelByteSdkInstance`. The `ServerConnector` object needs to obtain or be injected with `MatchmakingFacade` and `SessionBrowserFacade` instances. If DI is not set up, use a global singleton pattern or extension property at module level.

### Validation

- Build: `./gradlew :server-extend:build` completes without errors
- Log output shows ticket creation → polling → session fetch chain in live mode

### Dependencies

- Blocked by: Phase 1 (uses fixed `MatchTicketRequest`)
- Blocks: Phase 3 (wires the server info extraction)

---

## Phase 3: Wire `GameSessionDetailResponse` Server Extraction

### Objective
Ensure `ServerConnector` correctly extracts server IP and port from `GameSessionDetailResponse` returned by AccelByte, and properly populates the `gameSessions` map so `isServerReady` / `resolveUrl` continue to work.

### Agent: `coder`
### Parallel: No

### Files to Modify

- `server-extend/src/main/kotlin/matchmaking/ServerConnector.kt` — Populate `gameSessions` map from AccelByte session data post-match; update `isServerReady` and `resolveUrl` to work with AccelByte session IDs

### Implementation Details

After `executeLiveMatchmaking` returns a non-empty `GameTicket`:

1. **Construct `GameSessionStatus`** from the matched session data:
   ```kotlin
   val session = sessionBrowserFacade.getGameSessionDetails(matchId).await()
   val gameSession = GameSessionStatus().apply {
       sessionId = session.id
       serverUrl = "${session.dsInformation.server?.ip}:${session.dsInformation.server?.port ?: 9080}"
       maxPlayers = session.configuration.maxPlayers // if available
       // ... populate remaining fields
   }
   sessionMutex.withLock { gameSessions[session.id] = gameSession }
   ```

2. **Update `isServerReady`** — when `sessionId` matches an AccelByte session ID (not just local UUID), return `isReady = true`

3. **Update `resolveUrl`** — extract URL from `gameSessions[sessionId]` or directly from `GameSessionDetailResponse` if not in map

### Validation

- Build: `./gradlew :server-extend:build` completes without errors
- Manual: invoke `invokeMatchMaking` in live mode and confirm `gameSessions` map is populated

### Dependencies

- Blocked by: Phase 2
- Blocks: Phase 4

---

## Phase 4: Add Browser `invokeMatchMaking` Call Path

### Objective
Add a `requestMatchmaking()` function to `MatchmakingClient` (browser side) that calls `server.extend.invokeMatchMaking` (instead of `requestGame`) for live PvP matchmaking. The existing `requestSinglePlayerMatch` continues to use `requestGame` for AI-only flows.

### Agent: `coder`
### Parallel: No

### Files to Modify

- `kvisionApp/src/jsMain/kotlin/ui/MatchmakingClient.kt` — Add new `requestMatchmaking()` method

### Implementation Details

```kotlin
/**
 * Requests live PvP matchmaking via server-extend.
 * Server-extend creates and polls the AccelByte ticket on the client's behalf.
 *
 * @param gameType GameType (e.g., MULTIPLAYER)
 * @param commander Commander chosen by the player
 * @param matchPool Match pool name (e.g., "default")
 * @return GameTicket with sessionId + serverUrl on success, throws on failure
 */
suspend fun requestMatchmaking(
    gameType: GameType,
    commander: Commander?,
    matchPool: String = "default"
): GameTicket {
    WebSocketRpcBridge.waitForConnection()

    val playerName = AccelByteEnv.displayName.takeIf { it.isNotBlank() }
        ?: AccelByteEnv.userName.takeIf { it.isNotBlank() }
        ?: "Commander"

    val request = GameRequest(
        userName = playerName,
        gameType = gameType,
        accelByteId = AccelByteEnv.userId,
        websocketId = WebsocketConfig.websocketId,
        selectedCommander = commander
    )

    val invoker = ServerExtendBridge.rpcInvoker
        ?: throw Exception("RPC Invoker not initialized")

    val response = invoker.invoke(
        "server.extend.invokeMatchMaking",
        request,
        GameRequest.serializer()
    )

    val rpcError = response.error
    if (rpcError != null) {
        throw Exception("invokeMatchMaking failed: ${rpcError.message} (code: ${rpcError.code})")
    }

    return response.result?.let {
        RpcJson.decodeFromJsonElement(GameTicket.serializer(), it)
    } ?: throw Exception("invokeMatchMaking returned null result")
}
```

### Validation

- Build: `./gradlew :kvisionApp:build` completes without errors

### Dependencies

- Blocked by: Phase 3
- Blocks: None (final implementation phase)

---

## File Inventory

| # | File | Phase | Purpose |
|---|------|-------|---------|
| 1 | `accelbyteSdk/.../MatchmakingModels.kt` | 1 | Fix `cooldownInSec` typo |
| 2 | `server-extend/.../ServerConnector.kt` | 2, 3 | Live matchmaking orchestration + session tracking |
| 3 | `kvisionApp/.../MatchmakingClient.kt` | 4 | Browser-side PvP matchmaking entry point |

---

## Risk Classification

| Phase | Risk | Rationale |
|-------|------|-----------|
| 1     | LOW | Typo fix with backward-compat alias; isolated change |
| 2     | MEDIUM | Polling loop + AccelByte SDK call chain; new network path |
| 3     | MEDIUM | Session map population; edge case if `dsInformation.server` is null |
| 4     | LOW | New method only; existing code unaffected |

---

## Execution Profile

```
Execution Profile:
- Total phases: 4
- Parallelizable phases: 0 (all phases have linear dependency chain)
- Sequential-only phases: 4
- Estimated parallel wall time: N/A (sequential only)
- Estimated sequential wall time: ~4 agent turns

Note: Native subagents currently run without user approval gates.
All tool calls are auto-approved without user confirmation.
```