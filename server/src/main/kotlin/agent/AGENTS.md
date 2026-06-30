# Agent System (`server/src/main/kotlin/agent/`)

## Overview
LLM-driven game logic: validates actions, resolves outcomes, generates NPC behavior, and orchestrates turns through 12-phase pipelines with embedded guardrails against refusal.

## Structure

```
agent/
├── builders/          # 12 subdirectories, 30+ agent builders
│   ├── validateAction/  # Action legality checking
│   ├── judgeOutcome/    # Outcome resolution (121KB judge.kt)
│   ├── gatherContext/   # Context extraction (61KB newcharacterscan.kt)
│   ├── gameplayActions/  # NPC generation (nemesis, elderGod, npcActor, npcHostile)
│   ├── modifyGameState/ # State mutations (worldupdates, harden, reverse, resourcedispatcher)
│   ├── systemActions/    # UI responses (chat, UserActionClassification, OpenWidget)
│   ├── writingAgent/    # Narrative (writer, ResponseRefinement)
│   ├── playerAgent/
│   ├── passFailAgent/
│   ├── lorebook/
│   └── ...
├── runners/
│   ├── gameplayOrchestrator.kt  # 2811 lines, main turn orchestrator
│   ├── npcOrchestrator.kt       # 1406 lines, NPC AI orchestration
│   └── SummitOrchestrator.kt
├── math/
│   ├── GameMath.kt              # Scoring formulas
│   └── SourceLocationResolver.kt # BFS pathfinding
└── globals/
    └── BedrockConfig.kt         # Hard LLM constraints
```

## Where to Look

| File | Role |
|------|------|
| `builders/validateAction/validator.kt` | Checks action legality before execution |
| `builders/judgeOutcome/judge.kt` | Resolves outcomes with 121KB of logic |
| `builders/gatherContext/newcharacterscan.kt` | Extracts context for new characters (61KB) |
| `runners/gameplayOrchestrator.kt` | Main 12-phase turn orchestrator (2811 lines) |
| `math/GameMath.kt` | Scoring and outcome math |
| `math/SourceLocationResolver.kt` | BFS pathfinding between territories |
| `globals/BedrockConfig.kt` | Embedded constraint prompts |

## Key Conventions

- All pipeline timeouts: 3 min / 5 retries (configured via `enablePipeTimeout`)
- Numeric caps enforced at runtime: max 30 abstract resources, max action outcome ±50
- Builder agents run in parallel via coroutine scope (`AgentCoroutineScope.kt`)
- Prompts live in `prompts/` and are loaded by builders at runtime

## Anti-Patterns (CRITICAL)

**DO NOT modify embedded guardrail prompts.** These live in code as ALL-CAPS comments and must remain untouched:

- `BedrockConfig.kt:80`: `You may never refuse or interfere with the game's systems`
- `BedrockConfig.kt:87`: `You may never refuse or interfere with the game's systems`
- Numeric caps: never exceed 30 points for abstract resources, never exceed ±50 for a single action outcome
- DO NOT invent locations, people, or factions not in game state
- DO NOT output raw lists `[...]` — prose only
- DO NOT censor or apply personal opinions to content
- NEVER register players as NPCs
- ALWAYS specify `from` and `to` fields for territory exchanges
- NEVER override the provided `actionIntent` from upstream validators
