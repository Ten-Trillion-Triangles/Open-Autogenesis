#!/usr/bin/env python3
"""
Autogenesis 25-Round Game Cost Estimator
========================================
Computes per-model, per-agent, and total game costs for a 25-round game
using AWS Bedrock on-demand pricing and real trace data.

Pricing sources:
  - qwen235B:    $0.22/M inp, $0.88/M out  (LiteLLM release notes, AWS Bedrock)
  - qwenCoder30B: $0.15/M inp, $0.60/M out  (AWS Bedrock pricing page)
  - PalmyraX5:   $0.60/M inp, $6.00/M out  (AWS Bedrock pricing page)

Trace sources:
  - Round_2_Turn_0_Lord_Maple_Tree (118 unique calls, 766K inp, 80K out)
  - Round_2_Turn_1_Invis            (76 unique calls, 751K inp, 65K out)
  - Round_1_Turn_0_Lord_Maple_Tree  (118 unique calls, 418K inp, 68K out)
"""

# ──────────────────────────────────────────────────────────────────────────────
# PRICING  (per 1M tokens)
# ──────────────────────────────────────────────────────────────────────────────
PRICE = {
    "qwen235B":     {"in": 0.22,   "out": 0.88},
    "qwenCoder30B":  {"in": 0.15,   "out": 0.60},
    "PalmyraX5":     {"in": 0.60,   "out": 6.00},
}

# ──────────────────────────────────────────────────────────────────────────────
# ROUND 2 LMT — per-agent token breakdown (real trace data, deduplicated)
# ──────────────────────────────────────────────────────────────────────────────
# From Round_2_Turn_0_Lord_Maple_Tree trace analysis:
#   Judge:             qwenCoder30B  inp=306,749  out=10,200   (18 calls, large stat prompts)
#   MaintenanceSplit:  qwenCoder30B  inp=195,052  out=23,623   (36 calls)
#   NeoWritingAgent:  qwen235B      inp=172,852  out=16,323   (12 calls)
#   ValidationSplit:  qwenCoder30B  inp=35,940   out=5,086    (18 calls)
#   AnalysisSplit:    qwenCoder30B  inp=25,217   out=3,863    (10 calls)
#   TargetDetectors:  qwenCoder30B  inp=15,830   out=4,647    (12 calls)
#   ReversalAgent:    qwen235B      inp=6,583    out=7,187    (6 calls)
#   LorebookUpdate:   qwen235B      inp=3,117    out=7,628    (2 calls)
#   WritingAgents:     PalmyraX5     inp=4,972    out=1,739    (4 calls)

ROUND2_LMT = {
    "Judge":            {"model": "qwenCoder30B", "inp": 306749, "out": 10200},
    "MaintenanceSplit": {"model": "qwenCoder30B", "inp": 195052, "out": 23623},
    "NeoWritingAgent":  {"model": "qwen235B",     "inp": 172852, "out": 16323},
    "ValidationSplit":  {"model": "qwenCoder30B", "inp": 35940,  "out": 5086},
    "AnalysisSplitter": {"model": "qwenCoder30B", "inp": 25217,  "out": 3863},
    "TargetDetectors": {"model": "qwenCoder30B", "inp": 15830,  "out": 4647},
    "ReversalAgent":    {"model": "qwen235B",     "inp": 6583,   "out": 7187},
    "LorebookUpdate":   {"model": "qwen235B",     "inp": 3117,   "out": 7628},
    "WritingAgents":     {"model": "PalmyraX5",    "inp": 4972,   "out": 1739},
}

# ──────────────────────────────────────────────────────────────────────────────
# ROUND 2 NPC (Invis) — different pipeline (no MaintenanceSplitter/ValidationSplit)
# NeoWritingAgent here is shared with TurnResolutionSplitter so tokens counted once
# ──────────────────────────────────────────────────────────────────────────────
ROUND2_NPC = {
    "Judge":               {"model": "qwenCoder30B", "inp": 334610, "out": 12503},
    "NeoWritingAgent/Narr": {"model": "qwen235B",    "inp": 253084, "out": 16917},
    "AI_Player_Takeover":  {"model": "qwenCoder30B", "inp": 107713, "out": 12424},
    "AnalysisSplitter":    {"model": "qwenCoder30B", "inp": 25771,  "out": 3955},
    "TargetDetectors":     {"model": "qwenCoder30B", "inp": 16599,  "out": 5294},
    "ReversalAgent":       {"model": "qwen235B",     "inp": 5903,   "out": 6116},
    "LorebookUpdate":      {"model": "qwen235B",     "inp": 2800,   "out": 6312},
    "WritingAgents":       {"model": "PalmyraX5",    "inp": 5159,   "out": 1727},
}

