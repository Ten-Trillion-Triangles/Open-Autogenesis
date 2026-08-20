# Autogenesis Debugger

Debug tooling for observing game client + Python controller network traffic.

## Architecture

```
Browser ──(skipLogin)────→ :8080 (webpack)
       └──────→ :7070 (SE proxy) ──→ :7071 (real server-extend)
       └──────→ :9080 (WS proxy) ──→ :9081 (real game server)
       
Python controller ──────→ :7071 (SE direct) + :9081 (WS direct)
```

**Three operating modes:**

1. **Direct** (default): Python talks directly to real servers (ports 7071/9081). Browser talks to real servers via webpack proxy on 8080.

2. **Proxy-observed**: Browser connects through MITM proxy on 7070/9080 → logs all browser ↔ server traffic to `/tmp/autogenesis-proxy/`.

3. **Dual-client**: Python drives matchmaking, browser observes same game in real-time through proxy.

## Components

### `proxy/mitm_proxy.py`
MITM proxy that intercepts browser ↔ server traffic. Listens on 7070/9080, forwards to 7071/9081.
```
python3 mitm_proxy.py --start --http-port 7070 --ws-port 9080 --real-se-port 7071 --real-ws-port 9081
```

### `observer/browser_observer.py`
Playwright-based browser that captures all console + CDP network events.
```
python3 browser_observer.py --visible --hold 7200
```

### `observer_session.py`
Orchestrates full debug session: servers + proxy + browser + controller.
```
python3 observer_session.py --start
```

### `controller/controller.py`
Python game controller with `--se-port` and `--ws-port` flags for flexible targeting.
```
python3 controller.py --no-ui --se-port 7071 --ws-port 9081
```

## Files Created

```
debugger/
├── proxy/
│   └── mitm_proxy.py          # MITM proxy (WS + HTTP/SSE interception)
├── observer/
│   └── browser_observer.py     # Playwright browser observer
├── scripts/
│   └── start_servers.sh        # Quick server launcher
├── observer_session.py         # Full session orchestrator
└── README.md                   # This file
```

## Setup

### Start all servers + proxy + browser + controller
```bash
python3 observer_session.py --start
```

### Start servers only
```bash
python3 observer_session.py --servers
```

### Browser observer only (servers must be running)
```bash
python3 observer_session.py --browser-only
```

### Controller only (servers must be running)
```bash
python3 observer_session.py --controller-only
```

## Status

- **MITM proxy**: Written, syntax-verified, needs real server test
- **Browser observer**: Written, syntax-verified, needs real server test
- **Observer session**: Written, syntax-verified, needs real server test
- **Controller**: Modified to accept `--se-port` and `--ws-port` flags
- **Servers**: Currently running on :7070 and :9080 (direct mode)

## Next Steps

1. Test MITM proxy with live servers — verify browser traffic captured in logs
2. Test browser observer with visible Chromium — confirm console log capture
3. Test dual-client mode — browser sees game state while Python drives

## Logs

All output goes to `/tmp/autogenesis-proxy/`:
- `events-*.jsonl` — HTTP/SSE event log
- `ws-*.jsonl` — WebSocket frame log
- `proxy.log` — Proxy startup/runtime logs
- `browser-*.log` — Browser console events
- `network-*.jsonl` — CDP network events
