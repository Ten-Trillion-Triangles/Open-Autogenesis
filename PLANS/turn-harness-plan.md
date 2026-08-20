# Turn Harness Implementation Plan

**Summary**
- TurnHarness becomes the central orchestrator for round starts, turn execution, and end-game detection. It will refresh stats, insert NPCs, broadcast turn orders/active actors, manage player response timing, trigger karma/nemesis flows, enforce decay rules, and serialize the world after each turn.
- Execute phase uses a maintained turn-order pointer, checks reachability, starts timers, suspends for PromptManager callbacks, and routes actions to existing player/NPC runners while capturing results for karma, nemesis, decay, and victory checks.
- End-game logic reevaluates domination (75% point-based map control or destruction) after each turn, resolves ties via $1-per-resource scoring, and fires `UiSignalRpcHandlers.broadcastGameOver` once a winner is determined or round 25 is reached.

**Key Additions**
- TurnHarness API: round detection, `routePlayerPlay`, `advanceTurn`, AI takeover hooks, victory evaluation, world serialization via `TPipeConfig.getTraceDir()`.
- UI Rpc: `broadcastActiveTurn`, reusing existing turn-order/nemesis/game-over signals.
- Decay logic: trait-based rules with caps (sen 30/40/60) plus refill amounts, updates applied per round.
- Nemesis hooks: placeholder broadcast + karma-triggered agent spawning, 25% revival chance, summit point stubs.
- Persistence: serialize `WorldManager.world` to `${TPipeConfig.getTraceDir()}/saved-games/<gameId>/world.json` after each turn.

**Tests**
- Unit tests covering round start refreshes, NPC insertion randomization, and RPC payloads.
- Integration tests for execute stage (player response suspension, timers, AI takeover, nemesis path, decay updates).
- Serialization snapshot tests ensure saved files match world state.
- Endgame scenario tests verifying point thresholds, destroyed-territory triggers, resource-based tie-breaker, randomness fallback.

**Assumptions**
- NPCs supply `interferenceChance`; default 0.2 if missing.
- PromptManager continues rejecting off-turn prompts but now immediately hands allowed prompts to TurnHarness.
- Summit points remain at +0 until Summit system exists; placeholder hooks make it easy to flip to +1 later.
- Map domination detection uses `WorldManager.hasOwnerTerritoryPointShare` and `hasDestroyedTerritoryShare`.
- Saving uses TPipeConfig directories as referenced in the `TPipe` repo instructions.