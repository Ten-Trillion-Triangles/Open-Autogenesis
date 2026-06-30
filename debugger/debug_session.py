#!/usr/bin/env python3
"""
Autogenesis Debug Session Runner
Starts all components needed for a full debug session:
  1. Real servers (server-extend on :7071, game server on :9081, webpack on :8080)
  2. MITM proxy (:7070 and :9080) — browser and Python connect here
  3. Browser observer — captures all UI/network/console traffic
  4. Python controller — drives the game via REST/SSE + WS

All logs go to /tmp/autogenesis-proxy/

Usage:
    python3 debug_session.py --start          # Start everything
    python3 debug_session.py --servers-only   # Only start real servers
    python3 debug_session.py --proxy-only     # Only start proxy
    python3 debug_session.py --observer-only  # Only start browser observer
    python3 debug_session.py --controller-only # Only start Python controller
    python3 debug_session.py --stop           # Stop all processes
"""

import argparse
import asyncio
import os
import signal
import subprocess
import sys
import time
from pathlib import Path

# ============================================================
# Paths
# ============================================================
PROJECT_ROOT = Path(__file__).resolve().parent.parent
PROXY_DIR = PROJECT_ROOT / "debugger"
SCRIPTS_DIR = PROXY_DIR / "scripts"
PROXY_LOG_DIR = Path("/tmp/autogenesis-proxy")
PROXY_LOG_DIR.mkdir(exist_ok=True)

PYTHON = "/tmp/autogenesis-dev/bin/python"

# Server ports — real servers are ONE AHEAD of normal
# Real:     :7071 (server-extend), :9081 (game), :8080 (webpack)
# Proxy:    :7070 (SE proxy), :9080 (game proxy)  → forwards to real
# Python:   can use either (--direct vs --proxy flag)
REAL_SE_PORT = 7071
REAL_WS_PORT = 9081

# ============================================================
# Process management
# ============================================================
processes = {}
running = True

def log(msg):
    ts = time.strftime("%H:%M:%S")
    print(f"[{ts}] {msg}")

def signal_handler(sig, frame):
    global running
    log("Shutdown signal received...")
    running = False
    stop_all()
    sys.exit(0)

signal.signal(signal.SIGINT, signal_handler)
signal.signal(signal.SIGTERM, signal_handler)

# ============================================================
# Server management
# ============================================================

def start_real_servers():
    """Start the three real game servers (one port ahead of proxy)."""
    log("Starting real servers...")
    
    # server-extend (port :7071)
    log("  Starting server-extend on :7071...")
    se = subprocess.Popen(
        ["bash", "-c", f"cd {PROJECT_ROOT} && ./gradlew :server-extend:run > /tmp/se.log 2>&1"],
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
    )
    processes["server-extend"] = se
    log(f"  server-extend PID: {se.pid}")
    
    # game server (port :9081)
    log("  Starting game server on :9081...")
    srv = subprocess.Popen(
        ["bash", "-c", f"cd {PROJECT_ROOT} && ./gradlew :server:run > /tmp/srv.log 2>&1"],
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
    )
    processes["game-server"] = srv
    log(f"  game server PID: {srv.pid}")
    
    # webpack (port :8080)
    log("  Starting webpack on :8080...")
    kv = subprocess.Popen(
        ["bash", "-c", f"cd {PROJECT_ROOT} && ./gradlew runKvisionNoHotReload > /tmp/kv.log 2>&1"],
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
    )
    processes["webpack"] = kv
    log(f"  webpack PID: {kv.pid}")
    
    # Wait for ports to be ready
    log("  Waiting for servers to start...")
    time.sleep(100)  # ~90s for game server, ~45s for server-extend
    log("  Real servers should be ready")

def start_proxy():
    """Start the MITM proxy (listens on :7070/:9080, forwards to :7071/:9081)."""
    log("Starting MITM proxy on :7070 (SE) and :9080 (WS)...")
    proxy_script = PROXY_DIR / "proxy" / "mitm_proxy.py"
    proxy_proc = subprocess.Popen(
        [PYTHON, str(proxy_script), "--start",
         "--http-port", "7070",
         "--ws-port", "9080",
         "--real-se-port", "7071",
         "--real-ws-port", "9081"],
        stdout=open(PROXY_LOG_DIR / "proxy-stdout.log", "w"),
        stderr=subprocess.STDOUT,
    )
    processes["proxy"] = proxy_proc
    log(f"  Proxy PID: {proxy_proc.pid}")
    time.sleep(2)

def start_observer(visible=True):
    """Start browser observer (captures all browser traffic)."""
    log(f"Starting browser observer (visible={visible})...")
    observer_script = PROXY_DIR / "observer" / "browser_observer.py"
    args = [PYTHON, str(observer_script), "--hold", "7200"]  # 2 hour hold
    if not visible:
        args.append("--headless")
    obs = subprocess.Popen(args)
    processes["observer"] = obs
    log(f"  Observer PID: {obs.pid}")

