#!/bin/bash
# dev-run.sh — build, launch, and test the Autogenesis Map Editor.
# Usage: ./scripts/dev-run.sh [--rebuild] [--no-sandbox] [--e2e] [--help]

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
MODULE_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
REPO_DIR="$(cd "$MODULE_DIR/.." && pwd)"
APPIMAGE="$MODULE_DIR/build/electron/Autogenesis-MapEditor-linux-0.1.0.AppImage"

DO_REBUILD=false
DO_E2E=false
SANDBOX_FLAG=""
GRADLE_FLAGS=(--no-daemon)

usage() {
    cat <<EOF
dev-run.sh — build, launch, and test the Autogenesis Map Editor

Usage: ./scripts/dev-run.sh [options]

Options:
    --rebuild       Force a clean Gradle build before launching.
    --no-sandbox    Launch the AppImage with Chromium's renderer sandbox disabled.
                    Use this when chrome-sandbox's SUID bit is missing or the kernel
                    refuses to honor it (common inside containers / LXC).
    --e2e           Run the Playwright end-to-end suite instead of launching the
                    AppImage. The e2e suite uses _electron.launch() against the
                    packaged binary.
    --help          Show this message.

Environment variables:
    GRADLE_FLAGS    Extra arguments appended to every gradle invocation.
                    Default: --no-daemon.
    DISPLAY         X11 display for the AppImage and e2e suite. Defaults to :0
                    on Linux; ignored on macOS.
EOF
}

while [[ $# -gt 0 ]]; do
    case "$1" in
        --rebuild)    DO_REBUILD=true; shift ;;
        --no-sandbox) SANDBOX_FLAG="--no-sandbox"; shift ;;
        --e2e)        DO_E2E=true; shift ;;
        --help|-h)    usage; exit 0 ;;
        *)            echo "Unknown option: $1" >&2; usage; exit 2 ;;
    esac
done

log() { printf '\033[1;34m[dev-run]\033[0m %s\n' "$*"; }
warn() { printf '\033[1;33m[dev-run]\033[0m %s\n' "$*" >&2; }
fail() { printf '\033[1;31m[dev-run]\033[0m %s\n' "$*" >&2; exit 1; }

buildAppImage() {
    if [[ "$DO_REBUILD" == true ]]; then
        log "Clean rebuild requested"
        (cd "$REPO_DIR" && ./gradlew "${GRADLE_FLAGS[@]}" :electronMapEditor:clean :electronMapEditor:packageLinux)
    else
        log "Incremental build"
        (cd "$REPO_DIR" && ./gradlew "${GRADLE_FLAGS[@]}" :electronMapEditor:packageLinux)
    fi
}

launchAppImage() {
    if [[ ! -f "$APPIMAGE" ]]; then
        fail "AppImage not found at $APPIMAGE — run with --rebuild"
    fi
    chmod +x "$APPIMAGE"
    if [[ -n "$SANDBOX_FLAG" ]]; then
        warn "Launching with sandbox disabled ($SANDBOX_FLAG)"
    else
        SANDBOX_BIN="$MODULE_DIR/build/electron/linux-unpacked/chrome-sandbox"
        if [[ -f "$SANDBOX_BIN" && ! -u "$SANDBOX_BIN" ]]; then
            warn "chrome-sandbox lacks SUID bit — relaunch with --no-sandbox or restore SUID via: chmod 4755 $SANDBOX_BIN"
        fi
    fi
    log "Launching $APPIMAGE"
    exec "$APPIMAGE" $SANDBOX_FLAG
}

runE2E() {
    if [[ ! -f "$APPIMAGE" ]]; then
        fail "AppImage not found at $APPIMAGE — run with --rebuild"
    fi
    local NODE20="$REPO_DIR/electronMapEditor/.gradle/nodejs/node-v20.9.0-linux-x64/bin"
    if [[ ! -x "$NODE20/node" ]]; then
        fail "Node 20.9.0 not found at $NODE20 — run ./gradlew :electronMapEditor:packageLinux once to provision it"
    fi
    local E2E_DIR="$MODULE_DIR/e2e"
    log "Compiling e2e suite"
    (cd "$E2E_DIR" && PATH="$NODE20/bin:$PATH" npx tsc)
    log "Running Playwright e2e against the packaged AppImage"
    (cd "$E2E_DIR" && PATH="$NODE20/bin:$PATH" DISPLAY="${DISPLAY:-:0}" npm test)
}

buildAppImage

if [[ "$DO_E2E" == true ]]; then
    runE2E
else
    launchAppImage
fi
