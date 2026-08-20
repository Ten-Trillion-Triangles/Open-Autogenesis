# Judge Gains/Losses Pipe Prompt Additions

## ADJACENCY CAPTURE RULE

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

## TARGET TYPE RESTRICTIONS

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

## BATTLE VICTORY CAPTURE RULE

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

## FRIENDLY ACTION CAPTURE & BUFF RULES

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