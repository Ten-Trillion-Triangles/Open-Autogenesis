#!/usr/bin/env python3
"""
Autogenesis Subscription Tier Calculator
======================================
Per-turn facts (from trace data):
  Human turn:  846,608 tokens, $0.554, 7.85 min inference
  NPC turn:    816,887 tokens, $0.442, 7.78 min inference

Single-player game cost (human pays for ALL turns):
  1v1:  25 human + 25 NPC turns = 50 turns  =>  41,587,375 tokens, $24.90, 391 min
  1v2:  25 human + 50 NPC turns = 75 turns  =>  62,009,550 tokens, $35.95, 585 min
  1v3:  25 human + 75 NPC turns = 100 turns =>  82,431,725 tokens, $47.00, 780 min

Multiplayer (per player, 25-round game, 4 players):
  Per player: 25 own turns only
  NPC turns: 25/game, shared server pool (covered by inference-hour pool)
  Per player: 21,165,200 tokens, $13.85, 245 min (own 196 + NPC share 49)

Credits: 1 credit = 1K tokens (input OR output)
In-game purchase: ~$0.65 per 1K tokens (avg across all models)
"""

HUMAN_TOKENS = 846_608
NPC_TOKENS   = 816_887
HUMAN_COST   = 0.554
NPC_COST     = 0.442
HUMAN_INF_M = 7.85
NPC_INF_M    = 7.78

# ── Single-player per-game costs ──────────────────────────────────────────────
SP = {
    "1v1":  {"tokens": 25*HUMAN_TOKENS + 25*NPC_TOKENS,
               "cost":  25*HUMAN_COST   + 25*NPC_COST,
               "inf_m": 25*HUMAN_INF_M  + 25*NPC_INF_M},
    "1v2":  {"tokens": 25*HUMAN_TOKENS + 50*NPC_TOKENS,
               "cost":  25*HUMAN_COST   + 50*NPC_COST,
               "inf_m": 25*HUMAN_INF_M  + 50*NPC_INF_M},
    "1v3":  {"tokens": 25*HUMAN_TOKENS + 75*NPC_TOKENS,
               "cost":  25*HUMAN_COST   + 75*NPC_COST,
               "inf_m": 25*HUMAN_INF_M  + 75*NPC_INF_M},
}

# ── Multiplayer per-player per-game costs ────────────────────────────────────
MP_PLAYERS = 4
MP_PER_PLAYER_TURNS = 25
MP_NPC_SHARE_PER_PLAYER_M = (25 * NPC_INF_M) / MP_PLAYERS  # 48.6 min

MP = {
    "per_player": {
        "tokens": MP_PER_PLAYER_TURNS * HUMAN_TOKENS,
        "cost":   MP_PER_PLAYER_TURNS * HUMAN_COST,
        "inf_m":  MP_PER_PLAYER_TURNS * HUMAN_INF_M + MP_NPC_SHARE_PER_PLAYER_M,  # 196 + 49
    }
}
MP_PER_GAME_INF_M = MP["per_player"]["inf_m"]  # 245 min total

print("=== Per-game economics ===")
for name, data in SP.items():
    print(f"  SP {name}: {data['tokens']:,} tokens, ${data['cost']:.2f}, {data['inf_m']:.0f} min inference")
print(f"  MP 4p (per player): {MP['per_player']['tokens']:,} tokens, ${MP['per_player']['cost']:.2f}, {MP['per_player']['inf_m']:.0f} min inference")
print()

# ── Monthly burn rate table ──────────────────────────────────────────────────
# Monthly inference limits and credit pools
tiers = [
    ("Free",    0,        0),
    ("Casual",  10*60,    10_000),   # 10 inf-hrs, 10K credits = 10M tokens
    ("Pro",     30*60,    30_000),   # 30 inf-hrs, 30K credits
    ("Elite",   60*60,    60_000),   # 60 inf-hrs, 60K credits
]

print("=== MONTHLY BURN RATE (games/month) ===")
print()
for tier_name, inf_min, credits_k in tiers:
    cregs_tokens = credits_k * 1000  # credits × 1K = total tokens budget
    print(f"  ── {tier_name}: {inf_min:.0f} min inference + {credits_k:,}K credits ({cregs_tokens:,} tokens) ──")
    if tier_name == "Free":
        print(f"      Demo only. Server-capped.")
        print()
        continue
    for sp_name, sp_data in SP.items():
        games_inf   = inf_min / sp_data["inf_m"]
        games_creds = cregs_tokens / sp_data["tokens"]
        games_total = games_inf + games_creds
        print(f"      SP {sp_name}:  {games_inf:>4.1f} games (inf hrs) + {games_creds:>4.1f} games (credits) = {games_total:>4.1f} total/mo")
    mp_games_inf   = inf_min / MP_PER_GAME_INF_M
    mp_games_creds = cregs_tokens / MP["per_player"]["tokens"]
    mp_games_total = mp_games_inf + mp_games_creds
    print(f"      MP 4p:      {mp_games_inf:>4.1f} games (inf hrs) + {mp_games_creds:>4.1f} games (credits) = {mp_games_total:>4.1f} total/mo")
    print()

