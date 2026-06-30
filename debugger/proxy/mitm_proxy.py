#!/usr/bin/env python3
"""
Autogenesis MITM Proxy + Observer
Intercepts all game client traffic (browser) and mirrors it to Python controller.
Browser → proxy (:7070/:9080) → real servers (:7071/:9081)
Python → real servers directly (:7071/:9081)

The key insight: Python drives via REST/SSE and WebSocket to the real servers,
the browser connects to the proxy which logs everything and forwards to real servers.
The browser has no idea it's going through a proxy — it's a transparent MITM.

Usage:
    python3 mitm_proxy.py --start
        Proxy listens on :7070 (HTTP/SSE) and :9080 (WS)
        Forwarding to :7071 and :9081 (real servers)
        
    Browser connects to: ws://127.0.0.1:9080/events?playerId=...
    Python connects to:  ws://127.0.0.1:9081/events?playerId=...  (direct, bypassing proxy)

    python3 mitm_proxy.py --status   # Show running proxy
    python3 mitm_proxy.py --stop      # Stop proxy
"""

import argparse
import asyncio
import json
import logging
import os
import signal
import sys
import time
from datetime import datetime
from pathlib import Path

LOG_DIR = Path("/tmp/autogenesis-proxy")
LOG_DIR.mkdir(exist_ok=True)

EVENT_LOG = LOG_DIR / f"events-{datetime.now().strftime('%Y%m%d-%H%M%S')}.jsonl"
WS_LOG    = LOG_DIR / f"ws-{datetime.now().strftime('%Y%m%d-%H%M%S')}.jsonl"
PROXY_LOG = LOG_DIR / f"proxy.log"

logging.basicConfig(
    level=logging.INFO,
    format='%(asctime)s [%(levelname)s] %(message)s',
    handlers=[
        logging.FileHandler(PROXY_LOG),
        logging.StreamHandler(sys.stdout)
    ]
)
logger = logging.getLogger("proxy")

def log_json(ch, direction, player_id, msg_type, data=None):
    with open(EVENT_LOG, "a") as f:
        f.write(json.dumps({
            "ts": datetime.now().isoformat(),
            "ch": ch, "dir": direction, "pid": player_id,
            "type": msg_type, "data": data
        }) + "\n")

def log_ws(player_id, direction, data):
    try:
        parsed = json.loads(data)
    except Exception:
        parsed = {"raw": data[:200]}
    with open(WS_LOG, "a") as f:
        f.write(json.dumps({
            "ts": datetime.now().isoformat(),
            "pid": player_id, "dir": direction, "msg": parsed
        }) + "\n")

def extract_player_id(path):
    if "playerId=" in path:
        for part in path.split("playerId="):
            candidate = part.split("&")[0]
            if len(candidate) > 5:
                return candidate
    return ""

# ── WS Frame parse/build ────────────────────────────────────────────────────────

async def read_ws_frame(reader):
    """Read one WS frame. Returns bytes or None."""
    try:
        header = await reader.read(2)
        if not header or len(header) < 2:
            return None
        opcode = header[0] & 0x0F
        masked = (header[1] & 0x80) != 0
        length = header[1] & 0x7F
        offset = 2
        if length == 126:
            ext = await reader.read(2)
            length = int.from_bytes(ext, 'big')
            offset = 4
        elif length == 127:
            ext = await reader.read(8)
            length = int.from_bytes(ext, 'big')
            offset = 10
        mask_bits = b''
        if masked:
            mask_bits = await reader.read(4)
            offset += 4
        payload = await reader.read(length)
        if masked and mask_bits:
            payload = bytes(b ^ mask_bits[i % 4] for i, b in enumerate(payload))
        return bytes([0x80 | opcode]) + _build_length(length) + payload
    except Exception:
        return None

def _build_length(length):
    if length < 126:
        return bytes([length])
    elif length < 65536:
        return bytes([126]) + length.to_bytes(2, 'big')
    else:
        return bytes([127]) + length.to_bytes(8, 'big')

async def send_ws_frame(writer, opcode, payload):
    """Send a WS frame (unmasked, server→client)."""
    frame = bytearray()
    frame.append(0x80 | opcode)
    frame.extend(_build_length(len(payload)))
    frame.extend(payload)
    writer.write(bytes(frame))
    await writer.drain()

# ── HTTP relay ──────────────────────────────────────────────────────────────────

