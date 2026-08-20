# Agent Work Stream Broadcast Fix Plan

## Problem Statement

The Agent Work Stream system is currently broken because:
1. **NPC orchestrator** resolves `recipientIds = 0` (empty set), causing all streaming to be silently dropped
2. **Gameplay orchestrator** only streams to the acting player (`ctx.connectionId`), not all connected clients
3. **Public agents** (narrative, judge, lorebook) that affect all players should broadcast to everyone, not just one player
4. The subscription-based filtering in `resolveNpcAgentWorkRecipients()` prevents streaming when no clients are subscribed

## Root Causes

### NPC Orchestrator (npcOrchestrator.kt)
- Line 214: `val recipientIds = resolveNpcAgentWorkRecipients(originConnectionId)`
- `resolveNpcAgentWorkRecipients()` returns empty set when:
  - No active subscriptions exist
  - `originConnectionId` is null
- Result: All `streamPipelineOutputToAgentWorkBuffer(recipientIds, ...)` calls receive empty collection
- Log evidence: `[NPC_STREAM] Turn origin='none', recipients=0`

### Gameplay Orchestrator (gameplayOrchestrator.kt)
- Streams only to `ctx.connectionId` (the acting player)
- Other connected players don't see agent work streams
- Public agents (narrative, judge) should broadcast to all players

### AgentWorkStreamManager (client-side)
- Line 74-77: `if (window?.visible != true) return`
- Silently drops all data if window not visible
- No error logging when data is dropped
- Subscription can fail: `Subscription change failed: WebSocket session missing`

## Solution Architecture

### Broadcast Strategy
**Public agents** (affecting all players) → Broadcast to ALL connected clients
**Private agents** (player-specific) → Send only to acting player

### Public Agents (Broadcast to All)
- Narrative generation (writing agents)
- Judge agents (outcome resolution)
- Lorebook updates
- NPC validators
- Play/target detection
- Geopolitics updates

### Private Agents (Single Recipient)
- Answer agent (player-specific queries)
- Open widget agent (UI-specific)
- Validation agents for player actions (optional - could be public)

## Implementation Plan

### Step 1: Add Broadcast Helper Function
**File:** `server/src/main/kotlin/org/ttt/autogenesis/server/AgentWorkStreamStreaming.kt`

**Action:** Add new function to get all connected client IDs

```kotlin
/**
 * Returns all connected client player IDs for broadcasting public agent work.
 */
internal fun getAllConnectedClientIds(): Collection<String>
{
    val connectionManager = UiSignalRpcHandlers.connectionManager
    if (connectionManager == null)
    {
        Logger.warn(LogCategory.NETWORK, "AgentWorkStreamStreaming: connectionManager is null, no broadcast recipients")
        return emptyList()
    }
    
    val allIds = connectionManager.allSessions().map { it.playerId }.filter { it.isNotBlank() }
    Logger.debug(LogCategory.NETWORK, "AgentWorkStreamStreaming: Resolved ${allIds.size} broadcast recipients")
    return allIds
}
```

**Reasoning:** Centralized function to get all connected clients, with proper null checks and logging. Uses `playerId` property from PlayerSession (playerId is used as connectionId throughout the system).

**Validation:** 
- Returns empty list if connectionManager is null
- Filters out blank player IDs
- Logs recipient count for debugging

---

### Step 2: Fix NPC Orchestrator Recipient Resolution
**File:** `server/src/main/kotlin/agent/runners/npcOrchestrator.kt`

**Action:** Replace `resolveNpcAgentWorkRecipients()` with broadcast to all clients

**Change 1 - Line 214:**
```kotlin
// OLD:
val recipientIds = resolveNpcAgentWorkRecipients(originConnectionId)

// NEW:
val recipientIds = getAllConnectedClientIds()
```

**Change 2 - Remove obsolete function (lines 150-184):**
Delete `resolveNpcAgentWorkRecipients()` entirely - no longer needed.

**Reasoning:** 
- NPC turns are public events affecting all players
- Subscription-based filtering was causing empty recipient lists
- All connected clients should see NPC agent work

