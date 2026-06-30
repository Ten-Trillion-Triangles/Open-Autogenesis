---
title: "Autogenesis 25-Round Game Cost Estimator"
created: "2026-04-13T00:00:00Z"
status: "draft"
authors: ["Claude Code"]
type: "design"
design_depth: "standard"
task_complexity: "medium"
---

# Autogenesis 25-Round Game Cost Estimator — Design Document

## Problem Statement

Calculate the dollar cost, inference time, and token counts for a full 25-round game of Autogenesis using real trace data as a baseline and modeling context growth toward model context limits.

## Requirements

1. **Pricing lookup**: Identify per-token AWS Bedrock on-demand pricing for every model used in core gameplay loops
2. **Token accounting**: Aggregate input/output tokens per model from real trace runs (LMT turns + NPC turns)
3. **Context growth modeling**: Model how context (input tokens) grows per turn as game state and story accumulate over 25 rounds, approaching context window limits
4. **Cost output**: Produce a per-agent, per-model, and total cost breakdown chart in dollars

## Architecture

### Models in Scope (from codebase analysis)

| Model | Constant | Role | Price In | Price Out |
|-------|----------|------|----------|-----------|
| qwen.qwen3-235b-a22b-2507-v1:0 | qwen235B | Guide/distillation/reversal/strategic | $0.22/M | $0.88/M |
| qwen.qwen3-coder-30b-a3b-v1:0 | qwenCoder30B | PassFail/gains/losses/validation/target/detection | $0.15/M | $0.60/M |
| arn:aws:...writer.palmyra-x5-v1:0 | PalmyraX5 | Writing/refinement/resource-classification | $0.60/M | $6.00/M |

### Trace Data Baseline (from Round_2_Turn_0_Lord_Maple_Tree — most token-heavy human turn)

| Agent | Calls | Input | Output | % input |
|-------|-------|-------|--------|---------|
| Judge | 18 | 306,749 | 10,200 | 41% |
| MaintenanceSplitter | 36 | 195,052 | 23,623 | 26% |
| NeoWritingAgent | 12 | 172,852 | 16,323 | 23% |
| ValidationSplitter | 18 | 35,940 | 5,086 | 5% |
| AnalysisSplitter | 10 | 25,217 | 3,863 | 3% |
| TargetDetectors | 12 | 15,830 | 4,647 | 2% |
| ReversalAgent | 6 | 6,583 | 7,187 | 1% |
| LorebookUpdate | 2 | 3,117 | 7,628 | <1% |
| WritingAgents | 4 | 4,972 | 1,739 | <1% |
| **TOTAL** | **118** | **766,312** | **80,296** | **100%** |

### Context Growth Model

- **qwen235B / qwenCoder30B context window**: 262K tokens
- **PalmyraX5 context window**: 128K tokens (typical for enterprise models)
- **Round 1 baseline**: ~400K input tokens/turn (from Round 1 LMT trace)
- **Round 2 observed**: ~766K input tokens/turn (from Round 2 LMT trace — already at ~52% of 1.46M combined context across all models)
- **Growth rate**: Linear extrapolation from Round 1→2 data
- **Max round assumption**: Context hits practical limits (~200K per model) around round 10-15, then stabilizes

### 25-Round Game Structure

- 25 rounds × 2 players = 50 turns (1 human + 1 NPC per round)
- NPC turn (Invis) has different pipeline: ~816K tokens/turn vs ~766K human turn
- Total tokens = 25 × (LMT tokens + NPC tokens) = 25 × (766K + 816K input) = ~39.5M input tokens
- Total output = 25 × (80K + 65K) = ~3.6M output tokens

## Approach

1. **Collect**: Web research for Bedrock pricing (confirmed from AWS docs + LiteLLM release notes)
2. **Aggregate**: Per-model token breakdown from trace data (LMT Round 2 = max observed)
3. **Model**: Context growth curve extrapolated from Round 1→2 data, capped at model limits
4. **Calculate**: Cost = Σ(per_model_input × price_in + per_model_output × price_out) for all 25 rounds
5. **Chart**: Markdown table with per-agent costs, per-model costs, and game totals

## Cost Model

### Per-Turn Cost (Round 2 LMT baseline)

| Model | Input Tokens | Output Tokens | Input Cost | Output Cost | Total |
|-------|-------------|--------------|------------|-------------|-------|
| qwenCoder30B | 306,749 | 10,200 | $0.046 | $0.006 | $0.052 |
| qwen235B | 195,052 | 23,623 | $0.043 | $0.021 | $0.064 |
| PalmyraX5 | 264,511 | 46,473 | $0.159 | $0.279 | $0.438 |
| **Per human turn** | **766,312** | **80,296** | **$0.248** | **$0.306** | **$0.554** |

### Per-NPC Turn Cost (Round 2 Invis baseline)

| Model | Input Tokens | Output Tokens | Input Cost | Output Cost | Total |
|-------|-------------|--------------|------------|-------------|-------|
| qwenCoder30B | 334,610 | 12,503 | $0.050 | $0.008 | $0.058 |
| qwen235B | 253,084 | 16,917 | $0.056 | $0.015 | $0.071 |
| PalmyraX5 | 163,945 | 35,828 | $0.098 | $0.215 | $0.313 |
| **Per NPC turn** | **751,639** | **65,248** | **$0.204** | **$0.238** | **$0.442** |

## Agent Team

| Phase | Agent(s) | Parallel | Deliverables |
|-------|----------|---------|--------------|
| 1 | data-engineer | No | Pricing table confirmed, trace data aggregated, growth model defined |
| 2 | analytics-engineer | No | Full cost chart with per-round projections |

## Success Criteria

1. Per-model pricing confirmed from AWS Bedrock documentation
2. Per-agent token counts from real trace data
3. Context growth model shows round-by-round trajectory toward context limits
4. Total game cost in dollars with confidence range (optimistic/pessimistic)
