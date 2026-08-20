# Bug Investigation Report — May 22, 2026

**Hyperplan Adversarial Investigation Team** (Scout, Analyst, Oracle, Artiste)
**Source:** Log analysis (`~/.autogenesis/logs/`), code inspection, test files

---

## Executive Summary

The 10 reported bugs share **4 root cause clusters**. The primary cluster (Bugs 1, 3, 4, 6, 8, 9) stems from a **`.apply{}` metadata anti-pattern** in `BedrockConfig.authorBuilder()`. Two bugs (5, 10) share **UI timing race conditions**. One bug (7) is an **alert display timing issue**. Bug 2 is a secondary symptom of JSON extraction failures.

**Primary Root Cause (7 bugs):** `showThinking` metadata set via `.apply{}` on returned pipe reference, but the transformation function reads from internal pipe which has empty metadata. This causes silent thinking drops AND actorName propagation failure, which cascades to icon resolution, NPC goal routing, and elder god context.

**HIGH CONFIDENCE bugs:** 3, 4, 6, 8, 10 — proven by test files and explicit warning logs
**MODERATE CONFIDENCE:** 2, 5, 7 — architectural evidence, indirect logs
**REFRAMED:** Bug 1 (NPC strange plays) and Bug 9 (Elder God generic) are downstream consequences of the primary root cause

---

## Root Cause Cluster Map

| Cluster | Root Cause | Bugs Affected | Confidence |
|---------|------------|---------------|------------|
| A | `.apply{}` metadata anti-pattern | 1, 3, 4, 6, 8, 9 | HIGH |
| B | UI timing race condition | 5, 10 | MODERATE |
| C | Alert display timing | 7 | MODERATE |
| D | JSON extraction failure | 2 | MODERATE |

---

## Bug-by-Bug Analysis

### Bug 3, 4, 6: AI Thinking Vanishes / NPC Thinking Not Captured / Reasoning as []

**Status:** ✅ **CONFIRMED — PRIMARY ROOT CAUSE**

**Evidence:**
- 40+ log entries: `[THINKING_CAPTURE] showThinking=false - not capturing thinking for pipe=author`
- `NpcActorShowThinkingPropagationTest.kt:73`: internal reasoning pipe has empty metadata
- `BedrockConfig.kt:592`: `parentPipe.pipeMetadata["showThinking"] as? Boolean ?: false` always returns false

**Root Cause:**
```kotlin
// npcActorAgent.kt:60 — BUGGY PATTERN
setReasoningPipe(BedrockConfig.authorBuilder(...).apply {
    pipeMetadata["showThinking"] = true  // Sets on RETURNED pipe, not internal pipe
})
```
The `apply{}` block modifies metadata on the returned reference. But `authorBuilder()` creates an internal pipe via `reasonWithBedrock()` (BedrockConfig.kt:572). The transformation function (line 583) reads from `pipeContent.currentPipe` which is the internal pipe — with empty metadata.

**Code Path:**
```kotlin
// BedrockConfig.kt:583-593
setTransformationFunction { pipeContent ->
    val parentPipe: Pipe? = pipeContent.currentPipe  // This is internal pipe!
    val agentFlag = parentPipe.pipeMetadata["showThinking"] as? Boolean ?: false
    // agentFlag always false because internal pipe has empty metadata
}
```

**Affected Files:**
- `server/src/main/kotlin/globals/BedrockConfig.kt:582-638`
- `server/src/main/kotlin/agent/builders/gameplayActions/npcActorAgent.kt:60`
- `server/src/main/kotlin/agent/builders/gameplayActions/npcHostileAgent.kt:85,127`
- `server/src/main/kotlin/agent/builders/gameplayActions/nemesisAgent.kt:129,181,230,257`
- `server/src/main/kotlin/agent/builders/gameplayActions/elderGodAgent.kt:94,145`

---

### Bug 8: Character Icons Jumble into Blue Person Icon

