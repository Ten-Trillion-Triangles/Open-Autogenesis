# Judge Territory Fix - Learnings

## Date: 2026-05-02

## Context
Fixed judge agent's territory capture logic so player gets territory when they destroy/dominate it, even if damaged/trashed.

## Key Findings

### 1. Destroyed Territory Handling
- `Territory.isDestroyed` flag exists in `sharedModel/src/commonMain/kotlin/structs/Territory.kt:44`
- `WorldManager.world.destroyedTerritories` is a `List<String>` of territory names
- Post-processing filter at `judge.kt:1062-1073` checks destroyed territories (only Elder God case)

### 2. The Bug
Lord Maple Tree invaded New England, destroyed 83% landmass with "waffle iron", won battle decisively - but judge said `territoryGained: []`
- Existing code at lines 1127-1138 was BLOCKING destroyed territory capture
- The fix required adding clarifying language that destruction ≠ not captured

### 3. New Rules Added
- **Section 552**: `##DESTROYED TERRITORY CAPTURE RULE##` - Win Condition 5
- **Section 711**: `##LOSS CONDITION CHECK (MANDATORY - CHECK FIRST)##` - 4 loss conditions
- **Section 455**: `**IMPORTANT - Destroyed Territory Exception:**` - Clarification note

### 4. Test Coverage
- Created `JudgeTerritoryCaptureTest.kt` with 4 test cases
- Tests document expected behavior for Win Condition 5 and Loss Conditions 1-4
- All 4 tests pass

## Patterns Observed

### Test File Location
- Existing judge tests: `server/src/test/kotlin/agent/builders/judgeOutcome/`
- New test placed at: `server/src/test/kotlin/org/ttt/autogenesis/agent/`
- Package: `agent.builders.judgeOutcome`

### WorldManager Test Setup
```kotlin
@BeforeTest
fun resetWorld() {
    WorldManager.world = World()
    WorldManager.history.clear()
    WorldManager.actionHistoryLog.clear()
    WorldManager.pendingActionHistoryByTurn.clear()
    WorldManager.playerStats.clear()
}
```

## Edge Cases Handled
1. Destroyed territory player dominates → player gets it (Win Condition 5)
2. Third party contested, neither side decisive → neutral (Loss Condition 2)
3. Player beaten back/driven back → no territory (Loss Condition 1 or 4)
4. Player betrayed by own forces → no territory (Loss Condition 3)

## Elder God Exception Preserved
- Only Elder God can destroy territory so completely no one claims it
- Handled by post-processing code, not by judge prompt rules
- This is the ONLY case where destroyed territory becomes neutral
