#!/usr/bin/env python3
"""
Autogenesis Browser Observer
Launches a visible Chromium browser that connects to the game through the proxy.
Captures all console output, WebSocket frames, and network traffic to log files.

Usage:
    python3 browser_observer.py --no-proxy     # connect directly to real servers
    python3 browser_observer.py --proxy        # connect through proxy (must start proxy first)
    python3 browser_observer.py --visible     # show browser window (default: headless)
"""

import argparse
import asyncio
import json
import logging
import os
import signal
import sys
import time
import urllib.parse
from datetime import datetime
from pathlib import Path

# ============================================================
# Paths & Logging
# ============================================================
LOG_DIR = Path("/tmp/autogenesis-proxy")
LOG_DIR.mkdir(exist_ok=True)

BROWSER_LOG = LOG_DIR / f"browser-{datetime.now().strftime('%Y%m%d-%H%M%S')}.log"
NETWORK_LOG = LOG_DIR / f"network-{datetime.now().strftime('%Y%m%d-%H%M%S')}.jsonl"

logging.basicConfig(
    level=logging.INFO,
    format='%(asctime)s [%(levelname)s] %(message)s',
    handlers=[
        logging.FileHandler(BROWSER_LOG),
        logging.StreamHandler(sys.stdout)
    ]
)
logger = logging.getLogger("observer")

# ============================================================
# CDP (Chrome DevTools Protocol) via Playwright
# ============================================================

def setup_logging_cdp(cdp_session, player_id):
    """Subscribe to all CDP domains for comprehensive logging."""
    
    def log_cdp_event(method, params):
        entry = {
            "ts": datetime.now().isoformat(),
            "player_id": player_id,
            "type": "cdp",
            "method": method,
            "params": params,
        }
        with open(NETWORK_LOG, "a") as f:
            f.write(json.dumps(entry) + "\n")
    
    # Network events
    cdp_session.on("Network.requestWillBeSent", lambda e: log_cdp_event("Network.requestWillBeSent", e.get("params", {})))
    cdp_session.on("Network.responseReceived", lambda e: log_cdp_event("Network.responseReceived", e.get("params", {})))
    cdp_session.on("Network.dataReceived", lambda e: log_cdp_event("Network.dataReceived", e.get("params", {})))
    cdp_session.on("Network.loadingFinished", lambda e: log_cdp_event("Network.loadingFinished", e.get("params", {})))
    cdp_session.on("Network.loadingFailed", lambda e: log_cdp_event("Network.loadingFailed", e.get("params", {})))
    
    # Page events
    cdp_session.on("Page.frameNavigated", lambda e: log_cdp_event("Page.frameNavigated", e.get("params", {})))
    cdp_session.on("Page.consoleApiCalled", lambda e: log_cdp_event("Page.consoleApiCalled", e.get("params", {})))
    cdp_session.on("Page.windowOpened", lambda e: log_cdp_event("Page.windowOpened", e.get("params", {})))
    cdp_session.on("Page.windowClosed", lambda e: log_cdp_event("Page.windowClosed", e.get("params", {})))
    
    # JS events
    cdp_session.on("Runtime.consoleMessage", lambda e: log_cdp_event("Runtime.consoleMessage", e.get("params", {})))
    cdp_session.on("Runtime.exceptionThrown", lambda e: log_cdp_event("Runtime.exceptionThrown", e.get("params", {})))
    cdp_session.on("Runtime.exceptionRevoked", lambda e: log_cdp_event("Runtime.exceptionRevoked", e.get("params", {})))
    
    # WebSocket events
    cdp_session.on("Network.webSocketCreated", lambda e: log_cdp_event("Network.webSocketCreated", e.get("params", {})))
    cdp_session.on("Network.webSocketWillBeSent", lambda e: log_cdp_event("Network.webSocketWillBeSent", e.get("params", {})))
    cdp_session.on("Network.webSocketReceived", lambda e: log_cdp_event("Network.webSocketReceived", e.get("params", {})))
    cdp_session.on("Network.webSocketFrameSent", lambda e: log_cdp_event("Network.webSocketFrameSent", e.get("params", {})))
    cdp_session.on("Network.webSocketFrameReceived", lambda e: log_cdp_event("Network.webSocketFrameReceived", e.get("params", {})))
    cdp_session.on("Network.webSocketClosed", lambda e: log_cdp_event("Network.webSocketClosed", e.get("params", {})))

async def get_browser_console_logs(page):
    """Extract browser console output from page context."""
    logs = []
    # Access browser console messages through CDP
    try:
        cdp = page._impl_obj._client
        # This accesses Playwright's CDP session for the page
    except Exception:
        pass
    return logs

