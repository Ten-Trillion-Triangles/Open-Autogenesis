# Gameplay Agent Pipe Reasoning Reference

This reference captures every agent invoked from `server/src/main/kotlin/agent/runners/gameplayOrchestrator.kt`, lists the pipeline (or Bedrock pipe) names it emits, and flags whether that pipe wires in a reasoning pipe (e.g., `setReasoningPipe(...)` or an equivalent `Builder` helper).  Each entry cites the builder file and line range where the pipe is defined so the behavior can be traced back to the source.

> **Reasoning definition**: A pipe is marked as `Yes` when the builder calls `setReasoningPipe(...)` (or inherits a `BedrockPipe` that already has reasoning).  Comments like `//setReasoningPipe(...)` count as `No` unless there is another call that enables reasoning.

---

## buildPlayerAgent (`agent/builders/playerAgent/playerAgent.kt:32-210`)
| Pipe | Reasoning? | Source | Notes |
| --- | --- | --- | --- |
| `Synthesis Stage (${player.name})` | Yes | `playerAgent.kt:44-102` | Calls `setReasoningPipe(BedrockConfig.authorBuilder(...))` (strategic analysis, Qwen coder). |
| `Planning Stage (${player.name})` | Yes | `playerAgent.kt:103-169` | Injects `setReasoningPipe(BedrockConfig.authorBuilder(...))` to keep roleplay reasoning aligned with the plan. |
| `Execution Stage (${player.name})` | Yes | `playerAgent.kt:171-210` | Uses `setReasoningPipe(BedrockConfig.structuredCotBuilder(...))` to ensure the final third-person action follows tactical reasoning. |

## buildValidator (`agent/builders/validateAction/validator.kt:76-575`)
| Pipe | Reasoning? | Source | Notes |
| --- | --- | --- | --- |
| `legality checker pipe` | Yes | `validator.kt:84-254` | Calls `setReasoningPipe(BedrockConfig.structuredCotBuilder())`; also defines an author-builder branch with reasoning. |
| Branch (`PalmyraX5` fallback) | Yes | `validator.kt:254-286` | Branch pipe created by `buildBranchPipeFromTemplate(...).setReasoningPipe(BedrockConfig.authorBuilder(...))`. |
| `legality rectifier pipe` | No | `validator.kt:307-528` | Does not call `setReasoningPipe`; falls back to branch (with reasoning) only when validation fails. |
| `style reapply pipe` | No | `validator.kt:528-575` | Keeps formatting; `setReasoningPipe` is commented out. |

## buildRailroadAgent (`agent/builders/validateAction/railroadAgent.kt:1-110`)
| Pipe | Reasoning? | Source | Notes |
| --- | --- | --- | --- |
| `railroad detection pipe` | No | `railroadAgent.kt:23-63` | `setReasoningPipe` is commented out. |
| Branch (PalmyraX5 fallback) | Yes | `railroadAgent.kt:64-101` | Branch added via `buildBranchPipeFromTemplate(...).setReasoningPipe(BedrockConfig.authorBuilder(...))`. |

## buildPlayDetectionAgent (`agent/builders/validateAction/identifyPlayAgent.kt:42-180`)
| Pipe | Reasoning? | Source | Notes |
| --- | --- | --- | --- |
| `Play Detection Agent` | Yes | `identifyPlayAgent.kt:60-170` | Sets `setReasoningPipe(BedrockConfig.explicitCotBuilder(...))` on the PalmyraX5 detection pipe; no additional pipes. |

## buildTargetDetectorAgent (`agent/builders/validateAction/targetDetectorAgent.kt:1-200` + later sections)
| Pipe | Reasoning? | Source | Notes |
| --- | --- | --- | --- |
| `Target Detector Pipe` | Yes | `targetDetectorAgent.kt:112-210` | `setReasoningPipe(BedrockConfig.explicitCotBuilder(useFlex=false))` on the initial detection pipe. |
| `Universal Target Refinement Pipe` | Yes | `targetDetectorAgent.kt:83-154` | Explicit `setReasoningPipe(BedrockConfig.explicitCotBuilder())` while looking at all candidates. |
| `Target Disambiguation Pipe` | Yes | `targetDetectorAgent.kt:205-260` | Uses `setReasoningPipe(BedrockConfig.processFocusedBuilder())` to choose the final `ActionTargetTypeObj`. |

