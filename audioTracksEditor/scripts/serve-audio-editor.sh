#!/usr/bin/env bash
# scripts/serve-audio-editor.sh — build the audio editor dist and serve it.
#
# Resolves the repo root from its own location so it works no matter what the
# caller's CWD is (IntelliJ run config, Fenix click-to-run, terminal, CI).
# Builds the dist if it is missing, then starts `python3 -m http.server`
# against the dist path and (on a desktop session) auto-opens the browser.
#
# Usage:
#   ./scripts/serve-audio-editor.sh [PORT]              # default PORT=4174
#   AUDIO_EDITOR_NO_OPEN=1 ./scripts/serve-audio-editor.sh   # headless / CI
#   AUDIO_EDITOR_AUTO_BUILD=0 ./scripts/serve-audio-editor.sh # fail if dist missing
#
# Exit codes:
#   0  server started cleanly (Ctrl-C to stop)
#   1  port already in use
#   2  dist missing and AUDIO_EDITOR_AUTO_BUILD=0
#   3  python3 not on PATH
#   4  unable to detect repo root (script moved outside the audioTracksEditor/ module)

set -euo pipefail

# Resolve repo root from this script's location.
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/../.." && pwd)"
EDITOR_DIR="${REPO_ROOT}/audioTracksEditor"
DIST_DIR="${EDITOR_DIR}/build/dist/js/productionExecutable"

# Sanity checks
if [ ! -d "${EDITOR_DIR}" ]; then
    echo "serve-audio-editor: audioTracksEditor module not found at ${EDITOR_DIR}" >&2
    echo "  (this script must live at <repo>/audioTracksEditor/scripts/serve-audio-editor.sh)" >&2
    exit 4
fi

if ! command -v python3 >/dev/null 2>&1; then
    echo "serve-audio-editor: python3 not on PATH" >&2
    echo "  install Python 3.6+ and try again" >&2
    exit 3
fi

# Optionally build the dist if it is missing.
PORT="${1:-4174}"

if [ ! -f "${DIST_DIR}/index.html" ]; then
    if [ "${AUDIO_EDITOR_AUTO_BUILD:-1}" = "0" ]; then
        echo "serve-audio-editor: ${DIST_DIR}/index.html not found" >&2
        echo "  run \`${REPO_ROOT}/gradlew :audioTracksEditor:jsBrowserDistribution\` first" >&2
        echo "  or unset AUDIO_EDITOR_AUTO_BUILD to let this script build it for you" >&2
        exit 2
    fi
    echo "serve-audio-editor: dist missing — running :audioTracksEditor:jsBrowserDistribution..."
    (cd "${REPO_ROOT}" && ./gradlew :audioTracksEditor:jsBrowserDistribution --no-daemon --console=plain)
fi

# Refuse to clobber an existing server on the same port.
if command -v ss >/dev/null 2>&1; then
    if ss -ltn "sport = :${PORT}" 2>/dev/null | grep -q LISTEN; then
        echo "serve-audio-editor: port ${PORT} already in use" >&2
        echo "  pick a different port: $0 9090" >&2
        exit 1
    fi
elif command -v lsof >/dev/null 2>&1; then
    if lsof -iTCP:"${PORT}" -sTCP:LISTEN >/dev/null 2>&1; then
        echo "serve-audio-editor: port ${PORT} already in use" >&2
        echo "  pick a different port: $0 9090" >&2
        exit 1
    fi
fi

# Start the server in the background, then open the browser.
echo "serve-audio-editor: serving ${DIST_DIR} on http://localhost:${PORT}/"
python3 -m http.server "${PORT}" --directory "${DIST_DIR}" &
SERVER_PID=$!

# Open the browser only on a real desktop session.
SESSION_OK=0
if [ -n "${DISPLAY:-}" ] || [ "$(uname -s)" = "Darwin" ] || [ "$(uname -s)" = "MINGW"* ] || [ "$(uname -s)" = "CYGWIN"* ]; then
    SESSION_OK=1
fi

if [ "${AUDIO_EDITOR_NO_OPEN:-0}" != "1" ] && [ "${SESSION_OK}" = "1" ]; then
    sleep 0.3
    URL="http://localhost:${PORT}/"
    if command -v xdg-open >/dev/null 2>&1; then
        xdg-open "${URL}" >/dev/null 2>&1 &
    elif command -v open >/dev/null 2>&1; then
        open "${URL}" >/dev/null 2>&1 &
    elif command -v sensible-browser >/dev/null 2>&1; then
        sensible-browser "${URL}" >/dev/null 2>&1 &
    fi
    echo "serve-audio-editor: opened ${URL} in your default browser"
    echo "  (set AUDIO_EDITOR_NO_OPEN=1 to skip the auto-open)"
fi

# Clean shutdown on Ctrl-C.
trap 'echo "serve-audio-editor: stopping server (pid ${SERVER_PID})"; kill "${SERVER_PID}" 2>/dev/null || true; exit 0' INT TERM
wait "${SERVER_PID}"
