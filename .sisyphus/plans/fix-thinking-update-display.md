# Fix Plan: Thinking Update Display Issues in GameHistoryWindow

## Bug Analysis

### Bug 1: Each thinking dispatch treated as separate turn

**Root Cause**: In `appendThinkingUpdate()` (line 795-819), a NEW `GameHistoryData` entry is created and added to `historyEntries` for EVERY thinking update broadcast. Since each `GameHistoryData` gets a new random ID, each thinking update becomes a separate "turn" in the story panel.

**Evidence from logs**:
- 4 separate thinking broadcasts for "Zeta" at 21:35:28, 21:35:47, 21:36:33, 21:36:58
- Each creates a separate entry in `historyEntries`
- Turn "Zeta executes..." (line 1864 in server log) arrives LATER with `thinkingUpdates: []` because thinking was already consumed as separate entries

### Bug 2: Thinking data appears in Story page

**Root Cause**: `appendThinkingUpdate()` calls `refreshStoryPanel()` (line 815) which renders ALL entries in `historyEntries` including thinking-only entries. Since thinking uses `turnStory = data.thinking`, it gets displayed via `buildStoryEntry()` lines 429-434.

**The correct place for thinking is in Details tab only**: `renderDetailCardContent()` lines 528-544 already has the proper inline rendering of `entry.thinkingUpdates` with `[PLAYER]`/`[NPC]` badges.

## Fix Design

### Architecture Change

Instead of creating a new `GameHistoryData` entry per thinking update, buffer thinking updates and attach them to the appropriate turn entry:

```
Thinking Update Received
        ↓
Is there a pending turn for this character?
        ↓ yes                      ↓ no
Buffer in characterThinkingMap   Create stub entry (temp)
        ↓                           ↓
When turnComplete arrives:    When turnComplete arrives:
- Attach buffered thinking    - Attach buffered thinking
- Replace stub with real turn  - Replace stub with real turn
```

### New Data Structure

Add a pending thinking buffer map in `GameHistoryWindow`:
```kotlin
// Map of characterName -> list of thinking updates awaiting a turn
private val pendingThinkingByCharacter = mutableMapOf<String, MutableList<ThinkingUpdateData>>()
```

**Key behaviors preserved**:
- Thinking updates appear IMMEDIATELY in Details tab (real-time, no delay)
- Multiple thinking updates for same turn accumulate in the Details inline view
- Story panel unaffected - no fake "turns" created
- Player sees updates even if viewing Story tab (flashTab ensures they notice)

### Key Changes

**1. `appendThinkingUpdate()` modification**:
- Find existing entry for this character that doesn't have turnResult yet (pending turn)
- If found: add thinking to THAT entry's `thinkingUpdates` list, refresh detail card inline, flash tab
- If not found: buffer in `pendingThinkingByCharacter` for later attachment
- DO NOT add thinking as a full separate turn in story panel
- DO NOT call `refreshStoryPanel()` - only update detail card for real-time feel

**2. `recordGameHistoryEntry()` modification**:
- When receiving a turn, check `pendingThinkingByCharacter` for this character
- If thinking exists: assign it to `turn.thinkingUpdates` before processing
- Clear the pending buffer for this character

**3. `renderDetailCardContent()` - existing code already handles thinking rendering**:
```kotlin
if (entry.thinkingUpdates.isNotEmpty()) {
    target.add(p("AI Thinking") { ... })
    entry.thinkingUpdates.forEach { thinking ->
        val badge = if (thinking.isPlayer) "[PLAYER]" else "[NPC]"
        target.add(p("$badge ${thinking.characterName}: ${thinking.thinking}") { ... })
    }
}
```

**4. Story panel exclusion**:
- Either mark thinking-only entries with a flag, OR
- Just don't add stub entries to `historyEntries` at all - only add real turn entries
- The simplest approach: don't add thinking-only entries to `historyEntries`

## Implementation Tasks

### Task 1: Add pending thinking buffer

**File**: `GameHistoryWindow.kt`

Add property after line 84:
```kotlin
/**
 * Pending thinking updates by character, awaiting a turn completion to attach to.
 * Cleared when turnComplete arrives for that character.
 */
private val pendingThinkingByCharacter = mutableMapOf<String, MutableList<ThinkingUpdateData>>()
```

