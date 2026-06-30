import os
import re
import json
import sys
from datetime import datetime

def parse_metadata(metadata_str):
    metadata = {}
    # Extract strong tags and their following text
    parts = re.split(r'<br>|<strong>', metadata_str)
    for part in parts:
        if ':' in part:
            match = re.search(r'<strong>(.*?):</strong>\s*(.*)', '<strong>' + part if not part.startswith('<strong>') else part)
            if match:
                key = match.group(1).strip()
                value = match.group(2).strip()
                # Remove any remaining HTML tags from value
                value = re.sub(r'<[^>]+>', '', value)
                metadata[key] = value
    return metadata

def analyze_file(file_path):
    with open(file_path, 'r', encoding='utf-8') as f:
        content = f.read()

    report = {
        "file": file_path,
        "pipes": []
    }

    # Find all table rows
    rows = re.findall(r'<tr.*?>(.*?)</tr>', content, re.DOTALL)
    
    current_pipe = None
    pipe_data = {}

    for row in rows:
        cells = re.findall(r'<td>(.*?)</td>|<td.*?>(.*?)</td>', row, re.DOTALL)
        # cells is a list of tuples [(text, None), (None, html_text), ...]
        row_data = [c[0] if c[0] else c[1] for c in cells]
        
        if len(row_data) < 6:
            continue

        time_offset = row_data[0]
        pipe_name = row_data[1]
        event = row_data[2]
        phase = row_data[3]
        status = re.sub(r'<[^>]+>', '', row_data[4]).strip()
        metadata_raw = row_data[5]

        if pipe_name not in [p['name'] for p in report['pipes']]:
            pipe_entry = {
                "name": pipe_name,
                "model": "Unknown",
                "status": "Unknown",
                "input_tokens": 0,
                "output_tokens": 0,
                "events": []
            }
            report['pipes'].append(pipe_entry)
        
        pipe_entry = next(p for p in report['pipes'] if p['name'] == pipe_name)
        
        metadata = parse_metadata(metadata_raw)
        
        if 'model' in metadata:
            pipe_entry['model'] = metadata['model']
        
        if 'inputTokens' in metadata:
            try:
                pipe_entry['input_tokens'] = max(pipe_entry['input_tokens'], int(metadata['inputTokens']))
            except: pass
        if 'outputTokens' in metadata:
            try:
                pipe_entry['output_tokens'] = max(pipe_entry['output_tokens'], int(metadata['outputTokens']))
            except: pass
        
        if event == 'API_CALL_SUCCESS':
            pipe_entry['status'] = 'SUCCESS'
        elif event == 'PIPE_FAILURE' or event == 'VALIDATION_FAILURE':
            pipe_entry['status'] = 'FAILURE'

        # Capture result text if available
        if 'resultText' in metadata:
             pipe_entry['result'] = metadata['resultText']

    return report

def main():
    trace_dir = os.path.expanduser("~/.tpipe/debug/trace")
    if len(sys.argv) > 1:
        trace_dir = sys.argv[1]

    if not os.path.exists(trace_dir):
        print(f"Error: Trace directory {trace_dir} does not exist.")
        return

    trace_files = []
    for root, dirs, files in os.walk(trace_dir):
        for file in files:
            if file == "trace.html":
                trace_files.append(os.path.join(root, file))

    if not trace_files:
        print("No trace files found.")
        return

    # Sort by modification time, newest first
    trace_files.sort(key=os.path.getmtime, reverse=True)

    print(f"Found {len(trace_files)} trace files. Analyzing the most recent ones...\n")

    for i, file_path in enumerate(trace_files[:5]): # Analyze last 5
        try:
            report = analyze_file(file_path)
            rel_path = os.path.relpath(file_path, trace_dir)
            print(f"--- Trace: {rel_path} ---")
            for pipe in report['pipes']:
                print(f"Pipe: {pipe['name']}")
                print(f"  Model: {pipe['model']}")
                print(f"  Status: {pipe['status']}")
                print(f"  Tokens: In={pipe['input_tokens']}, Out={pipe['output_tokens']}")
                if 'result' in pipe:
                    print(f"  Result: {pipe['result'][:200]}...")
            print("")
        except Exception as e:
            print(f"Error analyzing {file_path}: {e}")

if __name__ == "__main__":
    main()