# ============================================================
# Playwright browser launcher
# ============================================================

SS_DIR = LOG_DIR / "screenshots"
SS_DIR.mkdir(exist_ok=True)

async def take_screenshot(page, label):
    """Capture and save a screenshot."""
    ts = datetime.now().strftime("%H%M%S")
    path = SS_DIR / f"{ts}_{label}.png"
    try:
        await page.screenshot(path=path)
        logger.info(f"  [SCREENSHOT] saved {path}")
    except Exception as e:
        logger.warning(f"  [SCREENSHOT] failed: {e}")
    return path

async def launch_observer(proxy_mode=False, visible=True, hold_seconds=3600):
    """Launch browser as observer, capturing all traffic."""
    from playwright.async_api import async_playwright
    
    logger.info(f"Starting browser observer (proxy={proxy_mode}, visible={visible})")
    
    browser_logger = logging.getLogger("browser")
    browser_logger.info(f"Logs: {BROWSER_LOG}")
    browser_logger.info(f"Network: {NETWORK_LOG}")
    
    async with async_playwright() as p:
        # Launch browser
        browser_args = [
            "--no-default-browser-check",
            "--disable-extensions",
            "--disable-popup-blocking",
            "--disable-dev-shm-usage",
            "--disable-blink-features=AutomationControlled",
            "--no-sandbox",
        ]
        
        if visible:
            browser = await p.chromium.launch(headless=False, args=browser_args)
        else:
            browser = await p.chromium.launch(headless=True, args=browser_args)
        
        logger.info(f"Browser launched (visible={visible})")
        
        # Create dedicated context for game session
        context = await browser.new_context(
            viewport={"width": 1280, "height": 800},
            ignore_https_errors=True,
        )
        
        # Create page
        page = await context.new_page()
        
        # Determine URL based on proxy mode
        if proxy_mode:
            base_url = "http://127.0.0.1:8080"
        else:
            base_url = "http://127.0.0.1:8080"

        # Include playerId in URL so browser's Main.kt can use it to set window.PLAYER_ID
        if args.player_id:
            login_url = f"{base_url}/?skipLogin=true&playerId={urllib.parse.quote(args.player_id, safe='')}"
        else:
            login_url = f"{base_url}/?skipLogin=true"
        logger.info(f"Navigating to {login_url}")
        
        try:
            await page.goto(login_url, wait_until="networkidle", timeout=30000)
        except Exception as e:
            logger.warning(f"Page load hit networkidle timeout (expected if game running): {e}")
            # Still capture what we have
            await page.wait_for_timeout(2000)
        
        logger.info("Page loaded")
        
        # Initial screenshot
        asyncio.get_event_loop().create_task(take_screenshot(page, "page_load"))

        # Subscribe to console messages via page.on
        console_entries = []
        
        def handle_console_msg(msg):
            text = msg.text
            msg_type = msg.type
            
            # Log to file
            entry = {
                "ts": datetime.now().isoformat(),
                "type": "console",
                "msg_type": msg_type,
                "text": text,
            }
            with open(BROWSER_LOG, "a") as f:
                f.write(json.dumps(entry) + "\n")
            
            console_entries.append(entry)
            
            # Also print important ones to stdout
            if any(k in text for k in ["WebSocket", "kvision-ws", "rest-client", "session.ready", "Round", "Turn", "LordMaple", "ERROR", "error"]):
                logger.info(f"  [{msg_type.upper()}] {text[:120]}")
                # Screenshot on game events
                if any(k in text for k in ["activeTurn", "turnComplete", "forceShowTurnResolution", "Resolution", "Narrative", "thinkingUpdate", "announceTurn"]):
                    asyncio.get_event_loop().create_task(take_screenshot(page, f"event_{datetime.now().strftime('%H%M%S')}"))
            elif msg_type == "error":
                logger.error(f"  [CONSOLE ERROR] {text[:200]}")
        
        page.on("console", handle_console_msg)
        
        # Also subscribe to page errors
        def handle_page_error(err):
            entry = {
                "ts": datetime.now().isoformat(),
                "type": "page_error",
                "error": str(err),
            }
            with open(BROWSER_LOG, "a") as f:
                f.write(json.dumps(entry) + "\n")
            logger.error(f"  [PAGE ERROR] {str(err)[:200]}")
        
        page.on("pageerror", handle_page_error)
        
        # Get CDP session for comprehensive network logging
        try:
            # Access the underlying CDP session
            cdp_session = await page.context.new_cdp_session(page)
            
            # Subscribe to all network events
            await cdp_session.send("Network.enable")
            await cdp_session.send("Page.enable")
            await cdp_session.send("Runtime.enable")
            
            # Log all CDP events
            def handle_cdp_event(event):
                params = event.get("params", {})
                entry = {
                    "ts": datetime.now().isoformat(),
                    "type": "cdp",
                    "method": event.get("method"),
                    "params": params,
                }
                with open(NETWORK_LOG, "a") as f:
                    f.write(json.dumps(entry) + "\n")
                
                # Log WebSocket-related events to stdout
                method = event.get("method", "")
                if "WebSocket" in method or any(k in str(params) for k in ["kvision-ws", "rest-client", "ws://", "wss://"]):
                    logger.info(f"  [CDP {method}] {str(params)[:100]}")
                    # Screenshot on WebSocket game frames
                    if any(k in str(params) for k in ["ui.activeTurn", "ui.turnComplete", "ui.setResolution", "ui.forceShowTurn"]):
                        asyncio.get_event_loop().create_task(take_screenshot(page, f"ws_event_{datetime.now().strftime('%H%M%S')}"))
            
            cdp_session.on("*", handle_cdp_event)
            
            logger.info("CDP session active — all network traffic being captured")
        except Exception as e:
            logger.warning(f"Could not set up CDP session: {e}")
            logger.info("Falling back to Playwright's built-in network capture")
        
        # Capture initial network requests
        logger.info("Page loaded. Monitoring network and console...")
        
        # Wait and monitor — periodically print state
        start = time.time()
        last_log_time = start
        
        while time.time() - start < hold_seconds:
            await asyncio.sleep(5)
            elapsed = int(time.time() - start)
            
            # Every 30 seconds: summary + periodic screenshot
            if time.time() - last_log_time >= 30:
                alive_pages = len(context.pages)
                logger.info(f"  [{elapsed}s] Browser alive, {alive_pages} page(s), {len(console_entries)} console entries captured")
                asyncio.get_event_loop().create_task(take_screenshot(page, f"periodic_{int(elapsed)}s"))
                last_log_time = time.time()
        
        logger.info(f"Hold period complete ({hold_seconds}s). Cleaning up.")
        await context.close()
        await browser.close()

