#!/usr/bin/env python3
import json
import os
import sys
from collections import defaultdict

target = sys.argv[1] if len(sys.argv) > 1 else "Round_2_Turn_1_Invis"
trace_dir = os.path.expanduser(f"~/.tpipe/debug/trace/{target}")

trace_files = []
for root, dirs, files in os.walk(trace_dir):
    if "trace.json" in files:
        trace_files.append(os.path.join(root, "trace.json"))

def rel_path(p):
    return p.replace(trace_dir + "/", "")

file_calls = {}
file_inf_ms = {}

for tf in sorted(trace_files):
    with open(tf) as f:
        content = f.read()
    try:
        events = json.loads(content)
    except:
        continue
    if not isinstance(events, list):
        continue

    all_evts = []
    for e in events:
        if not isinstance(e, dict):
            continue
        if e.get("eventType") != "API_CALL_SUCCESS":
            continue
        pipe_id = e.get("pipeId", "?")
        meta = e.get("metadata", {})
        inp = meta.get("inputTokens", 0)
        out = meta.get("outputTokens", 0)
        model = meta.get("modelId", "?")
        ts = e.get("timestamp", 0)
        is_primary = inp > 0 or out > 0
        all_evts.append({"pipeId": pipe_id, "inp": inp, "out": out,
                          "model": model, "ts": ts, "is_primary": is_primary})

    all_evts.sort(key=lambda x: (x["ts"], not x["is_primary"]))

    seen_keys = set()
    calls = []
    prev_ts = None
    total_inf = 0

    for e in all_evts:
        key = (e["pipeId"], e["inp"], e["out"], e["model"])
        if e["is_primary"]:
            if key not in seen_keys:
                calls.append(e)
                seen_keys.add(key)
                if prev_ts is not None:
                    diff = e["ts"] - prev_ts
                    if 0 < diff < 300000:
                        total_inf += diff
                prev_ts = e["ts"]

    file_calls[tf] = calls
    file_inf_ms[tf] = total_inf

unique_calls = {}
for tf, events in file_calls.items():
    for e in events:
        key = (e["pipeId"], e["inp"], e["out"], e["model"])
        if key not in unique_calls:
            unique_calls[key] = {
                "pipeId": e["pipeId"],
                "inputTokens": e["inp"],
                "outputTokens": e["out"],
                "model": e["model"],
                "files": set()
            }
        unique_calls[key]["files"].add(rel_path(tf))

total_inf = sum(file_inf_ms.values())

def categorize(pipe_id, files):
    bases = [f.split("/")[0] for f in files]
    for base in set(bases):
        if base in ("WritingAgents", "TargetDetectors", "Judge", "AnalysisSplitter",
                    "ReversalAgent", "LorebookUpdate", "AI_Player_Takeover", "NeoWritingAgent",
                    "MaintenanceSplitter", "ValidationSplitter", "TurnResolutionSplitter"):
            return base
    return "Other"

by_cat = defaultdict(lambda: {"calls": 0, "input": 0, "output": 0, "pipes": set()})
for call in unique_calls.values():
    cat = categorize(call["pipeId"], call["files"])
    by_cat[cat]["calls"] += 1
    by_cat[cat]["input"] += call["inputTokens"]
    by_cat[cat]["output"] += call["outputTokens"]
    by_cat[cat]["pipes"].add(call["pipeId"])

total_tokens = sum(c["inputTokens"] + c["outputTokens"] for c in unique_calls.values())
for cat in by_cat:
    cat_tokens = by_cat[cat]["input"] + by_cat[cat]["output"]
    by_cat[cat]["inf_ms"] = int(total_inf * cat_tokens / total_tokens) if total_tokens > 0 else 0

print(f"Trace Analysis — {target}")
print(f"Location: {trace_dir}")
print(f"Files: {len(file_calls)} | Unique calls: {len(unique_calls)}")
print(f"\n{'Agent':<30} {'Calls':>6} {'Input':>12} {'Output':>12} {'Infr.Time':>12}")
print("="*80)
tc, ti, to, tinf = 0, 0, 0, 0
for cat in sorted(by_cat.keys(), key=lambda c: -(by_cat[c]["input"] + by_cat[c]["output"])):
    d = by_cat[cat]
    tc += d["calls"]; ti += d["input"]; to += d["output"]; tinf += d["inf_ms"]
    inf = f"{d['inf_ms']/1000:.1f}s" if d["inf_ms"] > 0 else "-"
    print(f"{cat:<30} {d['calls']:>6} {d['input']:>12,} {d['output']:>12,} {inf:>12}")
print("-"*80)
inf = f"{tinf/1000:.1f}s" if tinf > 0 else "-"
print(f"{'TOTAL':<30} {tc:>6} {ti:>12,} {to:>12,} {inf:>12}")
print(f"\n{'Combined tokens':>43} {ti+to:>12,}")
print(f"{'Total inference time':>43} {inf:>12}")
inf_min = f"{tinf/1000/60:.2f}m" if tinf > 0 else "-"
print(f"{'In minutes':>43} {inf_min:>12}")
