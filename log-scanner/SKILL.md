---
name: log-scanner
description: Python+shell log parsing for Autogenesis. Use when the user wants targeted data from the runtime logs, including the latest file unless they named a specific one, and expects a compact bullet summary of the findings.
---

# Log Scanner

## Purpose
This skill helps you respond to log-related questions by combining Python logic with shell helpers to
(1) locate the relevant logs, (2) extract the user-requested context, and (3) deliver a formatted
bullet-point report. Assume the user is after recent activity unless they specify a file name,
snapshot, or custom directory.

## Workflow
1. **Clarify the target data.** Ask the user what keywords, timestamps, player IDs, categories, or
   error codes they care about, and whether they are interested in a specific log type (server,
   browser, RPC bridge, etc.). Capture any explicit file paths or time ranges they mention.
2. **Find the log directory.** Default to `~/.autogenesis/logs/` (see
   `references/log_locations.md` for the directory layout and file naming). If the user gives a
   different path, respect it; otherwise, let the script select the newest file by modification
   timestamp so you are working with the latest data.
3. **Run `scripts/log_scanner.py`.** Supply the patterns/tokens from step 1 using `--pattern` or
   `-p`, set `--tail` if you only need the tail of the file, and pass `--log-file` when the user named
   one. The script uses Python to orchestrate everything, but it shells out to `ls`/`tail`/`grep` for
   fast extraction and to demonstrate the mixed-mode approach requested.
4. **Capture the script output.** The script emits bullet points about the selected file, the
   recent sibling files, pattern matches (with samples), and a tail snippet. Re-use that structure to
   keep the final answer consistent.
5. **Respond with a bullet-point report.** Summarize what was found, mention the file path and
   modification time, list each pattern with counts and samples, and include any tail snippet or
   directory overview that helps the user understand the context. Close by asking if they need
   deeper slices (more patterns, a different time range, etc.).

## Script usage guidelines
- Run `python log-scanner/scripts/log_scanner.py --pattern ERROR --pattern "playerId=123"` to
  search for multiple tokens at once.
- Add `--tail 50` to see the last 50 lines when the user asks for the latest session context.
- Append `--log-file /path/to/file.log` or `--log-dir /custom/logs` when the default directory or
  file selection needs overriding.
- Use `--recent 3` to let the script list the three newest logs so the user can confirm you picked
  the correct timeframe.
- When you run the script, capture its bullet-point output and weave it directly into your answer.

## Report expectations
- Each response should remain a structured bullet list (not prose paragraphs) so the caller can scan
  the conclusions quickly.
- Start with the chosen file (`- Selected log file: … (last modified …)`).
- Follow with any directory summary (`- Recent candidates: …`).
- For each search pattern, include a bullet like
  `- Pattern "PLAYER_JOIN": 4 hits (sample: …)`.
- If no pattern was supplied, document the tail snippet with its own bullet point.
- End by asking whether the user needs additional slices (different patterns, another log file, etc.).

## References
- `references/log_locations.md` explains the Autogenesis log naming scheme and what each file holds.
