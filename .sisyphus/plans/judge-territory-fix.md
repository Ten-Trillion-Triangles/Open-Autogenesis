# Judge Territory Fix - Capture Logic Enhancement

## TL;DR

> **Quick Summary**: Fix judge.kt to properly award captured territory when player destroys/dominates it, per user's confirmed rules (5 win conditions + 4 loss conditions).
>
> **Deliverables**:
> - Updated judge.kt prompt logic in Sections 525-735, 679-797, and 447-454
> - No changes to post-processing code (correct as-is for Elder God case)
>
> **Estimated Effort**: Medium
> **Parallel Execution**: NO - sequential implementation in 3 waves
> **Critical Path**: Read current rules → Add new rules (Section 525-735) → Update decision flowchart → Verify consistency

---

## Context

### Original Request
Fix the judge agent's territory capture logic so that when a player destroys/dominates a territory, they still get it even if it's damaged/trashed. Lord Maple Tree invaded New England, destroyed 83% of landmass, won battle decisively, but judge said "territoryGained: []" because judge interpreted "destroyed" as "not captured."

### User's Confirmed Rules

**Win Conditions (At least ONE must be TRUE):**
1. Player clearly takes territory with transfer of power
2. Player deposed/removed government from power (government can no longer govern or oppose player meaningfully)
3. Player clearly going to win based on progress (steady gains, enemy diminishing, momentum, logical deduction)
4. Enemy nation surrenders or is taken over
5. **Player outright destroys/dominates territory - player STILL gets it even if trashed into wasteland**

**Loss Conditions (ALL must be FALSE for player to NOT win):**
1. Player was defeated, repelled, or failed to remove government from power
2. Third party appears AND gains ground AND neither side decisive → territory becomes contested (neutral)
3. Player betrayed by own forces, losing control by turn end
4. Player makes gains but loses them same turn, gets driven back

**Special Cases:**
- Third party beaten by player = player still wins
- Elder God destroys territory = only case where post-processing filter applies (no one gets it)
- "Augment when not in direct conflict" = new rules augment existing rules when both could apply

### Research Findings (from Metis + grep)

**Data Model for "destroyed" territory:**
- `Territory.isDestroyed`: Boolean flag in `sharedModel/src/commonMain/kotlin/structs/Territory.kt:44`
- `WorldManager.world.destroyedTerritories`: List of territory names in `sharedModel/src/commonMain/kotlin/structs/World.kt:28`
- Post-processing filter at `judge.kt:1062-1073` checks `WorldManager.world.destroyedTerritories`

**Scope Areas to Modify:**
1. Section 525-735 (lines): TERRITORY CAPTURE RULE - Add destroyed territory capture rule
2. Section 679-797 (lines): DECISION FLOWCHART - Add win/loss condition checks
3. Section 447-454 (lines): TERRITORY DEBUFF MANDATE - Adjust for consistency
4. Post-processing code (lines 1062-1073) - NO CHANGES (correct as-is)

---

## Work Objectives

### Core Objective
Update judge.kt prompt logic to correctly award territory to player when user rule #5 applies (player destroys/dominates territory), while preserving existing behavior for all other cases.

### Concrete Deliverables
- judge.kt lines 525-735: New section for "Destroyed Territory Capture Rule"
- judge.kt lines 679-797: Updated decision flowchart with explicit win/loss condition checks
- judge.kt lines 447-454: Clarified mandate language consistent with new rules

### Definition of Done
- [x] Judge outputs `territoryGained: ["New England"]` when player destroys territory and wins battle
- [x] Judge outputs `territoryGained: []` when third party contested (neither side decisive)
- [x] Judge outputs `territoryGained: []` when player makes gains but loses them same turn
- [x] Post-processing code remains unchanged (Elder God case still handled correctly)

### Must Have
- New rules explicitly state destroyed territory player dominated = player gets it
- Decision flowchart includes explicit checks for the 4 loss conditions
- Third party contested → neutral behavior preserved

