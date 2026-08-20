#!/usr/bin/env python3
"""
Autogenesis Observer — Browser + Controller combined debug session.
Launches a visible Chromium browser to observe the game while Python drives it.
The browser is the "game client" — Python drives the server via network calls,
and the browser receives the same UI updates as any normal game client.

Usage:
    python3 observer_session.py --start        # Start full session
    python3 observer_session.py --browser-only # Just browser observer
    python3 observer_session.py --controller-only # Just controller
    python3 observer_session.py --stop        # Stop everything
"""

import argparse
import asyncio
import os
import signal
import subprocess
import sys
import time
from pathlib import Path

PROJECT_ROOT = Path(__file__).resolve().parent.parent
CONTROLLER_SCRIPT = PROJECT_ROOT / "controller" / "controller.py"
PYTHON = "/tmp/autogenesis-dev/bin/python"

LOG_DIR = Path("/tmp/autogenesis-proxy")
LOG_DIR.mkdir(exist_ok=True)

def log(msg):
    ts = time.strftime("%H:%M:%S")
    print(f"[{ts}] {msg}")

# ── Server management ──────────────────────────────────────────────────────────

def start_servers():
    """Start the three game servers."""
    log("Starting servers...")
    
    se = subprocess.Popen(
        ["bash", "-c", f"cd {PROJECT_ROOT} && ./gradlew :server-extend:run > /tmp/se.log 2>&1"],
        stdout=subprocess.PIPE, stderr=subprocess.PIPE
    )
    log(f"  server-extend PID: {se.pid}")
    
    srv = subprocess.Popen(
        ["bash", "-c", f"cd {PROJECT_ROOT} && ./gradlew :server:run > /tmp/srv.log 2>&1"],
        stdout=subprocess.PIPE, stderr=subprocess.PIPE
    )
    log(f"  game server PID: {srv.pid}")
    
    kv = subprocess.Popen(
        ["bash", "-c", f"cd {PROJECT_ROOT} && ./gradlew runKvisionNoHotReload > /tmp/kv.log 2>&1"],
        stdout=subprocess.PIPE, stderr=subprocess.PIPE
    )
    log(f"  webpack PID: {kv.pid}")
    
    return {"server-extend": se, "game-server": srv, "webpack": kv}

def wait_for_ports(ports, timeout=120):
    """Wait for all ports to be listening."""
    import socket
    start = time.time()
    while time.time() - start < timeout:
        ready = True
        for port in ports:
            try:
                sock = socket.create_connection(("127.0.0.1", port), timeout=1)
                sock.close()
            except Exception:
                ready = False
                break
        if ready:
            log(f"  All ports ready: {ports}")
            return True
        time.sleep(5)
    log(f"  Timeout waiting for ports: {ports}")
    return False

def stop_all():
    """Kill all server and controller processes by port."""
    log("Stopping all processes...")
    for port in [7070, 9080, 8080, 9091, 9092]:
        subprocess.run(f"fuser -k {port}/tcp 2>/dev/null", shell=True)
    time.sleep(2)
    log("All stopped")

# ── Browser observer ────────────────────────────────────────────────────────────

def start_browser():
    """Launch visible Chromium with full CDP network + console capture."""
    log("Starting browser observer...")
    
    browser_script = PROJECT_ROOT / "debugger" / "observer" / "browser_observer.py"
    
    proc = subprocess.Popen(
        [PYTHON, str(browser_script), "--visible", "--hold", "7200"],
        stdout=open(LOG_DIR / "browser-stdout.log", "w"),
        stderr=subprocess.STDOUT,
    )
    log(f"  Browser PID: {proc.pid}")
    return proc

# ── Controller ─────────────────────────────────────────────────────────────────

def start_controller():
    """Start Python game controller."""
    log("Starting controller...")
    
    proc = subprocess.Popen(
        [PYTHON, "-u", str(CONTROLLER_SCRIPT), "--no-ui"],
        stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
        bufsize=1,
    )
    log(f"  Controller PID: {proc.pid}")
    return proc

# ── Monitor ─────────────────────────────────────────────────────────────────────

def monitor_processes(processes, controller_proc):
    """Monitor all processes, log output, restart if needed."""
    import select
    
    # Merge stdout/stderr fds
    outputs = []
    name_map = {}
    
    for name, proc in processes.items():
        if proc.stdout:
            outputs.append(proc.stdout)
            name_map[proc.stdout.fileno()] = name
    
    if controller_proc and controller_proc.stdout:
        outputs.append(controller_proc.stdout)
        name_map[controller_proc.stdout.fileno()] = "controller"
    
    log("Monitoring started. Press Ctrl+C to stop.")
    
    while True:
        readable, _, _ = select.select(outputs, [], [], 10)
        
        for fd in readable:
            name = name_map.get(fd.fileno(), "unknown")
            line = fd.readline()
            if line:
                ts = time.strftime("%H:%M:%S")
                sys.stdout.write(f"[{ts}][{name}] {line.decode()}")
                sys.stdout.flush()
        
        # Check if any server died
        for name, proc in list(processes.items()):
            if proc.poll() is not None:
                log(f"  WARNING: {name} died (exit={proc.returncode})")
        
        if controller_proc and controller_proc.poll() is not None:
            log(f"  Controller exited (code={controller_proc.returncode})")
            break

# ── Main ───────────────────────────────────────────────────────────────────────

def main():
    parser = argparse.ArgumentParser(description="Autogenesis Debug Session")
    parser.add_argument("--start", action="store_true", help="Start full session (servers + browser + controller)")
    parser.add_argument("--servers", action="store_true", help="Start servers only")
    parser.add_argument("--browser-only", action="store_true", help="Browser observer only")
    parser.add_argument("--controller-only", action="store_true", help="Controller only")
    parser.add_argument("--stop", action="store_true", help="Stop all")
    args = parser.parse_args()

    if args.stop:
        stop_all()
        return

    server_procs = {}
    controller_proc = None
    browser_proc = None

    def shutdown(sig, frame):
        log("Shutdown signal...")
        for p in [browser_proc, controller_proc]:
            if p: p.terminate()
        stop_all()
        sys.exit(0)
    signal.signal(signal.SIGINT, shutdown)
    signal.signal(signal.SIGTERM, shutdown)

    if args.start or args.servers:
        server_procs = start_servers()
        wait_for_ports([7070, 9080, 8080])

    if args.start or args.browser_only:
        browser_proc = start_browser()
        time.sleep(3)  # Let browser load

    if args.start or args.controller_only:
        controller_proc = start_controller()

    if args.start:
        log("=" * 60)
        log("Debug session running!")
        log("  Browser: http://127.0.0.1:8080/?skipLogin=true")
        log("  Controller: running (PID {})".format(controller_proc.pid if controller_proc else "N/A"))
        log("  Logs: /tmp/autogenesis-proxy/")
        log("=" * 60)
        
        monitor_processes(server_procs, controller_proc)

    elif args.servers:
        log("Servers running. Press Ctrl+C to stop.")
        while True: time.sleep(5)

    elif args.browser_only:
        log("Browser running. Press Ctrl+C to stop.")
        while True: time.sleep(5)

    elif args.controller_only:
        log("Controller running. Press Ctrl+C to stop.")
        while True: time.sleep(5)

if __name__ == "__main__":
    main()
