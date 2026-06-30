# Autogenesis Log Locations

- `~/.autogenesis/logs/` holds every runtime log produced by the Autogenesis binaries.
- JVM servers (e.g., `server`, `server-extend`, `mapEditor`) each start a new file named
  `autogenesis-YYYY-MM-DD-HHmmss.log` or `server-extend-YYYY-MM-DD-HHmmss.log`; the timestamp marks the
  server start time. `maxLogFiles` controls retention, so only the last few appear by default.
- Browser sessions append to `browser-YYYY-MM-DD-HHmmss.log` and keep a local copy in the browser
  console/localStorage; the script in this skill scans the most recent `browser-*.log` files only when
  the user explicitly mentions them.
- Logs are written in the format `timestamp [PRIORITY] [CATEGORY]: message`. Filtering by category
  (`SYSTEM`, `NETWORK`, `LLM`, etc.) helps locate the relevant subsystem.
- When a user references `~/.autogenesis/logs/browser-*.log`, double-check the `SERVER_LOG_ENDPOINT`
  (`http://localhost:9080/api/browser-log`) so you know whether POST batches may also be arriving on the
  server side.
