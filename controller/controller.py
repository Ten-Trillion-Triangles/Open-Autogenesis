#!/usr/bin/env python3
"""
Autogenesis Game Controller

A Python-based game controller for the Autogenesis game that manages
matchmaking, game state, and player actions via a curses-based TUI.

Game Servers:
    - server-extend (port 7070): REST/SSE for matchmaking
    - game server (port 9080): WebSocket for game state + pings
    - webpack dev server (port 8080): KVision browser UI (not needed)

Author: AI Assistant
Python: 3.11+
"""

from __future__ import annotations

import argparse
import json
import logging
import os
import random
import signal
import subprocess
import sys
import threading
import time
import uuid
from dataclasses import dataclass, field

from debug_signal_server import DebugSignalServer
from datetime import datetime
from enum import Enum, auto
from typing import Any, Callable, Optional

import requests
import websocket

# Curses is stdlib on Linux/macOS but not Windows
try:
    import curses
except ImportError:
    curses = None  # type: ignore

# =============================================================================
# Configuration
# =============================================================================

CONFIG = {
    "server_extend_host": "127.0.0.1",
    "server_extend_port": 7070,
    "game_server_host": "127.0.0.1",
    "game_server_port": 9080,
    "tpipe_host": "127.0.0.1",
    "tpipe_port": 8000,
    "max_reconnect_attempts": 10,
    "max_reconnect_delay": 60.0,
    "initial_reconnect_delay": 1.0,
    "pong_timeout": None,  # No timeout - managed by thread
    "log_dir": process.env.get("AUTOGENESIS_LOG_DIR", "./logs"),
    "player_name": "Lord Maple Tree",
    "player_type": "Land",
    "player_trait": "Researcher",
    "player_rarity": "LEGENDARY",
    "player_description": "Emperor of All Canada",
    "ai_opponent_count": 3,
    "match_pool": ["standard"],
    # playerAlias: stable identifier used to match Python controller to browser's
    # existing player slot. Extract from player_id mid-segment so it's stable
    # across reconnects (e.g., player_id "lord-1234567890-ABC" → alias "1234567890").
    # Server uses this to find the browser's existing playerStats entry.
    "player_alias": "",
}


# =============================================================================
# Logging Setup
# =============================================================================

def setup_logging() -> logging.Logger:
    """Set up logging to both file and console."""
    os.makedirs(CONFIG["log_dir"], exist_ok=True)
    timestamp = datetime.now().strftime("%Y%m%d_%H%M%S")
    log_file = os.path.join(CONFIG["log_dir"], f"controller_{timestamp}.log")
    
    logger = logging.getLogger("autogenesis_controller")
    logger.setLevel(logging.DEBUG)
    
    # File handler - all events
    file_handler = logging.FileHandler(log_file)
    file_handler.setLevel(logging.DEBUG)
    file_formatter = logging.Formatter(
        "%(asctime)s | %(levelname)-8s | %(threadName)-10s | %(message)s"
    )
    file_handler.setFormatter(file_formatter)
    
    # Console handler - info and above
    console_handler = logging.StreamHandler()
    console_handler.setLevel(logging.INFO)
    console_formatter = logging.Formatter("%(asctime)s | %(levelname)-8s | %(message)s")
    console_handler.setFormatter(console_formatter)
    
    logger.addHandler(file_handler)
    logger.addHandler(console_handler)
    
    logger.info(f"Logging to {log_file}")
    return logger


logger = setup_logging()


# =============================================================================
# Enums and Data Classes
# =============================================================================

class GameState(Enum):
    """Game state machine states."""
    DISCONNECTED = auto()
    MATCHMAKING = auto()
    IN_GAME = auto()
    MY_TURN = auto()
    AWAITING_RESULT = auto()
    GAME_OVER = auto()


class ConnectionStatus(Enum):
    """Connection status indicators."""
    DISCONNECTED = "DISCONNECTED"
    CONNECTING = "CONNECTING"
    CONNECTED = "CONNECTED"
    ERROR = "ERROR"


@dataclass
class PlayerInfo:
    """Player information for matchmaking."""
    player_id: str
    user_name: str
    commander_id: str
    commander_name: str
    commander_type: str
    commander_trait: str
    commander_rarity: str
    commander_description: str


@dataclass
class GameContext:
    """Current game context information."""
    round_number: int = 0
    current_actor: Optional[str] = None
    turn_order: list[str] = field(default_factory=list)
    scores: dict[str, int] = field(default_factory=dict)
    resources: dict[str, int] = field(default_factory=dict)
    websocket_id: Optional[str] = None
    # The browser's WebSocket ID, received from the debug server after the browser polls.
    # Python reads this via GET /debug/browser_websocket_id and uses it in matchmaking
    # so the browser's existing WebSocket session is matched to the game session.
    browser_websocket_id: Optional[str] = None
    session_ready: bool = False
    last_event: Optional[dict] = None
    event_history: list[dict] = field(default_factory=list)


# =============================================================================
# TPipe Client for AI Action Generation
# =============================================================================

