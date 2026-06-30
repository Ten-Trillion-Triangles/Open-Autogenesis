import os
import re


def parse_trace(file_path):
    """
    Extracts structured trace events from a single HTML trace file.

    The trace files come from the Autogenesis pipeline viewer, which renders
    `<tr class="trace-item">` rows with `<td>` cells for timestamp, pipe name,
    event type, category, and optional status/metadata cells. We strip HTML tags
    to keep only textual values so other scripts can reason about errors.
    :param file_path: Absolute path to the `trace.html` exported by the Autogenesis pipeline viewer.
    :return: List of dictionaries representing timestamped trace events.
    """
    with open(file_path, 'r') as f:
        content = f.read()
    
    # Regex to find trace-item rows and their cells
    # <tr id="..." class="trace-item" data-pipe="...">
    # <td>...</td> ... <td>...</td>
    row_pattern = re.compile(r'<tr[^>]*class="trace-item"[^>]*>(.*?)</tr>', re.DOTALL)
    cell_pattern = re.compile(r'<td>(.*?)</td>', re.DOTALL)
    metadata_pattern = re.compile(r'<td class="metadata">(.*?)</td>', re.DOTALL)
    
    events = []
    for row_match in row_pattern.finditer(content):
        row_content = row_match.group(1)
        cells = cell_pattern.findall(row_content)
        metadata_match = metadata_pattern.search(row_content)
        
        if len(cells) >= 4:
            timestamp = cells[0].strip()
            pipe_name = cells[1].strip()
            event_type = cells[2].strip()
            category = cells[3].strip()
            status = cells[4].strip() if len(cells) > 4 else "N/A"
            metadata = metadata_match.group(1).strip() if metadata_match else "N/A"
            
            # Clean HTML tags from fields
            clean = lambda x: re.sub(r'<[^>]*>', '', x).strip()
            
            events.append({
                'timestamp': clean(timestamp),
                'pipe_name': clean(pipe_name),
                'event_type': clean(event_type),
                'category': clean(category),
                'status': clean(status),
                'metadata': clean(metadata)
            })
    return events


def scan_directory(base_dir):
    """
    Walks a trace directory and collects parsed events for every `trace.html`.

    Returns a mapping from the trace file path (relative to `base_dir`) to the
    list of event dictionaries produced by [parse_trace]. This lets callers
    spot failures by looking across every pipeline execution snapshot.
    :param base_dir: Root directory containing pipeline trace subfolders (defaults to `~/.tpipe/debug/trace` in the CLI block).
    :return: Mapping from relative trace file paths to their parsed event lists.
    """
    results = {}
    for root, dirs, files in os.walk(base_dir):
        if 'trace.html' in files:
            file_path = os.path.join(root, 'trace.html')
            relative_path = os.path.relpath(file_path, base_dir)
            events = parse_trace(file_path)
            results[relative_path] = events
    return results

if __name__ == "__main__":
    trace_dir = os.path.expanduser("~/.tpipe/debug/trace")
    all_results = scan_directory(trace_dir)
    
    for path, events in all_results.items():
        print(f"--- Trace: {path} ---")
        # Find failures, refusals, or errors
        errors = [e for e in events if any(x in e['status'].upper() for x in ["ERROR", "FAILURE", "REFUSED", "❌", "⚠"])]
        if errors:
            for err in errors:
                print(f"  {err['status']} at {err['pipe_name']} ({err['event_type']}): {err['metadata'][:300]}...")
        else:
            final_event = events[-1] if events else None
            if final_event:
                print(f"  Final Status: {final_event['status']} at {final_event['pipe_name']}")
            else:
                print("  No events found.")