async def relay_http(reader, writer, target_host, target_port, player_id):
    """Read HTTP request, forward to target, stream response back."""
    try:
        # Request line
        line = await reader.readline()
        if not line:
            return
        request = line.decode().strip()
        parts = request.split()
        # Handle both "GET /path HTTP/1.1" and "GET http://host:port/path HTTP/1.1"
        if len(parts) == 4:
            method, path = parts[0], parts[1]
        else:
            method, path, _ = parts  # 3 parts
        
        # Headers
        headers = {}
        while True:
            line = await reader.readline()
            if line in (b'\r\n', b'\n', b''):
                break
            key, _, value = line.decode().strip().partition(": ")
            headers[key.lower()] = value
        
        # Body
        body = b""
        if "content-length" in headers:
            cl = int(headers["content-length"])
            body = await reader.readexactly(cl)
        
        pid = player_id or extract_player_id(path)
        log_json("http", "rx", pid, f"{method} {path}", body[:80] if body else None)
        
        # Connect to target
        try:
            r, w = await asyncio.wait_for(
                asyncio.open_connection(target_host, target_port),
                timeout=5.0
            )
        except Exception as e:
            logger.error(f"HTTP: connect to {target_host}:{target_port} failed: {e}")
            return
        
        # Forward request
        req = f"{request}\r\n"
        for k, v in headers.items():
            if k not in ["host", "content-length"] and not k.startswith("sec-"):
                req += f"{k}: {v}\r\n"
        if body:
            req += f"content-length: {len(body)}\r\n"
        req += "\r\n"
        w.write(req.encode())
        if body:
            w.write(body)
        await w.drain()
        
        log_json("http", "tx", pid, f"→{target_port}")
        
        # Read response
        status = await r.readline()
        if not status:
            return
        
        resp_headers = {}
        while True:
            line = await r.readline()
            if line in (b'\r\n', b'\n', b''):
                break
            key, _, value = line.decode().strip().partition(": ")
            resp_headers[key.lower()] = value
        
        # Forward status + headers
        writer.write(status)
        for k, v in resp_headers.items():
            if k not in ["transfer-encoding", "content-encoding"]:
                writer.write(f"{k}: {v}\r\n".encode())
        writer.write(b"\r\n")
        
        log_json("http", "tx", pid, "resp", status.decode().strip()[:50])
        
        # Forward body
        if "chunked" in resp_headers.get("transfer-encoding", ""):
            while True:
                chunk_line = await r.readline()
                if not chunk_line or chunk_line.strip() == b"0":
                    writer.write(b"0\r\n\r\n")
                    await writer.drain()
                    break
                chunk_size = int(chunk_line.strip(), 16)
                chunk = await r.readexactly(chunk_size)
                writer.write(chunk_line)
                writer.write(chunk)
                await writer.drain()
                await r.readline()
        elif "content-length" in resp_headers:
            cl = int(resp_headers["content-length"])
            body = await r.readexactly(cl)
            writer.write(body)
            await writer.drain()
        elif "text/event-stream" in resp_headers.get("content-type", ""):
            try:
                while True:
                    line = await asyncio.wait_for(r.readline(), timeout=60.0)
                    if not line:
                        break
                    if line.strip():
                        log_json("sse", "tx", pid, "sse", line.decode().strip()[:80])
                    writer.write(line)
                    await writer.drain()
            except asyncio.TimeoutError:
                pass
            except Exception:
                pass
        else:
            if "content-length" not in resp_headers:
                body = await r.read()
                writer.write(body)
                await writer.drain()
        
        w.close()
        await w.wait_closed()
    except Exception as e:
        logger.error(f"HTTP relay error: {e}")
    finally:
        try:
            writer.close()
            await writer.wait_closed()
        except Exception:
            pass

# ── WS relay ───────────────────────────────────────────────────────────────────

async def ws_relay(reader, writer, player_id, target_host, target_port):
    """Bidirectional WS relay with full frame logging."""
    try:
        try:
            real_reader, real_writer = await asyncio.wait_for(
                asyncio.open_connection(target_host, target_port),
                timeout=5.0
            )
        except Exception as e:
            logger.error(f"WS-{player_id}: connect to {target_host}:{target_port} failed: {e}")
            return
        
        logger.info(f"WS-{player_id}: relay active")
        
        async def forward(src, dst, direction):
            """Relay WS frames src → dst."""
            try:
                while True:
                    frame = await read_ws_frame(src)
                    if not frame:
                        break
                    opcode = frame[0] & 0x0F
                    payload = frame[2 + (frame[1] & 0x7F == 126 and 2 or 0) + (frame[1] & 0x7F == 127 and 8 or 0):]
                    # Extract payload properly
                    length_byte = frame[1]
                    mask_bit = (length_byte & 0x80) != 0
                    length = length_byte & 0x7F
                    header_end = 2
                    if length == 126:
                        header_end = 4
                        length = int.from_bytes(frame[2:4], 'big')
                    elif length == 127:
                        header_end = 10
                        length = int.from_bytes(frame[2:10], 'big')
                    if mask_bit:
                        header_end += 4
                    payload_start = header_end
                    actual_payload = frame[payload_start:payload_start+length]
                    
                    if opcode == 1:  # Text
                        try:
                            text = actual_payload.decode('utf-8')
                            log_ws(player_id, direction, text)
                        except Exception:
                            pass
                    elif opcode == 8:
                        break
                    
                    # Forward frame as-is
                    dst.write(frame)
                    await dst.drain()
            except Exception as e:
                logger.debug(f"WS-{player_id} {direction} ended: {e}")
        
        await asyncio.gather(
            forward(reader, real_writer, "c→s"),
            forward(real_reader, writer, "s→c"),
        )
        
        real_writer.close()
        await real_writer.wait_closed()
    except Exception as e:
        logger.error(f"WS-{player_id} relay error: {e}")
    finally:
        try:
            writer.close()
            await writer.wait_closed()
        except Exception:
            pass
        logger.info(f"WS-{player_id}: closed")