class TPipeClient:
    """
    Client for connecting to TPipe (local DynamoDB on port 8000).
    Sends game state and receives narrative actions back.
    """
    
    def __init__(self, host: str = CONFIG["tpipe_host"], port: int = CONFIG["tpipe_port"]):
        self.host = host
        self.port = port
        self.url = f"http://{self.host}:{self.port}/"
        self.timeout = 30.0
    
    def generate_action(self, game_context: GameContext, player_name: str) -> Optional[str]:
        """
        Generate an action for the player based on current game state.
        
        Args:
            game_context: Current game state
            player_name: Name of the player to generate action for
            
        Returns:
            Narrative action string or None if generation fails
        """
        try:
            # Prepare game state summary for TPipe
            state_summary = {
                "round": game_context.round_number,
                "current_actor": game_context.current_actor,
                "turn_order": game_context.turn_order,
                "scores": game_context.scores,
                "resources": game_context.resources,
                "player_name": player_name,
                "event_history_count": len(game_context.event_history),
            }
            
            # Add recent events for context
            recent_events = game_context.event_history[-10:] if game_context.event_history else []
            state_summary["recent_events"] = recent_events
            
            payload = {
                "game_state": state_summary,
                "player": player_name,
                "action_type": "game_move"
            }
            
            logger.debug(f"TPipe request: {json.dumps(payload, indent=2)[:500]}")
            
            # Try DynamoDB-compatible API first (TPipe uses DynamoDB JSON protocol)
            response = requests.post(
                self.url,
                json=payload,
                timeout=self.timeout,
                headers={"Content-Type": "application/json"}
            )
            
            if response.status_code == 200:
                result = response.json()
                action = result.get("action") or result.get("text") or result.get("response")
                if action:
                    logger.info(f"TPipe generated action: {action[:100]}...")
                    return action
                    
            logger.warning(f"TPipe returned status {response.status_code}: {response.text[:200]}")
            return None
            
        except requests.exceptions.ConnectionError:
            logger.warning("TPipe connection failed - not running?")
            return None
        except requests.exceptions.Timeout:
            logger.warning("TPipe request timed out")
            return None
        except Exception as e:
            logger.error(f"TPipe error: {e}")
            return None


# =============================================================================
# SSE Reader via curl subprocess
# =============================================================================

class SSEClient:
    """
    Server-Sent Events client using curl subprocess.
    Reads events from server-extend for matchmaking.
    """
    
    def __init__(
        self,
        host: str = CONFIG["server_extend_host"],
        port: int = CONFIG["server_extend_port"]
    ):
        self.host = host
        self.port = port
        self.url = f"http://{self.host}:{self.port}/events"
        self.process: Optional[subprocess.Popen] = None
        self.running = False
        self.thread: Optional[threading.Thread] = None
        self.event_callbacks: list[Callable[[str, dict], None]] = []
        self._lock = threading.Lock()
        
    def _build_url(self, player_id: str) -> str:
        """Build SSE connection URL with player ID."""
        return f"{self.url}?playerId={player_id}&guestMode=true"
    
    def _parse_sse_line(self, line: str) -> tuple[Optional[str], Optional[str]]:
        """
        Parse a single SSE line.
        
        Returns:
            (event_type, data) tuple or (None, None) for empty/comment lines
        """
        line = line.strip()
        if not line or line.startswith(":"):
            return None, None
        
        if line.startswith("event:"):
            return line[6:].strip(), None
        
        if line.startswith("data:"):
            return None, line[5:].strip()
        
        return None, None
    
    def _process_sse_data(self, event_type: Optional[str], data: str) -> dict:
        """Process raw SSE data into a structured dictionary."""
        try:
            parsed = json.loads(data)
            
            # Check if this is a session.ready event
            if isinstance(parsed, dict):
                if parsed.get("type") == "session.ready" or "session.ready" in str(parsed):
                    parsed["_is_session_ready"] = True
                    
                # Check for session.ready as a field value
                if "session" in parsed and isinstance(parsed["session"], dict):
                    if parsed["session"].get("ready"):
                        parsed["_is_session_ready"] = True
                        
            return parsed
        except json.JSONDecodeError:
            logger.debug(f"Non-JSON SSE data: {data[:100]}")
            return {"raw": data}
    
    def _reader_loop(self, player_id: str) -> None:
        """Main reading loop for SSE events."""
        logger.info("SSE reader thread started")
        
        while self.running:
            try:
                # Start curl process for SSE
                cmd = [
                    "stdbuf", "-oL",  # Line-buffered output
                    "curl", "-s", "-N",  # Silent, no buffer
                    self._build_url(player_id)
                ]
                
                logger.debug(f"Starting SSE curl: {' '.join(cmd)}")
                
                self.process = subprocess.Popen(
                    cmd,
                    stdout=subprocess.PIPE,
                    stderr=subprocess.PIPE,
                    text=True,
                    bufsize=1
                )
                
                current_event_type: Optional[str] = None
                current_data_lines: list[str] = []
                
                while self.running and self.process.poll() is None:
                    line = self.process.stdout.readline()
                    
                    if not line:
                        break
                    
                    # Remove trailing newline
                    line = line.rstrip("\n\r")
                    logger.debug(f"SSE raw: {line[:100]}")
                    
                    event_type, data = self._parse_sse_line(line)
                    
                    if event_type:
                        current_event_type = event_type
                    
                    if data:
                        current_data_lines.append(data)
                    
                    # End of event (empty line or new event:)
                    if not line or event_type:
                        if current_data_lines and current_event_type != "heartbeat":
                            combined_data = "\n".join(current_data_lines)
                            parsed = self._process_sse_data(current_event_type, combined_data)
                            parsed["_event_type"] = current_event_type
                            
                            with self._lock:
                                for callback in self.event_callbacks:
                                    try:
                                        callback(current_event_type, parsed)
                                    except Exception as e:
                                        logger.error(f"SSE callback error: {e}")
                            
                            current_data_lines = []
                            current_event_type = None
                            
                # Process any remaining data
                if current_data_lines:
                    combined_data = "\n".join(current_data_lines)
                    parsed = self._process_sse_data(current_event_type, combined_data)
                    parsed["_event_type"] = current_event_type
                    
                    with self._lock:
                        for callback in self.event_callbacks:
                            try:
                                callback(current_event_type, parsed)
                            except Exception as e:
                                logger.error(f"SSE callback error: {e}")
                
            except Exception as e:
                logger.error(f"SSE reader error: {e}")
                if self.running:
                    time.sleep(1.0)  # Brief delay before retry
                    
        logger.info("SSE reader thread stopped")
    
    def start(self, player_id: str) -> None:
        """Start the SSE client in a background thread."""
        if self.running:
            logger.warning("SSE client already running")
            return
            
        self.running = True
        self.thread = threading.Thread(
            target=self._reader_loop,
            args=(player_id,),
            name="SSE-Reader",
            daemon=True
        )
        self.thread.start()
        logger.info(f"SSE client started for player {player_id}")
    
    def stop(self) -> None:
        """Stop the SSE client and cleanup."""
        self.running = False
        
        if self.process:
            try:
                self.process.terminate()
                self.process.wait(timeout=5)
            except subprocess.TimeoutExpired:
                self.process.kill()
            except Exception as e:
                logger.debug(f"Error terminating SSE process: {e}")
            self.process = None
        
        if self.thread:
            self.thread.join(timeout=5)
            self.thread = None
            
        logger.info("SSE client stopped")
    
    def add_callback(self, callback: Callable[[str, dict], None]) -> None:
        """Add an event callback."""
        with self._lock:
            self.event_callbacks.append(callback)


