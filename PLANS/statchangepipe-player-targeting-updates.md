# StatChangePipe Player-Targeting Updates - Implementation Complete

## Summary

Successfully updated statChangePipe to handle friendly player-targeting actions with proportional stat sharing, completing the player-targeting action rules implementation.

## Implementation Date
Monday, February 9, 2026

## Files Modified

1. **judge.kt** - Updated statChangePipe system prompt and context injection

## Changes Implemented

### Prompt Updates

**1. Added PLAYER-TARGETING STAT RULES Section**
- Placed after "##When to decrease stats##" section
- Covers both hostile and friendly player-targeting
- Includes proportional distribution guidelines
- Provides 4 detailed examples

**2. Updated autoInjectContext**
- Added `action_intent` context key explanation
- Added `target_data` context key explanation
- LLM now knows to check these values for player-targeting rules

### Code Enforcement

**No code changes needed** - Existing infrastructure supports new rules:
- `MultiActorStatChanges` output already handles multiple players
- `pullPipelineContext()` already provides access to all necessary context
- No transformation function needed - prompt-based approach sufficient

## Rules Implemented

### Hostile Player-Targeting (Already Covered)

Referenced existing "When to decrease stats" section:
- Resource destruction → Debuff wealth, might, or relevant stats
- NPC destruction → Debuff might, reputation, or relevant stats
- Sabotage/disruption → Debuff affected stats
- Direct attacks → Debuff militaryReadiness, might, or combat stats

### Friendly Player-Targeting (New)

**Proportional Distribution Rules:**
- Default: Distribute stat buffs proportionally based on contribution
- Use narrative cues to determine contribution
- Acting player (turn initiator) typically receives primary/larger buffs
- Target player (helper/cooperator) receives secondary/smaller buffs
- If narrative says "equal split" → Override proportional default

**Contribution Assessment:**
- Primary contributor (usually acting player): 60-70% of total buff value
- Secondary contributor (usually target player): 30-40% of total buff value
- Equal contributors: 50-50 split
- Adjust based on narrative description of each player's role

**Critical Rules:**
- ALWAYS buff both players in friendly player-targeting actions (if action succeeds)
- Use narrative to determine proportional split (default: 60-40 or 70-30)
- Equal split only when narrative explicitly states it
- Acting player typically gets larger share unless narrative says otherwise
- Total buff magnitude should match the significance of the cooperation

## Examples Added to Prompt

### Example 1 - Research Cooperation (Unequal Contribution)
```
Action: "Team up with Player B to research advanced weapons"
Narrative: "Player A led the research while Player B provided funding"
Output:
- Player A: +25 might (primary researcher)
- Player B: +15 wealth (economic contributor, gets wealth for investment value)
```

### Example 2 - Joint Military Operation (Equal Contribution)
```
Action: "Coordinate with Player B for joint strike"
Narrative: "Both players contributed equally to the operation"
Output:
- Player A: +20 might
- Player B: +20 might
```

### Example 3 - Diplomatic Alliance (Proportional)
```
Action: "Form alliance with Player B"
Narrative: "Player A initiated and negotiated, Player B agreed and supported"
Output:
- Player A: +25 reputation (primary negotiator)
- Player B: +15 reputation (supporting partner)
```

### Example 4 - Economic Partnership (Narrative Override)
```
Action: "Create trade agreement with Player B"
Narrative: "Both players agreed to split profits equally"
Output:
- Player A: +20 wealth
- Player B: +20 wealth (equal split per narrative)
```

## Context Available to StatChangePipe

Via `pullPipelineContext()`:
- ✅ `player stats` - Active player's stats
- ✅ `target stats` - Target player's stats (if present)
- ✅ `action_intent` - Hostile or Friendly
- ✅ `target_data` - Target type (Player, NPC, Territory) and names
- ✅ `judge result` - Success/failure of turn
- ✅ `known NPCs` - List of existing characters
- ✅ `classified resources` - Abstract resources needing stat buffs

## Technical Details

### Existing Infrastructure (No Changes Needed)

**MultiActorStatChanges Output:**
```kotlin
// Already supports multiple players
Output a 'MultiActorStatChanges' object containing a map of character names to their 'StatBuff'.
```

**Context Access:**
```kotlin
pullPipelineContext()  // Gets all ContextBank data
```

**System Prompt Structure:**
```
1. Multi-actor support: "for BOTH the active player and any targets"
2. Hostile rules: "When to decrease stats" section
3. NEW: Friendly rules: "PLAYER-TARGETING STAT RULES" section
4. Proportional distribution guidelines
5. 4 detailed examples
```