def main():
    parser = argparse.ArgumentParser(description="Autogenesis Browser Observer")
    parser.add_argument("--no-proxy", action="store_true", help="Connect directly to game servers (not through proxy)")
    parser.add_argument("--proxy", action="store_true", help="Connect through proxy")
    parser.add_argument("--headless", action="store_true", help="Run browser headless (default: visible)")
    parser.add_argument("--visible", action="store_true", help="Run browser visible (default: True unless --headless)")
    parser.add_argument("--hold", type=int, default=3600, help="Seconds to hold the browser open (default: 3600)")
    parser.add_argument("--base-url", default="http://127.0.0.1:8080", help="Game UI base URL")
    parser.add_argument("--output-dir", default="/tmp/autogenesis-proxy", help="Output directory for logs")
    parser.add_argument("--player-id", default="", help="Explicit player ID to pass as ?playerId= URL param")

    args = parser.parse_args()
    
    global BROWSER_LOG, NETWORK_LOG, LOG_DIR
    LOG_DIR = Path(args.output_dir)
    LOG_DIR.mkdir(exist_ok=True)
    BROWSER_LOG = LOG_DIR / f"browser-{datetime.now().strftime('%Y%m%d-%H%M%S')}.log"
    NETWORK_LOG = LOG_DIR / f"network-{datetime.now().strftime('%Y%m%d-%H%M%S')}.jsonl"
    
    visible = not args.headless
    proxy_mode = args.proxy and not args.no_proxy
    
    logger.info(f"Browser Observer starting:")
    logger.info(f"  Proxy mode: {proxy_mode}")
    logger.info(f"  Visible: {visible}")
    logger.info(f"  Hold: {args.hold}s")
    logger.info(f"  Browser log: {BROWSER_LOG}")
    logger.info(f"  Network log: {NETWORK_LOG}")
    
    def shutdown(sig, frame):
        logger.info("Shutting down observer...")
        sys.exit(0)
    signal.signal(signal.SIGINT, shutdown)
    signal.signal(signal.SIGTERM, shutdown)
    
    asyncio.run(launch_observer(
        proxy_mode=proxy_mode,
        visible=visible,
        hold_seconds=args.hold,
    ))

if __name__ == "__main__":
    main()
