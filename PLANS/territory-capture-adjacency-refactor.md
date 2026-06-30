# Territory Capture & Exchange Refactor Plan

## Problem Statement

The current system lacks enforcement of critical territorial gameplay rules:
1. **No adjacency validation** - Players can capture non-adjacent territories
2. **No territory stat modification system** - Cannot debuff territories without capturing them
3. **Target type confusion** - Targeting a player/NPC can affect their territories
4. **Capture vs debuff ambiguity** - No distinction between capturing territory vs weakening it

This creates balance issues where long-range attacks have the same outcome as adjacent attacks, and diplomatic actions can inappropriately transfer territories.

## Requirements

### Core Rules to Enforce:

**Adjacency Capture Rule:**
- Player can only CAPTURE territory if they own an adjacent territory
- Non-adjacent actions can debuff territory stats but cannot capture

**Action Type Outcomes:**

| Action Type | Adjacent | Non-Adjacent |
|-------------|----------|--------------|
| **Military (Hostile)** | Win battle = capture territory | Debuff militaryThreatStat, can depose government (territory becomes neutral) |
| **Diplomatic (Hostile)** | Can capture if conditions met | Debuff diplomacyThreatStat, can depose government (territory becomes neutral) |
| **Diplomatic (Friendly)** | Can capture if qualifying agreement | Buff diplomacyThreatStat (no capture) |

**Target Type Rules:**
- **Territory target** → Can affect territory (capture/debuff/buff)
- **Player/NPC target** → Can only affect that actor's stats, NOT their territories
- **Enforcement:** Code-level validation + agent prompts

**Battle Victory = Territory Capture (Adjacent Only):**
- If player wins battle OR captures and holds any portion of territory → capture
- Temporary captures that are lost during same turn don't count
- Defender must clearly defeat attacker to prevent capture
- Non-adjacent battles cannot result in capture regardless of victory

## Background

**Current System:**
- `transferTerritory()` executes unconditionally (no validation)
- Judge determines territory awards via LLM prompts only
- Territory has `militaryThreatStat` and `diplomacyThreatStat` (existing, used by assessment agent)
- `ActionTargetType` enum distinguishes Territory/Player/NPC targets
- `ActionIntent` enum distinguishes Hostile/Friendly actions
- Long-range modifier applies distance penalties but doesn't block capture

**Agents Involved:**
- `buildJudge` - Determines gains/losses, stat changes
- `buildAssessmentAgent` - Geopolitical favor calculation (uses territory stats)
- `buildReverseAgent/buildHardenAgent/buildActOfGodAgent` - Narrative refinement
- `targetDetectorAgent` - Identifies and categorizes targets
- `validator` - Validates action legality
- `gameplayOrchestrator` - Orchestrates all phases

## Proposed Solution

### Architecture Overview

```
Player Action
  ↓
Target Detection → ActionTargetTypeObj (type: Territory/Player/NPC, intent: Hostile/Friendly)
  ↓
Validation → Check target type rules
  ↓
GameMath → Calculate success/failure (existing)
  ↓
Judge Gains/Losses Pipe → Determine outcome type:
  ├─ Territory target + Adjacent + Hostile/Diplomatic → Capture possible
  ├─ Territory target + Non-Adjacent + Hostile → Debuff only (or depose)
  ├─ Territory target + Non-Adjacent + Friendly → Buff only
  └─ Player/NPC target → No territory effects
  ↓
Judge Transformation Function → Validate adjacency, filter invalid captures
  ↓
WorldManager.applyJudgeResults → Final adjacency check before transfer
  ↓
Apply outcome (capture OR debuff/buff territory stats)
```

### Data Model Changes

**1. Judge Output Enhancement**

```kotlin
// judge.kt - Add to Results data class
@Serializable
data class TerritoryStatChange(
    var territoryName: String = "",
    var militaryThreatStat: Int = 0,    // Defense/military strength changes
    var diplomacyThreatStat: Int = 0,   // Diplomatic influence changes
    var reasoning: String = ""
)

@Serializable
data class Results(
    // ... existing fields ...
    var territoryStatChanges: MutableList<TerritoryStatChange> = mutableListOf(),
    var territoriesDeposed: MutableList<String> = mutableListOf() // Governments collapsed
)
```

**2. Adjacency Validation Result**

```kotlin
// WorldManager.kt
data class AdjacencyValidationResult(
    val isValid: Boolean,
    val reason: String,
    val closestOwnedTerritory: String? = null,
    val distance: Int = Int.MAX_VALUE
)
```

