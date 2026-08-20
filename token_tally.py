import os
import json

def compare_hypothetical_costs():
    trace_root = os.path.expanduser("~/.tpipe/debug/trace/")
    turns = [d for d in os.listdir(trace_root) if os.path.isdir(os.path.join(trace_root, d)) and d != "saved-games"]
    turns.sort()

    # Current pricing (per 1M tokens)
    current_pricing = {
        "qwen.qwen3-235b-a22b-2507-v1:0": {"in": 0.1133, "out": 0.4532},
        "qwen.qwen3-coder-30b-a3b-v1:0": {"in": 0.07725, "out": 0.3090},
        "writer.palmyra-x5-v1:0": {"in": 0.60, "out": 6.00}
    }

    # Hypothetical pricing: Replace 30B with 9B ($0.05 In / $0.08 Out)
    hypo_pricing = {
        "qwen.qwen3-235b-a22b-2507-v1:0": {"in": 0.1133, "out": 0.4532},
        "qwen.qwen3-coder-30b-a3b-v1:0": {"in": 0.05, "out": 0.08}, # Hypothetical 9B
        "writer.palmyra-x5-v1:0": {"in": 0.60, "out": 6.00}
    }

    total_current = 0.0
    total_hypo = 0.0

    print(f"{'Turn Name':<45} | {'Current ($)':>10} | {'Hypo 9B ($)':>10} | {'Savings (%)':>10}")
    print("=" * 85)

    for turn in turns:
        turn_path = os.path.join(trace_root, turn)
        json_files = []
        for root, dirs, files in os.walk(turn_path):
            for file in files:
                if file == "trace.json":
                    json_files.append(os.path.join(root, file))
        
        calls = {}
        for json_file in json_files:
            try:
                with open(json_file, 'r') as f:
                    events = json.load(f)
                    for event in events:
                        if event.get("eventType") != "API_CALL_SUCCESS":
                            continue
                        pipe_id = event.get("pipeId")
                        timestamp = event.get("timestamp")
                        metadata = event.get("metadata", {})
                        it = metadata.get("inputTokens")
                        ot = metadata.get("outputTokens")
                        model_id = metadata.get("modelId") or metadata.get("model") or ""
                        
                        short_id = ""
                        if "qwen3-235b" in model_id: short_id = "qwen.qwen3-235b-a22b-2507-v1:0"
                        elif "qwen3-coder-30b" in model_id: short_id = "qwen.qwen3-coder-30b-a3b-v1:0"
                        elif "palmyra-x5" in model_id: short_id = "writer.palmyra-x5-v1:0"
                        
                        call_key = (pipe_id, timestamp // 1000)
                        if call_key not in calls:
                            calls[call_key] = {"model": short_id, "input": 0, "output": 0}
                        
                        if it and isinstance(it, (int, float)):
                            calls[call_key]["input"] = max(calls[call_key]["input"], int(it))
                        if ot and isinstance(ot, (int, float)):
                            calls[call_key]["output"] = max(calls[call_key]["output"], int(ot))
                        if short_id:
                            calls[call_key]["model"] = short_id
            except Exception:
                pass

        turn_curr = 0.0
        turn_hypo = 0.0
        for call in calls.values():
            m = call["model"]
            pc = current_pricing.get(m, {"in": 0, "out": 0})
            ph = hypo_pricing.get(m, {"in": 0, "out": 0})
            
            turn_curr += (call["input"] / 1_000_000 * pc["in"]) + (call["output"] / 1_000_000 * pc["out"])
            turn_hypo += (call["input"] / 1_000_000 * ph["in"]) + (call["output"] / 1_000_000 * ph["out"])

        savings = ((turn_curr - turn_hypo) / turn_curr * 100) if turn_curr > 0 else 0
        print(f"{turn:<45} | {turn_curr:>10.4f} | {turn_hypo:>10.4f} | {savings:>10.1f}%")
        total_current += turn_curr
        total_hypo += turn_hypo

    print("=" * 85)
    total_savings = ((total_current - total_hypo) / total_current * 100) if total_current > 0 else 0
    print(f"{'GRAND TOTAL':<45} | {total_current:>10.4f} | {total_hypo:>10.4f} | {total_savings:>10.1f}%")
    print(f"\nAverage cost per turn (Current): ${total_current/len(turns):.4f}")
    print(f"Average cost per turn (Hypo 9B): ${total_hypo/len(turns):.4f}")

if __name__ == "__main__":
    compare_hypothetical_costs()