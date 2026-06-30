# Live PvP Steering Index

## Purpose

This directory is the steering pack for moving Autogenesis from the current single-player / dev-mode loop into a live PvP flow that can:

- connect to AGS Lobby
- request and resolve matchmaking
- allocate or join dedicated servers through AMS
- keep the current single-player path as a fallback while multiplayer is being built out

The documents here are intentionally about direction, sequencing, and risk control. They are not implementation patches.

## Reading Order

1. [`LIVE_PVP_CURRENT_STATE.md`](./LIVE_PVP_CURRENT_STATE.md) - what exists today and what is stubbed.
2. [`LIVE_PVP_TARGET_ARCHITECTURE.md`](./LIVE_PVP_TARGET_ARCHITECTURE.md) - the intended end-state design.
3. [`LIVE_PVP_PHASED_ROADMAP.md`](./LIVE_PVP_PHASED_ROADMAP.md) - the step-by-step rollout path.
4. [`LIVE_PVP_RISKS_TEST_MATRIX.md`](./LIVE_PVP_RISKS_TEST_MATRIX.md) - validation, failure modes, and launch checks.

## Ground Rules

- Treat AGS Lobby as the always-on player activity hub.
- Treat AGS Matchmaking as the system that turns player intent into a match ticket and session assignment.
- Treat AMS as the dedicated-server allocation layer, not as an optional enhancement.
- Treat game sessions as the live PvP session primitive. The session browser path is deprecated and should not be the primary plan.
- Keep single-player / dev-mode behavior intact until the new flow is explicitly verified.

## Source Files Worth Knowing

- [`server-extend/src/main/kotlin/matchmaking/ServerConnector.kt`](../server-extend/src/main/kotlin/matchmaking/ServerConnector.kt)
- [`server/src/main/kotlin/accelbyte/session/Session.kt`](../server/src/main/kotlin/accelbyte/session/Session.kt)
- [`server/src/main/kotlin/accelbyte/ams/AMS.kt`](../server/src/main/kotlin/accelbyte/ams/AMS.kt)
- [`sharedModel/src/commonMain/kotlin/structs/accelbyte/session/SessionModels.kt`](../sharedModel/src/commonMain/kotlin/structs/accelbyte/session/SessionModels.kt)
- [`sharedModel/src/commonMain/kotlin/structs/accelbyte/matchmaking/MatchmakingModels.kt`](../sharedModel/src/commonMain/kotlin/structs/accelbyte/matchmaking/MatchmakingModels.kt)

## External References

- AGS Play overview: https://docs.accelbyte.io/gaming-services/services/play/
- Lobby connection: https://docs.accelbyte.io/gaming-services/modules/multiplayer/session/lobby/how-to/connect-to-lobby/
- Matchmaking integration: https://docs.accelbyte.io/gaming-services/modules/multiplayer/matchmaking/integrating-matchmaking/
- AMS dedicated server upload: https://docs.accelbyte.io/gaming-services/modules/multiplayer/multiplayer-servers/upload-a-dedicated-server-build/
- AMS SDK integration: https://docs.accelbyte.io/gaming-services/modules/multiplayer/multiplayer-servers/integrate-dedicated-servers-with-the-sdk/
- Session browser deprecation notice: https://docs.accelbyte.io/gaming-services/modules/multiplayer/session/integrate-p2p-with-session-browser/

## Revision Log

- 2026-04-09: Initial steering pack created for the live PvP rollout.
