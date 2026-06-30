#!/usr/bin/env bash
# Seed-and-repush workflow for the resume-game snapshot.
#
# The user's user-visible flow is:
#   1. Play one turn (probe-driven, existing `resume-preserves-round.mjs`).
#   2. Server auto-saves the snapshot on disconnect.
#   3. Optionally fetch the snapshot JSON to a local file for inspection.
#   4. Restart the server (servers die, snapshot dies with it).
#   5. Repush the snapshot from the local file → resume game available
#      without re-playing.
#
# Prerequisites:
#   - All three dev servers running with AUTOGENESIS_DEBUG_SEED=true.
#   - The user logged in as guest at least once so the AccelByte user
#     record exists (the script reads accelbyteUserId from the MainMenu
#     data attribute via Playwright, but you can also pass --userId=...
#     directly if you know it).
#
# Usage:
#   # Step 1+2+3: play one turn, fetch snapshot to disk
#   ./seed-and-repush-snapshot.sh fetch --out /tmp/snap.json
#
#   # Step 5: repush a saved snapshot
#   ./seed-and-repush-snapshot.sh repush --in /tmp/snap.json
#
#   # Convenience: fetch AND repush in one shot (useful for the test loop)
#   ./seed-and-repush-snapshot.sh fetch-and-repush --out /tmp/snap.json
#
#   # Detect the userId automatically from a running browser via Playwright
#   ./seed-and-repush-snapshot.sh fetch --auto-user
#
# Notes:
#   - `--userId` accepts the AccelByte UUID (the
#     `data-accelbyte-user-id` attribute on the MainMenu root).
#   - `--base-url` defaults to http://127.0.0.1:9080 (the game server).
#   - The endpoints (POST /debug/seed-snapshot + GET /debug/fetch-snapshot)
#     are gated on AUTOGENESIS_DEBUG_SEED=true; start the dev servers with
#     `AUTOGENESIS_DEBUG_SEED=true ./gradlew :server:run` for them to
#     respond.

set -euo pipefail

BASE_URL="${BASE_URL:-http://127.0.0.1:9080}"
USER_ID=""
INPUT_FILE=""
OUTPUT_FILE=""
AUTO_USER=false
ACTION=""

print_usage() {
  cat <<'EOF'
seed-and-repush-snapshot.sh — fetch and repush the resume-game snapshot

Subcommands:
  fetch         fetch the current running-game snapshot from the server
  repush        write a saved snapshot back to the server
  fetch-and-repush   fetch then immediately repush (useful for the test loop)

Common options:
  --userId=UUID       AccelByte user id (REQUIRED for fetch/repush;
                      can be omitted with --auto-user)
  --base-url=URL      game server base URL (default: http://127.0.0.1:9080)
  --out=PATH          for fetch: write the snapshot JSON to this file
  --in=PATH           for repush: read the snapshot JSON from this file
  --auto-user         detect the userId from the running browser via
                      Playwright (looks at data-accelbyte-user-id on
                      [data-testid="main-menu"])
  -h, --help          show this message
EOF
}

# Parse args
while [[ $# -gt 0 ]]; do
  case "$1" in
    fetch|repush|fetch-and-repush)
      ACTION="$1"
      shift
      ;;
    --userId=*)
      USER_ID="${1#*=}"
      shift
      ;;
    --base-url=*)
      BASE_URL="${1#*=}"
      shift
      ;;
    --out=*)
      OUTPUT_FILE="${1#*=}"
      shift
      ;;
    --in=*)
      INPUT_FILE="${1#*=}"
      shift
      ;;
    --auto-user)
      AUTO_USER=true
      shift
      ;;
    -h|--help)
      print_usage
      exit 0
      ;;
    *)
      echo "[$(date +%H:%M:%S)] unknown arg: $1" >&2
      print_usage >&2
      exit 2
      ;;
  esac
done

if [[ -z "$ACTION" ]]; then
  echo "[$(date +%H:%M:%S)] ERROR: no subcommand given" >&2
  print_usage >&2
  exit 2
fi