# =============================================================================
# WebSocket Game Client
# =============================================================================

class GameWSClient:
    """
    WebSocket client for the game server.
    Handles game state events and pong protocol.
    """
    
    def __init__(
        self,
        host: str = CONFIG["game_server_host"],
        port: int = CONFIG["game_server_port"]
    ):
        self.host = host
        self.port = port
        self.url = f"ws://{self.host}:{self.port}/events"
        self.ws: Optional[websocket.WebSocket] = None
        self.running = False
        self.pong_thread: Optional[threading.Thread] = None
        self._lock = threading.Lock()
        self.event_callbacks: list[Callable[[dict], None]] = []
        self.pong_count = 0
        self.last_pong_time = 0.0
        
    def _build_url(self, player_id: str) -> str:
        """Build WebSocket connection URL."""
        return f"{self.url}?playerId={player_id}&guestMode=true&role=CONTROLLER"
    
    def _handle_pong(self, msg: dict) -> bool:
        """
        Handle a ping/pong message.
        
        Args:
            msg: Parsed message dictionary
            
        Returns:
            True if this was a ping/pong message that was handled
        """
        if msg.get("type") != "request":
            return False
            
        method = msg.get("method", "")
        
        if method not in ("client.ping", "client.pong"):
            return False
            
        # Build response
        response = {
            "type": "response",
            "id": msg.get("id") or str(uuid.uuid4()),
            "result": {}
        }
        
        try:
            self.ws.send(json.dumps(response))
            self.pong_count += 1
            self.last_pong_time = time.time()
            logger.debug(f"Sent pong response for {method} id={response['id']}")
            return True
        except Exception as e:
            logger.error(f"Failed to send pong: {e}")
            return False
    
    def _pong_loop(self) -> None:
        """Main loop for receiving and responding to pings."""
        logger.info("Pong thread started")
        
        while self.running:
            try:
                if self.ws is None:
                    time.sleep(0.1)
                    continue
                    
                # Use blocking recv with no timeout
                data = self.ws.recv()
                
                if not data:
                    continue
                    
                try:
                    msg = json.loads(data)
                except json.JSONDecodeError:
                    logger.warning(f"Invalid JSON from WS: {data[:100]}")
                    continue
                
                logger.debug(f"WS recv: {json.dumps(msg, indent=2)[:200]}")
                
                # Handle pong protocol
                if not self._handle_pong(msg):
                    # Not a ping/pong, dispatch to callbacks
                    with self._lock:
                        for callback in self.event_callbacks:
                            try:
                                callback(msg)
                            except Exception as e:
                                logger.error(f"WS callback error: {e}")
                                
            except websocket.WebSocketConnectionClosedException:
                logger.warning("WebSocket connection closed")
                break
            except Exception as e:
                logger.error(f"Pong thread error: {e}")
                if self.running:
                    time.sleep(0.5)
                    
        logger.info("Pong thread stopped")
    
    def connect(self, player_id: str) -> bool:
        """
        Connect to the game WebSocket server.
        
        Args:
            player_id: Player identifier
            
        Returns:
            True if connection succeeded
        """
        try:
            if self.ws:
                try:
                    self.ws.close()
                except Exception:
                    pass
                    
            logger.info(f"Connecting to {self._build_url(player_id)}")
            
            # Create WebSocket with no timeout (managed by thread)
            self.ws = websocket.WebSocket(
                timeout=None,  # No socket timeout
                enable_multithread=True
            )
            
            self.ws.connect(
                self._build_url(player_id),
                timeout=CONFIG["pong_timeout"]
            )
            
            # Start pong thread if not already running
            if not self.pong_thread or not self.pong_thread.is_alive():
                self.running = True
                self.pong_thread = threading.Thread(
                    target=self._pong_loop,
                    name="Pong-Thread",
                    daemon=True
                )
                self.pong_thread.start()
            
            logger.info("WebSocket connected")
            return True
            
        except Exception as e:
            logger.error(f"WebSocket connection failed: {e}")
            return False
    
    def disconnect(self) -> None:
        """Disconnect from the game server."""
        self.running = False
        
        if self.ws:
            try:
                self.ws.close()
            except Exception as e:
                logger.debug(f"Error closing WebSocket: {e}")
            self.ws = None
            
        logger.info("WebSocket disconnected")
    
    def send(self, data: dict) -> bool:
        """
        Send a message to the game server.
        
        Args:
            data: Message dictionary to send
            
        Returns:
            True if send succeeded
        """
        try:
            if self.ws is None:
                logger.error("Cannot send - not connected")
                return False
                
            msg_str = json.dumps(data)
            logger.debug(f"WS send: {msg_str[:200]}")
            self.ws.send(msg_str)
            return True
            
        except Exception as e:
            logger.error(f"Failed to send: {e}")
            return False
    
    def add_callback(self, callback: Callable[[dict], None]) -> None:
        """Add an event callback."""
        with self._lock:
            self.event_callbacks.append(callback)


