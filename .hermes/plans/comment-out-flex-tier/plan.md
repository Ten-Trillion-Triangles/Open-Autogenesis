# Comment Out Flex Service Tier in All Agents

**Goal:** Comment out every `setServiceTier(BedrockPriorityTier.Flex)` call across 13 agent files, replacing it with `// setServiceTier(BedrockPriorityTier.Flex)`. One agent (`answerAgent.kt`) already has it commented and requires no action.

**Architecture:** Simple grep + patch per file — each file gets a series of `patch` calls to comment the relevant lines. No logic changes, no test changes, no new files.

**Tech Stack:** Hermes `patch` tool (fuzzy find-replace), `search_files` for verification.

---

## Todo List

```json
[
  {"id": "task-1",  "content": "Comment Flex in reverseAgent.kt (lines 70, 106)", "status": "completed"},
  {"id": "task-2",  "content": "Comment Flex in actOfGodAgent.kt (line 25)", "status": "completed"},
  {"id": "task-3",  "content": "Comment Flex in playerAgent.kt (lines 51, 254)", "status": "completed"},
  {"id": "task-4",  "content": "Comment Flex in newcharacterscan.kt (lines 151, 386, 547, 637, 729, 925)", "status": "completed"},
  {"id": "task-5",  "content": "Comment Flex in npcJudge.kt (lines 219, 597, 773)", "status": "completed"},
  {"id": "task-6",  "content": "Comment Flex in judge.kt (lines 323, 1222, 1280, 1876)", "status": "completed"},
  {"id": "task-7",  "content": "Comment Flex in counterResponseIntentDetector.kt (line 43)", "status": "completed"},
  {"id": "task-8",  "content": "Comment Flex in railroadAgent.kt (line 25)", "status": "completed"},
  {"id": "task-9",  "content": "Comment Flex in validator.kt (lines 500, 680)", "status": "completed"},
  {"id": "task-10", "content": "Comment Flex in targetDetectorAgent.kt (lines 202, 301)", "status": "completed"},
  {"id": "task-11", "content": "Comment Flex in ValidatorPipeAgent.kt (line 40)", "status": "completed"},
  {"id": "task-12", "content": "Comment Flex in defensiveValidator.kt (lines 45, 338)", "status": "completed"},
  {"id": "task-13", "content": "Comment Flex in identifyPlayAgent.kt (line 130)", "status": "completed"},
  {"id": "task-14", "content": "Verify: run search_files for remaining Flex tier calls", "status": "completed"}
]
```

---

## Pre-flight: Verify Baseline

Before starting, confirm the exact lines that will change.

Run: `search_files` across the agent builders directory for `setServiceTier(BedrockPriorityTier.Flex)`

Expected: 28 matches across the 13 files listed above. If the count differs, stop and re-scan.

---

## Task Execution

For each task below, the patch is identical in structure: find the exact line and prepend `// ` to comment it out.

---

### Task 1: Comment Flex in reverseAgent.kt

**Objective:** Comment out 2 Flex tier calls.

**Files:**
- Modify: `server/src/main/kotlin/agent/builders/modifyGameState/reverseAgent.kt:70`
- Modify: `server/src/main/kotlin/agent/builders/modifyGameState/reverseAgent.kt:106`

**Step 1: Patch line 70**

Patch: `mode=replace`, `path=.../reverseAgent.kt`
`old_string=        setServiceTier(BedrockPriorityTier.Flex)`
`new_string=        // setServiceTier(BedrockPriorityTier.Flex)`

**Step 2: Patch line 106**

Patch: same pattern at line 106.

**Step 3: Verify**

Run: `grep -n "setServiceTier(BedrockPriorityTier.Flex)" server/src/main/kotlin/agent/builders/modifyGameState/reverseAgent.kt`
Expected: no output (all commented)

---

### Task 2: Comment Flex in actOfGodAgent.kt

**Objective:** Comment out 1 Flex tier call.

