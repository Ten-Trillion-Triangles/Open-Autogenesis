# Operations Runbook

This file captures operational concerns that the game code itself does not
encode. It is the right place for "things an operator needs to know to keep
the system healthy" — IAM roles, log paths, manual recovery steps, known
issues with workarounds, and environment-specific gotchas.

## Known Issue: AccelByte CloudSave delete permission (errorCode 20013)

When server-extend (or the in-game server using its admin token) attempts
to delete a player's `running-game` record after the auto-restore on
connect consumes it, the `AdminUserRecord.deleteRecord` call returns
HTTP `20013` with:

```
requiredPermission: ADMIN:NAMESPACE:{namespace}:USER:{userId}:CLOUDSAVE:RECORD DELETE
```

AccelByte IAM expresses this permission as the string action `DELETE`
on the resource
`ADMIN:NAMESPACE:{namespace}:USER:{userId}:CLOUDSAVE:RECORD` (see the
admin-portal required-permissions table at
`https://docs.accelbyte.io/gaming-services/knowledge-base/admin-portal-required-permissions/`,
"Player Records" → "Delete Player JSON Records"). The admin service
account the in-game server uses is missing this permission on the
user-scoped CLOUDSAVE:RECORD resource.

**Current workaround (already in code):** the
`TurnHarness.invalidateRunningGameRecord` helper falls back to writing
a `{"consumed":true, "consumedAt":"<ISO-8601>"}` sentinel value at the
same `running-game` key when the delete fails. The
`hasRunningGame` RPC (in `GameRestoreRpcHandlers.kt`) and the
`ResumeAvailabilityPushService` both recognize the sentinel and treat
it as "no live save", so the modal is suppressed and the user can
either resume (using the rehydrated world in memory) or start fresh.

**Why this is not a blocker for the resume-game feature:** the
race-recovery logic added in Phase A of the
`resume-game-architecture` plan handles the sentinel case at the
`hasRunningGame` boundary, and the server-pushed notification added in
Phase B handles it at the modal-render boundary. The IAM fix is a
cleanliness improvement, not a correctness requirement.

**To fix at the IAM level:** grant the in-game server's service
account the
`ADMIN:NAMESPACE:{namespace}:USER:{userId}:CLOUDSAVE:RECORD DELETE`
permission in the AccelByte admin portal. Once the delete succeeds,
the consume-sentinel fallback path becomes unreachable and the
consumed-sentinel test in `GameRestoreRpcHandlersTest.kt:476` can
be removed.

## Log Paths

- **JVM server logs**: `~/.autogenesis/logs/{serverType}-YYYY-MM-DD-HHmmss.log`
  - `serverType` is `server` or `server-extend`. The `keepN` retention
    policy is set via `Logger.configure(..., maxLogFiles = N, serverType = ...)`.
  - The newest log file per type reflects the most-recent run; old logs
    are pruned on startup.
- **Browser logs (when DEBUG is on)**: written to
  `~/.autogenesis/logs/browser-YYYY-MM-DD-HHmmss.log` (10 most recent
  kept) AND persisted to `localStorage["autogenesis_logs"]` in the
  browser. Server POSTs the browser logs to `/api/browser-log` on
  every browser log line.
- **Plan / goal state**: stored in Hermes SessionDB at
  `goal:<session_id>` and in the
  `~/.hermes/plans/<task-slug>/plan.md` file structure.

## Closed Gaps (resume-game-architecture plan)

The two plan tasks previously marked **cancelled** were closed on 2026-06-24:

- **Task 17** ✓ — `GameInitDefineGameRulesResumeTest` (server/src/test/kotlin/gameInit/)
  pins the `defineGameRules(resumeFromVfs=true)` branch contract with 4 cases:
  rehydrate-before-reset call order, blank userId skipped, fresh-session skipped,
  and fall-through when restore returns false. All 4 pass.
- **Task 18** ✓ — `ServerConnectorRequestResumeTest` (server-extend/src/test/kotlin/...)
  covers the full live-mode resume end-to-end with 4 cases: live success (resumeFromVfs
  + resumeUserId verified on forwarded GameSessionStatus), match timeout → empty
  GameTicket, dev mode → local serverUrl, blank accelByteId → empty GameTicket.
  All 4 pass.

Additional coverage added 2026-06-24:
- `ServerExtendSseAccelbyteIdTest` — pins the SSE /events accelbyteId → resume push
  wiring contract (4 cases). The SSE handler was refactored to delegate to a
  testable `internal fun triggerSseResumePush(accelbyteId: String)` helper.

The manual smoke test below remains the operator-facing acceptance check.

## Manual smoke test for the resume-game flow

End-to-end validation the dev operator can run after deploying:

1. Start a single-player game in dev mode. Make a few moves. Disconnect
   the WS.
2. Verify the server log shows the snapshot saved
   (`TurnHarness.serializeCurrentWorldSnapshotToUserRecord` succeeded
   at `~/.autogenesis/logs/server-*.log`).
3. Reconnect the WS in a fresh browser tab.
4. Verify the client receives the `client.resumeAvailable` notification
   (NETWORK category in `~/.autogenesis/logs/browser-*.log` and
   `~/.autogenesis/logs/server-*.log`).
5. Verify the `ResumeOrNewDialog` renders.
6. Click Resume.
7. Verify the gameplay UI mounts with the resumed state (same round,
   same world, same player names).
8. Repeat the test in live mode by setting `SERVER_EXTEND_LIVE_MODE=true`
   and `kvision.liveMode=true` on the build.