# ── Connection router ─────────────────────────────────────────────────────────

async def handle_connection(reader, writer):
    """Route HTTP or WS upgrade."""
    try:
        line = await reader.readline()
        if not line:
            writer.close()
            return
        
        request = line.decode().strip()
        
        if "GET" in request and ("Upgrade" in request or "/events" in request):
            method, path, _ = request.split()
            
            # Read remaining headers
            headers = {}
            while True:
                line = await reader.readline()
                if line in (b'\r\n', b'\n', b''):
                    break
                key, _, value = line.decode().strip().partition(": ")
                headers[key.lower()] = value
            
            pid = extract_player_id(path)
            
            if "Upgrade" in headers.get("upgrade", "") or "websocket" in headers.get("upgrade", "").lower():
                # WebSocket upgrade
                logger.info(f"WS upgrade: {path} (pid={pid})")
                key = headers.get("sec-websocket-key", "")
                import base64, hashlib
                accept = base64.b64encode(
                    hashlib.sha1((key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").encode()).digest()
                ).decode()
                writer.write(
                    "HTTP/1.1 101 Switching Protocols\r\n"
                    "Upgrade: websocket\r\n"
                    "Connection: Upgrade\r\n"
                    f"Sec-WebSocket-Accept: {accept}\r\n\r\n".encode()
                )
                await writer.drain()
                await ws_relay(reader, writer, pid, "127.0.0.1", REAL_WS_PORT)
            else:
                # SSE HTTP
                logger.info(f"SSE HTTP: {path} (pid={pid})")
                await relay_http(reader, writer, "127.0.0.1", REAL_SE_PORT, pid)
        else:
            # Regular HTTP
            method, path, _ = request.split()
            pid = extract_player_id(path)
            logger.info(f"HTTP: {method} {path} (pid={pid})")
            await relay_http(reader, writer, "127.0.0.1", REAL_SE_PORT, pid)
    except Exception as e:
        logger.error(f"Connection error: {e}")
        try:
            writer.close()
            await writer.wait_closed()
        except Exception:
            pass

# ── Main ───────────────────────────────────────────────────────────────────────

REAL_SE_PORT = 7071
REAL_WS_PORT = 9081

async def main(http_port, ws_port):
    logger.info(f"Proxy starting:")
    logger.info(f"  HTTP/SSE :{http_port} → 127.0.0.1:{REAL_SE_PORT}")
    logger.info(f"  WebSocket :{ws_port} → 127.0.0.1:{REAL_WS_PORT}")
    logger.info(f"  Events: {EVENT_LOG}")
    logger.info(f"  WS log:   {WS_LOG}")
    
    http_srv = await asyncio.start_server(handle_connection, "127.0.0.1", http_port)
    ws_srv   = await asyncio.start_server(handle_connection, "127.0.0.1", ws_port)
    
    logger.info(f"Proxy listening on :{http_port} (HTTP/SSE) and :{ws_port} (WS)")
    
    async with http_srv:
        async with ws_srv:
            await asyncio.Future()

if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--start", action="store_true")
    parser.add_argument("--http-port", type=int, default=7070)
    parser.add_argument("--ws-port", type=int, default=9080)
    parser.add_argument("--real-se-port", type=int, default=7071)
    parser.add_argument("--real-ws-port", type=int, default=9081)
    parser.add_argument("--stop", action="store_true")
    args = parser.parse_args()
    
    if args.stop:
        import subprocess
        for port in [args.http_port, args.ws_port]:
            subprocess.run(f"fuser -k {port}/tcp 2>/dev/null", shell=True)
        print(f"Proxy on :{args.http_port}/:{args.ws_port} stopped")
        sys.exit(0)
    
    REAL_SE_PORT = args.real_se_port
    REAL_WS_PORT = args.real_ws_port
    
    def shutdown(sig, frame):
        logger.info("Proxy shutdown")
        sys.exit(0)
    signal.signal(signal.SIGINT, shutdown)
    signal.signal(signal.SIGTERM, shutdown)
    
    asyncio.run(main(args.http_port, args.ws_port))