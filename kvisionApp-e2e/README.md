# kvisionApp-e2e

Browser-side end-to-end probes (Playwright + Node). The KVision UI runs in
real Chromium and exercises the same wire paths the production browser
uses — WebSocket RPC bridge, the service worker registration, VAPID
subscription round-trip, and AccelByte CloudSave-backed persistence on
the server.

## Layout

```
probes/
├── guest-login.mjs                  ← smoke test: login as guest
├── login-flow-e2e.mjs               ← login → main menu
├── resume-e2e.mjs                   ← resume saved game across WS reconnect
├── push-turn-start.mjs              ← Web Push: subscribe → store → mock receive
├── echo-verify-resume.mjs
├── never-played-resume.mjs
└── ...                              ← one probe per user-facing flow

playwright.config.mjs                ← base config; tests/ uses it
start.mjs                            ← build + serve production bundle on :4175
static-server-8080.mjs               ← serve production bundle on :8080 (fallback)
run-tests.mjs                        ← batch runner (DEPRECATED, use playwright test)
package.json
```

The probes are intentionally split into individual `.mjs` scripts rather
than a single `playwright.config.mjs` test suite. Most flows depend on
a live AccelByte SDK + server-extend matchmaking ticket, and the
deeper probes (resume, push) need to drive the DS state through
explicit commands before/after the browser step. A monolithic
`@playwright/test` runner would obscure that interplay.

## Local dev loop

The three JVM processes + the static server:

```
┌─ port 7070 ─ server-extend ─┐    matches players, talks to AGS, dispatch
├─ port 9080 ─ server ────────┤    hosts WorldManager, TurnHarness, push
├─ port 8080 ─ static 8080 ───┘    serves the KVision bundle + sw.js / vapid
└─ port 9099 ─ push mock ─────┘    only opened by push-turn-start.mjs itself
```

### Start the JVMs

```bash
# 1) Server-extend — talks to AGS, dispatches into the game server
AUTOGENESIS_DEV_PUSH_MOCK_PORT=9099 ./gradlew :server-extend:run

# 2) Server — main game server with push + TurnHarness; needs the long
#    shutdown delay so the probe can disconnect + reconnect within
#    one run. Also enables the /debug/seed-push-subscription,
#    /debug/advance-turn, /debug/trigger-push-now routes the probe uses.
AUTOGENESIS_SHUTDOWN_DELAY_MS=600000 \
  AUTOGENESIS_DEV_PUSH_MOCK_PORT=9099 \
  AUTOGENESIS_PUSH_TEST_ENDPOINT=true \
  ./gradlew :server:run
```

### Serve the UI

```bash
node static-server-8080.mjs
```

`static-server-8080.mjs` is a fallback that:
- Serves `kvisionApp/build/dist/js/productionExecutable/` (which is the
  already-bundled output the CI image produces).
- Overlays `sw.js`, `manifest.webmanifest`, and `vapid_public.json` from
  `kvisionApp/build/processedResources` and `.../build/generated/vapid`.

See `kvisionApp/README.md` for why this exists — the webpack dev server
(`./gradlew :kvisionApp:jsBrowserDevelopmentRun`) fails on Node 22+ with
`SyntaxError: Identifier 'path' has already been declared` because
`kvisionApp/webpack.config.d/pwa-push.js` and the auto-generated
prelude both declare `const path = require('path');`. Until webpack-cli
6.0.1 is updated, use the static server.

### Use the dev webpack server instead (older Node only)

If you're on Node ≤ 20 (CI image, Docker, etc.), the dev webpack server
runs:

```bash
./gradlew :kvisionApp:jsBrowserDevelopmentRun      # webpack-dev-server on :8080
# when that's bound on :8080, do NOT also run static-server-8080.mjs
```

In that mode `static-server-8080.mjs` is not needed; the prod bundle
will be served by webpack-dev-server itself, and `sw.js` /
`vapid_public.json` come from the same `pwa-push` hook.

### Run a probe

```bash
node probes/guest-login.mjs
node probes/push-turn-start.mjs     # see "Push Turn Start probe" below
```

Most probes bind their own short-lived mock ports (9099 for push). They
exit non-zero if any required port is already in use.

## Push Turn Start probe (`probes/push-turn-start.mjs`)

End-to-end Web Push test. Opens a real Chromium, logs in as a guest,
plays through commander selection, then proves that a turn-start push
notification reaches the local mock receiver after the player is
disconnected — without going through FCM/APNs/Mozilla.

The probe uses **three debug HTTP routes** on the server (gated by
`AUTOGENESIS_PUSH_TEST_ENDPOINT=true`, see
`server/src/main/kotlin/org/ttt/autogenesis/server/Server.kt`):

| Route                              | Purpose                                                                                                                                                       |
| ---------------------------------- | --------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `POST /debug/seed-push-subscription?userId=…` | Writes a synthetic P-256 push subscription into the VFS for `userId`. The endpoint host is rewritten to `http://127.0.0.1:9099` at push send (via `AUTOGENESIS_DEV_PUSH_MOCK_PORT`).          |
| `POST /debug/advance-turn?player=…`           | Completes any parked `awaitPlayerAction` for `player` with a synthetic AI-takeover submission. Skips waiting 5+ minutes for the real LLM-driven AI takeover.                          |
| `POST /debug/trigger-push-now?userId=…&actor=…&round=…` | Calls `PushNotificationService.sendTurnStart(userId, actor, round)` synchronously. Same wire path as the in-game trigger at `TurnHarness.kt:1393`.                                |