## buildResponseRefinementAgent (`agent/builders/writingAgent/ResponseRefinementAgent.kt:1-150`)
| Pipe | Reasoning? | Source | Notes |
| --- | --- | --- | --- |
| `Response Detection Pipe` | Yes | `ResponseRefinementAgent.kt:30-88` | Uses `setReasoningPipe(BedrockConfig.explicitCotBuilder())` to identify POV issues. |
| `Response Refinement Pipe` | No | `ResponseRefinementAgent.kt:89-150` | Transforms 1st-person input to 3rd-person; reasoning pipe is not set. |

## Counter-play helpers
| Pipe | Reasoning? | Source | Notes |
| --- | --- | --- | --- |
| `Counter-Response Intent Detector` | No | `validateAction/counterResponseIntentDetector.kt:1-70` | Classification pipe deliberately avoids a reasoning pipe. |
| `buildDefensiveValidator` pipes | No | `validateAction/defensiveValidator.kt:200-360` | Neither the legality checker nor rectifier define `setReasoningPipe`. |

## Neo Writing + Assessment (Splitter in `executeSimulationAndAssessment`)
| Agent | Pipe | Reasoning? | Source | Notes |
| --- | --- | --- | --- | --- |
| `buildNeoWritingAgent` | `guide pipe` | Yes | `writingAgent.kt:124-210` | `setReasoningPipe(BedrockConfig.authorBuilder(...))`. |
|  | `guide branch fail pipe` | Yes | `writingAgent.kt:260-340` | Branch uses the same author builder for retries. |
|  | `distill guide pipe` | Yes | `writingAgent.kt:330-410` | `setReasoningPipe(BedrockConfig.authorBuilder(...))`. |
|  | `selection pipe branch repair` | Yes | `writingAgent.kt:410-480` | Branch replicates reasoning builder. |
|  | `writing pipe` | No | `writingAgent.kt:500-660` | Reasoning builder is commented out (pipe relies on context). |
| `buildAssessmentAgent` | `essay pipe` | No | `geoPoliticsAssessmentAgent.kt:100-220` | This pipe lacks `setReasoningPipe`; it focuses on essay generation. |
|  | `overton window pipe` | Yes | `geoPoliticsAssessmentAgent.kt:230-300` | Calls `setReasoningPipe(BedrockConfig.explicitCotBuilder())`. |
|  | `conflict level pipe` | Yes | `geoPoliticsAssessmentAgent.kt:300-360` | Uses `setReasoningPipe(BedrockConfig.explicitCotBuilder())`. |
|  | `play normalcy pipe` | Yes | `geoPoliticsAssessmentAgent.kt:360-430` | Explicit CoT reasoning. |
|  | `written assessment pipe` | Yes | `geoPoliticsAssessmentAgent.kt:430-520` | Another explicit CoT pipe. |
|  | `numeric scoring pipe` | Yes | `geoPoliticsAssessmentAgent.kt:560-620` | Uses `setReasoningPipe(BedrockConfig.explicitCotBuilder())`. |

## buildPassFailAgent (`agent/builders/passFailAgent/passFailAgent.kt:1-220`)
| Pipe | Reasoning? | Source | Notes |
| --- | --- | --- | --- |
| `pass or fail pipe` | Yes | `passFailAgent.kt:20-120` | Adds `setReasoningPipe(BedrockConfig.structuredCotBuilder())` to evaluate success/failure. |

## buildResourceUsageDetectorAgent (`agent/builders/validateAction/resourceUsageDetectorAgent.kt:1-230`)
| Pipe | Reasoning? | Source | Notes |
| --- | --- | --- | --- |
| `resource detection pipe` | No | `resourceUsageDetectorAgent.kt:23-90` | Reasoning is commented out. |
| `resource detection fallback` | No | `resourceUsageDetectorAgent.kt:120-190` | Fallback also omits reasoning. |

## buildReverseAgent (`agent/builders/modifyGameState/reverseAgent.kt:1-200`)
| Pipe | Reasoning? | Source | Notes |
| --- | --- | --- | --- |
| `reversal-pipe` | No | `reverseAgent.kt:40-160` | Reasoning pipe is commented out; pipeline uses validator + branch for repair. |
| `validator-pipe` | No | `reverseAgent.kt:70-140` | No reasoning. |
| `reversal-repair` | No | `reverseAgent.kt:140-200` | Branch pipe repairs failures without explicit reasoning. |

