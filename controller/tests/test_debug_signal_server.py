"""
Tests for debug_signal_server.py

Covers:
- DebugSignal thread-safety (set/get/clear)
- DebugSignalServer lifecycle (start/stop)
- GET /debug/signal returns signal value
- POST /debug/signal sets signal value
- Signal PERSISTS across multiple GET polls (no auto-clear)
- GET /debug/enabled returns OK
- GET /debug/browser_websocket_id stores and returns browser's websocket_id
- POST /debug/echo echoes back msg param
"""

import pytest
import threading
import time
import urllib.request
import urllib.error
from typing import Generator
import sys
import os

# Add controller dir to path so imports work
sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from debug_signal_server import DebugSignal, DebugSignalServer, _DebugHandler


# -------------------------------------------------------------------
# DebugSignal unit tests
# -------------------------------------------------------------------

def test_debug_signal_set_get():
    ds = DebugSignal()
    ds.set("GAME_STARTED:Lord Maple Tree:3")
    assert ds.get() == "GAME_STARTED:Lord Maple Tree:3"


def test_debug_signal_clear():
    ds = DebugSignal()
    ds.set("SHOW_MAP")
    assert ds.get() == "SHOW_MAP"
    ds.clear()
    assert ds.get() == ""


def test_debug_signal_empty_initial():
    ds = DebugSignal()
    assert ds.get() == ""


def test_debug_signal_overwrite():
    ds = DebugSignal()
    ds.set("FIRST")
    ds.set("SECOND")
    assert ds.get() == "SECOND"


def test_debug_signal_thread_safety():
    """Verify that concurrent set/get operations don't crash or corrupt data."""
    ds = DebugSignal()
    errors = []

    def writer(value: str, count: int) -> None:
        for i in range(count):
            try:
                ds.set(f"{value}_{i}")
            except Exception as e:
                errors.append(e)

    def reader(count: int) -> None:
        for _ in range(count):
            try:
                _ = ds.get()
            except Exception as e:
                errors.append(e)

    threads = []
    for i in range(4):
        t = threading.Thread(target=writer, args=(f"WRITER{i}", 500))
        threads.append(t)
    for i in range(4):
        t = threading.Thread(target=reader, args=(500,))
        threads.append(t)

    for t in threads:
        t.start()
    for t in threads:
        t.join()

    assert errors == [], f"Thread safety violations: {errors}"


# -------------------------------------------------------------------
# DebugSignalServer integration tests
# -------------------------------------------------------------------

@pytest.fixture
def server() -> Generator[DebugSignalServer, None, None]:
    srv = DebugSignalServer(port=19755)
    srv.start()
    yield srv
    srv.stop()


def make_url(path: str) -> str:
    return f"http://127.0.0.1:19755{path}"


def get(path: str) -> str:
    with urllib.request.urlopen(make_url(path), timeout=5) as resp:
        return resp.read().decode()


def post(path: str, data: bytes = b"") -> int:
    req = urllib.request.Request(make_url(path), data=data, method="POST")
    with urllib.request.urlopen(req, timeout=5) as resp:
        return resp.status


# -------------------------------------------------------------------
# Lifecycle
# -------------------------------------------------------------------

def test_server_starts_and_is_active(server: DebugSignalServer):
    assert server.is_active() is True


def test_server_stop(server: DebugSignalServer):
    server.stop()
    assert server.is_active() is False


# -------------------------------------------------------------------
# GET /debug/enabled
# -------------------------------------------------------------------

def test_debug_enabled_returns_ok(server: DebugSignalServer):
    assert get("/debug/enabled") == "OK"


# -------------------------------------------------------------------
# POST /debug/signal
# -------------------------------------------------------------------

def test_post_signal_sets_value(server: DebugSignalServer):
    status = post("/debug/signal", b"GAME_STARTED:Lord Maple Tree:3")
    assert status == 200
    server._signal._lock.acquire()
    try:
        assert server._signal._value == "GAME_STARTED:Lord Maple Tree:3"
    finally:
        server._signal._lock.release()


def test_post_signal_via_http_returns_200(server: DebugSignalServer):
    status = post("/debug/signal", b"SHOW_MAP")
    assert status == 200


# -------------------------------------------------------------------
# GET /debug/signal — signal persistence (the key property)
# -------------------------------------------------------------------

def test_get_signal_returns_current_value(server: DebugSignalServer):
    server.set_signal("SHOW_MAP")
    assert get("/debug/signal") == "SHOW_MAP"


