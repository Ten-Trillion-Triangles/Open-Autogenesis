#!/usr/bin/env python3
"""
Autogenesis Unified Session — Browser + Controller share the same game session.

Architecture:
  Browser (Playwright) and Python Controller share the SAME playerId.
  1. Browser connects SSE to server-extend (playerId=lord-<ts>-<rand>) → gets session.ready
  2. Browser calls requestGame via REST with same playerId → server-extend notifies game server
  3. Browser connects WebSocket to game server with same playerId
  4. Controller shares the same playerId, also connects to game server
  5. Game server sees both connections as part of the same session → sends UI events to both
  6. Browser receives UI events, triggers screenshots, MiniMax describes state

Usage:
  python unified_session.py --start
  python unified_session.py --stop
"""

import argparse
import asyncio
import os
import random
import signal
import socket
import subprocess
import sys
import time
from pathlib import Path
from datetime import datetime

PROJECT_ROOT = Path(__file__).resolve().parent.parent
PYTHON = "/tmp/autogenesis-dev/bin/python"
LOG_DIR = Path("/tmp/autogenesis-proxy")
LOG_DIR.mkdir(exist_ok=True)

# ── Config ────────────────────────────────────────────────────────────────────

COMMANDER_NAME = "Lord Maple Tree"
PLAYER_NAME = "Lord Maple Tree"
PLAYER_TYPE = "Land"
PLAYER_TRAIT = "Researcher"
PLAYER_RARITY = "LEGENDARY"
PLAYER_DESC = "Emperor of All Canada, Duke of the Golden Forest"
AI_OPPONENT_COUNT = 0  # For a real test, set to 1-3

# ── Utilities ──────────────────────────────────────────────────────────────────

def log(msg):
    ts = time.strftime("%H:%M:%S")
    print(f"[{ts}] {msg}")

def wait_port(port, timeout=120):
    start = time.time()
    while time.time() - start < timeout:
        try:
            s = socket.create_connection(("127.0.0.1", port), timeout=1)
            s.close()
            return True
        except:
            time.sleep(3)
    return False

def wait_servers():
    log("Waiting for servers...")
    for port, name in [(7070, "server-extend"), (9080, "game"), (8080, "webpack")]:
        if not wait_port(port, 120):
            log(f"  FATAL: {name} (:{port}) not ready")
            sys.exit(1)
        log(f"  :{port} ({name}) ready")

# ── Browser Observer (screenshot + describe) ────────────────────────────────────