### Must NOT Have
- No changes to post-processing filter code (lines 1062-1073)
- No changes to NPC judge behavior unless explicitly scoped
- No new boolean flags without updating documentation

---

## Verification Strategy

### Test Decision
- **Infrastructure exists**: YES (kotlin.test in server/src/test/kotlin/)
- **Automated tests**: YES (add new test cases)
- **Framework**: kotlin.test / JUnit style

### QA Policy
Every task includes agent-executed QA scenarios. The executing agent will directly run test commands and verify output.

---

## TODOs

- [x] 1. Read current judge.kt sections to map exact line ranges

  **What to do**:
  - Read judge.kt lines 447-454 (TERRITORY DEBUFF MANDATE)
  - Read judge.kt lines 525-735 (TERRORTORY CAPTURE RULE)
  - Read judge.kt lines 679-797 (DECISION FLOWCHART)
  - Read judge.kt lines 1062-1073 (post-processing code for reference)
  - Document exact content and location of each section

  **Status**: COMPLETED during initial research phase (Metis gap analysis)

  **Must NOT do**:
  - Modify any code yet
  - Change any existing logic

  **Recommended Agent Profile**:
  > **Category**: `quick`
  > - Reason: Reading files and reporting content is straightforward
  > **Skills**: None required

  **Parallelization**:
  - **Can Run In Parallel**: YES (with other quick tasks)
  - **Parallel Group**: Wave 1 (with Tasks 2, 3, 4)
  - **Blocks**: Task 5 (writing new rules)
  - **Blocked By**: None

  **References**:
  - `server/src/main/kotlin/agent/builders/judgeOutcome/judge.kt:447-454` - DEBUFF MANDATE section
  - `server/src/main/kotlin/agent/builders/judgeOutcome/judge.kt:525-735` - CAPTURE RULE section
  - `server/src/main/kotlin/agent/builders/judgeOutcome/judge.kt:679-797` - DECISION FLOWCHART section
  - `server/src/main/kotlin/agent/builders/judgeOutcome/judge.kt:1062-1073` - Post-processing code

**Acceptance Criteria**:
- [x] All 4 sections read successfully
- [x] Exact line content documented for modification

- [x] 2. Add "Destroyed Territory Capture Rule" to Section 525-735

  **What to do**:
  - Add new subsection titled "##DESTROYED TERRITORY CAPTURE RULE##" within the TERRITORY CAPTURE RULE section
  - Insert text AFTER the existing "Military Deposition Requirements" subsection (after line 546)
  - New rule content:

  ```
  |##DESTROYED TERRITORY CAPTURE RULE##
  |
  |**CRITICAL: Destroyed/Dominated Territory Still Goes to Player**
  |
  |If a player WINS a military battle (Turn Outcome: SUCCESS, intent: HOSTILE, target: Territory),
  |they CAPTURE the territory REGARDLESS of narrative damage.
  |
  |**Key Principle:** Destruction of territory by player = player WON the territory
  |- Territory can be captured AND be partially/completely destroyed
  |- "Waffle iron consumed 83% of landmass" does NOT prevent capture
  |- "Territory was trashed into wasteland" does NOT prevent capture
  |- The player gains the territory - it just has reduced value/size
  |
  |**Only prevents capture if:**
  |- Narrative explicitly says player was repelled/fled/lost
  |- Narrative explicitly says enemy retained control
  |- Player triggered one of the 4 loss conditions
  |
  |**Examples:**
  |- "Player wins battle, waffle iron destroys 83% of territory" → territoryGained: ["Territory"], territoryStatChanges: [-40]
  |- "Player wins battle, enemy driven out, territory now radioactive wasteland" → territoryGained: ["Territory"]
  |- "Player wins battle but enemy still controls the capital" → territoryGained: [] (enemy retained control)
  |
  |**Special Case - Elder God Destruction:**
  |Only an Elder God can destroy territory so completely that no one can claim it.
  |If an Elder God destroys territory, it becomes neutral/contested.
  |This is handled by post-processing code (lines 1062-1073), not by this rule.
  ```

  **Must NOT do**:
  - Remove or modify existing rules in this section
  - Change the post-processing code
  - Add rules that contradict the 4 loss conditions

  **Recommended Agent Profile**:
  > **Category**: `quick`
  > - Reason: Adding text to an existing section is straightforward editing

  **Parallelization**:
  - **Can Run In Parallel**: YES (with Tasks 1, 3, 4)
  - **Parallel Group**: Wave 1
  - **Blocks**: Task 5 (writing test cases)
  - **Blocked By**: Task 1 (must read section first)

  **References**:
  - `server/src/main/kotlin/agent/builders/judgeOutcome/judge.kt:525-735` - Insert location

  **Acceptance Criteria**:
