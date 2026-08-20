#!/usr/bin/env bash
#
# e2e/run-e2e.sh — Orchestrates a real-server Playwright E2E test for the
# audio system.
#
# What it does:
#   1. Starts the game server with the test-only /test/audio/trigger-schedule-play
#      endpoint enabled (gated on -Daudio.test.endpoint=true; default OFF).
#   2. Waits for GET /player to return 200.
#   3. Starts a python3 static-file server in e2e/ on port 8085.
#   4. POSTs to the server's test endpoint to drive AudioManager.schedulePlay.
#   5. Prints the Playwright MCP commands the user (or operator) should run
#      to drive the browser and assert on __audioProbe state.
#   6. Cleans up both background processes on exit (trap).
#
# Usage:
#   ./e2e/run-e2e.sh
#
# After the script prints "[READY] Playwright commands:", the operator runs
# the Playwright MCP browser_navigate to http://localhost:8085/test-client.html
# and the subsequent evaluate calls. The curl POST below the comment block
# triggers the server-side schedulePlay that the browser observes.

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
SERVER_PORT="${SERVER_PORT:-8080}"
STATIC_PORT="${STATIC_PORT:-8085}"
SERVER_LOG=/tmp/audio-e2e-server.log
STATIC_PID_FILE=/tmp/audio-e2e-static.pid
SERVER_PID_FILE=/tmp/audio-e2e-server.pid

cleanup() {
    echo
    echo "[CLEANUP] Stopping background processes..."
    if [ -f "$SERVER_PID_FILE" ]; then
        kill "$(cat "$SERVER_PID_FILE")" 2>/dev/null || true
        rm -f "$SERVER_PID_FILE"
    fi
    if [ -f "$STATIC_PID_FILE" ]; then
        kill "$(cat "$STATIC_PID_FILE")" 2>/dev/null || true
        rm -f "$STATIC_PID_FILE"
    fi
}
trap cleanup EXIT INT TERM

echo "[1/4] Starting game server with test endpoint enabled..."
cd "$REPO_ROOT"
JAVA_OPTS="-Daudio.test.endpoint=true" ./gradlew :server:run > "$SERVER_LOG" 2>&1 &
SERVER_PID=$!
echo "$SERVER_PID" > "$SERVER_PID_FILE"
echo "  Server PID: $SERVER_PID (log: $SERVER_LOG)"

echo "[2/4] Waiting for server to be ready (max 90s)..."
for i in $(seq 1 90); do
    if curl -sf "http://localhost:${SERVER_PORT}/player" > /dev/null 2>&1; then
        echo "  Server ready after ${i}s"
        break
    fi
    sleep 1
    if [ "$i" = "90" ]; then
        echo "  ERROR: Server did not become ready in 90s. Tail of log:"
        tail -30 "$SERVER_LOG"
        exit 1
    fi
done

echo "[3/4] Starting static file server for e2e/ on port ${STATIC_PORT}..."
cd "$SCRIPT_DIR"
python3 -m http.server "$STATIC_PORT" > /tmp/audio-e2e-static.log 2>&1 &
STATIC_PID=$!
echo "$STATIC_PID" > "$STATIC_PID_FILE"
echo "  Static server PID: $STATIC_PID"
sleep 2

if ! curl -sf "http://localhost:${STATIC_PORT}/test-client.html" > /dev/null 2>&1; then
    echo "  ERROR: Static server did not respond for test-client.html"
    tail -10 /tmp/audio-e2e-static.log
    exit 1
fi
echo "  Static server ready: http://localhost:${STATIC_PORT}/test-client.html"

echo "[4/4] Triggering server-side schedulePlay via test endpoint..."
TRIGGER_PAYLOAD='{"resourceName":"test.beep","channelId":"Sfx","volume":0.8,"loop":true,"loopStart":1.25,"loopEnd":3.75}'
TRIGGER_RESP=$(curl -s -X POST "http://localhost:${SERVER_PORT}/test/audio/trigger-schedule-play" \
    -H "Content-Type: application/json" \
    -d "$TRIGGER_PAYLOAD")
echo "  Trigger response: $TRIGGER_RESP"

echo
echo "=================================================================="
echo "[READY] Server is running, test endpoint was called. Now run these"
echo "        Playwright MCP commands to drive the browser assertion:"
echo "=================================================================="
cat <<'EOF'

# Step 1: Open the test client in headless Chrome
mcp__playwright__browser_navigate(url="http://localhost:8085/test-client.html")

# Step 2: Wait for the WebSocket handshake to complete (echoed pong appears in log)
mcp__playwright__browser_wait_for(time=2)

# Step 3: Verify the browser received the audio.schedulePlay notification
#         and the AudioBufferSourceNode has the right loop/loopStart/loopEnd
mcp__playwright__browser_evaluate(function="() => {
    const probe = window.__audioProbe;
    const scheduleNotifications = probe.received.filter(
        m => m.type === 'notification' && m.method === 'audio.schedulePlay'
    );
    const last = scheduleNotifications[scheduleNotifications.length - 1];
    const src = probe.lastSourceNode();
    return {
        totalMessagesReceived: probe.received.length,
        schedulePlayCount: scheduleNotifications.length,
        lastSchedulePlay: last,
        lastSchedulePlayPayload: last?.params,
        sourceNode: src ? {
            loop: src.loop,
            loopStart: src.loopStart,
            loopEnd: src.loopEnd,
            bufferDuration: src.buffer?.duration
        } : null,
        audioContextState: probe.audioContextState(),
        bufferDuration: probe.bufferDuration()
    };
}")

# Step 4: Screenshot the page state (saves to e2e/screenshots/)
mkdir -p e2e/screenshots
mcp__playwright__browser_take_screenshot(filename="e2e/screenshots/audio-scheduleplay-verified.png")

# Step 5: Stop the Playwright browser
mcp__playwright__browser_close()
EOF

echo
echo "=================================================================="
echo "Expected assertions in Step 3's response:"
echo "  totalMessagesReceived >= 2        (ConnectionState + pong + schedulePlay)"
echo "  schedulePlayCount == 1"
echo "  lastSchedulePlay.params.objects[0].loopStart == 1.25"
echo "  lastSchedulePlay.params.objects[0].loopEnd == 3.75"
echo "  sourceNode.loop == true"
echo "  sourceNode.loopStart == 1.25"
echo "  sourceNode.loopEnd == 3.75"
echo "  bufferDuration == 1.0  (synthetic 1-second 440Hz sine)"
echo "=================================================================="

echo
echo "[INFO] Both server and static file server are still running."
echo "[INFO] Press Ctrl-C to stop them, or run the Playwright commands above."
echo "[INFO] Server log is at: $SERVER_LOG"
echo "[INFO] Static log is at: /tmp/audio-e2e-static.log"
echo
echo "[HOLD] Sleeping indefinitely so the background processes stay up..."
echo "       (Press Ctrl-C to stop everything.)"
sleep infinity