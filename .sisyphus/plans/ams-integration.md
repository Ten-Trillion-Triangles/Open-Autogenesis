# AMS Integration Completion Plan

## TL;DR

> **Quick Summary**: Complete the missing AMS server hosting features — drain signal handling and live matchmaking — to enable production AccelByte deployment. Phase 2 features (backfill, party management, session storage) are deferred until Phase 1 is stable.
>
> **Deliverables**:
> - Drain signal handler with graceful server shutdown sequence
> - `requestGame` live mode implementation connecting matchmaking to game server notification
> - Backfill ticket acceptance/rejection framework
> - Party management write operations (createParty, joinParty, leaveParty)
> - Session storage read/write binding for crash recovery and match end
>
> **Estimated Effort**: Medium (30-50 tasks)
> **Parallel Execution**: YES — 3 waves
> **Critical Path**: Drain state in WorldManager → DrainSignalHandler → Live matchmaking → Integration tests

---

## Context

### Original Request
Complete missing AMS server integration features to enable production AccelByte hosting. Features include: drain signal handling, live matchmaking completion, backfill support, party management, and session storage.

### Research Findings

**AMS Server Integration** (5 agents ran in parallel):
- DS Hub WebSocket handles `serverClaimed`/`MatchmakingV2ServerClaimed` — ✅ Done
- Server registers via DSM Controller REST API — ✅ Done
- Heartbeat via WebSocket pings (30s interval) — ✅ Done
- **NO drain signal handling** — critical gap
- **Live matchmaking incomplete** — `requestGame` returns `false` in live mode (line 151-153)
- **No backfill ticket handling** — gap
- **Party management** — `LobbyFacade` has read ops only (getParty, updatePartyLimit), missing write ops
- **No session storage binding** — gap
- 201 RPC methods across 61 files — ✅ Done

**Key Code Locations**:
- `server-extend/src/main/kotlin/matchmaking/ServerConnector.kt:151-153` — `//todo: Implement live mode post demo`
- `server/src/main/kotlin/accelbyte/dsm/DsHubClient.kt` — DS Hub WebSocket client
- `server/src/main/kotlin/accelbyte/dsm/DSM.kt` — `shutdownDedicatedServer()` at line 247
- `server/src/main/kotlin/gameState/WorldManager.kt:96` — `bindSession()` session binding
- `server/src/main/kotlin/org/ttt/autogenesis/server/TurnHarness.kt:110-130` — arrival gate

### Metis Review

**Identified Gaps** (from Metis consultation):
1. Deallocation contract ambiguous — server calls DSM shutdown, AMS auto-deallocates after drain
2. `requestGame` live path (line 151) returns `false` — needs live mode implementation
3. `invokeMatchMaking` + `executeLiveMatchmaking` IS the live matchmaking path — partially implemented
4. Session storage read is NOT needed in Phase 1 (write-only at match end)
5. Backfill: server receives candidates but does NOT create tickets autonomously

**Scope Lock Applied**:
- Phase 1 (NOW): Drain signal handling + live matchmaking completion
- Phase 2 (DEFER): Backfill, party write ops, session storage
- EXPLICITLY DO NOT TOUCH: Matchmaking cancel UI, session browser UI

---

## Work Objectives

### Core Objective
Enable production AccelByte deployment by completing drain signal handling and live matchmaking integration.

### Concrete Deliverables

**Phase 1 (Critical — Production Blockers)**:
- Drain signal handler class: `DrainSignalHandler.kt` in `server/src/main/kotlin/accelbyte/dsm/`
- Drain state machine in `WorldManager`: `draining`, `drained` states
- Server rejects new session binds when `draining` with `SERVER_DRAINING` error
- `requestGame` live mode: implement full flow from game request to game server notification
- `executeLiveMatchmaking` timeout: configurable max wait (default 180s) with partial-fill fallback

**Phase 2 (Important — Post-Stability)**:
- Backfill handler: accept/reject backfill candidates via `PATCH /backfill` callback
- Party write ops: `createParty`, `joinParty`, `leaveParty` in `LobbyFacade`
- Session storage: `PUT /storage` at match end, `GET /storage` on crash recovery

### Definition of Done

- [ ] Drain signal received → server sets `DRAINING` state, rejects new binds, allows existing sessions
- [ ] All sessions end after drain → `DSM.shutdownDedicatedServer()` called
- [ ] `requestGame` in live mode → creates match ticket, polls, fetches session, notifies game server
- [ ] `executeLiveMatchmaking` → timeout at 180s, starts match with partial capacity if minimum not reached
- [ ] Backfill candidate arrives → server evaluates and responds within 5s
- [ ] Match ends → game state written to session storage within 5s
- [ ] Server crash recovers → reads session storage, reconstructs state

### Must Have

- Drain signal handler integrated with DS Hub WebSocket message dispatch
- `WorldManager.draining` state prevents new session binds
- `DSM.shutdownDedicatedServer()` called when drain complete and all sessions ended
- `requestGame` live mode path implemented end-to-end
- `executeLiveMatchmaking` timeout + partial-fill behavior

### Must NOT Have

- DO NOT implement automatic backfill ticket creation (server receives only)
- DO NOT add UI components for Phase 1 features
- DO NOT modify `ExtendConfig.debugMode` behavior
- DO NOT add matchmaking cancel UI flow
- DO NOT add session browser UI
- DO NOT change dev mode bypass logic

---

## Verification Strategy

### Test Decision
- **Infrastructure exists**: YES (`kotlin.test`, JUnit 5 via Gradle)
- **Automated tests**: Tests-after (no TDD for this task)
- **Framework**: `kotlin.test` (as per AGENTS.md conventions)
- **Agent-Executed QA**: Playwright for browser UI, Bash/curl for API testing

### QA Policy
Every task includes agent-executed QA scenarios. Evidence saved to `.sisyphus/evidence/`.

- **Drain handler**: Bash script sends drain signal, verifies server state transitions
- **Live matchmaking**: curl commands exercise match ticket creation/polling/session resolution
- **API tests**: Direct HTTP calls to DSM Controller and session service endpoints

---

## Execution Strategy

### Wave 1: Foundation (Drain + Live Matchmaking — Start Immediately)

```
Wave 1 (Foundation — drain state machine + live matchmaking completion):
├── Task 1: Add DRAINING/DRAINED state to WorldManager [quick, 1 file]
├── Task 2: Create DrainSignalHandler class in accelbyte/dsm/ [quick, new file]
├── Task 3: Wire drain handler into Server.kt startup + DS Hub dispatch [quick]
├── Task 4: Block new session binds when draining (WorldManager.bindSession) [quick]
├── Task 5: Implement requestGame live mode path in ServerConnector [deep]
├── Task 6: Add matchmaking timeout + partial-fill to executeLiveMatchmaking [deep]
└── Task 7: Test drain signal flow end-to-end [unspecified-high]
```

### Wave 2: Phase 2 Features (Backfill + Party + Storage)

```
Wave 2 (Phase 2 — backfill, party, storage):
├── Task 8: Backfill handler skeleton + accept/reject logic [deep]
├── Task 9: Wire backfill into match state machine [deep]
├── Task 10: Add createParty to LobbyFacade + session service binding [quick]
├── Task 11: Add joinParty to LobbyFacade + session service binding [quick]
├── Task 12: Add leaveParty to LobbyFacade + session service binding [quick]
├── Task 13: Session storage write on match end [quick]
├── Task 14: Session storage read on crash recovery [quick]
└── Task 15: Integration test: backfill + party + storage [unspecified-high]
```

### Wave 3: Verification

```
Wave 3 (Final verification):
├── Task 16: Full integration test — drain → matchmaking → backfill → storage [unspecified-high]
└── Task 17: Code quality review + lint [unspecified-high]
```

### Dependency Matrix

| Task | Blocks | Blocked By |
|------|--------|------------|
| 1 | 2, 3, 4 | — |
| 2 | 3, 4 | 1 |
| 3 | 4, 7 | 1, 2 |
| 4 | 7 | 1, 2, 3 |
| 5 | 6 | — |
| 6 | 7 | 5 |
| 7 | — | 3, 4, 6 |
| 8 | 9 | 7 |
| 9 | 15 | 8 |
| 10 | 15 | 7 |
| 11 | 15 | 7 |
| 12 | 15 | 7 |
| 13 | 15 | 7 |
| 14 | 15 | 13 |
| 15 | 16 | 9, 10, 11, 12, 13, 14 |
| 16 | 17 | 15 |
| 17 | — | 16 |

---

