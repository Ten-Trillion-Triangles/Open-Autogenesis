"""
Debug Signal Server for Python Controller → Browser KVision Bridge.

Provides a minimal HTTP server on port 7075 that KVision's DebugSignalBridge
polls via GET /debug/signal every 500ms. The Python controller writes signals
(via set_signal()) that KVision reads and dispatches to its UI handlers.

Signals:
    LOGIN_AS_GUEST         → DebugSignalBridge → DebugConsole.triggerLoginAsGuest()
    PLAY_WITH_COMMANDER:Name:Count → DebugConsole.triggerMatchmaking(name, count)
    CLOSE_OVERLAY          → DebugConsole.triggerCloseOverlay()
"""

from __future__ import annotations

import logging
import threading
from http.server import BaseHTTPRequestHandler, HTTPServer
from typing import Optional

logger = logging.getLogger("autogenesis_controller")


class DebugSignal:
    """Thread-safe signal container."""

    def __init__(self) -> None:
        self._value: str = ""
        self._lock = threading.Lock()

    def set(self, value: str) -> None:
        with self._lock:
            self._value = value

    def get(self) -> str:
        with self._lock:
            return self._value

    def clear(self) -> None:
        with self._lock:
            self._value = ""


class _DebugHandler(BaseHTTPRequestHandler):
    """HTTP request handler for the debug signal server."""

    signal: Optional[DebugSignal] = None
    # The browser's WebSocket ID, written when KVision polls with ?websocket_id=XXX
    # Read by Python via GET /debug/browser_websocket_id to use in matchmaking
    _browser_websocket_id: Optional[str] = None
    _id_lock = threading.Lock()

    def do_GET(self) -> None:
        if self.signal is None:
            self.send_response(503)
            self.end_headers()
            self.wfile.write(b"Signal not initialized")
            return

        # Parse any query parameters
        if "?" in self.path:
            path, query = self.path.split("?", 1)
            for param in query.split("&"):
                if "=" in param:
                    key, value = param.split("=", 1)
                    if key == "websocket_id":
                        with _DebugHandler._id_lock:
                            _DebugHandler._browser_websocket_id = value
                        logger.debug(f"DebugSignal: browser websocket_id → {value}")
        else:
            path = self.path

        if path == "/debug/enabled":
            self.send_response(200)
            self.send_header("Content-Type", "text/plain")
            self.end_headers()
            self.wfile.write(b"OK")
            return

        if path == "/debug/browser_websocket_id":
            # Python reads this to know which websocketId to use in matchmaking.
            # Returns the most recent websocket_id reported by the browser,
            # or empty string if the browser hasn't connected yet.
            with _DebugHandler._id_lock:
                ws_id = _DebugHandler._browser_websocket_id or ""
            self.send_response(200)
            self.send_header("Content-Type", "text/plain")
            self.end_headers()
            self.wfile.write(ws_id.encode("utf-8"))
            return

        if path == "/debug/signal":
            # Return current signal value.
            # NOTE: We do NOT auto-clear here. The signal persists across polls until
            # explicitly overwritten by a new signal. This is critical for the
            # dual-control architecture: if the browser navigates to the page AFTER
            # Python sent GAME_STARTED (while Python's session was already running),
            # the browser needs to receive that signal on its first poll.
            # If the signal needs to be re-sent (e.g. browser refreshed while game
            # is still active), Python calls set_signal() again which overwrites it.
            current = self.signal.get()
            self.send_response(200)
            self.send_header("Content-Type", "text/plain")
            self.end_headers()
            self.wfile.write(current.encode("utf-8"))
            return

        self.send_response(404)
        self.end_headers()

    def log_message(self, format: str, *args) -> None:
        pass

    def do_POST(self) -> None:
        """Allow Python to write signals via POST /debug/signal, and debug echo via POST /debug/echo."""
        if self.path == "/debug/signal":
            content_length = int(self.headers.get("Content-Length", 0))
            body = self.rfile.read(content_length).decode("utf-8")
            self.signal.set(body.strip())
            logger.debug(f"DebugSignal: POST set → '{body.strip()}'")
            self.send_response(200)
            self.send_header("Content-Type", "text/plain")
            self.end_headers()
            self.wfile.write(b"OK")
            return
        if self.path.startswith("/debug/echo?"):
            # Echo back the msg parameter for tracing
            import urllib.parse
            query = urllib.parse.urlparse(self.path).query
            params = urllib.parse.parse_qs(query)
            msg = params.get("msg", [""])[0]
            logger.debug(f"DebugSignal: echo → '{msg}'")
            self.send_response(200)
            self.send_header("Content-Type", "text/plain")
            self.end_headers()
            self.wfile.write(f"ECHO: {msg}".encode())
            return
        self.send_response(404)
        self.end_headers()


class DebugSignalServer:
    """
    HTTP server that exposes debug signals for KVision polling.

    Start it before the main loop; stop it during shutdown.
    The server runs in a daemon thread so it doesn't block the main thread.
    """

    DEFAULT_PORT = 7075

    def __init__(self, port: int = DEFAULT_PORT) -> None:
        self.port = port
        self._signal = DebugSignal()
        self._server: Optional[HTTPServer] = None
        self._thread: Optional[threading.Thread] = None

    def start(self) -> None:
        """Start the HTTP server in a daemon thread."""
        _DebugHandler.signal = self._signal

        self._server = HTTPServer(("127.0.0.1", self.port), _DebugHandler)
        self._server.allow_reuse_address = True

        self._thread = threading.Thread(
            target=self._server.serve_forever,
            name=f"DebugSignalServer-{self.port}",
            daemon=True
        )
        self._thread.start()
        logger.info(f"Debug signal server started on port {self.port}")

    def stop(self) -> None:
        """Shutdown the HTTP server."""
        if self._server is not None:
            self._server.shutdown()
            self._server = None
        if self._thread is not None:
            self._thread.join(timeout=5.0)
            self._thread = None
        logger.info("Debug signal server stopped")

    def set_signal(self, value: str) -> None:
        """
        Write a signal that KVision will read on the next poll.

        The signal persists until KVision reads it (at which point it's cleared
        automatically). Setting a new signal before the previous one is read
        overwrites the previous value.

        Args:
            value: Signal string, e.g. "LOGIN_AS_GUEST" or
                   "PLAY_WITH_COMMANDER:Lord Maple Tree:1"
        """
        self._signal.set(value)
        logger.debug(f"DebugSignal: set → '{value}'")

    def clear_signal(self) -> None:
        """Clear any pending signal."""
        self._signal.clear()

    def is_active(self) -> bool:
        """Returns True if the server thread is alive."""
        return self._thread is not None and self._thread.is_alive()

    def get_browser_websocket_id(self) -> Optional[str]:
        """
        Return the WebSocket ID most recently reported by the browser.

        KVision calls GET /debug/signal?websocket_id=XXX on every poll.
        The server stores this so GameController can read it after the poll
        and use the correct websocketId in its matchmaking REST call.

        Returns None if no browser has connected yet.
        """
        with _DebugHandler._id_lock:
            return _DebugHandler._browser_websocket_id