# =============================================================================
# REST API Client for Matchmaking
# =============================================================================

class MatchmakingClient:
    """
    REST API client for server-extend matchmaking.
    """
    
    def __init__(
        self,
        host: str = CONFIG["server_extend_host"],
        port: int = CONFIG["server_extend_port"]
    ):
        self.host = host
        self.port = port
        self.base_url = f"http://{self.host}:{self.port}"
        self.timeout = 30.0
        
    def request_game(
        self,
        player_id: str,
        player: PlayerInfo,
        websocket_id: str,
        ai_opponent_count: int = CONFIG["ai_opponent_count"],
        match_pool: list[str] = CONFIG["match_pool"],
        player_alias: str = ""
    ) -> bool:
        """
        Request a new game via matchmaking.
        
        Args:
            player_id: Player identifier
            player: Player info
            websocket_id: WebSocket session ID
            ai_opponent_count: Number of AI opponents
            match_pool: Match pool to search
            
        Returns:
            True if matchmaking request was accepted (HTTP 202)
        """
        payload = {
            "type": "request",
            "id": "1",
            "method": "server.extend.requestGame",
            "params": {
                "userName": player.user_name,
                "gameType": "SINGLEPLAYER",
                "accelByteId": "guest-user",
                "websocketId": websocket_id,
                "selectedCommander": {
                    "id": player.commander_id,
                    "name": player.commander_name,
                    "type": player.commander_type,
                    "trait": player.commander_trait,
                    "imageUrl": None,
                    "rarity": player.commander_rarity,
                    "description": player.commander_description
                },
                "aiOpponentCount": ai_opponent_count,
                "aiOnly": False,
                "matchPool": match_pool[0] if match_pool else "standard",
                "playerAlias": player_alias
            }
        }
        
        try:
            logger.info(f"Requesting game for {player.user_name}")
            logger.debug(f"Matchmaking payload: {json.dumps(payload, indent=2)}")
            
            response = requests.post(
                f"{self.base_url}/rpc?playerId={player_id}",
                json=payload,
                timeout=self.timeout
            )
            
            logger.info(f"Matchmaking response: {response.status_code}")
            
            if response.status_code == 202:
                logger.info("Matchmaking accepted")
                return True
            else:
                logger.warning(f"Matchmaking rejected: {response.status_code} - {response.text}")
                return False
                
        except Exception as e:
            logger.error(f"Matchmaking request failed: {e}")
            return False


# =============================================================================
# TUI Interface
# =============================================================================