**Validation:**
- Log should show `recipients > 0` when clients are connected
- All `streamPipelineOutputToAgentWorkBuffer(recipientIds, ...)` calls will have recipients

---

### Step 3: Fix Gameplay Orchestrator to Broadcast Public Agents
**File:** `server/src/main/kotlin/agent/runners/gameplayOrchestrator.kt`

**Action:** Change public agent streaming from single player to all clients

**Change 1 - Import the helper (add to imports):**
```kotlin
import org.ttt.autogenesis.server.getAllConnectedClientIds
```

**Change 2 - Line 392 (validation splitter):**
```kotlin
// OLD:
validationSplitter.getAllChildPipelines().forEach { streamPipelineOutputToAgentWorkBuffer(ctx.connectionId, it)}

// NEW:
val broadcastIds = getAllConnectedClientIds()
validationSplitter.getAllChildPipelines().forEach { streamPipelineOutputToAgentWorkBuffer(broadcastIds, it)}
```

**Change 3 - Line 537 (narrative/assessment splitter):**
```kotlin
// OLD:
getAllChildPipelines().forEach { streamPipelineOutputToAgentWorkBuffer(ctx.connectionId, it) }

// NEW:
val broadcastIds = getAllConnectedClientIds()
getAllChildPipelines().forEach { streamPipelineOutputToAgentWorkBuffer(broadcastIds, it) }
```

**Change 4 - Line 669 (AI player agent):**
```kotlin
// OLD:
streamPipelineOutputToAgentWorkBuffer(connectionId, aiPlayerAgent)

// NEW:
val broadcastIds = getAllConnectedClientIds()
streamPipelineOutputToAgentWorkBuffer(broadcastIds, aiPlayerAgent)
```

**Change 5 - Line 706 (counter-play cascade - identifyPlayType helper):**
```kotlin
// OLD:
streamPipelineOutputToAgentWorkBuffer(connectionId, this)

// NEW:
val broadcastIds = getAllConnectedClientIds()
streamPipelineOutputToAgentWorkBuffer(broadcastIds, this)
```

**Change 6 - Line 1334 (counter-play defender agent):**
```kotlin
// OLD:
streamPipelineOutputToAgentWorkBuffer(ctx.connectionId, this)

// NEW:
val broadcastIds = getAllConnectedClientIds()
streamPipelineOutputToAgentWorkBuffer(broadcastIds, this)
```

**Change 7 - Line 1341 (counter-play attacker agent):**
```kotlin
// OLD:
streamPipelineOutputToAgentWorkBuffer(ctx.connectionId, this)

// NEW:
val broadcastIds = getAllConnectedClientIds()
streamPipelineOutputToAgentWorkBuffer(broadcastIds, this)
```

**Change 8 - Line 1411 (lorebook agent):**
```kotlin
// OLD:
streamPipelineOutputToAgentWorkBuffer(ctx.connectionId, this)

// NEW:
val broadcastIds = getAllConnectedClientIds()
streamPipelineOutputToAgentWorkBuffer(broadcastIds, this)
```

**Reasoning:**
- Narrative generation affects all players (public story)
- Judge outcomes affect all players (game state changes)
- Lorebook updates are shared knowledge
- Counter-play agents show conflict resolution to all players
- All players should see the same agent reasoning

**Validation:**
- Multiple connected clients should all receive streaming data
- Check logs for `Wiring pipeline 'X' for N recipients` where N > 1

---

### Step 4: Keep Private Agents Single-Recipient
**Files:** 
- `server/src/main/kotlin/agent/builders/systemActions/answerAgent.kt`
- `server/src/main/kotlin/agent/builders/systemActions/OpenWidgetAgent.kt`

**Action:** NO CHANGES - these already use single connectionId correctly

**Reasoning:**
- Answer agent responds to specific player queries
- Open widget is UI-specific to one player
- These should NOT broadcast

**Validation:**
- Verify these still use `streamPipelineOutputToAgentWorkBuffer(connectionId, ...)` with single ID

---