def test_get_signal_empty_when_not_set(server: DebugSignalServer):
    assert get("/debug/signal") == ""


def test_signal_persists_across_multiple_polls(server: DebugSignalServer):
    """
    Critical test: the signal must NOT be auto-cleared after a GET.

    The bug was that GET /debug/signal called signal.clear() after returning
    the value. If the browser navigated to the page AFTER Python had already
    sent GAME_STARTED, the browser's first poll would find an empty signal
    (already cleared) and never trigger GameplayUI.

    With the fix, signals persist until explicitly overwritten.
    """
    server.set_signal("GAME_STARTED:Lord Maple Tree:3")

    # Poll 5 times without any intervening POST
    results = []
    for _ in range(5):
        results.append(get("/debug/signal"))
        time.sleep(0.05)  # Small delay to simulate real polling

    # All polls must return the same signal value
    assert results == ["GAME_STARTED:Lord Maple Tree:3"] * 5, \
        f"Signal was cleared between polls! Got: {results}"

    # Verify the signal is still set in the server
    assert server._signal.get() == "GAME_STARTED:Lord Maple Tree:3"


def test_signal_persists_for_10_consecutive_polls(server: DebugSignalServer):
    """Extended version to more closely simulate real browser polling."""
    server.set_signal("SHOW_MAP")
    for i in range(10):
        result = get("/debug/signal")
        assert result == "SHOW_MAP", \
            f"Poll #{i+1}: expected 'SHOW_MAP', got '{result}' (signal was cleared!)"


def test_new_signal_overwrites_old_signal(server: DebugSignalServer):
    """Setting a new signal must overwrite the old one."""
    server.set_signal("FIRST")
    assert get("/debug/signal") == "FIRST"

    server.set_signal("SECOND")
    assert get("/debug/signal") == "SECOND"


# -------------------------------------------------------------------
# GET /debug/browser_websocket_id
# -------------------------------------------------------------------

def test_browser_websocket_id_returns_empty_initially(server: DebugSignalServer):
    assert get("/debug/browser_websocket_id") == ""


def test_browser_websocket_id_stored_via_query_param(server: DebugSignalServer):
    """
    KVision calls GET /debug/signal?websocket_id=XXX on every poll.
    The server should store this so Python can read it.
    """
    # Simulate browser polling with websocket_id in query string
    get("/debug/signal?websocket_id=lord-1234567890-999999")

    # Python reads via GET /debug/browser_websocket_id
    assert get("/debug/browser_websocket_id") == "lord-1234567890-999999"


def test_browser_websocket_id_updates_on_subsequent_polls(server: DebugSignalServer):
    get("/debug/signal?websocket_id=first-id")
    assert get("/debug/browser_websocket_id") == "first-id"

    get("/debug/signal?websocket_id=second-id")
    assert get("/debug/browser_websocket_id") == "second-id"


# -------------------------------------------------------------------
# POST /debug/echo
# -------------------------------------------------------------------

def test_echo_returns_ECHO_msg(server: DebugSignalServer):
    # POST /debug/echo?msg=XXX echoes back the msg parameter
    req = urllib.request.Request(
        make_url("/debug/echo?msg=hello_world"),
        data=b"",
        method="POST"
    )
    with urllib.request.urlopen(req, timeout=5) as resp:
        assert resp.read().decode() == "ECHO: hello_world"


# -------------------------------------------------------------------
# Concurrent signal access
# -------------------------------------------------------------------

def test_concurrent_get_and_set(server: DebugSignalServer):
    """Multiple threads reading and writing signals simultaneously."""
    errors = []

    def setter(value: str, count: int) -> None:
        for i in range(count):
            try:
                server.set_signal(f"{value}_{i}")
            except Exception as e:
                errors.append(e)

    def getter(count: int) -> None:
        for _ in range(count):
            try:
                result = get("/debug/signal")
                # Result should always be a string (possibly empty)
                assert isinstance(result, str)
            except Exception as e:
                errors.append(e)

    threads = []
    for i in range(4):
        t = threading.Thread(target=setter, args=(f"SETTER{i}", 100))
        threads.append(t)
    for i in range(4):
        t = threading.Thread(target=getter, args=(100,))
        threads.append(t)

    for t in threads:
        t.start()
    for t in threads:
        t.join()

    assert errors == [], f"Concurrent access errors: {errors}"


if __name__ == "__main__":
    pytest.main([__file__, "-v"])