# ──────────────────────────────────────────────────────────────────────────────
# CONTEXT GROWTH MODEL
# Round 1: 418K input/turn (LMT Round 1)
# Round 2: 766K input/turn (LMT Round 2)
# Growth:  +348K per round for human turns
# Cap at: ~1,400K input (practical limit considering 262K context + story accum)
# NPC turns assumed stable at 816K input (AI takeover doesn't grow with story)
# ──────────────────────────────────────────────────────────────────────────────
ROUND1_HUMAN_INP = 418_000
ROUND2_HUMAN_INP = 766_312
GROWTH_PER_ROUND  = ROUND2_HUMAN_INP - ROUND1_HUMAN_INP   # ~348K
NPC_TURN_INP      = 751_639  # stable
HUMAN_OUT_BASE    = 80_296   # Round 2 LMT output
NPC_OUT_BASE      = 65_248   # Round 2 Invis output
MAX_ROUNDS        = 25
CONTEXT_CAP       = 1_400_000  # practical cap on input per human turn

def human_turn_tokens(round_num):
    """Input tokens for a human turn at a given round number."""
    inp = ROUND1_HUMAN_INP + GROWTH_PER_ROUND * (round_num - 1)
    return int(min(inp, CONTEXT_CAP))

def npc_turn_tokens(round_num):
    """NPC turn is stable regardless of round."""
    return NPC_TURN_INP

# ──────────────────────────────────────────────────────────────────────────────
# COST CALCULATION
# ──────────────────────────────────────────────────────────────────────────────
def calc_cost(inp, out, model):
    p = PRICE[model]
    return (inp / 1_000_000) * p["in"] + (out / 1_000_000) * p["out"]

def human_turn_cost(round_num):
    """Total cost for one human turn at given round."""
    # Scale output proportionally with input growth
    scale = human_turn_tokens(round_num) / ROUND2_HUMAN_INP
    total = 0.0
    for agent, data in ROUND2_LMT.items():
        inp = data["inp"]
        out = int(data["out"] * scale)
        total += calc_cost(inp, out, data["model"])
    return total

def npc_turn_cost(round_num):
    """Total cost for one NPC turn (stable)."""
    scale = 1.0  # NPC turns don't grow
    total = 0.0
    for agent, data in ROUND2_NPC.items():
        out = int(data["out"] * scale)
        total += calc_cost(data["inp"], out, data["model"])
    return total

# ──────────────────────────────────────────────────────────────────────────────
# ROUND-BY-ROUND TABLE
# ──────────────────────────────────────────────────────────────────────────────
print("=" * 110)
print(f"{'Round':>5}  {'Human Inp':>12}  {'NPC Inp':>11}  {'Total Inp':>11}  {'Human Cost':>12}  {'NPC Cost':>10}  {'Round Cost':>11}  {'Cumul $':>12}")
print("-" * 110)

total_game = 0.0
total_human_inp = 0
total_npc_inp = 0
total_inp = 0
total_out = 0

rows = []
for r in range(1, MAX_ROUNDS + 1):
    h_inp = human_turn_tokens(r)
    n_inp = npc_turn_tokens(r)
    h_cost = human_turn_cost(r)
    n_cost = npc_turn_cost(r)
    r_cost = h_cost + n_cost
    total_game += r_cost
    total_human_inp += h_inp
    total_npc_inp += n_inp
    total_inp += h_inp + n_inp
    # Output scales with input for human; NPC output is stable
    scale_h = h_inp / ROUND2_HUMAN_INP
    total_out += int(HUMAN_OUT_BASE * scale_h) + NPC_OUT_BASE
    print(f"{r:>5}  {h_inp:>12,}  {n_inp:>11,}  {h_inp+n_inp:>11,}  ${h_cost:>11.4f}  ${n_cost:>9.4f}  ${r_cost:>10.4f}  ${total_game:>11.4f}")
    rows.append((r, h_inp, n_inp, h_cost, n_cost, r_cost))

print("-" * 110)
print(f"{'TOTAL':>5}  {total_human_inp:>12,}  {total_npc_inp:>11,}  {total_inp:>11,}  {'—':>12}  {'—':>10}  ${total_game:>10.4f}  {'—':>12}")
print()

# ──────────────────────────────────────────────────────────────────────────────
# PER-MODEL COST BREAKDOWN
# ──────────────────────────────────────────────────────────────────────────────
print("=" * 90)
print("PER-MODEL COST BREAKDOWN (25-round game)")
print("=" * 90)
print(f"{'Model':<16}  {'Total Input':>14}  {'Total Output':>14}  {'In Cost':>12}  {'Out Cost':>12}  {'Total Cost':>12}")
print("-" * 90)

model_totals = {"qwen235B": {"inp": 0, "out": 0}, "qwenCoder30B": {"inp": 0, "out": 0}, "PalmyraX5": {"inp": 0, "out": 0}}

for r in range(1, MAX_ROUNDS + 1):
    h_inp = human_turn_tokens(r)
    scale = h_inp / ROUND2_HUMAN_INP
    for agent, data in ROUND2_LMT.items():
        m = data["model"]
        model_totals[m]["inp"] += data["inp"]
        model_totals[m]["out"] += int(data["out"] * scale)
    for agent, data in ROUND2_NPC.items():
        m = data["model"]
        model_totals[m]["inp"] += data["inp"]
        model_totals[m]["out"] += int(data["out"] * scale)