def start_controller(proxy_mode=True):
    """Start Python controller (drives the game)."""
    log(f"Starting Python controller (proxy_mode={proxy_mode})...")
    controller_script = PROJECT_ROOT / "controller" / "controller.py"
    if proxy_mode:
        # Controller uses --proxy-mode or sets SE/WSPORT to :7070/:9080
        ctrl = subprocess.Popen(
            [PYTHON, "-u", str(controller_script), "--no-ui"],
            stdout=subprocess.PIPE,
            stderr=subprocess.STDOUT,
        )
    else:
        ctrl = subprocess.Popen(
            [PYTHON, "-u", str(controller_script), "--no-ui"],
            stdout=subprocess.PIPE,
            stderr=subprocess.STDOUT,
        )
    processes["controller"] = ctrl
    log(f"  Controller PID: {ctrl.pid}")

def stop_all():
    """Stop all processes."""
    log("Stopping all processes...")
    
    # SIGTERM the controlled processes first
    for name, proc in processes.items():
        if proc.poll() is None:  # still running
            log(f"  Terminating {name} (PID {proc.pid})...")
            try:
                proc.terminate()
            except Exception as e:
                log(f"    Error terminating {name}: {e}")
    
    # Kill by port as backup
    for port in [7070, 9080, 7071, 9081, 8080]:
        try:
            subprocess.run(f"kill $(fuser {port}/tcp 2>/dev/null) 2>/dev/null", shell=True)
        except Exception:
            pass
    
    log("All processes stopped")
    processes.clear()

def wait_for_servers(timeout=120):
    """Wait for all ports to be open."""
    import socket
    ports = [7071, 9081, 8080]
    start = time.time()
    while time.time() - start < timeout:
        ready = True
        for port in ports:
            try:
                with socket.create_connection(("127.0.0.1", port), timeout=1):
                    pass
            except Exception:
                ready = False
                break
        if ready:
            log("All servers ready!")
            return True
        time.sleep(5)
    log(f"Timeout waiting for servers ({timeout}s)")
    return False

# ============================================================
# Main
# ============================================================

def main():
    parser = argparse.ArgumentParser(description="Autogenesis Debug Session Runner")
    parser.add_argument("--start", action="store_true", help="Start full debug session (servers + proxy + observer)")
    parser.add_argument("--servers-only", action="store_true", help="Start only real servers")
    parser.add_argument("--proxy-only", action="store_true", help="Start only proxy")
    parser.add_argument("--observer-only", action="store_true", help="Start only browser observer")
    parser.add_argument("--controller-only", action="store_true", help="Start only Python controller")
    parser.add_argument("--stop", action="store_true", help="Stop all processes")
    parser.add_argument("--proxy-mode", action="store_true", default=True, help="Controller uses proxy")
    parser.add_argument("--direct", action="store_true", help="Controller talks directly to real servers")
    
    args = parser.parse_args()
    
    if args.stop:
        stop_all()
        return
    
    if args.start:
        log("=" * 60)
        log("Starting Autogenesis Debug Session")
        log("=" * 60)
        
        # 1. Real servers
        start_real_servers()
        wait_for_servers()
        
        # 2. Proxy
        start_proxy()
        
        # 3. Brief pause
        time.sleep(3)
        
        # 4. Browser observer (visible)
        start_observer(visible=True)
        
        # 5. Python controller
        time.sleep(5)
        proxy_mode = not args.direct
        start_controller(proxy_mode=proxy_mode)
        
        log("=" * 60)
        log("Debug session running!")
        log("  Browser: http://127.0.0.1:8080/?skipLogin=true")
        log("  Python controller: running in background")
        log("  Logs: /tmp/autogenesis-proxy/")
        log("=" * 60)
        
        # Monitor
        while running:
            time.sleep(10)
            for name, proc in list(processes.items()):
                if proc.poll() is not None:
                    log(f"  WARNING: {name} exited with code {proc.poll()}")
    
    elif args.servers_only:
        start_real_servers()
        wait_for_servers()
        log("Servers running. Press Ctrl+C to stop.")
        while running:
            time.sleep(5)
    
    elif args.proxy_only:
        start_proxy()
        log("Proxy running. Press Ctrl+C to stop.")
        while running:
            time.sleep(5)
    
    elif args.observer_only:
        start_observer(visible=True)
        log("Observer running. Press Ctrl+C to stop.")
        while running:
            time.sleep(5)
    
    elif args.controller_only:
        start_controller(proxy_mode=not args.direct)
        log("Controller running. Press Ctrl+C to stop.")
        while running:
            time.sleep(5)
    
    else:
        parser.print_help()

if __name__ == "__main__":
    main()