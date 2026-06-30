# Live PvP Current State

## What Exists Today

- `server-extend` already has the multiplayer entrypoint in [`ServerConnector`](../server-extend/src/main/kotlin/matchmaking/ServerConnector.kt).
- `server` already has AccelByte-facing wrappers for AMS, login, session, DSM, lobby-adjacent, and gameplay-related data.
- `sharedModel` already contains serializable models for session, matchmaking, lobby, and related AccelByte payloads.
- The repo already distinguishes between dev-mode behavior and a future live mode, so the transition is architectural rather than a greenfield rewrite.

## Current Live-Mode Blockers

- [`ServerConnector.requestGame`](../server-extend/src/main/kotlin/matchmaking/ServerConnector.kt#L55-L118) returns a dev-mode session only when `ExtendConfig.debugMode` is enabled, and otherwise logs that live mode is not implemented.
- [`ServerConnector.invokeMatchMaking`](../server-extend/src/main/kotlin/matchmaking/ServerConnector.kt#L125-L146) returns a fixed localhost game ticket in dev mode and an empty ticket in live mode.
- There is no documented production path in the codebase that:
  - connects the player to AGS Lobby first
  - requests matchmaking with a live session template
  - receives a ticket and resolves it into a dedicated server
  - handles cancellation, retries, or backfill in a durable way

## What the Current Models Support

- [`SessionBrowserFilter`](../sharedModel/src/commonMain/kotlin/structs/accelbyte/session/SessionModels.kt#L15-L36) already covers a broad session-query shape, but the live plan should use game sessions, not session-browser-specific assumptions.
- [`CreateGameSessionRequest`](../sharedModel/src/commonMain/kotlin/structs/accelbyte/session/SessionModels.kt#L38-L46) and [`GameSessionDetailResponse`](../sharedModel/src/commonMain/kotlin/structs/accelbyte/session/SessionModels.kt#L182-L214) provide a good baseline for session creation and result handling.
- [`MatchTicketRequest`](../sharedModel/src/commonMain/kotlin/structs/accelbyte/matchmaking/MatchmakingModels.kt#L8-L15) and [`MatchTicketFilter`](../sharedModel/src/commonMain/kotlin/structs/accelbyte/matchmaking/MatchmakingModels.kt#L17-L24) are the current matchmaking payloads.
- [`AMS`](../server/src/main/kotlin/accelbyte/ams/AMS.kt#L19-L140) already exposes many AMS operations and should be treated as the existing backend contract surface.

## What the Current Docs Say

- AccelByte Play describes Lobby, Matchmaking, Session, Party, Peer-to-Peer, and AMS as complementary multiplayer services.
- The Lobby service is the main hub for player activity and the entry point for friends and presence.
- The session-browser route is explicitly deprecated and replaced by game sessions.

## Implication

The live PvP path should be documented as:

1. login and connect to Lobby
2. request matchmaking or session allocation
3. use a session template that matches the game mode and server requirements
4. resolve to a dedicated server or joinable session
5. keep dev-mode single-player as a fallback for local iteration and demos

That means the steering docs should focus on game sessions, matchmaking, lobby, and AMS first.
