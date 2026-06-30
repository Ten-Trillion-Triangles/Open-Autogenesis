# Live PvP Target Architecture

## Goal

Define the end-state multiplayer architecture for Autogenesis so the game can move from a local single-player flow into a live PvP loop powered by AGS.

## High-Level Flow

1. Player authenticates.
2. Client connects to AGS Lobby and stays connected.
3. Player selects a competitive mode or queue.
4. Client submits matchmaking or session-intent data.
5. AGS Matchmaking evaluates the request and returns a ticket.
6. A session template determines the resulting game-session shape.
7. AMS allocates or resolves the dedicated server behind that session.
8. The game client joins the resolved server/session and begins live play.

## Subsystem Responsibilities

### Client

- Own the user flow from menu to queue to match entry.
- Maintain lobby connectivity and reconnect behavior.
- Surface match state, queue status, cancellations, and failures.
- Preserve a clear dev-mode fallback for local testing.

### Server

- Own the game-session lifecycle, match resolution, and server handoff.
- Translate AGS results into internal session state and game-start signals.
- Keep live session bookkeeping separate from local demo-mode bookkeeping.

### Server-Extend

- Own orchestration between player intent and the live game server.
- Coordinate matchmaking, session readiness, and server resolution.
- Replace the current placeholder path in `ServerConnector` with a real live-mode branch.

### Shared Model

- Hold the serializable request/response types that both the client and orchestration layers use.
- Keep matchmaking, lobby-adjacent, session, and AMS payloads aligned across JVM and JS targets.

## Design Decisions

- Use Lobby as the persistence and presence anchor for live players.
- Use Matchmaking as the primary entry point into competitive play.
- Use AMS for dedicated-server allocation and lifecycle handling.
- Use game sessions as the canonical session abstraction for live play.
- Keep session-browser concepts only where they are needed to explain legacy behavior or P2P history.

## API Surface Expectations

The steering docs should assume the following public surfaces will matter:

- session creation and join/leave operations
- matchmaking ticket creation, query, cancel, and result handling
- AMS fleet/build configuration and dedicated-server lifecycle operations
- lobby connection and session/presence awareness

## Non-Goals for This Phase

- No code changes yet.
- No UI redesign yet.
- No removal of the single-player/dev flow yet.
- No assumption that every AccelByte feature needs to be surfaced at once.

## Architecture Constraint

The plan should prefer incremental adoption:

- first make the multiplayer path real in one end-to-end mode
- then widen the supported feature set
- then harden, optimize, and expose more UI