# ── Reverse engineer tiers from target game counts ──────────────────────────────
print("=== RECOMMENDED TIER SIZES (from target game counts) ===")
print()
print("Design intent: a player should be able to get meaningful play at their tier.")
print()

# What inference hours give meaningful play?
targets = [
    ("Casual",  "Light play",   3,  2,  5),   # 3 MP games or 2 SP 1v1 or 5 MP
    ("Pro",     "Regular play",  9,  4, 18),   # 9 MP games or 4 SP 1v1 or 18 MP
    ("Elite",   "Heavy play",  20,  9, 40),   # 20 MP games or 9 SP 1v1 or 40 MP
]

# Inference hours needed (credits can cover some too)
for tier, label, mp_target, sp1v1_target, mp_target2 in targets:
    # inf_hrs * 60 / mp_inf_m + cregs*1000 / mp_tokens >= mp_target
    # Need to find inf_hrs and cregs
    # Pick inf_hrs first (round to nice numbers), compute creds from remainder
    inf_hrs_candidates = [5, 10, 15, 20, 30, 40, 50]
    best = None
    for ih in inf_hrs_candidates:
        inf_games = (ih * 60) / MP_PER_GAME_INF_M
        rem_mp = mp_target - inf_games
        if rem_mp <= 0:
            cregs_needed = 0
        else:
            cregs_needed = rem_mp * MP["per_player"]["tokens"] / 1000
        if cregs_needed <= 60:  # reasonable
            best = (ih, round(cregs_needed))
            break
    if best:
        ih, cregs = best
        inf_games_sp1v1 = (ih * 60) / SP["1v1"]["inf_m"]
        total_sp1v1 = inf_games_sp1v1 + cregs * 1000 / SP["1v1"]["tokens"]
        print(f"  {tier} ({label}):  {ih} inference-hrs + {cregs}K credits  →  ~{mp_target} MP games, ~{total_sp1v1:.0f} SP 1v1 games")

print()
print("=== FINAL RECOMMENDED TIERS ===")
print()
print(f"{'Tier':<8}  {'Inf Hrs':>9}  {'Credits':>10}  {'SP 1v1/mo':>10}  {'SP 1v3/mo':>10}  {'MP 4p/mo':>10}")
print("-" * 65)

final_tiers = [
    ("Free",    0,    0),
    ("Casual",  10,   5),     # 10 inf-hrs + 5K credits
    ("Pro",     30,  15),     # 30 inf-hrs + 15K credits
    ("Elite",   60,  30),    # 60 inf-hrs + 30K credits
]

for tier_name, inf_hrs, credits_k in final_tiers:
    inf_min = inf_hrs * 60
    cregs_tokens = credits_k * 1000
    if tier_name == "Free":
        print(f"{tier_name:<8}  {'0':>9}  {'0':>10}  {'0':>10}  {'0':>10}  {'0':>10}")
        continue
    sp1v1 = inf_min / SP["1v1"]["inf_m"] + cregs_tokens / SP["1v1"]["tokens"]
    sp1v3 = inf_min / SP["1v3"]["inf_m"] + cregs_tokens / SP["1v3"]["tokens"]
    mp4p  = inf_min / MP_PER_GAME_INF_M + cregs_tokens / MP["per_player"]["tokens"]
    print(f"{tier_name:<8}  {inf_hrs:>8}h  {credits_k:>9}K  {sp1v1:>10.0f}  {sp1v3:>10.0f}  {mp4p:>10.0f}")

print()
print("=== NOTES ===")
print("  - SP 1v1:  1 human + 1 AI  (50 turns, human pays for all NPC turns)")
print("  - SP 1v3:  1 human + 3 AI  (100 turns, human pays for all NPC turns)")
print("  - MP 4p:   4 human players (25 turns each, NPC turns shared via inf-hr pool)")
print(f"  - Per MP player per game: {MP_PER_GAME_INF_M:.0f} min inference, {MP['per_player']['tokens']:,} tokens")
print(f"  - Per SP 1v1 per game: {SP['1v1']['inf_m']:.0f} min inference, {SP['1v1']['tokens']:,} tokens")
print()
print("  Credit overage: if inference hours run out, credits cover token cost.")
print("  Cost per 1K tokens = ~$0.65 (avg across qwen235B + qwenCoder30B + PalmyraX5)")
print("  Therefore: 5K credits = $3.25 in token budget  |  10K = $6.50  |  15K = $9.75  |  30K = $19.50")