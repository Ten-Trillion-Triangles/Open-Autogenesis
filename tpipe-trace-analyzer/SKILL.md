---
name: tpipe-trace-analyzer
description: Finds and analyzes TPipe trace.html files using Python scripts to extract pipe names, models, token counts, and execution status. Use when you need to understand why an AI agent failed, check model performance, or audit pipeline execution flow.
---

# TPipe Trace Analyzer

## Overview

This skill provides a specialized workflow for analyzing TPipe execution traces. It automates the discovery of `trace.html` files and uses a Python-based parser to extract critical execution metrics without needing to manually inspect large HTML files.

## Workflow

1.  **Analyze Recent Traces**: Run the analysis script to see a summary of the most recent pipeline executions.
    ```bash
    python3 tpipe-trace-analyzer/scripts/parse_trace.py
    ```
2.  **Target Specific Turn**: You can provide a path to a specific turn's folder to analyze only those traces.
    ```bash
    python3 tpipe-trace-analyzer/scripts/parse_trace.py ~/.tpipe/debug/trace/Round_1_Turn_0_Commander_Shepard
    ```
3.  **Identify Failures**: Look for `Status: FAILURE` in the output to pinpoint which pipe in the pipeline crashed or returned invalid results.
4.  **Audit Token Usage**: Use the `Tokens: In=X, Out=Y` metrics to monitor costs and context window usage.

## Resources

### scripts/parse_trace.py
The core analysis script. It parses HTML trace tables and extracts:
- **Pipe Name**: The identity of the agent/step.
- **Model**: The LLM used for that step.
- **Status**: SUCCESS/FAILURE based on API calls and validation steps.
- **Tokens**: Input and Output token counts.
- **Result Preview**: A snippet of the text returned by the model.