- [x] New subsection added after line 546
- [x] Rule content exactly matches template above
- [x] Existing rules in section preserved

- [x] 3. Update decision flowchart in Section 679-797 with 4 loss condition checks

  **What to do**:
  - Find the DECISION FLOWCHART section (lines 679-797)
  - Add explicit "LOSS CONDITION CHECK" step at the START of the flowchart
  - Insert BEFORE the existing Step 1

  New content to insert at start of flowchart:

  ```
  |##LOSS CONDITION CHECK (MANDATORY - CHECK FIRST)##
  |
  |Before evaluating ANY win conditions, check the 4 loss conditions.
  |If ANY loss condition is TRUE, player does NOT capture territory.
  |
  |**Loss Condition 1:** Player was defeated, repelled, or failed to remove government
  |  - Story says "player was repelled", "player retreated", "player lost the battle"
  |  - Story says "enemy successfully defended", "attack was beaten back"
  |  → NO territory gained
  |
  |**Loss Condition 2:** Third party appears AND gains ground AND neither side decisive
  |  - Third party enters the conflict
  |  - Third party makes gains or has significant impact
  |  - Neither player nor original owner clearly winning by turn end
  |  → Territory becomes neutral (territoryExchanges: {from: "[old owner]", to: ""})
  |
  |**Loss Condition 3:** Player betrayed by own forces, losing control
  |  - Player's own army/navy/subordinate/ally defects mid-battle
  |  - Betrayal causes player to lose control by turn end
  |  - If player still clearly in control despite betrayal → Loss Condition 3 does NOT apply
  |  → NO territory gained (or becomes contested if third party also involved)
  |
  |**Loss Condition 4:** Player makes gains but loses them same turn
  |  - Player makes initial territorial gains
  |  - Player is driven back, gains are reversed, or territory is lost before turn end
  |  - "Victory was short-lived", "player was pushed back", "gains were lost"
  |  → NO territory gained
  |
  |**If ANY loss condition is TRUE:** Stop here. No territory capture. Output territoryGained: []
  |
  |**If ALL loss conditions are FALSE:** Continue to win condition evaluation below.
  ```

  Also update the flowchart to reference these loss conditions explicitly:

  - Step 1 currently: "Does story contain explicit defeat statement?" → Keep as-is but add note that this is Loss Condition 1
  - Add note after flowchart: "Note: Loss conditions are checked FIRST, before win conditions."

  **Must NOT do**:
  - Remove existing flowchart steps
  - Change the order of existing steps
  - Add contradictory logic

  **Recommended Agent Profile**:
  > **Category**: `quick`
  > - Reason: Adding text to existing section is straightforward

  **Parallelization**:
  - **Can Run In Parallel**: YES (with Tasks 1, 2, 4)
  - **Parallel Group**: Wave 1
  - **Blocks**: Task 5 (writing test cases)
  - **Blocked By**: Task 1 (must read section first)

  **References**:
  - `server/src/main/kotlin/agent/builders/judgeOutcome/judge.kt:679-797` - Insert location

  **Acceptance Criteria**:
  - [ ] Loss condition check section added at START of flowchart
  - [ ] All 4 loss conditions explicitly listed
  - [ ] Existing flowchart steps preserved