## Task Breakdown

### Phase 1: Data Model & Infrastructure

**Task 1: Document Existing Territory Stats Usage**
- Territory already has `militaryThreatStat` and `diplomacyThreatStat`
- **No new fields needed** - use existing stats for debuff/buff system
- Map action types to existing stats:
  - **Military actions** → Modify `militaryThreatStat`
  - **Diplomatic actions** → Modify `diplomacyThreatStat`
- **Demo:** Verify existing stats can be modified and affect assessment agent calculations

**Task 2: Extend Judge Results Schema**
- Add `territoryStatChanges` list to `Results` data class
- Add `territoriesDeposed` list to `Results` data class
- Add `TerritoryStatChange` data class with militaryThreatStat and diplomacyThreatStat fields
- Update serialization (handled by @Serializable)
- **Demo:** Judge can output territory stat changes without errors

**Task 3: Add Adjacency Validation Helper**
- Create `validateTerritoryAdjacency()` in `WorldManager`
- Input: player name, territory name
- Output: `AdjacencyValidationResult`
- Logic: Check if player owns any territory adjacent to target using `World.hasAdjacentTerritory()`
- Include closest owned territory and distance in result
- **Demo:** Function correctly identifies adjacent/non-adjacent territories

### Phase 2: Code-Level Enforcement

**Task 4: Add Adjacency Check to transferTerritory()**
- Modify `WorldManager.transferTerritory()`
- Add adjacency validation before transfer
- Log blocked transfers with reason
- Allow override flag for admin/testing purposes
- **Demo:** Non-adjacent transfers are blocked, logged with clear reason

**Task 5: Add Judge Transformation Validation**
- Modify `gainsAndLossesPipe.setTransformationFunction()` in `judge.kt`
- Check adjacency for all `territoryGained` entries
- Move invalid captures to `territoryStatChanges` (debuff instead)
- Apply appropriate stat debuff based on action type (military vs diplomatic)
- Log all conversions
- **Demo:** Judge automatically converts non-adjacent captures to debuffs

**Task 6: Add Target Type Enforcement**
- Modify `gainsAndLossesPipe.setTransformationFunction()` in `judge.kt`
- Check `targetData.type` from context
- If type is Player/NPC, clear all territory gains/losses/exchanges
- Log blocked territory effects
- **Demo:** Targeting a player cannot affect their territories

### Phase 3: Territory Stat Application

**Task 7: Implement Territory Stat Change Application**
- Add `applyTerritoryStatChanges()` to `WorldManager`
- Apply stat changes from `Results.territoryStatChanges`
- Clamp militaryThreatStat and diplomacyThreatStat to reasonable ranges
- Log all changes
- **Demo:** Territory stats are modified correctly after judge results

**Task 8: Implement Territory Deposition Logic**
- Add `deposeTerritory()` to `WorldManager`
- Remove territory from owner (becomes neutral)
- Apply severe stat debuffs (militaryThreatStat -30, diplomacyThreatStat -30)
- Log deposition events
- **Demo:** Territories can be deposed without being captured

**Task 9: Integrate Stat Changes into applyJudgeResults()**
- Call `applyTerritoryStatChanges()` in `applyJudgeResults()`
- Call `deposeTerritory()` for each entry in `territoriesDeposed`
- Update action history with stat change events
- **Demo:** Full judge results flow includes territory stat changes

### Phase 4: Agent Prompt Updates

**Task 10: Update Judge Gains/Losses Pipe Prompts**
- Add adjacency capture rule to system prompt
- Add target type restriction rules
- Add capture vs debuff decision logic:
  - Adjacent + Territory target + Battle victory → Capture
  - Non-adjacent + Territory target + Hostile → Debuff or depose
  - Non-adjacent + Territory target + Friendly → Buff
  - Player/NPC target → No territory effects
- Add examples for each scenario
- **Demo:** Judge correctly distinguishes capture vs debuff scenarios

**Task 11: Update Judge Stat Change Pipe Prompts**
- Add territory stat change instructions using existing stats only
- Define stat change magnitudes:
  - Minor debuff: -10 to -20
  - Moderate debuff: -20 to -35
  - Severe debuff/depose: -30 to -50
  - Minor buff: +10 to +20
  - Moderate buff: +20 to +35
- Map action types to stats:
  - **Military actions** → militaryThreatStat (makes territory harder/easier to attack)
  - **Diplomatic actions** → diplomacyThreatStat (makes territory more/less diplomatically resistant)