# Resolve auto-user if requested. Looks at the live MainMenu DOM via
# Playwright — opens a single chromium instance, navigates to the
# webpack dev server, waits for MainMenu, reads the data attribute.
resolve_auto_user() {
  local tmp
  tmp="$(mktemp)"
  if ! command -v node >/dev/null 2>&1; then
    echo "[$(date +%H:%M:%S)] ERROR: node not on PATH; can't --auto-user" >&2
    return 1
  fi
  if ! command -v npx >/dev/null 2>&1; then
    echo "[$(date +%H:%M:%S)] ERROR: npx not on PATH; can't --auto-user" >&2
    return 1
  fi
  local playwright_dir
  playwright_dir="$(cd "$(dirname "$0")/../../kvisionApp-e2e" && pwd)"
  cat > "$tmp" <<EOF
import { chromium } from '@playwright/test'
const browser = await chromium.launch({ headless: true })
const page = await browser.newPage()
await page.goto('${BASE_URL_HTTP:-http://127.0.0.1:8080}/index.html')
try {
  await page.locator('[data-testid="loading-screen-cta"]').click({ timeout: 5_000 })
} catch (_) {}
try {
  await page.locator('[data-testid="login-as-guest"]').click({ timeout: 5_000 })
} catch (_) {}
try {
  await page.waitForFunction(
    () => document.querySelector('[data-testid="main-menu"]') !== null,
    { timeout: 30_000 }
  )
  const id = await page.locator('[data-testid="main-menu"]').first().getAttribute('data-accelbyte-user-id')
  console.log(id || '')
} catch (e) {
  console.log('')
} finally {
  await browser.close()
}
EOF
  USER_ID="$(cd "$playwright_dir" && node --input-type=module < "$tmp" 2>/dev/null | tail -1 | tr -d '\r\n')"
  rm -f "$tmp"
  if [[ -z "$USER_ID" ]]; then
    echo "[$(date +%H:%M:%S)] ERROR: --auto-user could not detect userId from MainMenu. Pass --userId=... instead." >&2
    return 1
  fi
  echo "[$(date +%H:%M:%S)] auto-detected userId=$USER_ID"
}

if [[ "$AUTO_USER" == "true" ]]; then
  resolve_auto_user
fi

if [[ -z "$USER_ID" ]]; then
  echo "[$(date +%H:%M:%S)] ERROR: --userId=... is required (or use --auto-user)" >&2
  exit 2
fi

cmd_fetch() {
  local out="${OUTPUT_FILE:-/tmp/snapshot-${USER_ID}.json}"
  echo "[$(date +%H:%M:%S)] fetching snapshot for userId=$USER_ID from $BASE_URL/debug/fetch-snapshot"
  local body
  body="$(curl -sfG "$BASE_URL/debug/fetch-snapshot" --data-urlencode "userId=$USER_ID")" \
    || { echo "[$(date +%H:%M:%S)] ERROR: GET failed (is AUTOGENESIS_DEBUG_SEED=true set?)" >&2; exit 1; }
  # The /debug/fetch-snapshot response shape is:
  #   { "userId": "...", "snapshot": "<raw GameSnapshot JSON as a STRING>" }
  # We extract the `snapshot` string and pretty-print it to a file
  # so the caller can inspect it as standalone JSON. Without jq this
  # would be awkward (the inner string is itself escaped JSON), so
  # require jq and fail loudly if it's missing.
  if ! command -v jq >/dev/null 2>&1; then
    echo "[$(date +%H:%M:%S)] ERROR: jq is required to extract the inner snapshot string" >&2
    echo "[$(date +%H:%M:%S)]   install with: sudo apt install jq" >&2
    exit 1
  fi
  echo "$body" | jq -r '.snapshot' | jq . > "$out"
  local size
  size="$(wc -c < "$out")"
  echo "[$(date +%H:%M:%S)] wrote $size bytes to $out"
}

cmd_repush() {
  local in="${INPUT_FILE:?--in=PATH is required for repush}"
  if [[ ! -f "$in" ]]; then
    echo "[$(date +%H:%M:%S)] ERROR: input file $in does not exist" >&2
    exit 1
  fi
  if ! command -v jq >/dev/null 2>&1; then
    echo "[$(date +%H:%M:%S)] ERROR: jq is required" >&2
    exit 1
  fi
  echo "[$(date +%H:%M:%S)] repushing snapshot for userId=$USER_ID from $in via $BASE_URL/debug/seed-snapshot"
  # The /debug/seed-snapshot endpoint expects:
  #   { "userId": "...", "snapshot": <raw GameSnapshot JSON> }
  # Where `snapshot` is a JSON value (object), not a string. We re-stringify
  # the input file (which is the raw GameSnapshot JSON object) and embed it.
  local snapshot_value
  snapshot_value="$(jq -c . < "$in")"
  local body
  body="$(jq -n --arg uid "$USER_ID" --argjson snap "$snapshot_value" '{userId: $uid, snapshot: $snap}')"
  local resp
  resp="$(curl -sf -X POST "$BASE_URL/debug/seed-snapshot" \
    -H "Content-Type: application/json" \
    -d "$body" \
    -w "\nHTTP_STATUS:%{http_code}")" \
    || { echo "[$(date +%H:%M:%S)] ERROR: POST failed" >&2; exit 1; }
  echo "$resp"
}

case "$ACTION" in
  fetch)
    cmd_fetch
    ;;
  repush)
    cmd_repush
    ;;
  fetch-and-repush)
    cmd_fetch
    INPUT_FILE="$OUTPUT_FILE"
    cmd_repush
    ;;
esac

echo "[$(date +%H:%M:%S)] done."