## TODOs

---

## TODOs

- [x] 1. Add DRAINING/DRAINED state to WorldManager

  **What to do**:
  - Add `draining: Boolean` and `drained: Boolean` fields to `WorldManager`
  - Add `setDraining()` and `setDrained()` methods
  - Add `isDraining()` and `isDrained()` query methods
  - Add `activeSessionCount: Int` counter — incremented on `bindSession()`, decremented on session end
  - Log state transitions using `LogCategory.SYSTEM`
  - Ensure `clearSession()` does NOT reset drain state (drain is server-level, not session-level)

  **Must NOT do**:
  - DO NOT modify `bindSession()` signature
  - DO NOT add drain state to `TurnHarness` — it belongs in `WorldManager` as server-level state
  - DO NOT add any network calls in this task

  **Recommended Agent Profile**:
  - **Category**: `quick`
  - **Skills**: []
  - **Skills Evaluated but Omitted**: `formatter` (style rules applied manually after)

  **Parallelization**:
  - **Can Run In Parallel**: YES
  - **Parallel Group**: Wave 1 (with Tasks 2, 3, 5, 6)
  - **Blocks**: Tasks 2, 3, 4
  - **Blocked By**: None (can start immediately)

  **References**:
  - `server/src/main/kotlin/gameState/WorldManager.kt:69-133` — existing session binding state, follow same field pattern
  - `server/src/main/kotlin/gameState/WorldManager.kt:96` — `bindSession()` signature to match

  **Acceptance Criteria**:
  - [ ] `WorldManager` has `draining: Boolean = false` field
  - [ ] `WorldManager` has `drained: Boolean = false` field
  - [ ] `WorldManager.setDraining()` sets `draining = true`, logs with `LogCategory.SYSTEM`
  - [ ] `WorldManager.setDrained()` sets `drained = true`, logs with `LogCategory.SYSTEM`
  - [ ] `activeSessionCount` incremented in `bindSession()`, decremented in `clearSession()`
  - [ ] `isDraining()` returns `draining` value
  - [ ] `isDrained()` returns `drained` value
  - [ ] `clearSession()` does NOT reset `draining` or `drained`
  - [ ] `Logger` imports from `org.ttt.autogenesis.logging`

  **QA Scenarios**:

  \`\`\`
  Scenario: WorldManager drain state transitions
    Tool: Bash
    Preconditions: Clean WorldManager instance
    Steps:
      1. Read WorldManager.kt and verify fields exist
      2. Grep for "draining" in WorldManager.kt to verify getter/setter
    Expected Result: Fields and methods found at correct lines
    Evidence: .sisyphus/evidence/task-1-drain-state.{ext}
  \`\`\`

  **Commit**: YES
  - Message: `feat(ams): add draining/drained state to WorldManager`
  - Files: `server/src/main/kotlin/gameState/WorldManager.kt`
  - Pre-commit: `grep -n "draining" server/src/main/kotlin/gameState/WorldManager.kt`

---

- [x] 2. Create DrainSignalHandler class

  **What to do**:
  - Create `server/src/main/kotlin/accelbyte/dsm/DrainSignalHandler.kt`
  - Class name: `DrainSignalHandler`
  - Constructor: `(worldManager: WorldManager, dsm: DSM, scope: CoroutineScope)`
  - `handleDrainSignal()`: suspend function that:
    1. Calls `worldManager.setDraining()`
    2. Logs drain started with `LogCategory.SYSTEM`
    3. Polls `worldManager.activeSessionCount` until it reaches 0 OR 60-second timeout
    4. If timeout reached with sessions still active, log warning and proceed to shutdown
    5. Calls `dsm.shutdownDedicatedServer(...)` with appropriate payload
    6. Calls `worldManager.setDrained()`
    7. Logs drain complete with `LogCategory.SYSTEM`
  - Use `Logger` for all logging with `LogCategory.SYSTEM` or `LogCategory.NETWORK`
  - Follow existing code style: braces on new lines, KDoc comments

  **Must NOT do**:
  - DO NOT call `handleDrainSignal()` from here — that's wired in Task 3
  - DO NOT create singleton or object — instance per Server is correct
  - DO NOT add delay() between state transitions without reason

  **Recommended Agent Profile**:
  - **Category**: `quick`
  - **Skills**: []
  - **Skills Evaluated but Omitted**: `formatter` (Kotlin code style follows existing patterns)

  **Parallelization**:
  - **Can Run In Parallel**: YES
  - **Parallel Group**: Wave 1 (with Tasks 1, 3, 5, 6)
  - **Blocks**: Tasks 3, 4
  - **Blocked By**: Task 1

  **References**:
  - `server/src/main/kotlin/accelbyte/dsm/DSM.kt:247` — `shutdownDedicatedServer()` signature and error handling pattern
  - `server/src/main/kotlin/gameState/WorldManager.kt:96` — `worldManager` reference pattern
  - `server/src/main/kotlin/accelbyte/dsm/DsHubClient.kt` — `DsHubClient` constructor pattern for reference
  - `server/src/main/kotlin/org/ttt/autogenesis/server/TurnHarness.kt:128` — `PLAYER_JOIN_TIMEOUT_MS = 120_000` pattern for timeout constants

  **Acceptance Criteria**:
  - [ ] `DrainSignalHandler.kt` created in `server/src/main/kotlin/accelbyte/dsm/`
  - [ ] Class has `handleDrainSignal()` suspend function
  - [ ] Function calls `worldManager.setDraining()` first
  - [ ] Function polls `activeSessionCount` with 60-second timeout
  - [ ] Function calls `dsm.shutdownDedicatedServer()` when sessions complete or timeout
  - [ ] Function calls `worldManager.setDrained()` after shutdown
  - [ ] All logging uses `Logger` with appropriate `LogCategory`
  - [ ] KDoc comment on class and `handleDrainSignal()` method

  **QA Scenarios**:

  \`\`\`
  Scenario: DrainSignalHandler class exists and has correct structure
    Tool: Bash
    Preconditions: File does not exist
    Steps:
      1. Read DrainSignalHandler.kt
      2. Grep for "handleDrainSignal" in file
      3. Grep for "shutdownDedicatedServer" in file
    Expected Result: File exists, method found, DSM shutdown call found
    Evidence: .sisyphus/evidence/task-2-drain-handler.{ext}
  \`\`\`

  **Commit**: YES
  - Message: `feat(ams): add DrainSignalHandler class for graceful drain`
  - Files: `server/src/main/kotlin/accelbyte/dsm/DrainSignalHandler.kt`
  - Pre-commit: `ls server/src/main/kotlin/accelbyte/dsm/DrainSignalHandler.kt`

---

- [x] 3. Wire drain handler into Server.kt startup + DS Hub dispatch

  **What to do**:
  - In `Server.kt`:
    1. Create `DrainSignalHandler` instance after `DsHubClient` initialization (around line 62-85)
    2. Add `drainSignalHandler: DrainSignalHandler` as a field
    3. In `DsHubClient` message dispatch (around line 228 where `MatchmakingV2ServerClaimed` is handled), add a case for `DRAIN_SIGNAL` message type
    4. When drain signal received, call `drainSignalHandler.handleDrainSignal()` asynchronously (do NOT block the WebSocket thread)
  - Handle `DRAIN_SIGNAL` as a String message type — the exact format depends on DS Hub protocol (typically a JSON message with `type: "DRAIN_SIGNAL"` or similar field)
  - If the exact drain signal message format is unknown, add a `when` branch that logs "Unknown DS Hub message type: $type" for now — do NOT silently ignore

  **Must NOT do**:
  - DO NOT call `handleDrainSignal()` synchronously — that would block the WebSocket reader thread
  - DO NOT create DrainSignalHandler before WorldManager is initialized
  - DO NOT swallow drain signal — always log it

  **Recommended Agent Profile**:
  - **Category**: `unspecified-high`
  - **Skills**: []
  - **Reason**: Needs to understand DS Hub WebSocket message dispatch pattern and Server.kt initialization order

  **Parallelization**:
  - **Can Run In Parallel**: YES
  - **Parallel Group**: Wave 1 (with Tasks 1, 2, 5, 6)
  - **Blocks**: Tasks 4, 7
  - **Blocked By**: Tasks 1, 2

  **References**:
  - `server/src/main/kotlin/org/ttt/autogenesis/server/Server.kt:62-85` — `DsHubClient` initialization and subscription pattern
  - `server/src/main/kotlin/accelbyte/dsm/DsHubClient.kt:228` — `MatchmakingV2ServerClaimed` handling for message dispatch pattern
  - `server/src/main/kotlin/accelbyte/dsm/DrainSignalHandler.kt` — (Task 2 output, but file will exist when this task runs)

  **Acceptance Criteria**:
  - [ ] `DrainSignalHandler` instantiated in `Server.kt` after `DsHubClient`
  - [ ] DS Hub message dispatch has a case for drain signal
  - [ ] Drain signal handler called asynchronously (via `scope.launch` or similar)
  - [ ] Unknown message types are logged, not silently ignored
  - [ ] Server compiles after changes

  **QA Scenarios**:

  \`\`\`
  Scenario: Server.kt wires drain handler correctly
    Tool: Bash
    Preconditions: Server.kt modified
    Steps:
      1. Grep for "DrainSignalHandler" in Server.kt
      2. Grep for "handleDrainSignal" in Server.kt
      3. Grep for "drain" in Server.kt (case-insensitive)
    Expected Result: DrainSignalHandler instantiated, called on drain signal, drain logged
    Evidence: .sisyphus/evidence/task-3-server-wire.{ext}
  \`\`\`

  Scenario: Unknown DS Hub messages are logged
    Tool: Bash
    Preconditions: Server.kt modified
    Steps:
      1. Grep for "Unknown.*message.*type" in Server.kt or DsHubClient.kt
    Expected Result: Pattern found for unknown message logging
    Evidence: .sisyphus/evidence/task-3-unknown-msg.{ext}
  \`\`\`

  **Commit**: YES
  - Message: `feat(ams): wire DrainSignalHandler into Server startup and DS Hub dispatch`
  - Files: `server/src/main/kotlin/org/ttt/autogenesis/server/Server.kt`
  - Pre-commit: `grep -n "DrainSignalHandler" server/src/main/kotlin/org/ttt/autogenesis/server/Server.kt`

---

- [x] 4. Block new session binds when draining (WorldManager.bindSession)

  **What to do**:
  - In `WorldManager.bindSession()` (around line 96):
    1. Add early return with error logging if `draining == true`
    2. Return a `Result<Unit>` or throw a `ServerDrainingException` to indicate rejection
    3. Log rejection with `LogCategory.SYSTEM` at WARN level
    4. Add KDoc comment explaining drain rejection behavior
  - Create `ServerDrainingException.kt` in `server/src/main/kotlin/org/ttt/autogenesis/server/`:
    - Class: `class ServerDrainingException(message: String = "Server is draining, not accepting new sessions") : Exception(message)`
  - Caller (`ServerConnector.executeLiveMatchmaking` or wherever `bindSession` is called) should handle this exception and return appropriate error to client

  **Must NOT do**:
  - DO NOT block if `drained == true` — server should already be deallocating
  - DO NOT log at DEBUG level — this is a significant event warranting WARN
  - DO NOT silently skip the bind — always throw or return error

  **Recommended Agent Profile**:
  - **Category**: `quick`
  - **Skills**: []
  - **Reason**: Small targeted change to existing method

  **Parallelization**:
  - **Can Run In Parallel**: NO
  - **Parallel Group**: None (sequential)
  - **Blocks**: Task 7
  - **Blocked By**: Tasks 1, 2, 3

  **References**:
  - `server/src/main/kotlin/gameState/WorldManager.kt:96` — `bindSession()` signature and current implementation
  - `server/src/main/kotlin/gameState/WorldManager.kt` existing exception pattern (if any)
  - `server-extend/src/main/kotlin/matchmaking/ServerConnector.kt:305-310` — where `bindSession` is called

  **Acceptance Criteria**:
  - [ ] `bindSession()` checks `draining` flag before proceeding
  - [ ] If `draining == true`, bind is rejected with `ServerDrainingException`
  - [ ] `ServerDrainingException` created with appropriate message
  - [ ] Rejection logged at WARN level with `LogCategory.SYSTEM`
  - [ ] Existing sessions (non-draining) unaffected

  **QA Scenarios**:

  \`\`\`
  Scenario: New session bind rejected when draining
    Tool: Bash
    Preconditions: WorldManager with draining=true, activeSessionCount=0
    Steps:
      1. Call bindSession with valid sessionId and playerIds
      2. Verify ServerDrainingException is thrown
      3. Verify activeSessionCount unchanged
    Expected Result: Exception thrown, session count unchanged
    Evidence: .sisyphus/evidence/task-4-drain-reject.{ext}
  \`\`\`

  Scenario: Existing session unaffected when draining
    Tool: Bash
    Preconditions: WorldManager with draining=true, existing session bound
    Steps:
      1. Attempt to clear existing session
      2. Verify clearSession succeeds
    Expected Result: clearSession works normally during drain
    Evidence: .sisyphus/evidence/task-4-drain-existing.{ext}
  \`\`\`

  **Commit**: YES
  - Message: `feat(ams): reject new session binds when server is draining`
  - Files: `server/src/main/kotlin/gameState/WorldManager.kt`, `server/src/main/kotlin/org/ttt/autogenesis/server/ServerDrainingException.kt`
  - Pre-commit: `grep -n "draining" server/src/main/kotlin/gameState/WorldManager.kt`

---

- [x] 5. Implement requestGame live mode path in ServerConnector

  **What to do**:
  - In `ServerConnector.requestGame()` (around line 91-154):
    1. When `ExtendConfig.debugMode == false` (live mode):
       - Instead of returning `false`, implement the live mode flow
       - Call `executeLiveMatchmaking(request.matchPool ?: request.gameType)` to get a `GameTicket`
       - If ticket is empty (serverIp blank), return `false` with appropriate error log
       - Build a `GameSessionStatus` from the ticket response (same structure as dev mode, but from AccelByte data)
       - Call `notifyGameServer(gameSession)` to inform the game server
       - If `acknowledged`, store in `gameSessions` map and return `true`
       - If not `acknowledged`, return `false`
    2. Keep existing dev mode path unchanged
    3. The `//todo:` comment at line 151 should be removed once this is implemented

  **Must NOT do**:
  - DO NOT change dev mode behavior
  - DO NOT modify `ExtendConfig.debugMode` flag or its default value
  - DO NOT remove the TODO comment until implementation is complete and verified
  - DO NOT call `executeLiveMatchmaking` without the matchPool — use `request.matchPool ?: request.gameType` as fallback

  **Recommended Agent Profile**:
  - **Category**: `deep`
  - **Skills**: []
  - **Reason**: Complex coroutine flow with multiple async steps, error handling

  **Parallelization**:
  - **Can Run In Parallel**: YES
  - **Parallel Group**: Wave 1 (with Tasks 1, 2, 3, 6)
  - **Blocks**: Task 6
  - **Blocked By**: None (can start immediately, but Task 6 depends on this)

  **References**:
  - `server-extend/src/main/kotlin/matchmaking/ServerConnector.kt:91-154` — existing `requestGame` method to modify
  - `server-extend/src/main/kotlin/matchmaking/ServerConnector.kt:183-337` — `executeLiveMatchmaking` to understand its return values
  - `server-extend/src/main/kotlin/matchmaking/ServerConnector.kt:260-310` — how `GameSessionStatus` is built from live session data
  - `server-extend/src/main/kotlin/matchmaking/ServerConnector.kt:135-148` — `notifyGameServer` call pattern

  **Acceptance Criteria**:
  - [ ] `requestGame` in live mode calls `executeLiveMatchmaking`
  - [ ] `GameSessionStatus` constructed from matchmaking result (serverUrl, sessionId, players from ticket)
  - [ ] `notifyGameServer` called with constructed session
  - [ ] `gameSessions` map updated on successful acknowledgment
  - [ ] Returns `true` when game server acknowledges, `false` otherwise
  - [ ] TODO comment at line 151 removed
  - [ ] Dev mode path unchanged

  **QA Scenarios**:

  \`\`\`
  Scenario: requestGame live mode creates match and notifies server
    Tool: Bash
    Preconditions: ExtendConfig.debugMode=false, AccelByte SDK configured
    Steps:
      1. Call requestGame with gameType=MULTIPLAYER, matchPool="pvp"
      2. Verify executeLiveMatchmaking is called
      3. Verify GameSessionStatus is constructed
      4. Verify notifyGameServer is called
    Expected Result: true returned, gameSessions updated
    Evidence: .sisyphus/evidence/task-5-live-requestgame.{ext}
  \`\`\`

  Scenario: requestGame live mode fails gracefully on matchmaking timeout
    Tool: Bash
    Preconditions: ExtendConfig.debugMode=false, matchmaking service unavailable
    Steps:
      1. Call requestGame
      2. Verify false returned
      3. Verify error logged
    Expected Result: false returned, no crash
    Evidence: .sisyphus/evidence/task-5-live-fail.{ext}
  \`\`\`

  **Commit**: YES
  - Message: `feat(matchmaking): implement requestGame live mode path`
  - Files: `server-extend/src/main/kotlin/matchmaking/ServerConnector.kt`
  - Pre-commit: `grep -n "TODO" server-extend/src/main/kotlin/matchmaking/ServerConnector.kt | grep -i live`

---

- [x] 6. Add matchmaking timeout + partial-fill to executeLiveMatchmaking

  **What to do**:
  - In `ServerConnector.executeLiveMatchmaking()` (around line 183-337):
    1. Change polling timeout from hardcoded `30_000` (30s) to configurable `MATCHMAKING_TIMEOUT_MS` constant (default `180_000` = 180s)
    2. Add `minimumPlayers` parameter — when session has `currentPlayers >= minimumPlayers`, allow match to proceed even if timeout not reached
    3. After match found and session resolved, check if `currentPlayers >= minimumPlayers`
    4. If below minimum after timeout, log warning and still return the session (partial fill is allowed in production, just logged)
    5. Add `MATCHMAKING_MIN_CAPACITY` constant (default 2 — 1v1 minimum)
    6. The partial-fill behavior: return GameSessionStatus even with fewer players than max — the match starts "short"
    7. If match never found within timeout, return `GameTicket("", "")` (empty)

  **Must NOT do**:
  - DO NOT change dev mode behavior
  - DO NOT force minimum capacity — partial fills ARE allowed (just warned)
  - DO NOT block indefinitely — timeout MUST be enforced
  - DO NOT change the polling interval (3s is fine)

  **Recommended Agent Profile**:
  - **Category**: `deep`
  - **Skills**: []
  - **Reason**: Modifies existing polling loop with timeout and capacity logic

  **Parallelization**:
  - **Can Run In Parallel**: YES
  - **Parallel Group**: Wave 1 (with Tasks 1, 2, 3, 5)
  - **Blocks**: Task 7
  - **Blocked By**: Task 5

  **References**:
  - `server-extend/src/main/kotlin/matchmaking/ServerConnector.kt:208-210` — existing polling loop with 30s timeout
  - `server-extend/src/main/kotlin/matchmaking/ServerConnector.kt:260-294` — where GameSessionStatus is constructed from session data
  - `sharedModel/src/commonMain/kotlin/structs/matchmaking/GameSessionStatus.kt` — `maxPlayers` and `currentPlayers` fields

  **Acceptance Criteria**:
  - [ ] `MATCHMAKING_TIMEOUT_MS = 180_000` constant added
  - [ ] `MATCHMAKING_MIN_CAPACITY = 2` constant added
  - [ ] Polling loop uses `MATCHMAKING_TIMEOUT_MS` instead of hardcoded 30s
  - [ ] When `currentPlayers >= minimumPlayers`, match can proceed before timeout
  - [ ] When timeout reached below minimum, warning logged and partial session returned
  - [ ] When match never found within timeout, empty GameTicket returned
  - [ ] No change to dev mode behavior

  **QA Scenarios**:

  \`\`\`
  Scenario: Matchmaking proceeds when minimum players reached before timeout
    Tool: Bash
    Preconditions: 2 players matched after 10s
    Steps:
      1. Call executeLiveMatchmaking with minimumPlayers=2
      2. Verify match proceeds immediately when 2nd player joins at 10s
      3. Verify total wait time is ~10s, not full 180s
    Expected Result: Match returned at ~10s
    Evidence: .sisyphus/evidence/task-6-min-capacity.{ext}
  \`\`\`

  Scenario: Matchmaking timeout returns partial fill with warning
    Tool: Bash
    Preconditions: Only 1 player after 180s timeout
    Steps:
      1. Call executeLiveMatchmaking with minimumPlayers=2
      2. Wait for 180s timeout
      3. Verify partial GameSessionStatus returned with currentPlayers=1
      4. Verify warning logged about minimum not reached
    Expected Result: GameSessionStatus returned with 1 player, warning logged
    Evidence: .sisyphus/evidence/task-6-partial-fill.{ext}
  \`\`\`

  Scenario: Matchmaking timeout with no match returns empty ticket
    Tool: Bash
    Preconditions: No match found within timeout
    Steps:
      1. Call executeLiveMatchmaking
      2. Wait for 180s
      3. Verify empty GameTicket returned (serverIp and sessionId empty)
    Expected Result: GameTicket("", "") returned
    Evidence: .sisyphus/evidence/task-6-timeout-empty.{ext}
  \`\`\`

  **Commit**: YES
  - Message: `feat(matchmaking): add 180s timeout and partial-fill to executeLiveMatchmaking`
  - Files: `server-extend/src/main/kotlin/matchmaking/ServerConnector.kt`
  - Pre-commit: `grep -n "MATCHMAKING_TIMEOUT" server-extend/src/main/kotlin/matchmaking/ServerConnector.kt`

---

- [x] 7. Test drain signal flow end-to-end

  **What to do**:
  - Create integration test `server/src/test/kotlin/accelbyte/dsm/DrainSignalHandlerTest.kt`
  - Test cases:
    1. Drain signal received → `draining` state set, new binds rejected
    2. Drain signal with zero active sessions → shutdown called immediately
    3. Drain signal with active sessions → waits for sessions to end, then calls shutdown
    4. Drain signal timeout (60s) → forces shutdown even with active sessions
    5. Multiple drain signals → idempotent, same result
    6. `drained` state set after shutdown completes
  - Use `kotlin.test` framework as per project conventions
  - Mock `WorldManager` and `DSM` dependencies
  - Test file should be in `server/src/test/kotlin/` alongside existing tests

  **Must NOT do**:
  - DO NOT use real DSM Controller — mock it
  - DO NOT use real DS Hub WebSocket — test the handler in isolation
  - DO NOT write UI tests — this is backend unit/integration test

  **Recommended Agent Profile**:
  - **Category**: `unspecified-high`
  - **Skills**: []
  - **Reason**: Integration test requiring mock setup and multiple scenario coverage

  **Parallelization**:
  - **Can Run In Parallel**: NO
  - **Parallel Group**: None (sequential)
  - **Blocks**: Wave 2
  - **Blocked By**: Tasks 3, 4, 6

  **References**:
  - `server/src/test/kotlin/` — existing test files for test conventions
  - `server/src/main/kotlin/accelbyte/dsm/DrainSignalHandler.kt` — (Task 2 output)
  - `server/src/main/kotlin/gameState/WorldManager.kt` — (Task 1 output)

  **Acceptance Criteria**:
  - [ ] Test file created at `server/src/test/kotlin/accelbyte/dsm/DrainSignalHandlerTest.kt`
  - [ ] All 6 test scenarios implemented
  - [ ] Tests use `kotlin.test` framework
  - [ ] DSM and WorldManager are mocked
  - [ ] `./gradlew :server:test --tests "*DrainSignalHandlerTest*"` passes
  - [ ] Each scenario captures evidence (log output or assertion results)

  **QA Scenarios**:

  \`\`\`
  Scenario: Drain test suite runs and passes
    Tool: Bash
    Preconditions: DrainSignalHandlerTest.kt created
    Steps:
      1. Run ./gradlew :server:test --tests "*DrainSignalHandlerTest*"
      2. Verify all 6 tests pass
      3. Capture test output
    Expected Result: 6/6 tests pass, 0 failures
    Evidence: .sisyphus/evidence/task-7-drain-tests.{ext}
  \`\`\`

  **Commit**: YES
  - Message: `test(ams): add DrainSignalHandler integration tests`
  - Files: `server/src/test/kotlin/accelbyte/dsm/DrainSignalHandlerTest.kt`
  - Pre-commit: `./gradlew :server:test --tests "*DrainSignalHandlerTest*" 2>&1 | tail -20`

---

- [x] 8. Backfill handler skeleton + accept/reject logic

  **What to do**:
  - Create `server/src/main/kotlin/accelbyte/backfill/BackfillHandler.kt`
  - Class: `BackfillHandler` with constructor: `(worldManager: WorldManager, scope: CoroutineScope)`
  - `handleBackfillRequest(request: BackfillTicketRequest): BackfillHandlerResult`:
    1. If `worldManager.isDraining()` or `worldManager.isDrained()`, return `BackfillHandlerResult(rejected = true, reason = "SERVER_DRAINING")`
    2. If match is in final state (ending/terminated), return `rejected = true, reason = "MATCH_ENDING"`
    3. If server has capacity (currentPlayers < maxPlayers), return `rejected = false, candidate = accepted`
    4. If server is full, return `rejected = true, reason = "SERVER_FULL"`
    5. All evaluations should be logged with `LogCategory.NETWORK`
  - `BackfillTicketRequest`: data class with `ticketId`, `candidatePlayerId`, `sessionId`
  - `BackfillHandlerResult`: data class with `rejected: Boolean`, `reason: String?`
  - Create `BackfillTicketRequest.kt` and `BackfillHandlerResult.kt` in same directory

  **Must NOT do**:
  - DO NOT create backfill tickets — this handler ONLY accepts/receives candidates from AMS backfill service
  - DO NOT modify WorldManager session state — this is read-only evaluation
  - DO NOT add delay() for rate limiting — evaluate immediately

  **Recommended Agent Profile**:
  - **Category**: `deep`
  - **Skills**: []
  - **Reason**: New class with evaluation logic, state checks

  **Parallelization**:
  - **Can Run In Parallel**: YES (Wave 2 with Tasks 9-14)
  - **Blocks**: Task 9
  - **Blocked By**: Task 7

  **References**:
  - `server/src/main/kotlin/gameState/WorldManager.kt:96` — `bindSession()`, `isDraining()`, `isDrained()` for state checks
  - `sharedModel/src/commonMain/kotlin/structs/matchmaking/SessionStatus.kt` — `GameSessionStatus` with `currentPlayers`, `maxPlayers`
  - `server/src/main/kotlin/accelbyte/dsm/DrainSignalHandler.kt` — error result pattern

  **Acceptance Criteria**:
  - [ ] `BackfillHandler.kt` created with `handleBackfillRequest` method
  - [ ] Returns `SERVER_DRAINING` rejection when draining
  - [ ] Returns `MATCH_ENDING` rejection when match ending
  - [ ] Returns accepted when server has capacity
  - [ ] Returns `SERVER_FULL` when at max capacity
  - [ ] All decisions logged with `LogCategory.NETWORK`
  - [ ] Unit tests created

  **QA Scenarios**:

  \`\`\`
  Scenario: Backfill accepted when server has capacity
    Tool: Bash
    Preconditions: WorldManager with active session, 3/8 players
    Steps:
      1. Call handleBackfillRequest with valid candidate
      2. Verify result.rejected == false
    Expected Result: Backfill accepted
    Evidence: .sisyphus/evidence/task-8-backfill-accept.{ext}
  \`\`\`

  Scenario: Backfill rejected when server is draining
    Tool: Bash
    Preconditions: WorldManager with draining=true
    Steps:
      1. Call handleBackfillRequest
      2. Verify result.rejected == true
      3. Verify result.reason == "SERVER_DRAINING"
    Expected Result: Backfill rejected with SERVER_DRAINING
    Evidence: .sisyphus/evidence/task-8-backfill-draining.{ext}
  \`\`\`

  Scenario: Backfill rejected when server is full
    Tool: Bash
    Preconditions: WorldManager with session at maxPlayers
    Steps:
      1. Call handleBackfillRequest
      2. Verify result.rejected == true
      3. Verify result.reason == "SERVER_FULL"
    Expected Result: Backfill rejected with SERVER_FULL
    Evidence: .sisyphus/evidence/task-8-backfill-full.{ext}
  \`\`\`

  **Commit**: YES
  - Message: `feat(backfill): add BackfillHandler with accept/reject logic`
  - Files: `server/src/main/kotlin/accelbyte/backfill/BackfillHandler.kt`, `BackfillTicketRequest.kt`, `BackfillHandlerResult.kt`
  - Pre-commit: `ls server/src/main/kotlin/accelbyte/backfill/`

---

- [x] 9. Wire backfill into match state machine

  **What to do**:
  - In `WorldManager` or wherever match state is managed:
    1. Add `backfillHandler: BackfillHandler` as optional field (initialized lazily to avoid circular deps)
    2. Add `onBackfillReceived(callback)` registration mechanism so game logic can evaluate candidates
    3. When `BackfillHandlerResult(rejected = false)`:
       - Add candidate to `expectedPlayers` set
       - Notify `TurnHarness.notifyPlayerJoined()` when candidate connects
    4. The `PATCH /backfill` endpoint (AMS backfill callback) should call `backfillHandler.handleBackfillRequest()` and respond to AMS
  - This task connects the BackfillHandler to the AMS backfill callback endpoint

  **Must NOT do**:
  - DO NOT auto-add players to match without connection — backfill is a candidate, not a joined player
  - DO NOT modify the backfill evaluation logic — that stays in BackfillHandler
  - DO NOT create new RPC endpoints — wire into existing backfill callback mechanism

  **Recommended Agent Profile**:
  - **Category**: `deep`
  - **Skills**: []
  - **Reason**: Integration task connecting multiple components

  **Parallelization**:
  - **Can Run In Parallel**: YES (Wave 2 with Tasks 8, 10-14)
  - **Blocks**: Task 15
  - **Blocked By**: Task 8

  **References**:
  - `server/src/main/kotlin/accelbyte/backfill/BackfillHandler.kt` — (Task 8 output)
  - `server/src/main/kotlin/org/ttt/autogenesis/server/TurnHarness.kt:252` — `notifyPlayerJoined()` pattern
  - `server/src/main/kotlin/gameState/WorldManager.kt:96` — `bindSession()`, `expectedPlayers`

  **Acceptance Criteria**:
  - [ ] `WorldManager` has `backfillHandler` field
  - [ ] Backfill accepted candidates added to `expectedPlayers`
  - [ ] `TurnHarness.notifyPlayerJoined()` called when accepted candidate connects
  - [ ] Backfill rejection logged

  **QA Scenarios**:

  \`\`\`
  Scenario: Accepted backfill candidate added to expected players
    Tool: Bash
    Preconditions: BackfillHandler returns accepted
    Steps:
      1. Call backfill callback with accepted candidate
      2. Verify candidate added to expectedPlayers
      3. Verify notifyPlayerJoined called on connection
    Expected Result: Candidate in expectedPlayers, notified on connect
    Evidence: .sisyphus/evidence/task-9-backfill-wire.{ext}
  \`\`\`

  **Commit**: YES
  - Message: `feat(backfill): wire BackfillHandler into match state machine`
  - Files: `server/src/main/kotlin/gameState/WorldManager.kt`, `server/src/main/kotlin/org/ttt/autogenesis/server/TurnHarness.kt`
  - Pre-commit: `grep -n "backfill" server/src/main/kotlin/gameState/WorldManager.kt`

---

- [x] 10. Add createParty to LobbyFacade + session service binding

  **What to do**:
  - In `accelbyteSdk/src/commonMain/kotlin/org/ttt/autogenesis/accelbyte/facades/LobbyFacade.kt`:
    1. Add `createParty(request: CreatePartyRequest): PartyResponse` suspend function
    2. `CreatePartyRequest`: data class with `namespace`, `partyName?`, `maxPlayers`
    3. Call `sessionServiceWrapper.createParty()` with appropriate request body
    4. Return `PartyResponse` with `partyId`, `partyName`, `members`
  - If `sessionServiceWrapper` doesn't exist yet in LobbyFacade, this task includes adding it (lazy init like other facades)
  - Add `CreatePartyRequest.kt` and `PartyResponse.kt` to `sharedModel/src/commonMain/kotlin/structs/accelbyte/lobby/`

  **Must NOT do**:
  - DO NOT create party in server-extend without going through AccelByte SDK
  - DO NOT hardcode partyId — must come from AccelByte response
  - DO NOT skip namespace — use `AccelByteConfig.getNamespace()`

  **Recommended Agent Profile**:
  - **Category**: `quick`
  - **Skills**: []
  - **Reason**: Follows existing facade pattern, straightforward SDK binding

  **Parallelization**:
  - **Can Run In Parallel**: YES (Wave 2 with Tasks 8, 9, 11, 12, 13, 14)
  - **Blocks**: Task 15
  - **Blocked By**: Task 7

  **References**:
  - `accelbyteSdk/src/commonMain/kotlin/org/ttt/autogenesis/accelbyte/facades/LobbyFacade.kt` — existing facade pattern to follow
  - `accelbyteSdk/src/commonMain/kotlin/org/ttt/autogenesis/accelbyte/facades/MatchmakingFacade.kt` — `createMatchTicket` pattern for SDK call structure
  - `sharedModel/src/commonMain/kotlin/structs/accelbyte/lobby/LobbyModels.kt` — existing models to extend

  **Acceptance Criteria**:
  - [ ] `createParty` method added to `LobbyFacade`
  - [ ] `CreatePartyRequest` data class created
  - [ ] `PartyResponse` data class created
  - [ ] SDK call uses `sessionServiceWrapper.createParty()`
  - [ ] PartyId returned from AccelByte service
  - [ ] Unit tests for createParty

  **QA Scenarios**:

  \`\`\`
  Scenario: createParty calls session service and returns partyId
    Tool: Bash
    Preconditions: AccelByte SDK configured, session service available
    Steps:
      1. Call lobbyFacade.createParty(CreatePartyRequest(namespace="test", maxPlayers=8))
      2. Verify partyId returned (non-blank)
      3. Verify SDK call was made to session service
    Expected Result: Valid partyId from AccelByte
    Evidence: .sisyphus/evidence/task-10-create-party.{ext}
  \`\`\`

  **Commit**: YES
  - Message: `feat(party): add createParty to LobbyFacade`
  - Files: `accelbyteSdk/src/commonMain/kotlin/org/ttt/autogenesis/accelbyte/facades/LobbyFacade.kt`, `sharedModel/src/commonMain/kotlin/structs/accelbyte/lobby/CreatePartyRequest.kt`, `PartyResponse.kt`
  - Pre-commit: `grep -n "createParty" accelbyteSdk/src/commonMain/kotlin/org/ttt/autogenesis/accelbyte/facades/LobbyFacade.kt`

---

- [x] 11. Add joinParty to LobbyFacade + session service binding

  **What to do**:
  - In `LobbyFacade.kt`:
    1. Add `joinParty(partyId: String, userId: String): PartyResponse` suspend function
    2. Call `sessionServiceWrapper.joinParty()` with partyId and userId
    3. Return updated `PartyResponse`
    4. Add error handling for `PARTY_FULL`, `PARTY_NOT_FOUND`, `ALREADY_JOINED` cases
  - Add `JoinPartyRequest.kt` if needed (partyId, userId)

  **Must NOT do**:
  - DO NOT silently swallow errors — throw appropriate exceptions
  - DO NOT join party without going through AccelByte
  - DO NOT modify existing party state in server — AccelByte is source of truth

  **Recommended Agent Profile**:
  - **Category**: `quick`
  - **Skills**: []
  - **Reason**: Follows existing facade pattern

  **Parallelization**:
  - **Can Run In Parallel**: YES (Wave 2 with Tasks 8, 9, 10, 12, 13, 14)
  - **Blocks**: Task 15
  - **Blocked By**: Task 7

  **References**:
  - `accelbyteSdk/src/commonMain/kotlin/org/ttt/autogenesis/accelbyte/facades/LobbyFacade.kt` — (Task 10 output)
  - `sharedModel/src/commonMain/kotlin/structs/accelbyte/lobby/LobbyModels.kt` — existing models

  **Acceptance Criteria**:
  - [ ] `joinParty` method added to `LobbyFacade`
  - [ ] SDK call uses `sessionServiceWrapper.joinParty()`
  - [ ] Error cases handled with appropriate exceptions
  - [ ] Unit tests for joinParty

  **QA Scenarios**:

  \`\`\`
  Scenario: joinParty successful
    Tool: Bash
    Preconditions: Valid partyId, party has capacity
    Steps:
      1. Call lobbyFacade.joinParty(partyId="abc", userId="user1")
      2. Verify party response returned
    Expected Result: PartyResponse with updated members
    Evidence: .sisyphus/evidence/task-11-join-party.{ext}
  \`\`\`

  Scenario: joinParty fails when party is full
    Tool: Bash
    Preconditions: Party at max capacity
    Steps:
      1. Call lobbyFacade.joinParty(partyId, userId)
      2. Verify appropriate exception thrown
    Expected Result: PARTY_FULL exception
    Evidence: .sisyphus/evidence/task-11-join-full.{ext}
  \`\`\`

  **Commit**: YES
  - Message: `feat(party): add joinParty to LobbyFacade`
  - Files: `accelbyteSdk/src/commonMain/kotlin/org/ttt/autogenesis/accelbyte/facades/LobbyFacade.kt`
  - Pre-commit: `grep -n "joinParty" accelbyteSdk/src/commonMain/kotlin/org/ttt/autogenesis/accelbyte/facades/LobbyFacade.kt`

---

- [x] 12. Add leaveParty to LobbyFacade + session service binding

  **What to do**:
  - In `LobbyFacade.kt`:
    1. Add `leaveParty(partyId: String, userId: String): Boolean` suspend function
    2. Call `sessionServiceWrapper.leaveParty()` with partyId and userId
    3. Return `true` if successful, `false` if failed (user not in party, etc.)
    4. Add KDoc comment explaining behavior
  - If the SDK doesn't have `leaveParty`, use the appropriate HTTP method (likely `DELETE` or `POST`)

  **Must NOT do**:
  - DO NOT modify server state — AccelByte is source of truth
  - DO NOT throw if user already left — return `false` gracefully
  - DO NOT delete party — only remove user from party membership

  **Recommended Agent Profile**:
  - **Category**: `quick`
  - **Skills**: []
  - **Reason**: Follows existing facade pattern

  **Parallelization**:
  - **Can Run In Parallel**: YES (Wave 2 with Tasks 8, 9, 10, 11, 13, 14)
  - **Blocks**: Task 15
  - **Blocked By**: Task 7

  **References**:
  - `accelbyteSdk/src/commonMain/kotlin/org/ttt/autogenesis/accelbyte/facades/LobbyFacade.kt` — (Task 10 output)

  **Acceptance Criteria**:
  - [ ] `leaveParty` method added to `LobbyFacade`
  - [ ] SDK call uses appropriate session service method
  - [ ] Returns `true` on success, `false` on failure
  - [ ] Unit tests for leaveParty

  **QA Scenarios**:

  \`\`\`
  Scenario: leaveParty successful
    Tool: Bash
    Preconditions: User is party member
    Steps:
      1. Call lobbyFacade.leaveParty(partyId, userId)
      2. Verify true returned
    Expected Result: true, user removed from party
    Evidence: .sisyphus/evidence/task-12-leave-party.{ext}
  \`\`\`

  Scenario: leaveParty returns false when user not in party
    Tool: Bash
    Preconditions: User not in party
    Steps:
      1. Call lobbyFacade.leaveParty(partyId, userId)
      2. Verify false returned (no exception)
    Expected Result: false, no exception thrown
    Evidence: .sisyphus/evidence/task-12-leave-notmember.{ext}
  \`\`\`

  **Commit**: YES
  - Message: `feat(party): add leaveParty to LobbyFacade`
  - Files: `accelbyteSdk/src/commonMain/kotlin/org/ttt/autogenesis/accelbyte/facades/LobbyFacade.kt`
  - Pre-commit: `grep -n "leaveParty" accelbyteSdk/src/commonMain/kotlin/org/ttt/autogenesis/accelbyte/facades/LobbyFacade.kt`

---

- [x] 13. Session storage write on match end

  **What to do**:
  - Create `server/src/main/kotlin/accelbyte/session/SessionStorageHandler.kt`:
    1. `writeSessionStorage(sessionId: String, gameState: GameState): Result<Unit>` — serializes game state as JSON, calls `sessionServiceWrapper.updatePartyStorage()` or equivalent
    2. `gameState` should be a data class with: `matchOutcome`, `finalScores`, `playerStats`, `endTimestamp`
    3. Use `json.encodeToString` for serialization
    4. Retry up to 3 times with exponential backoff on failure
    5. Log success/failure with `LogCategory.DATABASE`
  - Wire into `WorldManager.clearSession()` or wherever match end is detected — after match ends and before clearing session state
  - The storage write should happen when `TurnHarness.resetState()` is called (match ending)

  **Must NOT do**:
  - DO NOT use session storage as a general-purpose database — only write critical match data
  - DO NOT block match end on storage write failure — write asynchronously with retry
  - DO NOT read from session storage during normal match operation — only on crash recovery (Task 14)

  **Recommended Agent Profile**:
  - **Category**: `quick`
  - **Skills**: []
  - **Reason**: Straightforward SDK binding with error handling pattern

  **Parallelization**:
  - **Can Run In Parallel**: YES (Wave 2 with Tasks 8, 9, 10, 11, 12, 14)
  - **Blocks**: Task 15
  - **Blocked By**: Task 7

  **References**:
  - `accelbyteSdk/src/commonMain/kotlin/org/ttt/autogenesis/accelbyte/facades/LobbyFacade.kt` — storage call pattern (if available)
  - `server/src/main/kotlin/org/ttt/autogenesis/server/TurnHarness.kt` — `resetState()` where match end is detected
  - `sharedModel/src/commonMain/kotlin/structs/accelbyte/session/SessionModels.kt` — existing session models

  **Acceptance Criteria**:
  - [ ] `SessionStorageHandler.kt` created
  - [ ] `writeSessionStorage` serializes game state as JSON
  - [ ] Calls session service storage endpoint
  - [ ] Retries 3 times with exponential backoff on failure
  - [ ] Success/failure logged with `LogCategory.DATABASE`
  - [ ] Match ending flow calls `writeSessionStorage` before clearing session
  - [ ] Write failure does NOT block match end

  **QA Scenarios**:

  \`\`\`
  Scenario: Session storage written on match end
    Tool: Bash
    Preconditions: Match ending, valid sessionId
    Steps:
      1. Trigger match end
      2. Verify writeSessionStorage called
      3. Verify JSON payload with matchOutcome, scores, playerStats
    Expected Result: Storage write attempted, success logged
    Evidence: .sisyphus/evidence/task-13-storage-write.{ext}
  \`\`\`

  Scenario: Storage write failure does not block match end
    Tool: Bash
    Preconditions: Session service unavailable
    Steps:
      1. Trigger match end
      2. Verify writeSessionStorage attempted and failed
      3. Verify match still ended (session cleared)
    Expected Result: Match ends despite storage failure, error logged
    Evidence: .sisyphus/evidence/task-13-storage-fail.{ext}
  \`\`\`

  **Commit**: YES
  - Message: `feat(storage): add session storage write on match end`
  - Files: `server/src/main/kotlin/accelbyte/session/SessionStorageHandler.kt`, related structs
  - Pre-commit: `ls server/src/main/kotlin/accelbyte/session/SessionStorageHandler.kt`

---

- [x] 14. Session storage read on crash recovery

  **What to do**:
  - In `SessionStorageHandler.kt`:
    1. Add `readSessionStorage(partyId: String): Result<GameState?>` — reads from session service storage endpoint
    2. Returns `null` if no storage exists (new session, never had data)
    3. On read failure, return `Result.failure` — crash recovery should NOT proceed with empty state on read failure
  - In `WorldManager` or `Server.kt`:
    1. On server startup, check if there are any active sessions assigned to this server (via DSM session lookup)
    2. If yes, call `readSessionStorage` for each active session
    3. If storage read succeeds and returns data, reconstruct `GameSessionStatus` from stored data
    4. Resume normal operations with reconstructed state
    5. If read fails, log error and proceed without recovery (fail open, not fail closed)

  **Must NOT do**:
  - DO NOT read session storage during normal match operation — only on crash recovery at startup
  - DO NOT block server startup on read failure — fail open is correct here
  - DO NOT use recovered state to re-join players — they must reconnect

  **Recommended Agent Profile**:
  - **Category**: `quick`
  - **Skills**: []
  - **Reason**: Straightforward SDK read + startup initialization

  **Parallelization**:
  - **Can Run In Parallel**: YES (Wave 2 with Tasks 8, 9, 10, 11, 12, 13)
  - **Blocks**: Task 15
  - **Blocked By**: Task 13

  **References**:
  - `server/src/main/kotlin/accelbyte/session/SessionStorageHandler.kt` — (Task 13 output)
  - `server/src/main/kotlin/org/ttt/autogenesis/server/Server.kt` — startup initialization pattern (around line 62)
  - `server/src/main/kotlin/accelbyte/dsm/DSM.kt:286` — `fetchDsmSession(sessionId)` for active session lookup

  **Acceptance Criteria**:
  - [ ] `readSessionStorage` added to `SessionStorageHandler`
  - [ ] Returns `GameState?` (null if no storage)
  - [ ] Read failure returns `Result.failure`
  - [ ] Server startup checks for active sessions
  - [ ] Active sessions trigger `readSessionStorage`
  - [ ] Recovered state used to reconstruct `GameSessionStatus`
  - [ ] Read failure does NOT block startup

  **QA Scenarios**:

  \`\`\`
  Scenario: Session storage read on crash recovery
    Tool: Bash
    Preconditions: Server restarted with active session in DSM
    Steps:
      1. Start server
      2. Verify DSM session lookup called
      3. Verify readSessionStorage called for active session
      4. Verify GameSessionStatus reconstructed from stored data
    Expected Result: Server resumes with recovered session
    Evidence: .sisyphus/evidence/task-14-storage-read.{ext}
  \`\`\`

  Scenario: No storage exists for session (new session)
    Tool: Bash
    Preconditions: No session storage for sessionId
    Steps:
      1. Call readSessionStorage for new session
      2. Verify null returned
      3. Verify no exception
    Expected Result: null returned
    Evidence: .sisyphus/evidence/task-14-storage-null.{ext}
  \`\`\`

  **Commit**: YES
  - Message: `feat(storage): add session storage read on crash recovery`
  - Files: `server/src/main/kotlin/accelbyte/session/SessionStorageHandler.kt`, `server/src/main/kotlin/org/ttt/autogenesis/server/Server.kt`
  - Pre-commit: `grep -n "readSessionStorage" server/src/main/kotlin/accelbyte/session/SessionStorageHandler.kt`

---

- [x] 15. Integration test: backfill + party + storage

  **What to do**:
  - Create `server/src/test/kotlin/accelbyte/IntegrationTest.kt` or `server-extend/src/test/kotlin/matchmaking/IntegrationTest.kt`
  - End-to-end test covering:
    1. Create party → joinParty → leaveParty flow
    2. Backfill candidate accepted → joins expectedPlayers
    3. Match ends → session storage written
    4. Crash recovery → session storage read → state reconstructed
  - Use mocks for AccelByte SDK, test the integration logic in isolation
  - Use `kotlin.test` framework as per project conventions

  **Must NOT do**:
  - DO NOT use real AccelByte services — mock everything
  - DO NOT write UI tests
  - DO NOT test single components in isolation — this is integration test

  **Recommended Agent Profile**:
  - **Category**: `unspecified-high`
  - **Skills**: []
  - **Reason**: Complex integration test with multiple mocks

  **Parallelization**:
  - **Can Run In Parallel**: NO
  - **Blocks**: Task 16
  - **Blocked By**: Tasks 9, 10, 11, 12, 13, 14

  **References**:
  - `server/src/test/kotlin/accelbyte/dsm/DrainSignalHandlerTest.kt` — (Task 7 output) for test conventions

  **Acceptance Criteria**:
  - [ ] Integration test file created
  - [ ] All 4 scenarios covered
  - [ ] All components mocked
  - [ ] Tests use `kotlin.test` framework
  - [ ] Test suite passes

  **QA Scenarios**:

  \`\`\`
  Scenario: Full integration test suite passes
    Tool: Bash
    Preconditions: IntegrationTest.kt created
    Steps:
      1. Run integration tests
      2. Verify all scenarios pass
    Expected Result: All tests pass
    Evidence: .sisyphus/evidence/task-15-integration-tests.{ext}
  \`\`\`

  **Commit**: YES
  - Message: `test(integration): add backfill party storage integration tests`
  - Files: `server/src/test/kotlin/accelbyte/IntegrationTest.kt`
  - Pre-commit: `./gradlew :server:test --tests "*IntegrationTest*" 2>&1 | tail -20`

---

- [x] 16. Full integration test — drain → matchmaking → backfill → storage

  **What to do**:
  - Create `server/src/test/kotlin/ams/FullAmsIntegrationTest.kt`
  - End-to-end scenario:
    1. Server starts, registers with DSM
    2. Drain signal received → server enters draining state
    3. While draining: existing session completes, new binds rejected
    4. After drain: DSM shutdown called
  - Combined test with drain flow (Task 7) + live matchmaking (Task 5-6) + backfill (Task 9) + storage (Task 13-14)
  - This is the ultimate integration test proving all Phase 1 + Phase 2 features work together

  **Must NOT do**:
  - DO NOT use real AccelByte services — mock DSM Controller, DS Hub WebSocket, session service
  - DO NOT skip any component — all must be wired together

  **Recommended Agent Profile**:
  - **Category**: `unspecified-high`
  - **Skills**: []
  - **Reason**: Complex end-to-end integration test

  **Parallelization**:
  - **Can Run In Parallel**: NO
  - **Blocks**: Task 17
  - **Blocked By**: Task 15

  **References**:
  - `server/src/test/kotlin/accelbyte/dsm/DrainSignalHandlerTest.kt` — (Task 7 output)
  - `server/src/test/kotlin/accelbyte/IntegrationTest.kt` — (Task 15 output)

  **Acceptance Criteria**:
  - [ ] Full AMS integration test created
  - [ ] All Phase 1 + Phase 2 features tested together
  - [ ] All external services mocked
  - [ ] Test passes

  **QA Scenarios**:

  \`\`\`
  Scenario: Full AMS integration — drain to shutdown
    Tool: Bash
    Preconditions: Server registered with DSM, active session exists
    Steps:
      1. Send drain signal
      2. Verify server enters draining state
      3. Verify new binds rejected
      4. Complete existing session
      5. Verify DSM shutdown called
    Expected Result: Full drain sequence completes
    Evidence: .sisyphus/evidence/task-16-full-drain.{ext}
  \`\`\`

  **Commit**: YES
  - Message: `test(integration): add full AMS integration test`
  - Files: `server/src/test/kotlin/ams/FullAmsIntegrationTest.kt`
  - Pre-commit: `./gradlew :server:test --tests "*FullAmsIntegrationTest*" 2>&1 | tail -20`

---

- [x] 17. Code quality review + lint

  **What to do**:
  - Run full project check: `./gradlew check`
  - Specifically verify:
    1. No `as any` or `@ts-ignore` in new Kotlin code
    2. No empty catch blocks
    3. No `println` or `console.log` (use `Logger` only)
    4. All new files have KDoc comments
    5. No commented-out code
    6. Imports are all used
  - Check AI slop patterns:
    - Excessive comments (1 comment per line = too many)
    - Over-abstraction (classes with only 1 method that could be a function)
    - Generic names (data/result/item/temp)
  - Fix any issues found before marking complete

  **Must NOT do**:
  - DO NOT skip `./gradlew check` — it must pass
  - DO NOT leave any warnings in new code

  **Recommended Agent Profile**:
  - **Category**: `unspecified-high`
  - **Skills**: []
  - **Reason**: Code quality review across all modified files

  **Parallelization**:
  - **Can Run In Parallel**: NO
  - **Blocks**: None (final task)
  - **Blocked By**: Task 16

  **References**:
  - Project's existing linting/check configuration

  **Acceptance Criteria**:
  - [ ] `./gradlew check` passes with no failures
  - [ ] No `as any` in new code
  - [ ] No empty catch blocks
  - [ ] Logger used instead of println/console.log
  - [ ] All new files have KDoc
  - [ ] No commented-out code
  - [ ] No AI slop patterns detected

  **QA Scenarios**:

  \`\`\`
  Scenario: Full project check passes
    Tool: Bash
    Preconditions: All tasks complete
    Steps:
      1. Run ./gradlew check
      2. Verify BUILD SUCCESSFUL
      3. Check for any warnings in new files
    Expected Result: BUILD SUCCESSFUL, 0 failures
    Evidence: .sisyphus/evidence/task-17-check.{ext}
  \`\`\`

  **Commit**: YES
  - Message: `chore: fix code quality issues in AMS integration`
  - Files: All modified files from previous tasks
  - Pre-commit: `./gradlew check 2>&1 | tail -30`

---

## Final Verification Wave

> 4 review agents run in PARALLEL. ALL must APPROVE. Present consolidated results to user and get explicit "okay" before completing.
>
> **Do NOT auto-proceed after verification. Wait for user's explicit approval before marking work complete.**

- [x] F1. **Plan Compliance Audit** — `oracle`
  Read the plan end-to-end. For each "Must Have": verify implementation exists (read file, grep for symbol). For each "Must NOT Have": search codebase for forbidden patterns — reject with file:line if found. Check evidence files exist in .sisyphus/evidence/.
  Output: `Must Have [N/N] | Must NOT Have [N/N] | Tasks [N/N] | VERDICT: APPROVE/REJECT`

- [x] F2. **Code Quality Review** — `unspecified-high`
  Run `grep -rn "as any" --include="*.kt"`, `grep -rn "@ts-ignore" --include="*.kt"`, `grep -rn "println\|console.log" --include="*.kt"`. Review all changed files for: empty catches, commented-out code, unused imports. Check AI slop: excessive comments, over-abstraction, generic names.
  Output: `Issues [N] | Files [N clean/N issues] | VERDICT`

- [x] F3. **Real Manual QA** — `unspecified-high`
  Execute every QA scenario from every task — follow exact steps, capture evidence. For drain: simulate drain signal, verify state transitions. For matchmaking: test live path with mock. For backfill: send candidate, verify response. Save to `.sisyphus/evidence/final-qa/`.
  Output: `Scenarios [N/N pass] | Integration [N/N] | VERDICT`

- [x] F4. **Scope Fidelity Check** — `deep`
  For each task: read "What to do", read actual diff (git log/diff). Verify 1:1 — everything in spec was built, nothing beyond spec was built. Check "Must NOT do" compliance. Detect cross-task contamination.
  Output: `Tasks [N/N compliant] | Contamination [CLEAN/N issues] | VERDICT`

---

## Success Criteria

### Verification Commands
```bash
# Phase 1: Drain signal handler
grep -n "handleDrainSignal" server/src/main/kotlin/accelbyte/dsm/DrainSignalHandler.kt
grep -n "draining" server/src/main/kotlin/gameState/WorldManager.kt
./gradlew :server:test --tests "*DrainSignalHandlerTest*" 2>&1 | tail -10