- Add examples for military/diplomatic actions
- **Demo:** Judge outputs appropriate territory stat changes using only existing stats

**Task 12: Update Supporting Agent Prompts**
- **Validator (`validator.kt`):** Add rule that targeting player/NPC cannot affect territories
- **Answer Agent (`answerAgent.kt`):** Update adjacency rule with capture vs debuff distinction
- **Assessment Agent (`geoPoliticsAssessmentAgent.kt`):** Note that non-adjacent actions are debuff-only
- **Narrative Refinement Agents:** Update to understand capture vs debuff outcomes
- **Demo:** All agents consistently enforce adjacency and target type rules

## Detailed Rule Specifications

### Adjacency Capture Rule (Code + Prompts)

**Code Validation:**
```kotlin
fun validateTerritoryAdjacency(playerName: String, territoryName: String): AdjacencyValidationResult {
    val player = world.findPlayerByName(playerName) ?: return AdjacencyValidationResult(false, "Player not found")
    val territory = world.mapTiles.findTerritoryByName(territoryName) ?: return AdjacencyValidationResult(false, "Territory not found")
    
    val hasAdjacent = world.hasAdjacentTerritory(territory, player)
    
    if (!hasAdjacent) {
        val closestTerritory = player.capturedTerritory.minByOrNull { 
            world.getTerritoryDistance(it, territory).distance 
        }
        val distance = closestTerritory?.let { world.getTerritoryDistance(it, territory).distance } ?: Int.MAX_VALUE
        
        return AdjacencyValidationResult(
            isValid = false,
            reason = "Player does not own any territory adjacent to '$territoryName'. Closest owned territory is '${closestTerritory?.name}' at distance $distance.",
            closestOwnedTerritory = closestTerritory?.name,
            distance = distance
        )
    }
    
    return AdjacencyValidationResult(isValid = true, reason = "Player owns adjacent territory")
}
```

**Judge Prompt Addition:**
```
##ADJACENCY CAPTURE RULE##

CRITICAL: Territory capture requires adjacency. Non-adjacent actions can only debuff or depose.

**Adjacency Check:**
1. Check "player stats" → capturedTerritory list
2. Check "world" → mapTiles → find target territory → check borders
3. If ANY player territory shares a border with target → ADJACENT
4. If NO player territory shares a border → NON-ADJACENT

**Outcome by Adjacency:**

ADJACENT + Territory Target:
- Hostile Military: Battle victory → territoryGained
- Hostile Diplomatic: Government collapse → territoryGained
- Friendly Diplomatic: Qualifying agreement → territoryGained

NON-ADJACENT + Territory Target:
- Hostile Military: Bombing/raids → territoryStatChanges (militaryThreatStat -20 to -40)
- Hostile Diplomatic: Destabilization → territoryStatChanges (diplomacyThreatStat -15 to -30) OR territoriesDeposed
- Friendly Diplomatic: Aid/support → territoryStatChanges (diplomacyThreatStat +15 to +30)

**NEVER add non-adjacent territories to territoryGained.**
```

### Target Type Enforcement (Code + Prompts)

**Code Validation:**
```kotlin
// In judge.kt gainsAndLossesPipe.setTransformationFunction()
val targetDataWindow = asMiniBank.contextMap["target_data"]
val targetDataJson = targetDataWindow?.contextElements?.getOrNull(0) ?: "{}"
val targetData = extractJson<ActionTargetTypeObj>(targetDataJson)

if (targetData?.type == ActionTargetType.Player || targetData?.type == ActionTargetType.Npc) {
    if (results.territoryGained.isNotEmpty() || results.territoryLost.isNotEmpty() || results.territoryExchanges.isNotEmpty()) {
        Logger.warn(LogCategory.SYSTEM, "[TARGET_TYPE_ENFORCEMENT] Blocking territory effects for Player/NPC target. Target type: ${targetData.type}, territories blocked: ${results.territoryGained + results.territoryLost}")
        results.territoryGained.clear()
        results.territoryLost.clear()
        results.territoryExchanges.clear()
    }
}
```