class TUIInterface:
    """
    Terminal UI for the game controller using curses.
    """
    
    def __init__(self, controller: "GameController"):
        self.controller = controller
        self.stdscr: Optional[Any] = None
        self.running = False
        self.input_buffer = ""
        self.input_mode = False
        self.max_event_lines = 20
        self.scroll_offset = 0
        
    def _get_state_color(self, state: GameState) -> int:
        """Get curses color pair for game state."""
        colors = {
            GameState.DISCONNECTED: 1,    # Red
            GameState.MATCHMAKING: 2,     # Yellow
            GameState.IN_GAME: 3,         # Blue
            GameState.MY_TURN: 4,        # Green
            GameState.AWAITING_RESULT: 5, # Cyan
            GameState.GAME_OVER: 6,       # Magenta
        }
        return colors.get(state, 0)
    
    def _format_event(self, event: dict) -> str:
        """Format an event for display."""
        method = event.get("method", "")
        event_type = event.get("_event_type", "")
        msg_type = event.get("type", "")
        
        if method:
            return f"method={method}"
        if event_type:
            return f"event={event_type}"
        if msg_type:
            return f"type={msg_type}"
        
        # Truncate long events
        event_str = json.dumps(event, separators=(",", ":"))
        if len(event_str) > 60:
            return event_str[:57] + "..."
        return event_str
    
    def _render(self) -> None:
        """Render the TUI."""
        if not self.stdscr:
            return
            
        try:
            self.stdscr.clear()
            max_y, max_x = self.stdscr.getmaxyx()
            
            # Title
            title = "=== Autogenesis Game Controller ==="
            self.stdscr.addstr(0, (max_x - len(title)) // 2, title)
            
            # Connection status
            sse_status = self.controller.sse_status.name
            ws_status = self.controller.ws_status.name
            self.stdscr.addstr(2, 2, f"SSE: [{sse_status}]  WS: [{ws_status}]  Pongs: {self.controller.ws_client.pong_count}")
            
            # Game state
            state = self.controller.state
            state_str = f"State: {state.name}"
            self.stdscr.addstr(3, 2, state_str, curses.A_BOLD)
            
            # Game context
            ctx = self.controller.game_context
            self.stdscr.addstr(5, 2, f"Round: {ctx.round_number}  Actor: {ctx.current_actor or 'N/A'}")
            
            # Turn order
            if ctx.turn_order:
                self.stdscr.addstr(6, 2, f"Turn Order: {', '.join(ctx.turn_order)}")
            
            # Scores
            if ctx.scores:
                scores_str = " | ".join(f"{k}: {v}" for k, v in ctx.scores.items())
                self.stdscr.addstr(7, 2, f"Scores: {scores_str}")
            
            # Resources
            if ctx.resources:
                res_str = " | ".join(f"{k}: {v}" for k, v in ctx.resources.items())
                self.stdscr.addstr(8, 2, f"Resources: {res_str}")
            
            # Event log header
            self.stdscr.addstr(10, 2, "=== Event Log ===", curses.A_UNDERLINE)
            
            # Event log
            events = ctx.event_history[-self.max_event_lines:]
            for i, event in enumerate(events):
                event_str = self._format_event(event)
                try:
                    self.stdscr.addstr(11 + i, 2, event_str[:max_x - 4])
                except curses.error:
                    pass
            
            # Last event details
            if ctx.last_event:
                self.stdscr.addstr(32, 2, "Last Event:", curses.A_UNDERLINE)
                last_str = json.dumps(ctx.last_event, indent=2)[:max_x - 4]
                try:
                    self.stdscr.addstr(33, 2, last_str[:max_x - 4])
                except curses.error:
                    pass
            
            # Input area
            if self.input_mode:
                prompt = f"Action [{self.controller.player_name}]: "
                self.stdscr.addstr(max_y - 3, 2, prompt)
                self.stdscr.addstr(max_y - 3, len(prompt) + 2, self.input_buffer)
                self.stdscr.addstr(max_y - 2, 2, "[Enter] Send  [Esc] Cancel")
            else:
                self.stdscr.addstr(max_y - 3, 2, "[A]I Action  [T]TPipe  [R]econnect  [Q]uit")
            
            # Player info
            self.stdscr.addstr(max_y - 1, 2, f"Player: {self.controller.player.user_name}")
            
            self.stdscr.refresh()
            
        except curses.error as e:
            # Handle terminal resize issues
            logger.debug(f"TUI render error: {e}")
    
    def _handle_input(self, char: int) -> None:
        """Handle keyboard input."""
        if self.input_mode:
            if char == 27:  # Escape
                self.input_mode = False
                self.input_buffer = ""
            elif char in (curses.KEY_ENTER, 10, 13):  # Enter
                if self.input_buffer.strip():
                    self.controller.submit_action(self.input_buffer)
                self.input_mode = False
                self.input_buffer = ""
            elif char == curses.KEY_BACKSPACE or char == 127:
                self.input_buffer = self.input_buffer[:-1]
            elif 32 <= char <= 126:
                self.input_buffer += chr(char)
        else:
            if char in (ord('a'), ord('A')):
                # AI action
                self.controller.generate_and_submit_action()
            elif char in (ord('t'), ord('T')):
                # TPipe action
                self.controller.generate_tpipe_action()
            elif char in (ord('r'), ord('R')):
                # Reconnect
                self.controller.reconnect_all()
            elif char in (ord('q'), ord('Q')):
                self.controller.shutdown()
            elif char == 10 or char == 13:
                # Enter - go to input mode
                if self.controller.state == GameState.MY_TURN:
                    self.input_mode = True
    
    def _input_thread(self) -> None:
        """Thread for handling input."""
        while self.running:
            try:
                char = self.stdscr.getch()
                self._handle_input(char)
            except curses.error:
                pass
            time.sleep(0.05)
    
    def start(self) -> None:
        """Start the TUI."""
        curses.wrapper(self._main_loop)
    
    def _main_loop(self, stdscr: Any) -> None:
        """Main curses loop."""
        self.stdscr = stdscr
        curses.curs_set(1)
        curses.noecho()
        self.stdscr.nodelay(True)
        
        self.running = True
        
        # Input thread
        input_thread = threading.Thread(
            target=self._input_thread,
            name="TUI-Input",
            daemon=True
        )
        input_thread.start()
        
        # Render loop
        while self.running and self.controller.state != GameState.DISCONNECTED:
            self._render()
            time.sleep(0.1)
        
        self._render()
    
    def stop(self) -> None:
        """Stop the TUI."""
        self.running = False


# =============================================================================
# Main Game Controller
# =============================================================================

class GameController:
    """
    Main game controller coordinating all components.
    """
    
    def __init__(self, args: Optional[argparse.Namespace] = None):
        self.args = args or argparse.Namespace()
        
        # Generate player identity — use explicit CONFIG["player_id"] if set
        explicit_id = getattr(args, 'player_id', None) or CONFIG.get("player_id", "")
        if explicit_id:
            self.player_id = explicit_id
        else:
            self.player_id = f"lord-{int(time.time()*1000)}-{random.randint(1,999999)}"
        # Extract stable playerAlias from the middle segment of player_id.
        # E.g. "lord-1234567890-ABC" → alias "1234567890".
        # This is used to match Python's session to the browser's existing player slot.
        parts = self.player_id.split("-")
        self.player_alias = CONFIG["player_alias"] or (parts[1] if len(parts) > 1 else self.player_id)
        self.player = PlayerInfo(
            player_id=self.player_id,
            user_name=CONFIG["player_name"],
            commander_id=str(uuid.uuid4()),
            commander_name=CONFIG["player_name"],
            commander_type=CONFIG["player_type"],
            commander_trait=CONFIG["player_trait"],
            commander_rarity=CONFIG["player_rarity"],
            commander_description=CONFIG["player_description"]
        )
        
        # Game state
        self.state = GameState.DISCONNECTED
        self.game_context = GameContext()
        self.sse_status = ConnectionStatus.DISCONNECTED
        self.ws_status = ConnectionStatus.DISCONNECTED
        
        # Clients
        self.sse_client = SSEClient()
        self.ws_client = GameWSClient()
        self.mm_client = MatchmakingClient()
        self.tpipe_client = TPipeClient()
        
        # Reconnection state
        self.reconnect_attempts = 0
        self.reconnect_delay = CONFIG["initial_reconnect_delay"]
        
        # TUI
        self.tui: Optional[TUIInterface] = None
        
        # Shutdown flag
        self.shutdown_flag = threading.Event()

        # Debug signal server (port 7075) for KVision browser control
        self.debug_server = DebugSignalServer(port=7075)
        self.debug_server.start()
        logger.info("Debug signal server started on port 7075")

        # Register signal handlers
        signal.signal(signal.SIGINT, self._signal_handler)
        signal.signal(signal.SIGTERM, self._signal_handler)
        
        logger.info(f"Controller initialized with player_id: {self.player_id}")
    
    def _signal_handler(self, signum: int, frame: Any) -> None:
        """Handle shutdown signals."""
        logger.info(f"Received signal {signum}, initiating shutdown")
        self.shutdown_flag.set()
        self.shutdown()
    
    def _on_sse_event(self, event_type: str, data: dict) -> None:
        """Handle SSE events."""
        logger.info(f"SSE event: {event_type} - {json.dumps(data, indent=2)[:200]}")
        
        # Update game context
        self.game_context.last_event = data
        self.game_context.event_history.append(data)
        
        # Check for session.ready
        if data.get("_is_session_ready") or event_type == "session.ready":
            logger.info("Session ready detected!")
            self.game_context.session_ready = True
            
            # Extract websocket ID if present
            if "sessionId" in data:
                self.game_context.websocket_id = data["sessionId"]
            
            # Move to matchmaking state and start matchmaking
            if self.state in (GameState.DISCONNECTED, GameState.MATCHMAKING):
                self.state = GameState.MATCHMAKING
                self._start_matchmaking()
    
    def _on_ws_event(self, event: dict) -> None:
        """Handle WebSocket game events."""
        method = event.get("method", "")
        msg_type = event.get("type", "")
        
        logger.debug(f"WS event: {json.dumps(event, indent=2)[:300]}")
        
        # Update game context
        self.game_context.last_event = event
        self.game_context.event_history.append(event)
        
        # Track last N events
        if len(self.game_context.event_history) > 1000:
            self.game_context.event_history = self.game_context.event_history[-500:]
        
        # Handle turn detection
        if method == "ui.setResolutionStep":
            step = event.get("params", {}).get("step", "")
            message = event.get("params", {}).get("message", "")
            # Only transition to MY_TURN if current_actor matches our player
            if step == "START" and CONFIG["player_name"] in message:
                if self.game_context.current_actor == CONFIG["player_name"]:
                    logger.info("It's Lord Maple Tree's turn!")
                    self.state = GameState.MY_TURN
                    # Signal browser to show map immediately
                    self.send_show_map()
                    
        elif method == "ui.activeTurn":
            params = event.get("params", {})
            self.game_context.current_actor = params.get("actorName")
            self.game_context.round_number = params.get("round", 0)
            if params.get("actorName") == CONFIG["player_name"]:
                self.state = GameState.MY_TURN
                # Signal browser to show map immediately
                self.send_show_map()
                
        elif method == "ui.endGame" or method == "game.gameOver":
            logger.info("Game over!")
            self.state = GameState.GAME_OVER
            
        elif method == "game.updateScore":
            params = event.get("params", {})
            self.game_context.scores.update(params)
            
        elif method == "game.updateResources":
            params = event.get("params", {})
            self.game_context.resources.update(params)
            
        elif method == "game.turnOrder":
            params = event.get("params", {})
            self.game_context.turn_order = params.get("order", [])
            
        elif method == "game.roundUpdate":
            params = event.get("params", {})
            self.game_context.round_number = params.get("round", 0)
            
        elif method == "game.actionResult":
            self.state = GameState.AWAITING_RESULT
            logger.info(f"Action result received: {event.get('params', {})}")
    
    def _start_matchmaking(self) -> None:
        """Start the matchmaking process."""
        logger.info("Starting matchmaking...")
        
        websocket_id = self.player_id  # Use our own player_id so browser matches us
        
        success = self.mm_client.request_game(
            player_id=self.player_id,
            player=self.player,
            websocket_id=websocket_id,
            ai_opponent_count=CONFIG["ai_opponent_count"],
            match_pool=CONFIG["match_pool"],
            player_alias=self.player_alias
        )
        
        if not success:
            logger.error("Matchmaking request failed")
            self.state = GameState.DISCONNECTED
            return
            
        # Connect to game WebSocket
        self._connect_game_ws()
    
    def _connect_game_ws(self) -> None:
        """Connect to the game WebSocket server."""
        self.ws_status = ConnectionStatus.CONNECTING
        self.ws_client.add_callback(self._on_ws_event)
        
        if self.ws_client.connect(self.player_id):
            self.ws_status = ConnectionStatus.CONNECTED
            self.reconnect_attempts = 0
            self.reconnect_delay = CONFIG["initial_reconnect_delay"]
            self.state = GameState.IN_GAME
            logger.info("Connected to game server")
        else:
            self.ws_status = ConnectionStatus.ERROR
            logger.error("Failed to connect to game server")
    
    def _reconnect_sse(self) -> bool:
        """Reconnect SSE with exponential backoff."""
        if self.reconnect_attempts >= CONFIG["max_reconnect_attempts"]:
            logger.error("Max SSE reconnection attempts reached")
            return False
            
        logger.info(f"SSE reconnecting in {self.reconnect_delay}s (attempt {self.reconnect_attempts + 1})")
        time.sleep(self.reconnect_delay)
        
        self.reconnect_attempts += 1
        self.reconnect_delay = min(
            self.reconnect_delay * 2,
            CONFIG["max_reconnect_delay"]
        )
        
        self.sse_status = ConnectionStatus.CONNECTING
        self.sse_client.start(self.player_id)
        
        return True
    
    def _reconnect_ws(self) -> bool:
        """Reconnect WebSocket with exponential backoff."""
        if self.reconnect_attempts >= CONFIG["max_reconnect_attempts"]:
            logger.error("Max WS reconnection attempts reached")
            return False
            
        logger.info(f"WS reconnecting in {self.reconnect_delay}s (attempt {self.reconnect_attempts + 1})")
        time.sleep(self.reconnect_delay)
        
        self.reconnect_attempts += 1
        self.reconnect_delay = min(
            self.reconnect_delay * 2,
            CONFIG["max_reconnect_delay"]
        )
        
        self.ws_status = ConnectionStatus.CONNECTING
        self.ws_client.disconnect()
        self._connect_game_ws()
        
        return True
    
    def reconnect_all(self) -> None:
        """Reconnect all connections."""
        logger.info("Manual reconnect requested")
        self.reconnect_attempts = 0
        self.reconnect_delay = CONFIG["initial_reconnect_delay"]
        
        # Restart SSE
        self.sse_client.stop()
        self.sse_status = ConnectionStatus.CONNECTING
        self.sse_client.start(self.player_id)
        
        # Reconnect WS
        self._connect_game_ws()
    
    def submit_action(self, action: str) -> bool:
        """
        Submit a game action.
        
        Args:
            action: Narrative action string
            
        Returns:
            True if action was submitted
        """
        if self.state != GameState.MY_TURN:
            logger.warning(f"Cannot submit action - not my turn (state: {self.state})")
            return False
            
        payload = {
            "type": "request",
            "id": str(int(time.time() * 1000)),
            "method": "game.submitAction",
            "params": {
                "action": action,
                "playerName": CONFIG["player_name"]
            }
        }
        
        if self.ws_client.send(payload):
            logger.info(f"Action submitted: {action[:100]}")
            self.state = GameState.AWAITING_RESULT
            return True
            
        logger.error("Failed to submit action")
        return False
    
    def generate_and_submit_action(self) -> None:
        """Generate a random action and submit it."""
        actions = [
            "The Ent army marches forward through the ancient forest.",
            "I command the frost giants to assault the enemy flanks.",
            "The dragon riders take to the skies for reconnaissance.",
            "I deploy the shadow assassins to disrupt enemy supply lines.",
            "The golem battalions hold the defensive line.",
            "I summon a blizzard to slow the approaching horde.",
        ]
        action = random.choice(actions)
        self.submit_action(action)
    
    def generate_tpipe_action(self) -> None:
        """Generate an action using TPipe and submit it."""
        logger.info("Generating TPipe action...")
        
        action = self.tpipe_client.generate_action(
            self.game_context,
            CONFIG["player_name"]
        )
        
        if action:
            self.submit_action(action)
        else:
            logger.warning("TPipe action generation failed, using fallback")
            self.generate_and_submit_action()
    
    def _matchmaking_loop(self) -> None:
        """Main matchmaking loop."""
        logger.info("Starting matchmaking loop")

        # Start SSE client
        self.sse_status = ConnectionStatus.CONNECTING
        self.sse_client.add_callback(self._on_sse_event)
        self.sse_client.start(self.player_id)

        # Wait for session ready
        timeout = 60.0
        start_time = time.time()
        polling_start = time.time()
        ws_id_check_interval = 2.0  # Check browser WS ID every 2 seconds
        last_ws_check = 0.0

        while not self.game_context.session_ready and not self.shutdown_flag.is_set():
            if time.time() - start_time > timeout:
                logger.error("Matchmaking timeout")
                self.state = GameState.DISCONNECTED
                return

            # Periodically check for browser websocket ID from debug server
            if time.time() - last_ws_check >= ws_id_check_interval:
                last_ws_check = time.time()
                browser_ws_id = self.debug_server.get_browser_websocket_id()
                if browser_ws_id:
                    self.game_context.browser_websocket_id = browser_ws_id
                    logger.info(f"Bridge: Captured browser websocket ID: {browser_ws_id}")

            time.sleep(0.5)

        if self.shutdown_flag.is_set():
            return

        logger.info("Matchmaking complete, game should be starting...")
    
    def run(self) -> None:
        """Run the game controller."""
        logger.info("Starting game controller")
        
        # Start matchmaking
        self.state = GameState.MATCHMAKING
        self._matchmaking_loop()
        
        if self.shutdown_flag.is_set():
            self.shutdown()
            return
        
        # Start TUI in main thread
        if not self.args.no_ui:
            try:
                self.tui = TUIInterface(self)
                self.tui.start()
            except Exception as e:
                logger.error(f"TUI error: {e}")
                # Fall back to non-UI mode
                self._run_non_ui()
        else:
            self._run_non_ui()
    
    def _run_non_ui(self) -> None:
        """Run without TUI - for debugging."""
        logger.info("Running in non-UI mode")

        # Poll loop: check for browser commands from debug server and
        # update browser_websocket_id before the game loop starts.
        logger.info("Polling for browser websocket ID...")
        browser_ws_id = None
        for _ in range(20):  # 20 attempts x 0.5s = 10s timeout
            if self.shutdown_flag.is_set():
                return
            browser_ws_id = self.debug_server.get_browser_websocket_id()
            if browser_ws_id:
                logger.info(f"Browser websocket ID received: {browser_ws_id}")
                self.game_context.browser_websocket_id = browser_ws_id
                break
            time.sleep(0.5)
        else:
            logger.warning("No browser websocket ID received within timeout — continuing anyway")

        # Connect WebSocket after matchmaking
        self._connect_game_ws()

        # Signal the browser to show the game view
        # The browser is polling /debug/signal — this tells it to display GameplayUI
        self.debug_server.set_signal(f"GAME_STARTED:{CONFIG['player_name']}:{CONFIG['ai_opponent_count']}")
        logger.info(f"Sent GAME_STARTED signal to browser: {CONFIG['player_name']}, ai={CONFIG['ai_opponent_count']}")

        while not self.shutdown_flag.is_set():
            if self.state == GameState.MY_TURN:
                logger.info("My turn - generating TPipe action")
                self.generate_tpipe_action()

            time.sleep(1.0)
    
    def shutdown(self) -> None:
        """Clean shutdown of all components."""
        logger.info("Shutting down controller")

        self.state = GameState.DISCONNECTED

        if self.tui:
            self.tui.stop()
            self.tui = None

        self.debug_server.stop()
        self.sse_client.stop()
        self.ws_client.disconnect()

        logger.info("Controller shutdown complete")

    # ============================================================================
    # Browser Launch Helpers — for dual-control with browser
    # ============================================================================

    def launch_browser(self) -> None:
        """
        Launch the browser observer with ?playerId= set to our player_id.

        This lets the browser read window.PLAYER_ID before generating its
        WebSocket playerId, ensuring the browser's session matches this
        Python controller's playerId in WorldManager routing.
        """
        import urllib.parse
        import subprocess
        import sys

        observer_script = os.path.join(
            os.path.dirname(os.path.dirname(os.path.abspath(__file__))),
            "debugger", "observer", "browser_observer.py"
        )
        player_id_encoded = urllib.parse.quote(self.player_id, safe="")
        logger.info(f"Launching browser with playerId={self.player_id}")

        try:
            subprocess.Popen(
                [sys.executable, observer_script, "--visible", "--player-id", self.player_id],
                stdout=subprocess.DEVNULL,
                stderr=subprocess.DEVNULL,
            )
        except Exception as e:
            logger.error(f"Failed to launch browser: {e}")

    #==========================================================================
    # UI Signal Methods — drive KVision browser UI via debug signal bridge
    #==========================================================================

    def send_open_widget(self, widget_name: str) -> None:
        """Open a named widget overlay in the browser.

        Valid names: worldStats, playerResources, stats, playerTerritories,
        playerInfo, settings, history
        """
        self.debug_server.set_signal(f"OPEN_WIDGET:{widget_name}")
        logger.info(f"Sent OPEN_WIDGET signal to browser: {widget_name}")

    def send_close_widget(self) -> None:
        """Close any open widget overlay."""
        self.debug_server.set_signal("CLOSE_WIDGET")
        logger.info("Sent CLOSE_WIDGET signal to browser")

    def send_execute_command(self, command_text: str) -> None:
        """Set command box text and submit the turn atomically.

        This drives the game without needing the browser UI for turn submission.
        The command is sent via WebSocket to the game server directly.
        """
        # Also send via WebSocket for server-side processing
        import json
        msg = {
            "type": "request",
            "id": f"cmd-{int(time.time() * 1000)}",
            "method": "game.submitAction",
            "params": {
                "action": command_text,
                "playerName": CONFIG["player_name"]
            }
        }
        if self.ws_client and hasattr(self.ws_client, 'ws') and self.ws_client.ws:
            try:
                self.ws_client.ws.send(json.dumps(msg))
                logger.info(f"Sent command via WS: {command_text[:50]}...")
            except Exception as e:
                logger.error(f"Failed to send command via WS: {e}")
        # Also signal the browser UI for visual feedback
        self.debug_server.set_signal(f"EXECUTE_COMMAND:{command_text}")
        logger.info(f"Sent EXECUTE_COMMAND signal to browser: {command_text[:50]}...")

    def send_show_turn_resolution(self) -> None:
        """Force the turn resolution panel to show in the browser."""
        self.debug_server.set_signal("SHOW_TURN_RESOLUTION")
        logger.info("Sent SHOW_TURN_RESOLUTION signal to browser")

    def send_show_map(self) -> None:
        """Force the map view to display in the browser.
        
        This switches the centerStackPanel to activeIndex=0, showing the
        MapViewer instead of TurnResolutionWidget or other overlays.
        """
        self.debug_server.set_signal("SHOW_MAP")
        logger.info("Sent SHOW_MAP signal to browser")

    def send_capture_screenshot(self) -> None:
        """Trigger screenshot capture.

        Note: Actual screenshot capture is done by the Python controller's
        Playwright observer loop, not by this signal. This exists for
        future expansion where the browser itself captures screenshots.
        """
        self.debug_server.set_signal("CAPTURE_SCREENSHOT")
        logger.info("Sent CAPTURE_SCREENSHOT signal to browser")


# =============================================================================
# Main Entry Point
# =============================================================================

def parse_args() -> argparse.Namespace:
    """Parse command line arguments."""
    parser = argparse.ArgumentParser(
        description="Autogenesis Game Controller",
        formatter_class=argparse.RawDescriptionHelpFormatter
    )
    
    parser.add_argument(
        "--no-ui",
        action="store_true",
        help="Run without TUI (for debugging)"
    )
    
    parser.add_argument(
        "--player-name",
        default=CONFIG["player_name"],
        help=f"Player name (default: {CONFIG['player_name']})"
    )
    
    parser.add_argument(
        "--ai-count",
        type=int,
        default=CONFIG["ai_opponent_count"],
        help=f"Number of AI opponents (default: {CONFIG['ai_opponent_count']})"
    )

    parser.add_argument(
        "--player-alias",
        default="",
        help="Stable identifier for matching controller to browser's existing player slot (default: auto-extracted from player_id)"
    )

    parser.add_argument(
        "--player-id",
        default="",
        help="Explicit player ID to use (default: auto-generated)"
    )

    parser.add_argument(
        "--se-port",
        type=int,
        default=CONFIG["server_extend_port"],
        help=f"server-extend port (default: {CONFIG['server_extend_port']})"
    )

    parser.add_argument(
        "--ws-port",
        type=int,
        default=CONFIG["game_server_port"],
        help=f"game server WS port (default: {CONFIG['game_server_port']})"
    )

    return parser.parse_args()


def main() -> None:
    """Main entry point."""
    args = parse_args()
    
    # Override config with args
    if args.player_name:
        CONFIG["player_name"] = args.player_name
    if args.ai_count is not None:
        CONFIG["ai_opponent_count"] = args.ai_count
    if args.player_alias:
        CONFIG["player_alias"] = args.player_alias
    if args.player_id:
        CONFIG["player_id"] = args.player_id
    if args.se_port:
        CONFIG["server_extend_port"] = args.se_port
    if args.ws_port:
        CONFIG["game_server_port"] = args.ws_port

    controller = GameController(args)
    
    try:
        controller.run()
    except KeyboardInterrupt:
        logger.info("Keyboard interrupt received")
    except Exception as e:
        logger.exception(f"Fatal error: {e}")
    finally:
        controller.shutdown()


if __name__ == "__main__":
    main()