async def run_browser_observer(player_id: str, session_ready_event: asyncio.Event):
    """Run browser observer — connects to server-extend, then game server, captures screenshots."""
    import json
    
    sys.path.insert(0, str(PROJECT_ROOT / "debugger" / "observer"))
    from browser_observer import launch_observer, LOG_DIR as B_LOG_DIR, SS_DIR
    
    log(f"Browser: Playwright starting with playerId={player_id}")
    
    # We need to intercept the browser's network to:
    # 1. Open SSE to server-extend (triggers session.ready)
    # 2. Call requestGame via REST
    # 3. Open WebSocket to game server
    # The browser_observer.py handles the browser itself
    
    # For now, use the existing browser_observer with custom session handling
    # by patching the URL to include our playerId
    
    base_url = f"http://127.0.0.1:8080/?skipLogin=true&playerId={player_id}"
    
    # Use Playwright directly for full control
    from playwright.async_api import async_playwright
    
    ss_dir = LOG_DIR / "screenshots"
    ss_dir.mkdir(exist_ok=True)
    
    async with async_playwright() as p:
        browser = await p.chromium.launch(headless=False)
        context = await browser.new_context()
        page = await context.new_page()
        
        log(f"Browser: Navigating to {base_url}")
        await page.goto(base_url, timeout=30000)
        await page.wait_for_timeout(2000)
        
        # Initial screenshot
        ts = datetime.now().strftime("%H%M%S")
        await page.screenshot(path=str(ss_dir / f"{ts}_page_load.png"))
        log(f"Browser: page_load screenshot saved")
        
        # Capture console messages
        console_entries = []
        
        async def handle_console(msg):
            text = msg.text
            console_entries.append({"type": msg.type, "text": text, "ts": datetime.now().isoformat()})
            if any(k in text for k in ["activeTurn", "turnComplete", "Resolution", "thinkingUpdate", "announceTurn", "LordMaple"]):
                ts2 = datetime.now().strftime("%H%M%S")
                await page.screenshot(path=str(ss_dir / f"{ts2}_event_{datetime.now().strftime('%H%M%S')}.png"))
                log(f"Browser: [EVENT SCREENSHOT] {text[:80]}")
        
        page.on("console", handle_console)
        
        # Wait for session ready via SSE
        log("Browser: Waiting for session.ready from server-extend...")
        
        # Open SSE connection to server-extend
        import urllib.request
        sse_url = f"http://127.0.0.1:7070/events?playerId={player_id}&guestMode=true"
        
        async def read_sse():
            try:
                req = urllib.request.Request(sse_url)
                req.add_header("Accept", "text/event-stream")
                with urllib.request.urlopen(req, timeout=30) as resp:
                    for line in resp:
                        line = line.decode().strip()
                        if line.startswith("data: "):
                            data = line[6:]
                            if "session.ready" in data:
                                log(f"Browser: Got session.ready!")
                                session_ready_event.set()
                                # Immediately call requestGame
                                await call_request_game(player_id)
                                # Then connect to game server WebSocket
                                await connect_game_ws(page, player_id)
            except Exception as e:
                log(f"Browser: SSE error: {e}")
        
        async def call_request_game(pid):
            """Call requestGame to trigger server-extend → game server notification."""
            import urllib.request, json as jsonlib
            
            # First, create commander if needed
            await create_commander_if_needed(page, pid)
            
            # Wait a moment
            await page.wait_for_timeout(1000)
            
            # Call requestGame via REST
            payload = {
                "type": "request",
                "id": "1",
                "method": "server.extend.requestGame",
                "params": {
                    "userName": PLAYER_NAME,
                    "gameType": "SINGLEPLAYER",
                    "accelByteId": pid,
                    "websocketId": pid,
                    "selectedCommander": {
                        "id": pid,
                        "name": COMMANDER_NAME,
                        "type": PLAYER_TYPE,
                        "trait": PLAYER_TRAIT,
                        "imageUrl": None,
                        "rarity": PLAYER_RARITY,
                        "description": PLAYER_DESC
                    },
                    "aiOpponentCount": AI_OPPONENT_COUNT,
                    "aiOnly": False,
                    "matchPool": "standard"
                }
            }
            
            try:
                data = jsonlib.dumps(payload).encode()
                req = urllib.request.Request(
                    f"http://127.0.0.1:7070/rpc?playerId={pid}",
                    data=data,
                    headers={"Content-Type": "application/json"},
                    method="POST"
                )
                with urllib.request.urlopen(req, timeout=15) as resp:
                    log(f"Browser: requestGame response: {resp.status}")
            except Exception as e:
                log(f"Browser: requestGame error: {e}")
        
        async def create_commander_if_needed(page, pid):
            """Use browser to create the commander via the UI."""
            log("Browser: Ensuring commander exists via UI...")
            
            # Navigate and create commander if needed
            try:
                # Click NEW COMMANDER + 
                elements = await page.query_selector_all("button")
                for el in elements:
                    text = await el.inner_text()
                    if "NEW COMMANDER" in text.upper():
                        await el.click()
                        await page.wait_for_timeout(1000)
                        break
                
                # Fill in the form
                name_input = await page.query_selector("input[placeholder*='commander']")
                if name_input:
                    await name_input.fill(COMMANDER_NAME)
                    desc_input = await page.query_selector("textarea")
                    if desc_input:
                        await desc_input.fill(PLAYER_DESC)
                        nation_input = await page.query_selector_all("input")[-1] if await page.query_selector_all("input") else None
                        if nation_input:
                            await nation_input.fill("The Empire of Maple")
                        
                        # Click CREATE
                        buttons = await page.query_selector_all("button")
                        for b in buttons:
                            t = await b.inner_text()
                            if "CREATE" in t.upper():
                                await b.click()
                                await page.wait_for_timeout(3000)
                                log("Browser: Commander created/verified")
                                break
            except Exception as e:
                log(f"Browser: Commander creation: {e}")
        
        async def connect_game_ws(page, pid):
            """Evaluate JavaScript in the browser page to connect WebSocket to game server."""
            log(f"Browser: Connecting WebSocket to game server with playerId={pid}")
            
            ws_js = f"""
            (function() {{
                const protocol = location.protocol === 'https:' ? 'wss:' : 'ws:';
                const ws = new WebSocket(`ws://127.0.0.1:9080/events?playerId={{playerId}}&accelbyteId=guest-user&guestMode=true`);
                
                ws.onopen = () => {{
                    console.log('[BROWSER_WS] Connected to game server');
                }};
                
                ws.onmessage = (event) => {{
                    try {{
                        const data = JSON.parse(event.data);
                        console.log('[BROWSER_WS] Received:', JSON.stringify(data).substring(0, 200));
                        
                        // Screenshot on game events
                        if (data.method && (data.method.includes('ui.') || data.method.includes('game.'))) {{
                            console.log('[BROWSER_WS] GAME EVENT:', data.method);
                        }}
                    }} catch(e) {{
                        console.log('[BROWSER_WS] Parse error:', e.message);
                    }}
                }};
                
                ws.onerror = (err) => {{
                    console.error('[BROWSER_WS] Error:', err);
                }};
                
                ws.onclose = () => {{
                    console.log('[BROWSER_WS] Connection closed');
                }};
                
                window._gameWs = ws;
            }})();
            """.replace("{playerId}", pid)
            
            try:
                await page.evaluate(ws_js)
                log("Browser: WebSocket JS injected")
            except Exception as e:
                log(f"Browser: WS injection error: {e}")
        
        # Run SSE reader and hold browser
        sse_task = asyncio.create_task(read_sse())
        
        # Periodic screenshots every 30s
        last_screenshot_time = time.time()
        
        try:
            while True:
                await asyncio.sleep(5)
                
                # Periodic screenshot
                if time.time() - last_screenshot_time >= 30:
                    ts3 = datetime.now().strftime("%H%M%S")
                    await page.screenshot(path=str(ss_dir / f"{ts3}_periodic_{int(time.time()-last_screenshot_time)}s.png"))
                    log(f"Browser: periodic screenshot ({len(console_entries)} console entries)")
                    last_screenshot_time = time.time()
                    
                    # Check if game has started (look for game-related console messages)
                    game_started = any("Round" in e["text"] or "Turn" in e["text"] or "activeTurn" in e["text"] for e in console_entries[-50:])
                    if game_started:
                        log("Browser: Game appears to be in progress!")
                        
        except asyncio.CancelledError:
            log("Browser: Shutting down...")
        finally:
            await browser.close()

