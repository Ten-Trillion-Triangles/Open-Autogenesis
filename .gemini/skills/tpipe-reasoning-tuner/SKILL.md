---
name: tpipe-reasoning-tuner
description: Assess TPipe agents, categorize their pipes (Checker, Rectifier, Stylizer, Compliance), and tune their ReasoningDepth and ReasoningDuration settings for optimal speed and accuracy in Autogenesis.
---

# TPipe Reasoning Tuner

This skill is designed to optimize the performance, reliability, and anti-censorship capabilities of AI agents in the Autogenesis project.

## Reasoning Type Selection

TPipe provides multiple reasoning methods. Choose based on the task's complexity and the risk of model refusal:

| Method | Type | Strength | Best Use Case |
| :--- | :--- | :--- | :--- |
| **Author** | `authorBuilder` | **Anti-Censorship** | Narrative, schemes, strategic planning, overcoming model "flinch". |
| **Explicit CoT** | `explicitCotBuilder` | Logical consistency | Intent detection, complex rule checks, reasoning over history. |
| **Structured CoT** | `structuredCotBuilder` | Auditability | Multi-step validation, compliance checks, phase-based analysis. |
| **Process Focused** | `processFocusedBuilder` | Methodology | Resource allocation, administrative logic, data extraction. |

## Workflow

### 1. Assess and Categorize
Analyze the pipe's task:
- **Checker**: Logical validation, binary outcomes, rule enforcement. (Use `Explicit CoT` or `Structured CoT`).
- **Rectifier**: Repairing or rewriting content while preserving intent. (Use `Author` or `Process Focused`).
- **Stylizer**: Formatting, linguistic checks, perspective shifts. (Use `Author` with low settings).
- **Compliance**: Meta-validation; checking if a prior agent followed instructions. (Use `Structured CoT`).

### 2. Tune Settings
Apply the Autogenesis tuning matrix:

| Category | Recommended Depth | Recommended Duration | Justification |
| :--- | :--- | :--- | :--- |
| **Logic/Check** | `High` | `Short` | Rigorous checklist without verbose babbling. |
| **Creative/Repair** | `Med` | `Med` | Balanced complexity for high-quality prose. |
| **Linguistic/POV** | `Low` | `Short` | Minimalist reasoning to ensure format without latency. |
| **Compliance** | `Med` | `Short` | Smart enough to catch hallucinations, fast enough to not block flow. |

## Implementation Patterns

```kotlin
// Example: Author reasoning for high-power strategy
setReasoningPipe(BedrockConfig.authorBuilder(
    author = profile,
    depth = ReasoningDepth.High,
    duration = ReasoningDuration.Short
))

// Example: Explicit CoT for intent classification
setReasoningPipe(BedrockConfig.explicitCotBuilder(
    depthLevel = ReasoningDepth.High,
    durationLevel = ReasoningDuration.Short
))
```

### Important: anti-censorship
`Author reasoning` is the most powerful method for defeating internal model weights. If an agent is refusing tasks due to "safety" or "policy" despite being in a fictional game context, switch its reasoning pipe to `authorBuilder`.

**Technical Mechanism:** TPipe reasoning "punches through" model weights by forcing double or triple recursive predictions. This process generates the necessary narrative data internally, which is then aggressively injected into high-focus areas (like the system prompt or user prompt), effectively "tilting" the model's weights in favor of compliance. This is most effective on models that lack native thinking modes, but can also work on thinking models by turning those native modes off and relying on TPipe's external orchestration.