#!/usr/bin/env bash
# Autogenesis server launcher — starts all three servers sequentially
#
# In dev mode the resume-game workflow needs:
#   - the game server to stay alive long enough for the player to
#     reconnect from a fresh browser (otherwise the 60-second shutdown
#     timer fires before the modal can render).
#     AUTOGENESIS_SHUTDOWN_DELAY_MS=600000 (10 minutes) gives plenty of
#     headroom for the player to switch tabs and log back in.
#   - auto-restore on WS rebind to be SUPPRESSED so server-extend's
#     push wins the race and the ResumeOrNewDialog modal renders. In
#     production live mode with AMS-provisioned fresh DS, auto-restore
#     IS the resume mechanism, so this stays OFF there.
#
# Note: BUG-2 fix 2026-06-25 raised the Server.kt default from 15s to 60s
# because 15s was too short for the player to switch tabs and log back in.
# If you see a "15-second shutdown timer" log, you're running a pre-fix
# build. Always launch via this script (or pass AUTOGENESIS_SHUTDOWN_DELAY_MS
# explicitly) to get the dev default of 600000ms.
#
# Override with AUTOGENESIS_SHUTDOWN_DELAY_MS=<ms>,
# AUTOGENESIS_DISABLE_AUTO_RESTORE=false (prod), or leave unset for
# dev defaults.
export AUTOGENESIS_SHUTDOWN_DELAY_MS="${AUTOGENESIS_SHUTDOWN_DELAY_MS:-600000}"
export AUTOGENESIS_DISABLE_AUTO_RESTORE="${AUTOGENESIS_DISABLE_AUTO_RESTORE:-true}"
# Debug seed endpoints (POST /debug/seed-snapshot + GET /debug/fetch-snapshot) are
# OFF by default — opt in per debug session by setting AUTOGENESIS_DEBUG_SEED=true.
export AUTOGENESIS_DEBUG_SEED="${AUTOGENESIS_DEBUG_SEED:-false}"
echo "[$(date +%H:%M:%S)] AUTOGENESIS_SHUTDOWN_DELAY_MS=${AUTOGENESIS_SHUTDOWN_DELAY_MS}"
echo "[$(date +%H:%M:%S)] AUTOGENESIS_DISABLE_AUTO_RESTORE=${AUTOGENESIS_DISABLE_AUTO_RESTORE}"
echo "[$(date +%H:%M:%S)] AUTOGENESIS_DEBUG_SEED=${AUTOGENESIS_DEBUG_SEED}"

PROJECT="$(cd "$(dirname "$(readlink -f "${BASH_SOURCE[0]}")")/.." && pwd)"
LOG_DIR="/tmp/autogenesis-proxy"
mkdir -p "$LOG_DIR"

echo "[$(date +%H:%M:%S)] Starting servers..."

# Kill any existing
for port in 7070 9080 8080 7071 9081 9091 9092; do
  fuser -k $port/tcp 2>/dev/null
done
sleep 2

# Start server-extend (port 7070)
echo "[$(date +%H:%M:%S)] Starting server-extend on 7070..."
cd "$PROJECT"
AUTOGENESIS_SHUTDOWN_DELAY_MS="$AUTOGENESIS_SHUTDOWN_DELAY_MS" \
  ./gradlew :server-extend:run > "$LOG_DIR/se.log" 2>&1 &
SE_PID=$!
echo "server-extend PID: $SE_PID"

# Start game server (port 9080) — forward AUTOGENESIS_SHUTDOWN_DELAY_MS
# so the dev server stays alive across a resume attempt.
echo "[$(date +%H:%M:%S)] Starting game server on 9080..."
AUTOGENESIS_SHUTDOWN_DELAY_MS="$AUTOGENESIS_SHUTDOWN_DELAY_MS" \
  ./gradlew :server:run -Dorg.gradle.jvmargs="-DAUTOGENESIS_SHUTDOWN_DELAY_MS=$AUTOGENESIS_SHUTDOWN_DELAY_MS" > "$LOG_DIR/srv.log" 2>&1 &
SRV_PID=$!
echo "game server PID: $SRV_PID"

# Start webpack (port 8080)
echo "[$(date +%H:%M:%S)] Starting webpack on 8080..."
AUTOGENESIS_SHUTDOWN_DELAY_MS="$AUTOGENESIS_SHUTDOWN_DELAY_MS" \
  ./gradlew runKvisionNoHotReload > "$LOG_DIR/kv.log" 2>&1 &
KV_PID=$!
echo "webpack PID: $KV_PID"

echo "[$(date +%H:%M:%S)] All servers started. PIDs: $SE_PID $SRV_PID $KV_PID"
echo "Logs: $LOG_DIR/{se,srv,kv}.log"