**Judge Prompt Addition:**
```
##TARGET TYPE RESTRICTIONS##

CRITICAL: Check "target_data" context to determine what the player targeted.

**Target Type Rules:**

target_data.type == "Territory":
- CAN affect territory (capture, debuff, buff, depose)
- CAN modify territory stats
- CAN transfer territory ownership

target_data.type == "Player" OR "Npc":
- CANNOT affect territories
- CANNOT modify territory stats
- CANNOT transfer territory ownership
- CAN ONLY affect the targeted actor's personal stats (reputation, might, wealth, etc.)

**Example:**
- "I attack Commander Shepard" → target_data.type = Player → NO territory effects
- "I attack The Citadel" → target_data.type = Territory → Territory effects allowed

**Validation:**
Before adding ANY territory to territoryGained/territoryLost/territoryExchanges:
1. Check target_data.type
2. If Player or Npc → DO NOT add territory effects
3. If Territory → Proceed with adjacency check
```

### Battle Victory = Capture Rule (Prompts Only)

**Judge Prompt Addition:**
```
##BATTLE VICTORY CAPTURE RULE##

For ADJACENT territories with HOSTILE MILITARY actions:

**Automatic Capture Conditions (Must meet ONE of these):**
1. **Decisive Battle Victory:**
   - Player wins the battle decisively
   - Enemy is defeated, retreats, or surrenders
   - Player's forces control the battlefield at battle's end

2. **Territory Held:**
   - Player captures ANY portion of the territory AND holds it by turn's end
   - Even temporary capture that is later lost does NOT count
   - Player must maintain control through the conclusion of their action
   - Examples of holding:
     * "Player's forces establish and maintain a foothold"
     * "Player captures 30% of territory and holds position"
     * "Player secures key locations and defends them successfully"

**NO Capture Conditions:**
- Player is clearly defeated
- Player retreats or withdraws
- Battle ends in stalemate with no ground gained
- Player captures territory but loses it before turn ends
- Temporary gains that are reversed during the same action
- Defender successfully repels all attacks
- Player's forces are driven out

**Critical Rules:**
- "Winning the battle" = "Capturing the territory" (for adjacent hostile military)
- "Capturing and holding any portion" = "Capturing the entire territory"
- Temporary capture that is lost = NO capture
- Must maintain control through turn conclusion

**Examples:**

✓ CAPTURE:
- "Player's forces win the battle, enemy retreats" → territoryGained
- "Player captures eastern districts and holds them" → territoryGained
- "Player establishes foothold and successfully defends it" → territoryGained
- "Player's forces push enemy back and secure the area" → territoryGained

✗ NO CAPTURE:
- "Player's forces are repelled" → territoryGained ✗
- "Player briefly captures territory but is driven out" → territoryGained ✗
- "Player captures ground but loses it by nightfall" → territoryGained ✗
- "Battle ends in stalemate, no ground held" → territoryGained ✗
- "Player's temporary gains are reversed" → territoryGained ✗

**Evaluation Process:**
1. Check if action is ADJACENT + HOSTILE MILITARY
2. Determine battle outcome at turn's END (not during)
3. Ask: "Does player control any portion of territory at conclusion?"
   - YES + Battle won → territoryGained
   - YES + Territory held → territoryGained
   - NO → territoryGained ✗
```

### Friendly Action Capture & Buff Rules (Prompts)