**Status:** ✅ **CONFIRMED — DOWNSTREAM OF Bug 3**

**Evidence:**
- 8 explicit log entries: `[BUG6_ICON] -> FALLBACK: No player or NPC match for '$name'`
- `StatsWidget.kt:673`: `activePlayerNames.contains(name)` (case-sensitive)
- `StatsWidget.kt:701`: Falls back to `fas fa-question-circle` → blue person icon via CSS

**Root Cause Chain:**
1. Bug 3 causes `actorName` to NOT be broadcast (showThinking=false)
2. `activePlayerNames` populated from thinking metadata (actorName)
3. When icon resolution runs, name isn't in `activePlayerNames`
4. Name lookup fails → fallback icon → blue person icon

**Alternative cause (case mismatch):**
```kotlin
// StatsWidget.kt:673-674 — EXACT string match
val isPlayer = activePlayerNames.contains(name)  // "Lord Maple Tree" != "Lord maple tree"
val npc = npcMap[name]  // Key not found
```

**Artiste's insight:** This is a CONSEQUENCE of Bug 3's thinking capture failure. The actorName needed for icon identity isn't broadcast.

**Affected File:** `kvisionApp/src/jsMain/kotlin/ui/gameplay/StatsWidget.kt:673-703`

---

### Bug 10: TurnResolutionWidget Stuck on "Updating Lorebook"

**Status:** ✅ **CONFIRMED — STALE LAMBDA RACE CONDITION**

**Evidence:**
- `TurnResolutionWidgetBug9Test.kt:7-16` documents the exact race
- `showUpdateNpcs()` (line 457-471) schedules `scheduleDemoTransition(1500ms)`
- If `showStep(0)` called during 1500ms window, scheduled lambda fires AFTER page changed
- `scheduleDemoTransition` (line 826-848) has NO activeIndex staleness check

**Root Cause:**
```kotlin
// TurnResolutionWidget.kt:457-471
fun showUpdateNpcs() {
    pageStack.activeIndex = 6
    scheduleDemoTransition(1500) {
        // BUG: No check if activeIndex changed during 1500ms delay
        showUpdateWorld()  // Fires even if now on different page!
    }
}
```

**Fix needed:**
```kotlin
private fun scheduleDemoTransition(delayMillis: Long, action: suspend () -> Unit) {
    val capturedIndex = pageStack.activeIndex  // Capture at scheduling time
    demoJob = GlobalScope.launch {
        delay(delayMillis)
        if (pageStack.activeIndex != capturedIndex) {
            Logger.debug("[TurnResolution] Stale demo transition aborted")
            return@launch  // ADDED: Abort if index changed
        }
        action()
    }
}
```

**Affected File:** `kvisionApp/src/jsMain/kotlin/ui/gameplay/TurnResolutionWidget.kt:457-471, 826-848`

---

### Bug 5: Writing UI Stuck on Prior Output After NPC Turn

**Status:** ⚠️ **MODERATE — TIMING-DEPENDENT RACE CONDITION**

**Evidence:**
- `NpcNarrativeChunkThrottlerTest.kt` documents buffer flush race
- 90ms flush delay (`NARRATIVE_STREAM_FLUSH_DELAY_MS`)
- When new turn starts before timer fires, old content remains

**Root Cause:**
```kotlin
// npcOrchestrator.kt:92 — NpcNarrativeChunkThrottler
class NpcNarrativeChunkThrottler(
    private val flushDelayMs: Long = 90L  // 90ms delay before flush
)
```

**Artiste's insight:** Both Bug 5 and Bug 10 are TIMING BUGS — scheduled callbacks fire after state changes. Different trigger points, same anti-pattern.

**Affected File:** `server/src/main/kotlin/agent/runners/npcOrchestrator.kt:92`

---

### Bug 2: JSON Failures During Judgement Stage

**Status:** ⚠️ **MODERATE — SECONDARY SYMPTOM OF Bug 3**

