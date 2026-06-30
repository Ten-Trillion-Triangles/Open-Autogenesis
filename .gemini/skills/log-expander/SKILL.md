---
name: log-expander
description: Detects the workspace logging system and expands logs wherever the user needs extra logging applied. Use when a user asks to add logging, debug a specific area by adding traces, or ensure better observability in a module.
---

# Log Expander

## Overview

This skill helps you automatically detect the logging framework used in a workspace and inject appropriate log statements into the code. It ensures that added logs follow existing patterns, use correct imports, and respect established logging categories.

## Workflow

1. **Detect Logging System**: Use `scripts/detect_logging.py` to identify which logging framework is in use (e.g., SLF4J, Autogenesis Custom Logger, etc.).
2. **Analyze Context**: Examine the target file to see how logging is already implemented (imports, common categories used, naming conventions for loggers).
3. **Inject Logs**: Add the requested log statements. Ensure necessary imports are present.
4. **Verify**: Check that the added logs are syntactically correct and follow the project's style.

## Usage Examples

### Adding entry/exit logs
"Add debug logging to the beginning and end of all methods in `UserManager.kt` to track user creation flow."

### Logging exceptions
"Find all empty catch blocks in `PaymentService.kt` and add error logging to them."

### Tracking state
"I'm having trouble with the `Order` state machine. Add logging to every place where `order.status` is updated."

## Resources

### scripts/detect_logging.py
Identifies the logging framework and provides templates for log statements.

### references/LOGGING_PATTERNS.md
Provides detailed examples and common injection points for various logging systems, including the project-specific `Autogenesis` logger.