The probe does NOT depend on the Service Worker subscribing in Chromium
(which fails in headless with "Registration failed - permission denied"
even with `grantPermissions(['notifications'])`). The seed endpoint
substitutes for that step. Everything else is the production code path.

### Probe environment variables

| Var                                | Default                  | Purpose                                                                                                            |
| ---------------------------------- | ------------------------ | ------------------------------------------------------------------------------------------------------------------ |
| `AUTOGENESIS_DEV_PUSH_MOCK_PORT`    | `9099`                   | Where the probe listens for the push. Override only if 9099 is busy.                                                |
| `AUTOGENESIS_SHUTDOWN_DELAY_MS`    | `600000`                 | Tells the server not to exit for 10 minutes after disconnect. Must be set when launching `:server`.              |
| `PUSH_TURN_START_WAIT_MS`          | `360000` (6 min)         | Upper bound for the in-game push-wait path (legacy). With `trigger-push-now` the probe completes in ~3 seconds. |
| `SERVER_URL`                        | `http://127.0.0.1:9080`  | Where the probe POSTs `/debug/seed-push-subscription` and `/debug/trigger-push-now`.                              |
| `BASE_URL`                           | `http://127.0.0.1:8080`  | KVision bundle URL. Pass `--base-url=...` to override.                                                              |

### Invariants the probe asserts

- Browser reaches `Round: 1` GameplayUI within `2s` after commander
  selection.
- `POST /debug/seed-push-subscription` returns `200 {"status":"stored"}`.
- Browser closes — no LLM calls fire (Phase 4 uses `trigger-push-now`).
- Mock on port 9099 receives a single POST within `30s` of seed.
- Payload has `content-encoding: aesgcm` and a `WebPush …` VAPID
  Authorization header.

Each invariant failure causes the probe to dump `console-phase1.log`
and exit non-zero with a clear `RESULT: FAIL` line.

## Failure modes the probe is designed to catch

- **VAPID keypair regenerated but bundle not redeployed**:
  probe's subscription is stored fine, trigger returns
  `{"status":"delivered"}`, but mock receives nothing because the
  server's signing key doesn't match the public key in the bundle.
- **`AUTOGENESIS_PUSH_TEST_ENDPOINT` not set on the server**:
  `/debug/seed-push-subscription` returns 404, and the seed log shows
  `no-awaiter`/404 from the trigger.
- **VAPID PEM in SEC1 form**: server boots but logs `Unable to
  decode key`. Convert with
  `openssl pkcs8 -topk8 -nocrypt -in <sec1> -out <pkcs8>`.
- **Tree-shaken push service in production webpack output**: bundle
  does not contain `subscribeIfPermitted`. Re-add the keepalive at
  `Main.kt:start()` — see `kvisionApp/README.md`.
- **Service Worker fails to register** (sw.js 404 from static server):
  probe prints `[error] Failed to load resource: 404`. Verify
  `static-server-8080.mjs` is up and that `sw.js` is reachable.

## Replay the probe against a fresh environment

```bash
# 0) Wipe per-user VFS so the test starts clean
rm -rf ~/.autogenesis/vfs/

# 1) Boot the JVMs with the right env vars
AUTOGENESIS_DEV_PUSH_MOCK_PORT=9099 ./gradlew :server-extend:run &
AUTOGENESIS_SHUTDOWN_DELAY_MS=600000 \
  AUTOGENESIS_DEV_PUSH_MOCK_PORT=9099 \
  AUTOGENESIS_PUSH_TEST_ENDPOINT=true \
  ./gradlew :server:run &
node static-server-8080.mjs &

# 2) Wait for both :9080 and :8080 to listen
until nc -z 127.0.0.1 9080 && nc -z 127.0.0.1 8080; do sleep 1; done

# 3) Run
node probes/push-turn-start.mjs

# 4) Cleanup
pkill -f ':server:run'
pkill -f ':server-extend:run'
pkill -f 'static-server-8080.mjs'
```

Artifacts land in `probes/artifacts-push-turn-start/` (DOM snapshots
per phase, full console log, the JSON list of received pushes).

## What this directory is NOT

- It is **not** a `@playwright/test` runner. Use it for one-shot
  operator checks and CI smoke; for CI-driven regression, wrap the
  probes in `tests/` (see `playwright.config.mjs`).
- It is **not** an integration test harness for `server/src/test`.
  JVM-side unit + integration lives in the gradle `:server:test` task.
- The two static servers (`static-server-8080.mjs` on :8080 and
  `start.mjs` on :4175) serve the same bundle; pick one per
  environment. Mixing them creates 404s because only the
  8080 variant is wired up to overlay `sw.js` and
  `vapid_public.json`.

## Related docs

- `server/README.md` § **Web Push (VAPID)** — server-side env vars,
  PEM format, debug endpoints, BouncyCastle provider.
- `kvisionApp/README.md` — service-worker registration, DCE keepalive,
  Node 22+ webpack-cli gotcha, VAPID public-key provisioning.
- `CLAUDE.md` § **Build, Test, and Development Commands** — top-level
  gradle commands plus the static-server fallback.