**Files:**
- Modify: `server/src/main/kotlin/agent/builders/modifyGameState/actOfGodAgent.kt:25`

**Step 1: Patch**

Patch: same pattern — `setServiceTier(BedrockPriorityTier.Flex)` → `// setServiceTier(BedrockPriorityTier.Flex)`

**Step 2: Verify**

Run: `grep -n "setServiceTier(BedrockPriorityTier.Flex)" server/src/main/kotlin/agent/builders/modifyGameState/actOfGodAgent.kt`
Expected: no output

---

### Task 3: Comment Flex in playerAgent.kt

**Objective:** Comment out 2 Flex tier calls.

**Files:**
- Modify: `server/src/main/kotlin/agent/builders/playerAgent/playerAgent.kt:51`
- Modify: `server/src/main/kotlin/agent/builders/playerAgent/playerAgent.kt:254`

**Step 1 & 2:** Patch both lines.

**Step 3: Verify**

Run: `grep -n "setServiceTier(BedrockPriorityTier.Flex)" server/src/main/kotlin/agent/builders/playerAgent/playerAgent.kt`
Expected: no output

---

### Task 4: Comment Flex in newcharacterscan.kt

**Objective:** Comment out 6 Flex tier calls.

**Files:**
- Modify: `server/src/main/kotlin/agent/builders/gatherContext/newcharacterscan.kt` at lines 151, 386, 547, 637, 729, 925

**Step 1-6:** Patch all 6 lines. Each occurrence is identical so patch will match the first. Use `replace_all=false` and call patch 6 times sequentially.

**Step 7: Verify**

Run: `grep -n "setServiceTier(BedrockPriorityTier.Flex)" server/src/main/kotlin/agent/builders/gatherContext/newcharacterscan.kt`
Expected: no output

---

### Task 5: Comment Flex in npcJudge.kt

**Objective:** Comment out 3 Flex tier calls.

**Files:**
- Modify: `server/src/main/kotlin/agent/builders/judgeOutcome/npcJudge.kt` at lines 219, 597, 773

**Step 1-3:** Patch all 3 lines.

**Step 4: Verify**

Run: `grep -n "setServiceTier(BedrockPriorityTier.Flex)" server/src/main/kotlin/agent/builders/judgeOutcome/npcJudge.kt`
Expected: no output

---

### Task 6: Comment Flex in judge.kt

**Objective:** Comment out 4 Flex tier calls.

**Files:**
- Modify: `server/src/main/kotlin/agent/builders/judgeOutcome/judge.kt` at lines 323, 1222, 1280, 1876

**Step 1-4:** Patch all 4 lines.

**Step 5: Verify**

Run: `grep -n "setServiceTier(BedrockPriorityTier.Flex)" server/src/main/kotlin/agent/builders/judgeOutcome/judge.kt`
Expected: no output

---

### Task 7: Comment Flex in counterResponseIntentDetector.kt

**Objective:** Comment out 1 Flex tier call.

**Files:**
- Modify: `server/src/main/kotlin/agent/builders/validateAction/counterResponseIntentDetector.kt:43`

**Step 1:** Patch.

**Step 2: Verify**

Run: `grep -n "setServiceTier(BedrockPriorityTier.Flex)" server/src/main/kotlin/agent/builders/validateAction/counterResponseIntentDetector.kt`
Expected: no output

---

### Task 8: Comment Flex in railroadAgent.kt

**Objective:** Comment out 1 Flex tier call.

**Files:**
- Modify: `server/src/main/kotlin/agent/builders/validateAction/railroadAgent.kt:25`

**Step 1:** Patch.

**Step 2: Verify**

Run: `grep -n "setServiceTier(BedrockPriorityTier.Flex)" server/src/main/kotlin/agent/builders/validateAction/railroadAgent.kt`
Expected: no output

---

### Task 9: Comment Flex in validator.kt

**Objective:** Comment out 2 Flex tier calls.

**Files:**
- Modify: `server/src/main/kotlin/agent/builders/validateAction/validator.kt` at lines 500, 680