**Evidence:**
- `ExtractJsonStreamingFailureTest.kt` documents JSON extraction failures
- `BedrockConfig.kt:599`: `extractJson<MethodActorResponse>(pipeContent.text) ?: MethodActorResponse()`
- `BedrockConfig.kt:601`: `if(reasoningResponse.isDefault())` causes early return

**Root Cause:**
When JSON is incomplete/truncated, `extractJson` returns a default object (all fields empty). The `isDefault()` check causes thinking NOT to be recorded. If thinking isn't captured, the judge pipe may receive malformed data.

**Affected File:** `server/src/test/kotlin/agent/builders/ExtractJsonStreamingFailureTest.kt`

---

### Bug 7: Nemesis/Elder God Alert Screen Didn't Appear

**Status:** ⚠️ **CONTRADICTED — ALERT IS TRIGGERED, BUT MAY NOT DISPLAY**

**Evidence:**
```
[NETWORK]: [BUG5_INVESTIGATION] showNemesisThreatAnnouncement called for The Syrup Prophet
TurnResolutionWidget: Showing nemesis threat announcement for Round 2 (ARRIVAL)
TurnResolutionWidget: Queued turn order announcement until nemesis threat page completes.
```

**Root Cause (Reframed):**
The alert IS being triggered. The bug is likely one of:
1. Nemesis threat page completes/closes too fast
2. Broadcast arrives before client initializes `nemesisThreatPage`
3. User doesn't notice the alert (display duration issue)

**Affected File:** `kvisionApp/src/jsMain/kotlin/ui/gameplay/TurnResolutionWidget.kt:91-107`

---

### Bug 1: NPC Strange Plays Not Advancing Goals Coherently

**Status:** ⚠️ **REFRAMED — DOWNSTREAM CONSEQUENCE OF Bug 3**

**Analysis:**
The phrase "activate and below" was NOT found as a game action. The real issue:
- `actorName` metadata fails to propagate due to Bug 3
- System can't match NPC actions to intended goals without actorName
- NPCs appear to make "incoherent" decisions because goal context is lost

**Root Cause:** Same as Bug 3 — `.apply{}` metadata anti-pattern causes actorName loss

---

### Bug 9: Elder God AI Broken — Generic Response

**Status:** ⚠️ **REFRAMED — DOWNSTREAM CONSEQUENCE OF Bug 3**

**Analysis:**
- Elder god uses same `.apply{}` pattern (`elderGodAgent.kt:94`)
- `actorName` not propagated → system can't identify WHICH elder god is acting
- Context loss makes response appear "generic"

**Root Cause:** Same as Bug 3 — `.apply{}` metadata anti-pattern

---

## Cross-Bug Pattern: Artiste's Discovery

**The `.apply{}` Metadata Anti-Pattern** affects 7+ bugs:

```kotlin
// ALL NPC AGENTS USE THIS PATTERN (BUGGY)
setReasoningPipe(BedrockConfig.authorBuilder(...).apply {
    pipeMetadata["showThinking"] = true
    pipeMetadata["actorName"] = npcData.name
    pipeMetadata["isPlayer"] = false
})
```

**Cascade Effect:**
1. `showThinking=false` → thinking NOT captured (Bugs 3,4,6)
2. `actorName` NOT propagated → NPC can't route goals (Bug 1)
3. `actorName` NOT propagated → icon identity lost (Bug 8)
4. Context lost → elder god appears generic (Bug 9)

**Single Fix Would Address:** Bugs 1, 3, 4, 6, 8, 9

---

## File Index

