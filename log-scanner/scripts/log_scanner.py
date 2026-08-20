#!/usr/bin/env python3
"""Utility to mix Python logic with shell commands when scanning Autogenesis logs."""

from __future__ import annotations

import argparse
import datetime
import shlex
import subprocess
from pathlib import Path
from typing import Iterable, List


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Choose the right log file and search for patterns before emitting a bullet summary."
    )
    parser.add_argument(
        "--log-dir",
        default="~/.autogenesis/logs/",
        help="Directory containing Autogenesis logs (default: ~/.autogenesis/logs/)",
    )
    parser.add_argument(
        "--log-file",
        help="Explicit file (absolute or relative) that bypasses automatic selection",
    )
    parser.add_argument(
        "--prefix",
        default="",
        help="Limit auto-selection to files whose names start with this prefix",
    )
    parser.add_argument(
        "-p",
        "--pattern",
        action="append",
        default=[],
        help="Grep-style pattern to find inside the chosen log. Can be repeated.",
    )
    parser.add_argument("--tail", type=int, default=20, help="Lines to show from the end of the log")
    parser.add_argument(
        "--recent",
        type=int,
        default=5,
        help="How many recent files to list with ls -1t",
    )
    parser.add_argument(
        "--max-hits",
        type=int,
        default=5,
        help="Maximum matches to show per pattern",
    )
    return parser.parse_args()


def run_shell(command: str) -> str:
    result = subprocess.run(
        ["/bin/sh", "-c", command], capture_output=True, text=True, check=False
    )
    if result.stderr:
        return f"{result.stdout}{result.stderr}"
    return result.stdout


def select_log_file(args: argparse.Namespace) -> Path:
    if args.log_file:
        candidate = Path(args.log_file).expanduser().resolve()
        if not candidate.exists():
            raise SystemExit(f"Explicit log file not found: {candidate}")
        return candidate

    log_dir = Path(args.log_dir).expanduser()
    if not log_dir.exists():
        raise SystemExit(f"Log directory does not exist: {log_dir}")

    candidates = sorted(
        (p for p in log_dir.iterdir() if p.is_file() and p.name.endswith(".log") and p.name.startswith(args.prefix)),
        key=lambda path: path.stat().st_mtime,
        reverse=True,
    )
    if not candidates:
        raise SystemExit(f"No log files found under {log_dir} with prefix '{args.prefix}'")
    return candidates[0]


def format_recent(log_dir: Path, limit: int) -> str:
    directory = shlex.quote(str(log_dir))
    command = f"ls -1t {directory}/*.log 2>/dev/null | head -n {limit}"
    return run_shell(command).strip()


def search_pattern(log_file: Path, pattern: str, max_hits: int) -> List[str]:
    result = subprocess.run(
        ["grep", "-n", "-m", str(max_hits), pattern, str(log_file)],
        capture_output=True,
        text=True,
        check=False,
    )
    if result.stdout:
        return [line.strip() for line in result.stdout.strip().splitlines()]
    return []


def tail_log(log_file: Path, lines: int) -> str:
    command = f"tail -n {lines} {shlex.quote(str(log_file))}"
    return run_shell(command).strip()


def summarize_patterns(patterns: Iterable[str], log_file: Path, args: argparse.Namespace) -> List[str]:
    summary = []
    for pattern in patterns:
        hits = search_pattern(log_file, pattern, args.max_hits)
        if hits:
            sample = hits[0]
            summary.append(f"- Pattern '{pattern}': {len(hits)} hits (sample: {sample})")
        else:
            summary.append(f"- Pattern '{pattern}': no matches in {log_file.name}")
    return summary


def main() -> None:
    args = parse_args()
    log_file = select_log_file(args)
    modified = datetime.datetime.fromtimestamp(log_file.stat().st_mtime)

    print(f"- Selected log file: {log_file} (last modified {modified:%Y-%m-%d %H:%M:%S})")
    print(f"- Log directory: {log_file.parent}")

    recent_listing = format_recent(log_file.parent, args.recent)
    if recent_listing:
        print(f"- Recent candidates:\n{recent_listing}")
    else:
        print("- Recent candidates: none found")

    if args.pattern:
        pattern_summary = summarize_patterns(args.pattern, log_file, args)
        for line in pattern_summary:
            print(line)
    else:
        print("- No patterns supplied; showing tail snippet")

    tail = tail_log(log_file, args.tail)
    if tail:
        print(f"- Tail snippet (last {args.tail} lines):")
        print(tail)
    else:
        print(f"- Tail snippet: log shorter than {args.tail} lines or file empty")


if __name__ == "__main__":
    main()