# Live PvP Risks and Test Matrix

## Top Risks

| Risk | Why it matters | Mitigation |
|---|---|---|
| Assuming a deprecated session-browser path | It can lead the team toward the wrong API surface | Anchor the plan on game sessions and mark session browser as legacy only |
| Treating dev mode as the production flow | That would keep multiplayer fake | Keep dev-mode only as a fallback branch and document live-mode exit criteria |
| Missing Lobby as the always-on hub | Players would lose presence, invites, and continuous connectivity | Make lobby connectivity a first-class phase and dependency |
| Under-specifying AMS build/fleet requirements | Dedicated server launch can fail late in the rollout | Put build config and fleet assumptions into the steering docs before coding |
| Not defining cancel/retry/disconnect behavior | Multiplayer UX gets stuck during failures | Include explicit failure and recovery paths in the roadmap |

## Test Matrix

### Authentication and Lobby

- Login succeeds and lobby connection is established.
- Lobby reconnects after an intentional disconnect.
- Lobby failure states are visible and recoverable.

### Matchmaking

- Solo matchmaking request creates a ticket.
- Party-based matchmaking keeps group identity intact.
- Ticket cancellation is handled cleanly.
- Timeout and error paths return usable user feedback.

### Session and Server Handoff

- Successful matchmaking resolves to a real joinable session/server.
- Session join failure returns a readable error and no stale state.
- The game can recover from a stale or missing session record.

### AMS

- Build configuration maps to the correct environment.
- A dedicated server can be launched with the expected command-line contract.
- Allocation or launch failures are visible in logs and surfaced in the docs.

### Fallback and Regression

- Dev-mode single-player still works.
- The live path can be disabled without breaking the local flow.
- Existing session and matchmaking models remain compatible where still used.

## Verification Commands

- `./gradlew :accelbyteSdk:build`
- `./gradlew :accelbyteSdk:test`
- `./gradlew :server:build`
- `./gradlew :server-extend:build`

## Launch Checklist

- Confirm Lobby connection is documented and understood.
- Confirm matchmaking uses a real queue and session-template mapping.
- Confirm AMS setup requirements are documented before implementation starts.
- Confirm the fallback path remains available during rollout.

## Open Questions to Resolve Later

- Which multiplayer mode is the first public milestone: solo queue, party queue, or one specific PvP mode?
- Which regions or namespaces are in scope for the first live pass?
- Which multiplayer features stay out of the first rollout to keep the surface manageable?