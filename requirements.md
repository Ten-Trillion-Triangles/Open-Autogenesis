# Requirements: Bug Investigation and Fixes

## 1. Streaming & Workstream
- **Problem:** AI players (especially the first one) and numerous other agents are not streaming their internal reasoning to the "workstream" window.
- **Problem:** Streaming to the workstream window can get cut off.
- **Investigation Findings:**
    - `TurnHarness.kt`'s `generateAiAction` and `generateNpcAction` build agents but do not call `streamPipelineOutputToAgentWorkBuffer`.
    - `AgentWorkStreamDispatcher` may not handle reconnections gracefully, as `subscribe` is called only once at the start of a pipeline.
    - `streamPipelineOutputToAgentWorkBuffer` only hooks `pipeCompletionCallback`, meaning output is only sent *after* a pipe finishes, not during.
- **Goal:** Ensure all relevant agents call `streamPipelineOutputToAgentWorkBuffer`. Consider periodic rebinding or heartbeat for the workstream connection.

## 2. Player Feedback during Defense
- **Problem:** Lack of feedback for players when they are defending (Counter-Play).
- **Goal:** Ensure defenders receive clear confirmation that their response was received and is being processed.

## 3. Round Start & Turn Transitions
- **Problem:** At Round 4, the round start screen did not play.
- **Problem:** Turn order skips player turns or displays incorrectly in the UI.
- **Investigation Findings:**
    - `TurnHarness.kt` manages round announcements via `lastAnnouncedRound` and `announcementLock`.
    - `advanceTurnIndexAndRoundIfNeeded` increments round numbers and calls `announceRoundStartIfNeeded`.
- **Goal:** Identify race conditions or logic errors in round/turn advancement and ensure UI signals are reliable.

## 4. AccelByte Records
- **Problem:** Fetching admin records instead fetches game records.
- **Investigation Findings:**
    - `VirtualFileSystem` interface only supports `GameRecord` and `UserRecord`.
    - `CloudVirtualFileSystem` uses `GameRecord.fetchRecord` (Public) for `fetchGameRecord`.
    - AccelByte has `Admin Game Record` and `Admin Player Record` as separate from public versions.
    - `GameRecordResponse` struct incorrectly includes `userId`, which belongs to `PlayerRecordResponse`.
- **Goal:** Disambiguate User, Game, and Admin records in `VirtualFileSystem` and ensure correct AccelByte SDK endpoints are used.

## 5. NPC Turn Resolution & History
- **Problem:** NPC results don't get displayed correctly in the game history window.
- **Problem:** Lord Maple Tree gains maple syrup bombs on the first turn, but the game does not award them.
- **Investigation Findings:**
    - `npcOrchestrator.kt` broadcasts `TurnComplete` history.
    - `WorldManager.applyNpcJudgeResults` handles resource grants.
    - "Maple Syrup Bomb Technology" is used as a mandatory inclusion example in judge prompts.
- **Goal:** Ensure `GameHistory` broadcasts for NPCs are complete and that the reward system correctly handles first-turn bonuses and NPC name matching.

## Probing Questions
1. **Workstream Connectivity:** Should we implement a "re-subscribe" mechanism in the RPC layer to ensure `AgentWorkStreamDispatcher` always has valid connection IDs even if a client temporarily disconnects?
2. **Admin Record Definition:** Can you confirm if "Admin Record" specifically refers to `Admin Game Records` (global settings) or `Admin Player Records` (per-user data readable only by admins)?
3. **Maple Syrup Bombs:** Is there a specific `RewardSystem` that handles turn-based bonuses, or is it entirely handled by the `Judge` agents?