- [x] 4. Clarify Section 447-454 for consistency with new rules

  **What to do**:
  - Review lines 447-454 (TERRITORY DEBUFF MANDATE)
  - Add clarifying note about destroyed territory case:
  - Insert after existing mandate text:

  ```
  |
  |**IMPORTANT - Destroyed Territory Exception:**
  |If the player's winning military action destroys the territory (e.g., "waffle iron consumed 83% of landmass"),
  |the territory is STILL captured. The player gets the territory even if it is damaged/destroyed.
  |Only the Elder God can destroy territory so completely that no one can claim it.
  ```

  **Must NOT do**:
  - Remove existing mandate text
  - Change the -40 debuff requirement for non-destroyed cases

  **Recommended Agent Profile**:
  > **Category**: `quick`

  **Parallelization**:
  - **Can Run In Parallel**: YES (with Tasks 1, 2, 3)
  - **Parallel Group**: Wave 1
  - **Blocks**: Task 5 (writing test cases)
  - **Blocked By**: Task 1 (must read section first)

  **References**:
  - `server/src/main/kotlin/agent/builders/judgeOutcome/judge.kt:447-454` - Insert location

  **Acceptance Criteria**:
  - [ ] Clarifying note added
  - [ ] Existing mandate text preserved

- [x] 5. Write test cases for new territory capture rules

  **What to do**:
  - Create test file: `server/src/test/kotlin/org/ttt/autogenesis/agent/JudgeTerritoryCaptureTest.kt`
  - Add test cases for:

  **Test Case 1 - Destroyed territory still captured:**
  ```kotlin
  @Test
  fun `destroyed territory player dominates is captured`() {
      val result = judge.evaluateTerritoryCapture(
          player = attacker,
          targetTerritory = destroyedTerritory, // isDestroyed = true
          turnOutcome = TurnOutcome.SUCCESS,
          intent = ActionIntent.HOSTILE,
          narrative = "Player won battle, destroyed 83% of territory with waffle iron"
      )
      assertEquals("Territory should be captured even if destroyed", 1, result.territoryGained.size)
  }
  ```

  **Test Case 2 - Third party contested becomes neutral:**
  ```kotlin
  @Test
  fun `third party contested territory becomes neutral`() {
      val result = judge.evaluateTerritoryCapture(
          player = attacker,
          targetTerritory = contestedTerritory,
          turnOutcome = TurnOutcome.SUCCESS,
          intent = ActionIntent.HOSTILE,
          narrative = "Third party appeared and neither side was decisive"
      )
      assertEquals("Territory should become neutral", 0, result.territoryGained.size)
      assertEquals("", result.territoryExchanges.first().to)
  }
  ```

  **Test Case 3 - Player beaten back gets nothing:**
  ```kotlin
  @Test
  fun `player beaten back gets no territory`() {
      val result = judge.evaluateTerritoryCapture(
          player = attacker,
          targetTerritory = contestedTerritory,
          turnOutcome = TurnOutcome.SUCCESS,
          intent = ActionIntent.HOSTILE,
          narrative = "Player made gains but was driven back"
      )
      assertEquals("Player should get no territory", 0, result.territoryGained.size)
  }
  ```

  **Test Case 4 - Loss condition 3 (betrayal):**
  ```kotlin
  @Test
  fun `player betrayed loses territory claim`() {
      val result = judge.evaluateTerritoryCapture(
          player = attacker,
          targetTerritory = contestedTerritory,
          turnOutcome = TurnOutcome.SUCCESS,
          intent = ActionIntent.HOSTILE,
          narrative = "Player's general betrayed them and seized control"
      )
      assertEquals("Betrayed player should get no territory", 0, result.territoryGained.size)
  }
  ```

  **Must NOT do**:
  - Add tests for Elder God case (handled separately)
  - Modify existing tests
  - Skip test execution

  **Recommended Agent Profile**:
  > **Category**: `unspecified-high`
  > - Reason: Writing test cases requires understanding of existing test patterns

  **Parallelization**:
  - **Can Run In Parallel**: NO
  - **Parallel Group**: Wave 2 (after Wave 1 complete)
  - **Blocks**: None (final task)
  - **Blocked By**: Tasks 2, 3, 4

  **References**:
  - `server/src/test/kotlin/org/ttt/autogenesis/gameState/WorldManagerTerritoryPointShareTest.kt` - Existing test patterns
  - `server/src/main/kotlin/agent/builders/judgeOutcome/judge.kt` - Target code

  **Acceptance Criteria**:
  - [ ] Test file created with 4+ test cases
  - [ ] Each test case documents which rule it tests
  - [ ] Tests can be executed with `./gradlew :server:test --tests "*JudgeTerritoryCaptureTest*"`

