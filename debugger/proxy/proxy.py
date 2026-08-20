#!/usr/bin/env python3
"""
Proxy + Observer for Autogenesis
Intercepts all game server traffic (HTTP/SSE/WebSocket) and logs it.
Browser and Python both connect through the proxy — it logs everything
and can optionally mirror traffic between player IDs.

Usage:
    python3 proxy.py                    # proxy mode (default)
    python3 proxy.py --replay           # replay game events to browser player ID
    python3 proxy.py --help            # full options
"""

import argparse
import asyncio
import json
import logging
import signal
import sys
import threading
import time
import uuid
from datetime import datetime
from pathlib import Path

# -----------------------------------------------------------------------------
# Logging setup
# -----------------------------------------------------------------------------
LOG_DIR = Path("/tmp/autogenesis-proxy")
LOG_DIR.mkdir(exist_ok=True)

LOG_FILE = LOG_DIR / f"proxy-{datetime.now().strftime('%Y%m%d-%H%M%S')}.log"
EVENT_LOG = LOG_DIR / f"events-{datetime.now().strftime('%Y%m%d-%H%M%S')}.jsonl"
WS_LOG = LOG_DIR / f"ws-frames-{datetime.now().strftime('%Y%m%d-%H%M%S')}.jsonl"

logging.basicConfig(
    level=logging.INFO,
    format='%(asctime)s [%(levelname)s] %(message)s',
    handlers=[
        logging.FileHandler(LOG_FILE),
        logging.StreamHandler(sys.stdout)
    ]
)
logger = logging.getLogger("proxy")

# JSONL event log helper
def log_event(channel, direction, player_id, frame_type, data=None, raw=None):
    entry = {
        "ts": datetime.now().isoformat(),
        "channel": channel,           # "ws" or "http" or "sse"
        "direction": direction,       # "inbound" or "outbound"
        "player_id": player_id,
        "frame_type": frame_type,
        "data": data,
    }
    with open(EVENT_LOG, "a") as f:
        f.write(json.dumps(entry) + "\n")

def log_ws_frame(player_id, direction, data):
    try:
        parsed = json.loads(data) if isinstance(data, str) else data
    except Exception:
        parsed = {"raw": data[:200] if isinstance(data, str) else str(data)}
    
    entry = {
        "ts": datetime.now().isoformat(),
        "player_id": player_id,
        "direction": direction,
        "data": parsed,
    }
    with open(WS_LOG, "a") as f:
        f.write(json.dumps(entry) + "\n")

# -----------------------------------------------------------------------------
# Configuration
# -----------------------------------------------------------------------------
# Real server addresses (where proxy forwards to)
REAL_GAME_HOST = "127.0.0.1"
REAL_GAME_WS_PORT = 9081      # proxy listens on 9080, forwards to 9081
REAL_SE_HTTP_PORT = 7071      # proxy listens on 7070, forwards to 7071

# Proxy listen addresses
PROXY_WS_PORT = 9080
PROXY_SE_PORT = 7070

# Browser player IDs (for replay/mirror mode)
# Set via --browser-ids or leave empty to auto-detect
BROWSER_WS_ID = ""
BROWSER_REST_ID = ""

# Replay mode: mirror game server → browser
REPLAY_MODE = False

# -----------------------------------------------------------------------------
# Global state
# -----------------------------------------------------------------------------
ws_connections = {}      # player_id → WebSocket connection (real server side)
http_sessions = {}       # player_id → session info
running = True

# -----------------------------------------------------------------------------
# WebSocket frame relay (proxy between browser and game server)
# This works as a transparent middle hop — browser connects to proxy :9080,
# proxy connects to real game server :9081 and forwards frames both ways.
# -----------------------------------------------------------------------------