**Judge Prompt Addition:**
```
##FRIENDLY ACTION CAPTURE & BUFF RULES##

For FRIENDLY DIPLOMATIC actions targeting territories:

**ADJACENT + FRIENDLY + SUCCESS:**

Capture Conditions (Must meet ONE):

1. **Military Agreements:**
   - Territory forms military pact with player
   - Territory allows player to establish military bases on their land
   - Territory agrees to joint military actions with player on long-term basis
   - Territory joins player in NATO/UN-like alliance

2. **Economic/Trade Agreements:**
   - Territory agrees to any economic or trade deal with player
   - Trade agreement that integrates economies

3. **Political Integration:**
   - Territory agrees to join any union with player (EU-style union, statehood, confederation)
   - Territory agrees to alliance where government allies with player
   - Territory peacefully joins player's nation

4. **Dynastic/Marriage Alliances:**
   - Territory agrees to marriage alliance (marrying off royalty, dynastic marriage)
   - Political marriage that transfers territorial control

5. **Voluntary Transfer:**
   - Territory ruler voluntarily cedes control to player
   - Territory ruler abdicates in favor of player
   - Treaty explicitly grants territory to player

6. **Empty Territory:**
   - Player arrives at territory with no government claiming rulership
   - Territory is abandoned and player establishes control

7. **Legal Victory:**
   - Player successfully stages lawsuit/legal dispute and is awarded territory

**If capture conditions NOT met but action succeeds:**
- Buff diplomacyThreatStat (+15 to +30)
- Examples: Cultural exchange, improved relations, non-binding agreements

**NON-ADJACENT + FRIENDLY + SUCCESS:**

CANNOT Capture (regardless of success):
- Always results in buff only
- Buff diplomacyThreatStat (+15 to +30)
- Examples: Foreign aid, trade deals, diplomatic support, cultural exchange

**Critical Rules:**
- ANY of the listed agreements = territory capture (if adjacent)
- Friendly actions can capture ONLY if adjacent AND qualifying agreement occurs
- Success without qualifying agreement = buff, not capture
- Non-adjacent friendly actions NEVER capture, only buff

**Examples:**

✓ ADJACENT CAPTURE:
- "Territory signs military pact with player" → territoryGained
- "Territory agrees to trade deal with player" → territoryGained
- "Territory allows player to establish military bases" → territoryGained
- "Territory joins player's alliance" → territoryGained
- "Royal marriage alliance transfers territorial control" → territoryGained
- "Territory agrees to join player's confederation" → territoryGained
- "Player wins lawsuit, awarded territory" → territoryGained
- "Player arrives at empty, unclaimed territory" → territoryGained

✓ ADJACENT BUFF (No Capture):
- "Player signs cultural exchange program" → territoryStatChanges (diplomacyThreatStat +20)
- "Player improves relations through diplomatic visit" → territoryStatChanges (diplomacyThreatStat +15)
- "Player sends humanitarian aid" → territoryStatChanges (diplomacyThreatStat +25)

✓ NON-ADJACENT BUFF:
- "Player sends foreign aid to distant territory" → territoryStatChanges (diplomacyThreatStat +20)
- "Player establishes trade route with remote nation" → territoryStatChanges (diplomacyThreatStat +15)

✗ INVALID:
- "Player's aid to non-adjacent territory results in capture" → territoryGained ✗ (should be buff)
- "Player's military pact with non-adjacent territory grants control" → territoryGained ✗ (should be buff)

**Evaluation Process for Friendly Actions:**
1. Check adjacency (adjacent vs non-adjacent)
2. If NON-ADJACENT → Always buff, never capture
3. If ADJACENT:
   a. Check if ANY qualifying agreement occurred (military pact, trade deal, alliance, marriage, union, bases, legal victory, empty territory)
   b. YES → territoryGained
   c. NO → territoryStatChanges (buff)
```

## Testing Strategy

**Unit Tests:**
- `validateTerritoryAdjacency()` with various map configurations
- `transferTerritory()` adjacency blocking
- Territory stat application and clamping
- Target type enforcement logic

**Integration Tests:**
- Full judge pipeline with adjacent capture
- Full judge pipeline with non-adjacent debuff
- Player target blocking territory effects
- Territory deposition flow

**Manual Testing Scenarios:**
1. Adjacent military attack → Should capture
2. Non-adjacent military attack → Should debuff militaryThreatStat, not capture
3. Non-adjacent friendly diplomatic → Should buff diplomacyThreatStat, not capture
4. Target player directly → Should not affect their territories
5. Non-adjacent hostile action with extreme success → Should depose, not capture
6. Adjacent friendly action with trade deal → Should capture
7. Adjacent friendly action with cultural exchange → Should buff, not capture

## Migration Strategy

**Backward Compatibility:**
- No new fields added to Territory (uses existing stats)
- Existing judge results without stat changes work normally
- Adjacency validation logs warnings initially
- Existing territories continue to function

**Rollout:**
1. Deploy data model changes (Phase 1)
2. Deploy code enforcement (Phase 2) - initially log-only mode
3. Deploy stat application (Phase 3)
4. Deploy agent prompts (Phase 4)
5. Enable strict enforcement after testing

## Success Criteria

- ✅ Non-adjacent captures are blocked at code level
- ✅ Territory stats can be modified without capture
- ✅ Targeting player/NPC cannot affect territories
- ✅ Battle victories result in adjacent captures
- ✅ Territory held through turn end results in capture
- ✅ Temporary captures that are lost don't count
- ✅ Non-adjacent hostile actions debuff militaryThreatStat or depose
- ✅ Non-adjacent friendly actions buff diplomacyThreatStat
- ✅ Adjacent friendly actions with qualifying agreements capture
- ✅ All diplomatic capture conditions preserved (military pacts, trade deals, alliances, marriages, unions, bases, legal victories, empty territories)
- ✅ All agents consistently enforce rules in prompts
- ✅ Existing games continue to function
- ✅ Clear logging for all rule enforcement actions
- ✅ Territory stat changes affect assessment agent calculations