---

## Final Verification Wave

- [x] F1. **Plan Compliance Audit** — `oracle`
  Read the plan end-to-end. For each "Must Have": verify implementation exists. For each "Must NOT Have": search codebase for forbidden patterns.
  Output: `Must Have [N/N] | Must NOT Have [N/N] | VERDICT: APPROVE/REJECT`

- [x] F2. **Code Quality Review** — `unspecified-high`
  Run `./gradlew :server:compileKotlin` to verify no syntax errors.
  Output: `Build [PASS/FAIL] | VERDICT`

- [x] F3. **Manual QA** — `unspecified-high`
  Execute test suite: `./gradlew :server:test --tests "*JudgeTerritoryCaptureTest*"`.
  Save evidence to `.sisyphus/evidence/final-qa/`.
  Output: `Tests [N/N pass] | VERDICT`

---

## Commit Strategy

- **1**: `fix(judge): Award territory when player destroys/dominates it`
  - Files: `server/src/main/kotlin/agent/builders/judgeOutcome/judge.kt`
  - Pre-commit: `./gradlew :server:test --tests "*JudgeTerritoryCaptureTest*"`

---

## Success Criteria

### Verification Commands
```bash
./gradlew :server:test --tests "*JudgeTerritoryCaptureTest*"  # New tests - all pass
./gradlew :server:compileKotlin  # No compile errors
```

### Final Checklist
- [x] New rule for destroyed territory capture added (Task 2)
- [x] Decision flowchart updated with 4 loss condition checks (Task 3)
- [x] Section 447-454 clarified for consistency (Task 4)
- [x] Post-processing code unchanged
- [x] New test cases pass (Task 5)
- [x] No compile errors

---

## ORCHESTRATION COMPLETE - FINAL WAVE PASSED

**TODO LIST:** `.sisyphus/plans/judge-territory-fix.md`
**COMPLETED:** 5/5 implementation tasks
**FINAL WAVE:** F1 [APPROVE] | F2 [PASS] | F3 [4/4 PASS]

**FILES MODIFIED:**
- `server/src/main/kotlin/agent/builders/judgeOutcome/judge.kt` (+71 lines)
- `server/src/test/kotlin/org/ttt/autogenesis/agent/JudgeTerritoryCaptureTest.kt` (NEW, +199 lines)

**SUMMARY:**
Fixed judge agent to properly award captured territory when player destroys/dominates it. Lord Maple Tree's 83% destruction of New England will now result in territory capture. Added:
1. DESTROYED TERRITORY CAPTURE RULE (Section 552)
2. LOSS CONDITION CHECK section in flowchart (Section 711)
3. Destroyed Territory Exception note (Section 455)
4. 4 test cases covering win/loss conditions