class WSRelay:
    """WebSocket relay that intercepts and logs all frames."""
    
    def __init__(self, player_id, client_ws, target_host, target_port):
        self.player_id = player_id
        self.client_ws = client_ws    # browser-side connection
        self.target_host = target_host
        self.target_port = target_port
        self.server_ws = None
        self.forwarder_task = None
        self.reader_task = None
        
    async def start(self):
        """Connect to real game server and start relaying."""
        import websockets
        
        uri = f"ws://{self.target_host}:{self.target_port}/events?playerId={self.player_id}&guestMode=true"
        logger.info(f"[WS-{self.player_id}] Connecting to real server: {uri}")
        
        try:
            self.server_ws = await websockets.connect(uri, ping_timeout=None)
            logger.info(f"[WS-{self.player_id}] Connected to real server")
            
            # Start bidirectional relay
            self.forwarder_task = asyncio.create_task(self.forward_client_to_server())
            self.reader_task = asyncio.create_task(self.read_server())
            
        except Exception as e:
            logger.error(f"[WS-{self.player_id}] Failed to connect to real server: {e}")
            await self.client_ws.close()
    
    async def forward_client_to_server(self):
        """Forward frames from browser client to real game server."""
        try:
            async for msg in self.client_ws:
                log_ws_frame(self.player_id, "client→server", msg)
                if self.server_ws:
                    await self.server_ws.send(msg)
                    log_ws_frame(self.player_id, "→real-server", msg)
        except Exception as e:
            logger.debug(f"[WS-{self.player_id}] Client receive loop ended: {e}")
    
    async def read_server(self):
        """Forward frames from real game server to browser client."""
        try:
            async for msg in self.server_ws:
                log_ws_frame(self.player_id, "server→client", msg)
                await self.client_ws.send(msg)
                log_ws_frame(self.player_id, "←real-server→browser", msg)
        except Exception as e:
            logger.debug(f"[WS-{self.player_id}] Server receive loop ended: {e}")
    
    async def close(self):
        if self.forwarder_task:
            self.forwarder_task.cancel()
        if self.reader_task:
            self.reader_task.cancel()
        if self.server_ws:
            await self.server_ws.close()

# -----------------------------------------------------------------------------
# HTTP/SSE proxy — intercepts REST calls and SSE streams
# -----------------------------------------------------------------------------

async def handle_http_request(reader, writer):
    """Handle HTTP requests (REST RPC + SSE) and forward to real server."""
    import aiohttp
    
    request_line = await reader.readline()
    if not request_line:
        writer.close()
        return
    
    method, path, _ = request_line.decode().split()
    
    # Read headers
    headers = {}
    while True:
        line = await reader.readline()
        if line in (b'\r\n', b'\n', b''):
            break
        key, _, value = line.decode().strip().partition(": ")
        headers[key.lower()] = value
    
    # Extract playerId from query string
    player_id = ""
    if "playerId=" in path:
        for part in path.split("playerId="):
            candidate = part.split("&")[0]
            if len(candidate) > 5:
                player_id = candidate
    
    # Read body if present
    body = b""
    if "content-length" in headers:
        cl = int(headers["content-length"])
        body = await reader.readexactly(cl)
    
    log_event("http", "inbound", player_id, f"{method} {path}", raw=body[:200] if body else None)
    
    # Determine target port
    if "events" in path and "playerId=" in path:
        # SSE channel → server-extend
        target_host = REAL_GAME_HOST
        target_port = REAL_SE_HTTP_PORT
    else:
        # REST RPC → server-extend
        target_host = REAL_GAME_HOST
        target_port = REAL_SE_HTTP_PORT
    
    target_url = f"http://{target_host}:{target_port}{path}"
    
    logger.info(f"[HTTP-{player_id}] {method} {path} → {target_url}")
    
    try:
        async with aiohttp.ClientSession() as session:
            req_headers = {k: v for k, v in headers.items() if k not in ["host", "connection"]}
            
            async with session.request(
                method, target_url,
                headers=req_headers,
                data=body if body else None,
                timeout=aiohttp.ClientTimeout(total=5)
            ) as resp:
                # Forward response back to client
                resp_headers = [f"HTTP/1.1 {resp.status} {resp.reason}\r\n"]
                for k, v in resp.headers.items():
                    if k.lower() not in ["transfer-encoding", "connection"]:
                        resp_headers.append(f"{k}: {v}\r\n")
                resp_headers.append("\r\n")
                
                writer.write("".join(resp_headers).encode())
                
                # For SSE, forward line by line
                if "text/event-stream" in resp.headers.get("content-type", ""):
                    async for line in resp.content:
                        writer.write(line)
                        await writer.drain()
                        # Log SSE lines
                        if line.strip():
                            log_event("sse", "outbound", player_id, "sse-line", raw=line.decode().strip()[:100])
                else:
                    # Regular HTTP response
                    body_data = await resp.read()
                    writer.write(body_data)
                    await writer.drain()
                    
                    log_event("http", "outbound", player_id, f"resp-{resp.status}", raw=body_data[:200] if body_data else None)
    
    except Exception as e:
        logger.error(f"[HTTP-{player_id}] Error: {e}")
        writer.write(f"HTTP/1.1 502 Proxy Error\r\n\r\nProxy error: {e}".encode())
    
    await writer.drain()
    writer.close()