# ── Python Controller ──────────────────────────────────────────────────────────

def run_controller(player_id: str):
    """Run Python controller with the same playerId as the browser."""
    log(f"Controller: Starting with playerId={player_id}")
    
    env = os.environ.copy()
    env["PYTHONPATH"] = str(PROJECT_ROOT)
    
    controller_script = f"""
import sys
sys.path.insert(0, "{PROJECT_ROOT}")
from controller.controller import GameController, CONFIG

# Override player_id to match browser
CONFIG["player_name"] = "{PLAYER_NAME}"
CONFIG["player_type"] = "{PLAYER_TYPE}"
CONFIG["player_trait"] = "{PLAYER_TRAIT}"
CONFIG["ai_opponent_count"] = {AI_OPPONENT_COUNT}

# Create controller with --no-ui
import argparse
parser = argparse.ArgumentParser()
parser.add_argument("--no-ui", action="store_true")
parser.add_argument("--server-url", default="http://127.0.0.1:7070")
parser.add_argument("--game-url", default="ws://127.0.0.1:9080")
args = parser.parse_args()

controller = GameController(args)
controller.player_id = "{player_id}"
controller.run()
"""
    
    proc = subprocess.Popen(
        [PYTHON, "-c", controller_script],
        env=env,
        stdout=subprocess.PIPE,
        stderr=subprocess.STDOUT,
        cwd=str(PROJECT_ROOT)
    )
    return proc

# ── Main ──────────────────────────────────────────────────────────────────────

def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--start", action="store_true")
    parser.add_argument("--stop", action="store_true")
    args = parser.parse_args()
    
    if args.stop:
        log("Stopping all processes...")
        subprocess.run(["pkill", "-f", "browser_observer|controller.py|gradle.*server"], stderr=subprocess.DEVNULL)
        return
    
    if args.start:
        log("=== Starting Autogenesis Unified Session ===")
        
        # Kill any existing
        subprocess.run(["pkill", "-f", "browser_observer|controller.py|gradle.*server"], stderr=subprocess.DEVNULL)
        time.sleep(2)
        
        # Start servers
        log("Starting servers...")
        se_proc = subprocess.Popen(
            ["bash", "-c", f"cd {PROJECT_ROOT} && ./gradlew :server-extend:run --no-daemon > /tmp/se.log 2>&1"],
            stdout=subprocess.DEVNULL
        )
        srv_proc = subprocess.Popen(
            ["bash", "-c", f"cd {PROJECT_ROOT} && ./gradlew :server:run --no-daemon > /tmp/srv.log 2>&1"],
            stdout=subprocess.DEVNULL
        )
        kv_proc = subprocess.Popen(
            ["bash", "-c", f"cd {PROJECT_ROOT} && ./gradlew runKvisionNoHotReload --no-daemon > /tmp/kv.log 2>&1"],
            stdout=subprocess.DEVNULL
        )
        
        wait_servers()
        log("All servers ready!")
        
        # Generate shared playerId
        shared_player_id = f"lord-{int(time.time()*1000)}-{random.randint(100000,999999)}"
        log(f"Shared playerId: {shared_player_id}")
        
        # Run browser observer + controller as coroutines
        session_ready = asyncio.Event()
        
        async def run_all():
            browser_task = asyncio.create_task(run_browser_observer(shared_player_id, session_ready))
            
            # Wait for session ready, then start controller
            await session_ready.wait()
            log("Main: Session ready — starting controller...")
            controller_proc = run_controller(shared_player_id)
            
            # Hold until interrupted
            try:
                await asyncio.sleep(600)
            except asyncio.CancelledError:
                log("Main: Cancelled")
            finally:
                controller_proc.terminate()
                browser_task.cancel()
        
        try:
            asyncio.run(run_all())
        except KeyboardInterrupt:
            log("Interrupted!")
        
        # Cleanup
        log("Cleaning up...")
        subprocess.run(["pkill", "-f", "browser_observer|controller.py|gradle.*server"], stderr=subprocess.DEVNULL)
        log("Done!")

if __name__ == "__main__":
    main()