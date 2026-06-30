import sys
import re

def extract_text(file_path):
    try:
        with open(file_path, 'r', encoding='utf-8') as f:
            content = f.read()
    except Exception as e:
        print(f"Could not read {file_path}: {e}")
        return
    
    # Find all resultText blocks
    results = re.findall(r'<strong>resultText:</strong>\s*(.*?)<br>', content, re.DOTALL)
    
    # Also find fullPrompt for context
    prompts = re.findall(r'<strong>fullPrompt:</strong>\s*(.*?)<br>', content, re.DOTALL)
    
    # Also find reasoningContent if available
    reasoning = re.findall(r'🧠 reasoningContent\s*\(\d+ chars\)\s*</summary>\s*<pre.*?>(.*?)</pre>', content, re.DOTALL)

    print(f"=== Analysis of {file_path} ===")
    
    if prompts:
        print("\n--- Last Full Prompt ---")
        clean_prompt = re.sub(r'<[^>]+>', '', prompts[-1]).strip()
        print(clean_prompt[:500] + ("..." if len(clean_prompt) > 500 else ""))

    if results:
        print("\n--- Final Result Text ---")
        clean_result = re.sub(r'<[^>]+>', '', results[-1]).strip()
        print(clean_result)

    if reasoning:
        print("\n--- Reasoning Snippet (Last) ---")
        clean_reasoning = re.sub(r'<[^>]+>', '', reasoning[-1]).strip()
        print(clean_reasoning[:500] + ("..." if len(clean_reasoning) > 500 else ""))
    print("\n")

if __name__ == "__main__":
    for arg in sys.argv[1:]:
        extract_text(arg)