### Proportional Distribution Approach

**Prompt-Based (No Code Enforcement):**
- LLM interprets narrative cues for contribution assessment
- Default ratios provided (60-40, 70-30, 50-50)
- Examples guide LLM behavior
- Flexible enough to handle edge cases

**Why No Code Enforcement:**
- Contribution is narrative-based and context-dependent
- Hard-coding ratios would be too rigid
- LLM can better assess nuanced contributions
- Examples provide sufficient guidance

## Integration with Gains/Losses Pipe

The statChangePipe works in conjunction with the gains/losses pipe:

**Gains/Losses Pipe:**
- Handles resource distribution (assetsGained, assetExchanges)
- Handles territory distribution (territory to initiator only)
- Handles NPC distribution (NPCs to initiator)

**StatChangePipe:**
- Handles stat buff distribution (proportional for friendly actions)
- Handles stat debuff distribution (for hostile actions)
- Handles multi-actor stat changes

Both pipes receive the same context (`action_intent`, `target_data`) and work together to implement the complete player-targeting rules.

## Compilation Status

✅ **BUILD SUCCESSFUL** - All changes compile without errors

## Testing Recommendations

### Friendly Player-Targeting Scenarios

1. **Unequal Contribution**
   - Action: "Team up with Player B to research tech"
   - Narrative: "Player A led, Player B assisted"
   - Expected: Player A gets larger buff, Player B gets smaller buff

2. **Equal Contribution**
   - Action: "Joint military operation with Player B"
   - Narrative: "Both contributed equally"
   - Expected: Both players get equal buffs

3. **Narrative Override**
   - Action: "Trade agreement with Player B"
   - Narrative: "Agreed to split profits equally"
   - Expected: Both players get equal buffs (override proportional default)

4. **Complex Cooperation**
   - Action: "Multi-phase project with Player B"
   - Narrative: "Player A provided resources, Player B provided expertise"
   - Expected: Different stat types buffed proportionally (A: wealth, B: science)

### Hostile Player-Targeting Scenarios

5. **Resource Destruction**
   - Action: "Sabotage Player B's factory"
   - Expected: Player B gets wealth/might debuff

6. **NPC Destruction**
   - Action: "Assassinate Player B's general"
   - Expected: Player B gets might/reputation debuff

7. **Generic Attack**
   - Action: "Attack Player B"
   - Expected: Player B gets militaryReadiness/might debuff

## Backward Compatibility

- ✅ Existing hostile player-targeting behavior preserved
- ✅ No new data structures required
- ✅ Uses existing MultiActorStatChanges output
- ✅ Prompt additions are additive, not breaking
- ✅ No code changes to transformation functions

## Success Criteria Met

- ✅ Hostile player-targeting stat debuffs (already covered)
- ✅ Friendly player-targeting stat sharing (new)
- ✅ Proportional distribution based on contribution (new)
- ✅ Narrative override for equal splits (new)
- ✅ Both players receive buffs in friendly actions (new)
- ✅ Acting player gets larger share by default (new)
- ✅ Clear examples provided in prompt (new)
- ✅ Context awareness (action_intent, target_data) (new)

## Relationship to Other Changes

This update completes the player-targeting action rules implementation:

**Previously Implemented (gains/losses pipe):**
- Territory blocking for Player/NPC targets
- Resource/NPC destruction rules for hostile actions
- Reward sharing rules for friendly actions (resources/NPCs)

**Now Implemented (statChangePipe):**
- Stat debuff rules for hostile player-targeting
- Stat buff sharing rules for friendly player-targeting
- Proportional distribution guidelines

**Complete System:**
- Gains/losses pipe handles material effects (resources, NPCs, territory)
- StatChangePipe handles stat effects (buffs, debuffs)
- Both pipes use same context (action_intent, target_data)
- Both pipes implement player-targeting rules consistently

## Next Steps

### Immediate Testing Required
1. Test friendly player-targeting with unequal contribution
2. Test friendly player-targeting with equal contribution
3. Test friendly player-targeting with narrative override
4. Test hostile player-targeting stat debuffs
5. Verify both players receive buffs in friendly actions
6. Verify proportional distribution works correctly

### Potential Enhancements
- Add UI indicators for multi-player stat changes
- Add action history events for cooperative actions
- Add stat change visualization for both players
- Add contribution calculation logging for debugging

## Notes

- All changes are prompt-based - no code enforcement needed
- Proportional distribution is narrative-based (LLM interprets)
- Examples guide LLM behavior for edge cases
- Existing multi-actor support handles multiple players
- Context access already available via pullPipelineContext()
- Integration with gains/losses pipe is seamless