### Task 2: Modify `appendThinkingUpdate()`

Replace current implementation (lines 795-819) with:
```kotlin
fun appendThinkingUpdate(data: ThinkingUpdateData) {
    Logger.debug(LogCategory.UI, "[GAME_HISTORY_LOG] appendThinkingUpdate called for ${data.characterName}")

    // Check if there's an existing entry for this character that doesn't have a result yet (pending turn)
    val existingEntry = historyEntries.findLast {
        it.turnPlayer == data.characterName && it.turnResult.isBlank()
    }

    if (existingEntry != null) {
        // REAL-TIME UPDATE: Attach to existing pending turn's thinkingUpdates list
        existingEntry.thinkingUpdates.add(data)
        // Refresh the detail card inline to show new thinking immediately
        detailCardMap[existingEntry.id]?.let { card ->
            refreshDetailCard(card, existingEntry, historyEntries.indexOf(existingEntry) + 1)
        }
        // Flash the Details tab so player sees the update even if on Story tab
        if (activeTabIndex != 1) {
            flashTab(1)
        }
    } else {
        // No pending turn yet - buffer the thinking for when turn completes
        val buffered = pendingThinkingByCharacter.getOrPut(data.characterName) { mutableListOf() }
        buffered.add(data)
    }

    // Don't re-render story panel for thinking updates - they appear inline in Details only
}
```

**Key behaviors preserved**:
- Thinking updates appear IMMEDIATELY in Details tab (real-time, no delay)
- Multiple thinking updates for same turn accumulate in the Details inline view
- Story panel unaffected - no fake "turns" created
- Player sees updates even if viewing Story tab (flashTab ensures they notice)

### Task 3: Modify `recordGameHistoryEntry()` 

In the block handling new entries (around line 337-347), before adding:
```kotlin
// Attach any pending thinking for this character
val pending = pendingThinkingByCharacter.remove(entry.turnPlayer)
if (!pending.isNullOrEmpty()) {
    entry.thinkingUpdates.addAll(pending)
}
```

### Task 4: Remove story panel re-render from `appendThinkingUpdate()`

The current line 815 `refreshStoryPanel()` should be removed. Thinking updates should only appear in Details tab.

## Verification

### QA Scenario 1: Thinking appears in Details only (not Story)
1. Start game, complete a turn for Zeta
2. Observe thinking updates in Details tab inline with the Zeta turn entry
3. Verify Story tab shows only Zeta's turn, not thinking fragments
4. Should see accumulating thinking with `[PLAYER]` or `[NPC]` badges

### QA Scenario 2: Multiple thinking updates for same turn (REAL-TIME)
1. During Zeta's turn (AI takes longer), trigger multiple thinking broadcasts
2. Each thinking update appears IMMEDIATELY in Details tab as it arrives
3. Details card shows all accumulated thinking for Zeta
4. Story tab still shows single entry for Zeta
5. Tab flashes to draw attention even if player is on Story tab

### QA Scenario 3: Thinking arrives before turn completion
1. Thinking broadcasts arrive while turn is still processing (AI planning)
2. Thinking is buffered in `pendingThinkingByCharacter`
3. When turnComplete arrives, thinking is attached and displayed
4. Player sees complete picture when turn finalizes

### QA Scenario 4: Player waiting during AI/NPC turn
1. Player submits action and waits
2. AI/NPC thinking updates arrive in real-time (every few seconds)
3. Player sees thinking appear in Details tab for the pending turn
4. Details card updates inline without page refresh
5. Tab flashes to notify player of new thinking content

## Files Affected

- `kvisionApp/src/jsMain/kotlin/ui/gameplay/GameHistoryWindow.kt`
  - Add `pendingThinkingByCharacter` map
  - Rewrite `appendThinkingUpdate()` 
  - Modify `recordGameHistoryEntry()` to attach pending thinking
  - Remove story panel re-render for thinking

## Test Infrastructure

No test changes needed - the existing `demoMode` in `GameHistoryWindow` can be used to verify the fix since it already exercises the turn history system.
