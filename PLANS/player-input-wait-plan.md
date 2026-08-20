# Player Input Wait Plan

## Goal
Ensure TurnHarness, gameplayOrchestrator, and npcOrchestrator halt and wait for player input whenever the current actor is a reachable human player. During that wait they should broadcast the `ResolutionStep.START` page so the UI shows "Your Turn To Act" and the "Go To Map" button, and only advance when a submission arrives or the player becomes unreachable/turn timer expires.

## Key Tasks
1. Add `awaitPlayerTurnInput(player)` helper in TurnHarness:
   - Sets `WorldManager.activeTurnActor`, broadcasts `ActiveTurnData` + `ResolutionStep.START`, and starts the shared timer.
   - Suspends on a `CompletableDeferred<PlaySubmission>` completed either by `submitPlayerPlay` or timer/timeout reasoning.
   - On timeout/unreachable, cancel timer and call `handleAiTakeover(actor)` before returning null.
   - Returns the submission so `executeSingleTurn` can continue to `executePlayerTurn`.
2. Modify `executeSingleTurn` to call the helper when the active actor is a player; if it returns null, skip executing the turn and rely on AI takeover flow.
3. Update gameplay/npc orchestrators so they only broadcast `PLAYER_ACTION` after receiving actual input, and they use the shared helper/timer instead of independently continuing when `withTimeoutOrNull` returns null.
4. Adjust demo `/play` blank branch to just call the new helper (not full harness) so it displays START and waits for `/play action` submissions.
5. Ensure `resetDemoWorld` clears per-session trigger flags, and logging reflects the new wait phase.

## Testing
- Blank `/play` in demo mode should show the start page and not trigger AI takeover unless timer expires.
- Submit an action before timeout—confirm harness proceeds through normal action/intents pages.
- Trigger timeout or unreachable path to verify AI takeover kicks in after waiting.

## Assumptions
- The UI handles duplicated `ResolutionStep.START` broadcasts gracefully.
- Shared timer stays authoritative; orchestrators rely on it rather than running their own.
- Demo mode uses Shepard as the active player and should retain the new trigger guard.