grand_inp_cost = 0.0
grand_out_cost = 0.0
for model, totals in model_totals.items():
    inp_cost = (totals["inp"] / 1_000_000) * PRICE[model]["in"]
    out_cost = (totals["out"] / 1_000_000) * PRICE[model]["out"]
    total_cost = inp_cost + out_cost
    grand_inp_cost += inp_cost
    grand_out_cost += out_cost
    print(f"{model:<16}  {totals['inp']:>14,}  {totals['out']:>14,}  ${inp_cost:>11.4f}  ${out_cost:>11.4f}  ${total_cost:>11.4f}")

print("-" * 90)
print(f"{'TOTAL':<16}  {sum(m['inp'] for m in model_totals.values()):>14,}  {sum(m['out'] for m in model_totals.values()):>14,}  ${grand_inp_cost:>11.4f}  ${grand_out_cost:>11.4f}  ${total_game:>11.4f}")
print()

# ──────────────────────────────────────────────────────────────────────────────
# PER-AGENT COST BREAKDOWN
# ──────────────────────────────────────────────────────────────────────────────
print("=" * 100)
print("PER-AGENT COST BREAKDOWN (25-round game, human turns)")
print("=" * 100)
print(f"{'Agent':<22}  {'Model':<14}  {'Inp/Call':>10}  {'Out/Call':>10}  {'Calls':>7}  {'Total Inp':>12}  {'Total Out':>12}  {'Total Cost':>12}")
print("-" * 100)

agent_totals = {}
for agent, data in ROUND2_LMT.items():
    calls = data["out"]  # use out count as proxy for call count ratio
    total_inp_25 = data["inp"] * 25
    scale_avg = sum(human_turn_tokens(r) / ROUND2_HUMAN_INP for r in range(1, 26)) / 25
    total_out_25 = int(data["out"] * 25 * scale_avg)
    cost = calc_cost(total_inp_25, total_out_25, data["model"])
    agent_totals[agent] = cost
    print(f"{agent:<22}  {data['model']:<14}  {data['inp']:>10,}  {data['out']:>10,}  {25:>7}  {total_inp_25:>12,}  {total_out_25:>12,}  ${cost:>11.4f}")

print("-" * 100)
print(f"{'TOTAL (human turns)':<22}  {' ':>14}  {' ':>10}  {' ':>10}  {' ':>7}  {sum(a['inp']*25 for a in ROUND2_LMT.values()):>12,}  {int(sum(a['out']*25*1.03 for a in ROUND2_LMT.values())):>12,}  ${sum(agent_totals.values()):>11.4f}")
print()

# ──────────────────────────────────────────────────────────────────────────────
# INFERENCE TIME ESTIMATE
# ──────────────────────────────────────────────────────────────────────────────
# From trace data: Round 2 LMT = 470.8s total inference (wall-clock across all pipes)
# NPC turn: 466.8s
# Per-call inference is I/O bound so time ~ output_tokens / throughput
# Typical throughput: qwen235B ~50 tok/s, qwenCoder30B ~80 tok/s, PalmyraX5 ~60 tok/s
# Using total output tokens / throughput as inference time estimate

print("=" * 90)
print("INFERENCE TIME ESTIMATE")
print("=" * 90)
print(f"  Round 2 LMT (118 unique calls):  {470.8:.1f}s total wall-clock inference")
print(f"  Round 2 Invis (76 unique calls):   {466.8:.1f}s total wall-clock inference")
print(f"  Per human turn (avg):             ~{(470.8+466.8)/2:.0f}s = ~{((470.8+466.8)/2)/60:.1f} min")
print(f"  Per NPC turn (avg):               ~{466.8:.0f}s = ~{466.8/60:.1f} min")
print(f"  25-round game (50 turns):         ~{25*(470.8+466.8)/60:.0f} min total inference time")
print()

# ──────────────────────────────────────────────────────────────────────────────
# FINAL SUMMARY
# ──────────────────────────────────────────────────────────────────────────────
print("=" * 90)
print("25-ROUND GAME COST SUMMARY")
print("=" * 90)
print(f"  Total input tokens:   {total_inp:>14,}  (~{total_inp/1_000_000:.1f}M)")
print(f"  Total output tokens:  {total_out:>14,}  (~{total_out/1_000_000:.1f}M)")
print(f"  Total inference time: ~{25*(470.8+466.8)/60:.0f} minutes")
print(f"  Total game cost:     ${total_game:>14,.4f}")
print()
print(f"  Optimistic (no growth, R2 baseline every turn):  ${25*(human_turn_cost(2)+npc_turn_cost(2)):.4f}")
print(f"  Pessimistic (context hits cap at R3 every turn):   ${25*(human_turn_cost(25)+npc_turn_cost(2)):.4f}")
print(f"  Expected (linear growth to cap):                  ${total_game:.4f}")
print()
print("  Cost drivers (largest first):")
agent_costs = sorted(agent_totals.items(), key=lambda x: -x[1])
for agent, cost in agent_costs[:5]:
    pct = cost / total_game * 100
    print(f"    {agent:<25}  ${cost:>10.4f}  ({pct:.1f}%)")
print()
print("=" * 90)