### Step 5: Fix Client-Side Silent Data Dropping
**File:** `kvisionApp/src/jsMain/kotlin/ui/gameplay/AgentWorkStreamManager.kt`

**Action:** Already added diagnostic logging in previous fix (lines 77, 79)

**Existing fix:**
```kotlin
fun handleStream(data: AgentWorkStreamData)
{
    val window = streamWindow
    Logger.debug(LogCategory.UI, "AgentWorkStreamManager.handleStream: window=$window, visible=${window?.visible}, contentLen=${data.content.length}, isComplete=${data.isComplete}")
    if (window?.visible != true)
    {
        Logger.warn(LogCategory.UI, "AgentWorkStreamManager.handleStream: Dropping data because window not visible (window=$window, visible=${window?.visible})")
        return
    }
    // ... rest of function
}
```

**Reasoning:**
- Logging already added to diagnose visibility issues
- Will show when/why data is being dropped
- No additional changes needed

**Validation:**
- Check browser logs for "Dropping data because window not visible"
- If seen, investigate window visibility state

---

### Step 6: Remove Subscription Requirement (Optional Enhancement)
**File:** `server/src/main/kotlin/org/ttt/autogenesis/server/AgentWorkStreamDispatcher.kt`

**Current behavior:**
- `isSubscribed()` checks if client explicitly subscribed
- Streaming only sent to subscribed clients

**Proposed change:** Make subscription optional for broadcast scenarios

**Option A - Keep subscription system:**
- Clients must open Agent Work Stream window to receive data
- Prevents unwanted data transmission
- Current behavior

**Option B - Auto-subscribe all clients:**
- Remove subscription requirement
- All clients receive streaming data automatically
- Window visibility controls rendering only

**Recommendation:** Keep Option A (current subscription system) but ensure clients auto-subscribe when connecting to gameplay.

**Action:** Add auto-subscription on gameplay UI initialization

**File:** `kvisionApp/src/jsMain/kotlin/ui/gameplay/GameplayUI.kt`

**Add to init block:**
```kotlin
init {
    // ... existing init code ...
    
    // Auto-subscribe to agent work stream for gameplay
    AgentWorkStreamManager.openStream()
}
```

**Reasoning:**
- Ensures all gameplay clients are subscribed
- Maintains subscription system for control
- Window can be hidden but subscription remains active

**Validation:**
- Check server logs for `AgentWorkStreamDispatcher: Subscribed <connectionId>`
- Should see subscription immediately after gameplay UI loads

---

## Testing Plan

### Test 1: NPC Turn Streaming
**Setup:**
1. Start server
2. Connect 2+ clients to gameplay
3. Trigger NPC turn

**Expected:**
- Server log: `[NPC_STREAM] Turn origin='...', recipients=2` (or more)
- Server log: `AgentWorkStreamStreaming: Wiring pipeline 'X' for 2 recipients`
- Server log: `AgentWorkStreamDispatcher: Fanout chunk length=X to 2 recipients`
- Browser logs (both clients): `AgentWorkStreamWindow: Streaming data received for the first time`

**Failure indicators:**
- `recipients=0` → Step 2 failed
- `No recipients for pipeline` → Step 2 failed
- No fanout logs → Streaming not configured
- No browser logs → Client-side issue (Step 5)

---

### Test 2: Player Turn Streaming
**Setup:**
1. Start server
2. Connect 2+ clients to gameplay
3. Execute player turn

**Expected:**
- Server log: `AgentWorkStreamStreaming: Wiring pipeline 'narrative' for 2 recipients`
- Server log: `AgentWorkStreamDispatcher: Fanout chunk length=X to 2 recipients`
- Browser logs (both clients): Streaming data received

**Failure indicators:**
- `for 1 recipients` → Step 3 failed (still single-recipient)
- No fanout to multiple clients → Broadcast not working

---

### Test 3: Private Agent Isolation
**Setup:**
1. Connect 2 clients
2. Client A uses answer agent
3. Client B observes

**Expected:**
- Only Client A receives answer agent streaming
- Client B does NOT receive answer agent data

**Failure indicators:**
- Client B receives data → Step 4 failed (over-broadcasting)

