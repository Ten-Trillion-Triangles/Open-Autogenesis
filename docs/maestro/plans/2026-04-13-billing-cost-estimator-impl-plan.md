---
title: "Autogenesis 25-Round Game Cost Estimator — Implementation Plan"
created: "2026-04-13T00:00:00Z"
status: "draft"
design_ref: "docs/maestro/plans/2026-04-13-billing-cost-estimator-design.md"
task_complexity: "medium"
---

# 25-Round Game Cost Estimator — Implementation Plan

## Plan Overview

- **Total phases**: 2
- **Agents involved**: data-engineer (research), analytics-engineer (calculation)
- **Estimated effort**: Single Python script computing costs from confirmed pricing and trace data

## Dependency Graph

```
Phase 1 (Pricing + Data) → Phase 2 (Cost Calculation + Chart)
```

## Phase 1: Data Collection & Pricing Lookup

### Objective
Confirm Bedrock pricing for all 3 core gameplay models and document token baseline from trace data.

### Validation
Prices match AWS Bedrock documentation; trace token counts match existing analysis.

## Phase 2: Cost Calculation and Charting

### Objective
Compute per-round and total game costs, output as formatted markdown chart.

### Execution
Single Python script (`game_cost_estimator.py`) using hardcoded pricing and trace-derived token baselines.

### Files to Create

- `game_cost_estimator.py` — cost calculation script producing markdown cost chart

### Implementation Details

```
Pricing (per 1M tokens):
  qwen235B:    in=$0.22  out=$0.88
  qwenCoder30B: in=$0.15  out=$0.60
  PalmyraX5:   in=$0.60  out=$6.00

Trace Baselines (Round 2 LMT):
  Judge:             qwenCoder30B  inp=306,749  out=10,200
  MaintenanceSplit:   qwenCoder30B  inp=195,052  out=23,623
  NeoWritingAgent:    qwen235B      inp=172,852  out=16,323
  ValidationSplit:    qwenCoder30B  inp=35,940   out=5,086
  AnalysisSplit:      qwenCoder30B  inp=25,217   out=3,863
  TargetDetectors:    qwenCoder30B  inp=15,830   out=4,647
  ReversalAgent:      qwen235B      inp=6,583    out=7,187
  LorebookUpdate:     qwen235B      inp=3,117    out=7,628
  WritingAgents:       PalmyraX5     inp=4,972    out=1,739

Context growth model:
  Round 1 input: 418K/turn  (LMT Round 1)
  Round 2 input: 766K/turn  (LMT Round 2)
  Growth: +348K/round
  NPC turns: ~816K input  (Invis Round 2)

Game structure:
  25 rounds × (1 human turn + 1 NPC turn)
  Human: 766K input / 80K output (grows to ~1.4M by round 25)
  NPC: 816K input / 65K output (stable)
```

### Validation
Script output matches hand-calculation for Round 2 LMT baseline ($0.554/human turn, $0.442/NPC turn).

## File Inventory

| # | File | Phase | Purpose |
|---|------|-------|---------|
| 1 | `game_cost_estimator.py` | 2 | Cost calculation and chart output |

## Risk Classification

| Phase | Risk | Rationale |
|-------|------|----------|
| 1 | LOW | Web research already completed; pricing confirmed |
| 2 | LOW | Simple arithmetic; trace data already collected |