**Step 1-2:** Patch both.

**Step 3: Verify**

Run: `grep -n "setServiceTier(BedrockPriorityTier.Flex)" server/src/main/kotlin/agent/builders/validateAction/validator.kt`
Expected: no output

---

### Task 10: Comment Flex in targetDetectorAgent.kt

**Objective:** Comment out 2 Flex tier calls.

**Files:**
- Modify: `server/src/main/kotlin/agent/builders/validateAction/targetDetectorAgent.kt` at lines 202, 301

**Step 1-2:** Patch both.

**Step 3: Verify**

Run: `grep -n "setServiceTier(BedrockPriorityTier.Flex)" server/src/main/kotlin/agent/builders/validateAction/targetDetectorAgent.kt`
Expected: no output

---

### Task 11: Comment Flex in ValidatorPipeAgent.kt

**Objective:** Comment out 1 Flex tier call.

**Files:**
- Modify: `server/src/main/kotlin/agent/builders/validateAction/ValidatorPipeAgent.kt:40`

**Step 1:** Patch.

**Step 2: Verify**

Run: `grep -n "setServiceTier(BedrockPriorityTier.Flex)" server/src/main/kotlin/agent/builders/validateAction/ValidatorPipeAgent.kt`
Expected: no output

---

### Task 12: Comment Flex in defensiveValidator.kt

**Objective:** Comment out 2 Flex tier calls.

**Files:**
- Modify: `server/src/main/kotlin/agent/builders/validateAction/defensiveValidator.kt` at lines 45, 338

**Step 1-2:** Patch both.

**Step 3: Verify**

Run: `grep -n "setServiceTier(BedrockPriorityTier.Flex)" server/src/main/kotlin/agent/builders/validateAction/defensiveValidator.kt`
Expected: no output

---

### Task 13: Comment Flex in identifyPlayAgent.kt

**Objective:** Comment out 1 Flex tier call.

**Files:**
- Modify: `server/src/main/kotlin/agent/builders/validateAction/identifyPlayAgent.kt:130`

**Step 1:** Patch.

**Step 2: Verify**

Run: `grep -n "setServiceTier(BedrockPriorityTier.Flex)" server/src/main/kotlin/agent/builders/validateAction/identifyPlayAgent.kt`
Expected: no output

---

### Task 14: Final Verification

**Objective:** Confirm zero remaining uncommented Flex calls across the entire agent builders tree.

Run: `search_files` for `setServiceTier(BedrockPriorityTier.Flex)` across `server/src/main/kotlin/agent/builders/`

Expected: Zero matches.

Also verify `answerAgent.kt` line 57 is still commented (pre-existing, untouched):
Run: `grep -n "setServiceTier" server/src/main/kotlin/agent/builders/systemActions/answerAgent.kt`
Expected: Line 57 shows `// setServiceTier(BedrockPriorityTier.Flex)` — confirm it was already commented before this plan.

---

## Commit

After all tasks complete, commit the changes:

```bash
git add server/src/main/kotlin/agent/builders/
git commit -m "chore: comment out Flex service tier across all agents

Comment setServiceTier(BedrockPriorityTier.Flex) in 13 agent files:
reverseAgent, actOfGodAgent, playerAgent, newcharacterscan, npcJudge,
judge, counterResponseIntentDetector, railroadAgent, validator,
targetDetectorAgent, ValidatorPipeAgent, defensiveValidator, identifyPlayAgent

Flex tier replaced with Standard where needed; answerAgent already had
it commented."
```

---

## Edge Cases

- If a file has already been partially changed (some Flex calls commented, some not), the grep verification will show fewer matches than expected. In that case, note which lines remain and patch only those.
- If a file has duplicate identical lines that aren't the target (e.g. two identical calls at different locations), `patch` with context strings should disambiguate. Add surrounding context lines to the `old_string` to ensure uniqueness.
- `newcharacterscan.kt` has 6 occurrences — call `patch` 6 times sequentially since each call will comment the first remaining match.