# Phase 1: Live matchmaking
grep -n "requestGame" server-extend/src/main/kotlin/matchmaking/ServerConnector.kt | grep -v "TODO"
grep -n "MATCHMAKING_TIMEOUT_MS" server-extend/src/main/kotlin/matchmaking/ServerConnector.kt

# Phase 2: Backfill
grep -n "BackfillHandler" server/src/main/kotlin/accelbyte/backfill/
grep -rn "handleBackfillRequest" server/src/

# Phase 2: Party
grep -n "createParty\|joinParty\|leaveParty" accelbyteSdk/src/commonMain/kotlin/org/ttt/autogenesis/accelbyte/facades/LobbyFacade.kt

# Phase 2: Storage
grep -n "writeSessionStorage\|readSessionStorage" server/src/main/kotlin/accelbyte/session/

# Full check
./gradlew check 2>&1 | tail -5
```

### Final Checklist
- [x] All Must Have items present
- [x] All Must NOT Have items absent
- [x] All 17 tasks complete with evidence
- [x] All tests pass
- [x] No code quality issues
- [x] Scope not creeped

---

## Commit Strategy

- **1**: `feat(ams): add drain signal handler with graceful shutdown` - DrainSignalHandler.kt, WorldManager.kt, Server.kt
- **2**: `feat(matchmaking): implement requestGame live mode path` - ServerConnector.kt, GameRequest.kt
- **3**: `feat(matchmaking): add timeout and partial-fill to executeLiveMatchmaking` - ServerConnector.kt
- **4**: `test(ams): add drain signal integration tests` - DrainSignalHandlerTest.kt
- **5**: `feat(backfill): add backfill accept/reject handler` - BackfillHandler.kt, SessionModels.kt
- **6**: `feat(party): add createParty joinParty leaveParty to LobbyFacade` - LobbyFacade.kt, LobbyModels.kt
- **7**: `feat(storage): add session storage read/write binding` - SessionStorage.kt, WorldManager.kt
- **8**: `test(integration): add AMS integration tests` - AmsIntegrationTest.kt

---

## Success Criteria

### Verification Commands
```bash
# Drain signal handler
curl -X POST "http://localhost:9080/api/drain" -H "Content-Type: application/json" # Expected: 200, server enters DRAINING state
curl "http://localhost:9080/api/session/bind" -X POST -d '{"sessionId":"test","playerIds":["p1"]}' # Expected: 503 SERVER_DRAINING

# Live matchmaking
curl -X POST "http://localhost:7070/rpc/server.extend.requestGame" -d '{"gameType":"MULTIPLAYER","userName":"test","matchPool":"pvp"}' # Expected: true (live mode not false)

# DSM shutdown (after drain)
./gradlew :server:check # Expected: all tests pass
```

### Final Checklist
- [x] All Must Have items present
- [x] All Must NOT Have items absent
- [x] No tests broken by changes
- [x] Code follows existing conventions (Logger, LogCategory, kotlin.test)