## buildHardenAgent (`agent/builders/modifyGameState/hardenAgent.kt:1-220`)
| Pipe | Reasoning? | Source | Notes |
| --- | --- | --- | --- |
| `harden story pipe` | No | `hardenAgent.kt:40-150` | No `setReasoningPipe`; fallback Palmyra pipe also lacks reasoning. |

## buildActOfGodAgent (`agent/builders/modifyGameState/actOfGodAgent.kt:1-110`)
| Pipe | Reasoning? | Source | Notes |
| --- | --- | --- | --- |
| `act of god agent` | Yes | `actOfGodAgent.kt:1-80` | Calls `setReasoningPipe(BedrockConfig.authorBuilder(...))` on the single pipe. |

## buildJudge (`agent/builders/judgeOutcome/judge.kt:160-1040`)
| Pipe | Reasoning? | Source | Notes |
| --- | --- | --- | --- |
| `pass or fail pipe` | Yes | `judge.kt:160-240` | Structured CoT reasoning for victory detection. |
| `gains and losses pipe` | Yes | `judge.kt:270-400` | Explicit CoT builder w/ branch for palmyra fallback. |
| `karma pipe` | No | `judge.kt:520-620` | Reasoning commented out. |
| `stat change pipe` | No | `judge.kt:620-760` | Reasoning commented out. |
| `resource classification pipe` | No | `judge.kt:880-1040` | No `setReasoningPipe`. |

## buildNewCharacterScanPipeline (`agent/builders/gatherContext/newcharacterscan.kt:120-980`)
| Pipe | Reasoning? | Source | Notes |
| --- | --- | --- | --- |
| `identify new npc pipe` | Yes | `newcharacterscan.kt:140-220` | Uses `BedrockConfig.structuredCotBuilder()`. |
| `character identify class pipe` | Yes | `newcharacterscan.kt:250-360` | Uses `BedrockConfig.explicitCotBuilder()`. |
| `description builder pipe` | No | `newcharacterscan.kt:360-460` | `setReasoningPipe` commented out. |
| `new npc resource assignment pipe` | Yes | `newcharacterscan.kt:500-620` | Uses `BedrockConfig.processFocusedBuilder()`. |
| `escalation pipe` | Yes | `newcharacterscan.kt:620-760` | `setReasoningPipe(BedrockConfig.processFocusedBuilder())`. |
| `existing resource update pipe` | No | `newcharacterscan.kt:760-900` | No reasoning specified. |
| `detect npc history pipe` | Yes | `newcharacterscan.kt:900-940` | Uses `BedrockConfig.explicitCotBuilder()`. |
| `update history pipe` | Yes | `newcharacterscan.kt:940-980` | Also uses `explictCotBuilder()`. |

## worldUpdatesPipeline (`agent/builders/modifyGameState/worldupdates.kt:1-200`)
| Pipe | Reasoning? | Source | Notes |
| --- | --- | --- | --- |
| `physics changes and map tiles removed pipe` | Yes | `worldupdates.kt:15-80` | Calls `setReasoningPipe(explicitCotBuilder(useFlex=false))`. |

## buildNemesisCreationAgent (`agent/builders/modifyGameState/nemesisCreationBuilder.kt:120-360`)
| Pipe | Reasoning? | Source | Notes |
| --- | --- | --- | --- |
| `player action analysis pipe` | Yes | `nemesisCreationBuilder.kt:120-220` | Uses `setReasoningPipe(BedrockConfig.structuredCotBuilder(useFlex=false))`. |
| `story analysis pipe` | Yes | `nemesisCreationBuilder.kt:220-320` | Applies `setReasoningPipe(BedrockConfig.processFocusedBuilder(useFlex=false))`. |
| `character design pipe` | Yes | `nemesisCreationBuilder.kt:320-420` | Uses `setReasoningPipe(BedrockConfig.structuredCotBuilder())` on the final design output. |

---

If you need an exported CSV/diagram or want to focus on a subset of pipes (e.g., only the ones without reasoning), let me know and I can slice this table accordingly.
