# Live PvP Phased Roadmap

## Phase 0: Contract and Reality Check

Purpose: confirm the multiplayer contract before building on top of it.

- Validate the AGS API surface used by the repo against actual docs and runtime behavior.
- Confirm the exact session template, match pool, and build configuration shape the game will rely on.
- Decide which existing models can stay as-is and which ones need replacement or extension.

Exit criteria:

- The docs describe a real flow, not a guessed one.
- The architecture is anchored to Lobby, Matchmaking, Session, and AMS.

## Phase 1: Connectivity Foundation

Purpose: make the live multiplayer entry path technically possible.

- Ensure login flows can reliably transition into Lobby connectivity.
- Standardize the lobby/session state model on the client and server sides.
- Define the queue and reconnect behavior the game should expose to the player.

Exit criteria:

- Player state can move from authenticated to lobby-connected to multiplayer-ready.

## Phase 2: Matchmaking and Session Resolution

Purpose: make a match request turn into a real game session.

- Define the match ticket payloads, match pool mapping, and session-template assumptions.
- Define how matchmaking results map to session creation or session join operations.
- Document cancel, retry, failure, and timeout behavior.

Exit criteria:

- A ticket can be created, tracked, canceled, and resolved into a joinable destination.

## Phase 3: AMS Allocation and Dedicated Server Handoff

Purpose: make the live PvP destination a real dedicated server.

- Document build configuration naming and how it maps to gameplay modes or environments.
- Document fleet and allocation prerequisites for each region or test environment.
- Define the server-ready handshake and the condition that moves the player from queue into play.

Exit criteria:

- The game can point to a valid dedicated server path that matches the selected queue or mode.

## Phase 4: Gameplay and UX Hardening

Purpose: make the live flow safe enough for broader use.

- Add resilience for disconnects, partial failures, and stale sessions.
- Add observability and logging expectations across client, server, and server-extend.
- Expand the docs for edge cases like reconnect, party flow, matchmaking failure, and fallback routing.

Exit criteria:

- The multiplayer path is stable enough to move beyond the initial live-PvP milestone.

## Suggested Ordering

1. Write the steering docs.
2. Validate the docs against real AGS behavior.
3. Implement lobby connectivity and matchmaking flow.
4. Wire dedicated-server allocation through AMS.
5. Harden, test, and expand.