| Bug | File | Line(s) |
|-----|------|---------|
| 3, 4, 6, 9 | `server/src/main/kotlin/globals/BedrockConfig.kt` | 582-638 |
| 3, 4 | `server/src/main/kotlin/agent/builders/gameplayActions/npcActorAgent.kt` | 60 |
| 3 | `server/src/main/kotlin/agent/builders/gameplayActions/npcHostileAgent.kt` | 85, 127 |
| 3 | `server/src/main/kotlin/agent/builders/gameplayActions/nemesisAgent.kt` | 129, 181, 230, 257 |
| 3, 9 | `server/src/main/kotlin/agent/builders/gameplayActions/elderGodAgent.kt` | 94, 145 |
| 8 | `kvisionApp/src/jsMain/kotlin/ui/gameplay/StatsWidget.kt` | 673-703 |
| 10 | `kvisionApp/src/jsMain/kotlin/ui/gameplay/TurnResolutionWidget.kt` | 457-471, 826-848 |
| 5 | `server/src/main/kotlin/agent/runners/npcOrchestrator.kt` | 92 |
| 2 | `server/src/test/kotlin/agent/builders/ExtractJsonStreamingFailureTest.kt` | - |
| 7 | `kvisionApp/src/jsMain/kotlin/ui/gameplay/TurnResolutionWidget.kt` | 91-107 |

---

## Test Files Documenting Bugs

| Bug | Test File |
|-----|-----------|
| 3, 4 | `NpcActorShowThinkingPropagationTest.kt` |
| 6 | `Bug4EmptyThoughtProcessTest.kt` |
| 2 | `ExtractJsonStreamingFailureTest.kt` |
| 10 | `TurnResolutionWidgetBug9Test.kt` |
| 7 | `TurnResolutionWidgetBug8Test.kt` |
| 8 | `StatsWidgetBug6Test.kt` |
| 5 | `NpcNarrativeChunkThrottlerTest.kt` |

---

## Recommended Fixes

### Fix 1: Metadata Propagation (Bugs 1, 3, 4, 6, 8, 9) — **PRIMARY**

**Option A:** Copy metadata to internal pipe AFTER `reasonWithBedrock()` returns
```kotlin
val pipe = reasonWithBedrock(...) as BedrockMultimodalPipe
// Copy metadata from returned pipe to internal reasoning pipe
pipe.reasoningPipe?.pipeMetadata?.putAll(pipe.pipeMetadata)
```

**Option B:** Set metadata BEFORE pipe creation and pass as parameters to `authorBuilder()`

**Option C:** Store metadata in shared context accessible to transformation function

### Fix 2: Icon Case Normalization (Bug 8)

```kotlin
// StatsWidget.kt:673-674 — FIX
val isPlayer = activePlayerNames.any { it.equals(name, ignoreCase = true) }
val npc = npcMap.entries.find { it.key.equals(name, ignoreCase = true) }?.value
```

### Fix 3: Stale Lambda Check (Bug 10)

```kotlin
// TurnResolutionWidget.kt:826 — FIX
private fun scheduleDemoTransition(delayMillis: Long, action: suspend () -> Unit) {
    val capturedIndex = pageStack.activeIndex
    demoJob = GlobalScope.launch {
        delay(delayMillis)
        if (pageStack.activeIndex != capturedIndex) {
            Logger.debug("[TurnResolution] Stale demo transition aborted")
            return@launch
        }
        action()
    }
}
```

### Fix 4: Narrative Throttler Flush (Bug 5)

Flush `NpcNarrativeChunkThrottler` buffer immediately on turn transition instead of waiting for 90ms timer.

---

## Confidence Summary

| Bug | Confidence | Evidence Type |
|-----|------------|---------------|
| 3, 4, 6 (thinking vanish) | HIGH | 40+ log entries, test files |
| 8 (icon jumble) | HIGH | 8 explicit FALLBACK logs |
| 10 (TurnResolution stuck) | HIGH | Test file documents race |
| 2 (JSON failures) | MODERATE | Test file, architectural |
| 5 (Writing UI stuck) | MODERATE | Architectural, timing-dependent |
| 7 (alert screen) | MODERATE | Logs show trigger, but display issue |
| 1 (NPC strange plays) | REFRAMED | Downstream of Bug 3 |
| 9 (elder god generic) | REFRAMED | Downstream of Bug 3 |

---

*Report generated by adversarial hyperplan team investigation.*