---

### Test 4: Window Visibility
**Setup:**
1. Connect client
2. Close Agent Work Stream window
3. Trigger agent execution

**Expected:**
- Server sends data normally
- Browser log: `Dropping data because window not visible`
- No rendering in UI (window closed)

**Failure indicators:**
- No "Dropping data" log → Step 5 logging not working
- Data still renders → Window state incorrect

---

## Rollback Plan

If issues occur:

1. **Revert Step 2:** Restore `resolveNpcAgentWorkRecipients()` function
2. **Revert Step 3:** Change `getAllConnectedClientIds()` back to `ctx.connectionId`
3. **Keep Step 5:** Diagnostic logging is safe to keep

**Rollback command:**
```bash
git diff HEAD > /tmp/streaming-fix.patch
# Test changes
# If broken:
git checkout HEAD -- server/src/main/kotlin/agent/runners/npcOrchestrator.kt
git checkout HEAD -- server/src/main/kotlin/agent/runners/gameplayOrchestrator.kt
```

---

## Success Criteria

- [ ] NPC turns show `recipients > 0` in logs
- [ ] Multiple clients receive streaming data simultaneously
- [ ] Browser logs show "Streaming data received" on all clients
- [ ] Agent Work Stream window renders content in real-time
- [ ] Private agents (answer, open widget) remain single-recipient
- [ ] No "No recipients for pipeline" warnings in logs
- [ ] No silent data dropping (unless window closed)

---

## Edge Cases

### No Connected Clients
**Scenario:** Agent executes but no clients connected
**Expected:** `getAllConnectedClientIds()` returns empty list, streaming skipped
**Validation:** Log shows "No recipients for pipeline"

### Client Disconnects Mid-Stream
**Scenario:** Client disconnects while agent is streaming
**Expected:** Server continues streaming to remaining clients
**Validation:** No errors in server logs, other clients unaffected

### Window Closed But Subscribed
**Scenario:** Client subscribed but window hidden
**Expected:** Data sent to client, client logs "Dropping data because window not visible"
**Validation:** Server sends normally, client drops silently

### Subscription Fails
**Scenario:** WebSocket not ready when subscribing
**Expected:** Browser log: "Subscription change failed: WebSocket session missing"
**Solution:** Step 6 auto-subscription on gameplay init ensures subscription happens after WebSocket ready

---

## Implementation Order

1. **Step 1** - Add broadcast helper (foundation)
2. **Step 2** - Fix NPC orchestrator (highest impact)
3. **Step 3** - Fix gameplay orchestrator (high impact)
4. **Step 5** - Client logging (already done, verify)
5. **Step 6** - Auto-subscription (polish)
6. **Testing** - Run all test scenarios
7. **Step 4** - Verify private agents (validation only)

---

## Files Modified

1. `server/src/main/kotlin/org/ttt/autogenesis/server/AgentWorkStreamStreaming.kt` - Add broadcast helper
2. `server/src/main/kotlin/agent/runners/npcOrchestrator.kt` - Fix recipient resolution
3. `server/src/main/kotlin/agent/runners/gameplayOrchestrator.kt` - Broadcast public agents (8 locations: lines 392, 537, 669, 706, 1334, 1341, 1411)
4. `kvisionApp/src/jsMain/kotlin/ui/gameplay/AgentWorkStreamManager.kt` - Logging (already done)
5. `kvisionApp/src/jsMain/kotlin/ui/gameplay/GameplayUI.kt` - Auto-subscription (optional)

---

## Estimated Impact

- **Lines changed:** ~50-60 lines (8 locations in gameplay orchestrator + NPC orchestrator + helper function)
- **Risk level:** Medium (affects core streaming system)
- **Testing time:** 30-60 minutes
- **Rollback time:** < 5 minutes

---

## Notes

- The subscription system is NOT the root cause - empty recipient lists are
- Broadcasting to all clients is correct behavior for public agents
- Private agents (answer, open widget) already work correctly
- Client-side visibility check is correct - window must be open to render
- Auto-subscription ensures clients are ready to receive streams