# -----------------------------------------------------------------------------
# WebSocket server (browser connects here instead of real game server)
# We use aiohttp for the HTTP parts and a custom WS handler
# -----------------------------------------------------------------------------

async def handle_ws(reader, writer):
    """Handle WebSocket upgrade requests."""
    # Read HTTP upgrade request
    request_line = await reader.readline()
    if not request_line:
        writer.close()
        return
    
    method, path, _ = request_line.decode().split()
    if method != "GET":
        writer.write(b"HTTP/1.1 405 Method Not Allowed\r\n\r\n")
        await writer.drain()
        writer.close()
        return
    
    # Read headers
    headers = {}
    while True:
        line = await reader.readline()
        if line in (b'\r\n', b'\n', b''):
            break
        key, _, value = line.decode().strip().partition(": ")
        headers[key.lower()] = value
    
    # Check for WebSocket upgrade
    if headers.get("upgrade", "").lower() != "websocket":
        writer.write(b"HTTP/1.1 400 Expected WebSocket Upgrade\r\n\r\n")
        await writer.drain()
        writer.close()
        return
    
    # Extract playerId
    player_id = ""
    if "playerId=" in path:
        for part in path.split("playerId="):
            candidate = part.split("&")[0]
            if len(candidate) > 5:
                player_id = candidate
    
    logger.info(f"[WS-{player_id}] Browser WebSocket upgrade request")
    
    # Read body if any
    if b'\r\n\r\n' in b'':
        pass
    
    # Send upgrade response
    key = headers.get("sec-websocket-key", "")
    import base64, hashlib
    accept_key = base64.b64encode(hashlib.sha1((key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").encode()).digest()).decode()
    
    response = (
        "HTTP/1.1 101 Switching Protocols\r\n"
        "Upgrade: websocket\r\n"
        "Connection: Upgrade\r\n"
        f"Sec-WebSocket-Accept: {accept_key}\r\n\r\n"
    )
    writer.write(response.encode())
    await writer.drain()
    
    # Create relay
    relay = WSRelay(player_id, None, REAL_GAME_HOST, REAL_GAME_WS_PORT)
    
    # Wrap raw socket as WebSocket-like object for our relay
    class RWrapped:
        def __init__(self, r, w):
            self.r = r
            self.w = w
            self._closed = False
        async def recv(self):
            # Simple WebSocket frame reading
            data = await self.r.read(2)
            if not data:
                raise Exception("Connection closed")
            fin = (data[0] & 0x80) != 0
            opcode = data[0] & 0x0F
            length = data[1] & 0x7F
            if length == 126:
                b = await self.r.read(2)
                length = int.from_bytes(b, 'big')
            elif length == 127:
                b = await self.r.read(8)
                length = int.from_bytes(b, 'big')
            payload = await self.r.read(length)
            if opcode == 8:
                self._closed = True
            return payload.decode('utf-8', errors='replace')
        async def send(self, msg):
            # Simple WS frame encoding
            import struct
            msg_bytes = msg.encode('utf-8')
            frame = bytearray()
            frame.append(0x81)  # fin + text opcode
            if len(msg_bytes) < 126:
                frame.append(0x80 | len(msg_bytes))
            elif len(msg_bytes) < 65536:
                frame.append(0x80 | 126)
                frame.extend(struct.pack('>H', len(msg_bytes)))
            else:
                frame.append(0x80 | 127)
                frame.extend(struct.pack('>Q', len(msg_bytes)))
            # Mask
            mask = [ord(c) for c in "xxxx"]
            frame.extend(mask)
            masked = bytearray(msg_bytes)
            for i in range(len(masked)):
                masked[i] ^= mask[i % 4]
            frame.extend(masked)
            await self.w.write(bytes(frame))
            await self.w.drain()
        async def close(self):
            self._closed = True
            self.w.close()
        @property
        def closed(self):
            return self._closed
    
    wrapped = RWrapped(reader, writer)
    relay.client_ws = wrapped
    
    # Connect to real server
    import websockets
    try:
        uri = f"ws://{REAL_GAME_HOST}:{REAL_GAME_WS_PORT}/events?playerId={player_id}&guestMode=true"
        server_ws = await websockets.connect(uri, ping_timeout=None)
        relay.server_ws = server_ws
        
        # Start relay tasks
        async def relay_client_to_server():
            try:
                async for msg in wrapped:
                    log_ws_frame(player_id, "client→server", msg)
                    await server_ws.send(msg)
            except Exception as e:
                logger.debug(f"[WS-{player_id}] Client→Server relay ended: {e}")
        
        async def relay_server_to_client():
            try:
                async for msg in server_ws:
                    log_ws_frame(player_id, "server→client", msg)
                    await wrapped.send(msg)
            except Exception as e:
                logger.debug(f"[WS-{player_id}] Server→Client relay ended: {e}")
        
        await asyncio.gather(relay_client_to_server(), relay_server_to_client())
    except Exception as e:
        logger.error(f"[WS-{player_id}] Relay error: {e}")
        await wrapped.close()

# -----------------------------------------------------------------------------
# Main server — runs WS on :9080 and HTTP on :7070
# -----------------------------------------------------------------------------

async def start_proxy():
    """Start the proxy server."""
    logger.info(f"Starting proxy...")
    logger.info(f"  WS proxy: :{PROXY_WS_PORT} → :{REAL_GAME_WS_PORT}")
    logger.info(f"  HTTP proxy: :{PROXY_SE_PORT} → :{REAL_SE_HTTP_PORT}")
    logger.info(f"  Logs: {LOG_FILE}")
    logger.info(f"  Event log: {EVENT_LOG}")
    logger.info(f"  WS frame log: {WS_LOG}")
    
    # Start HTTP server on :7070
    http_server = await asyncio.start_server(handle_http_request, "127.0.0.1", PROXY_SE_PORT)
    
    # Start WS server on :9080
    ws_server = await asyncio.start_server(handle_ws, "127.0.0.1", PROXY_WS_PORT)
    
    logger.info(f"Proxy listening on :{PROXY_SE_PORT} (HTTP) and :{PROXY_WS_PORT} (WS)")
    
    async with http_server:
        async with ws_server:
            await asyncio.Future()  # run forever

def main():
    global REPLAY_MODE, BROWSER_WS_ID, BROWSER_REST_ID
    
    parser = argparse.ArgumentParser(description="Autogenesis Proxy + Observer")
    parser.add_argument("--replay", action="store_true", help="Mirror game events to browser player ID")
    parser.add_argument("--browser-ws-id", default="", help="Browser WebSocket player ID")
    parser.add_argument("--browser-rest-id", default="", help="Browser REST/SSE player ID")
    parser.add_argument("--real-ws-port", type=int, default=9081, help="Real game server WS port (default 9081)")
    parser.add_argument("--real-se-port", type=int, default=7071, help="Real server-extend HTTP port (default 7071)")
    parser.add_argument("--proxy-ws-port", type=int, default=9080, help="Proxy WS listen port (default 9080)")
    parser.add_argument("--proxy-se-port", type=int, default=7070, help="Proxy HTTP listen port (default 7070)")
    args = parser.parse_args()
    
    global REAL_GAME_WS_PORT, REAL_SE_HTTP_PORT, PROXY_WS_PORT, PROXY_SE_PORT
    global BROWSER_WS_ID, BROWSER_REST_ID, REPLAY_MODE
    
    REAL_GAME_WS_PORT = args.real_ws_port
    REAL_SE_HTTP_PORT = args.real_se_port
    PROXY_WS_PORT = args.proxy_ws_port
    PROXY_SE_PORT = args.proxy_se_port
    BROWSER_WS_ID = args.browser_ws_id
    BROWSER_REST_ID = args.browser_rest_id
    REPLAY_MODE = args.replay
    
    logger.info(f"Proxy configuration:")
    logger.info(f"  WS proxy: :{PROXY_WS_PORT} → :{REAL_GAME_WS_PORT}")
    logger.info(f"  HTTP proxy: :{PROXY_SE_PORT} → :{REAL_SE_HTTP_PORT}")
    
    def signal_handler(sig, frame):
        global running
        logger.info("Shutting down proxy...")
        running = False
        sys.exit(0)
    
    signal.signal(signal.SIGINT, signal_handler)
    signal.signal(signal.SIGTERM, signal_handler)
    
    asyncio.run(start_proxy())

if __name__ == "__main__":
    main()
