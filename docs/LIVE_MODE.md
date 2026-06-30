# Live mode operator runbook

This runbook takes the Autogenesis game from local-dev mode (the default — the
Electron desktop bundle running the full stack on `127.0.0.1`) to live mode,
where the game server runs as an AMS-provisioned dedicated server inside
AccelByte Extend and the matchmaker matches real players through match2.

It is the manual counterpart to the `accelbyte-extend` skill
(`~/.codex/skills/accelbyte-extend/`), which describes the same Extend / match2
concepts the operator encounters here. A future skill will automate the AMS
side of this runbook; today every step is a manual command.

The two modes are controlled by **one env var on the server** and **one build
flag on the client**:

| Side | Flag | Default | Live value |
|---|---|---|---|
| server-extend JVM | `SERVER_EXTEND_LIVE_MODE` (or `-DserverExtend.liveMode`) | unset → dev | `true` |
| kvisionApp build | `-Pkvision.liveMode=true` | unset → dev | `true` |

The two flags **must agree at runtime**. A dev server with a live client
(connected to live `extend-helper-cli`-deployed `server-extend` via the
AEGS URL) works; a live server with a dev client is just a different way to
do local dev.

## 1. Prerequisites

- `extend-helper-cli` (the binary I built/downloaded) on `PATH`. The version
  installed for this repo is at `~/Desktop/Workspaces/AMS/extend-helper-cli`
  v0.0.11. Symlink or add to `PATH` for convenience.
- `ags` CLI on `PATH` — install from
  [accelbyte-ags-cli releases](https://github.com/AccelByte/accelbyte-ags-cli/releases).
  Used for App registration, deployment, and match2 wiring.
- `ams` CLI on `PATH` — install from the AGS portal. Used to register the
  game-server image and create the fleet.
- A confidential IAM client with permissions:
  - `ADMIN:NAMESPACE:{namespace}:EXTEND:APP [CREATE|READ|UPDATE|DELETE]`
  - `ADMIN:NAMESPACE:{namespace}:EXTEND:IMAGE [CREATE|READ]`
  - `ADMIN:NAMESPACE:{namespace}:EXTEND:DEPLOYMENT [CREATE|READ|UPDATE]`
  - `ADMIN:NAMESPACE:{namespace}:EXTEND:REPOCREDENTIALS [READ]`
  - `ADMIN:NAMESPACE:{namespace}:EXTEND:TUNNEL [READ]`
  - `ADMIN:NAMESPACE:{namespace}:AMS:FLEET [CREATE|READ]`
  - `ADMIN:NAMESPACE:{namespace}:AMS:IMAGE [CREATE|READ]`
  - `NAMESPACE:{namespace}:SESSION:TEMPLATE [CREATE|READ]`
  - `NAMESPACE:{namespace}:MATCHMAKING:FUNCTIONS [CREATE|UPDATE|READ]`
  - `NAMESPACE:{namespace}:MATCHMAKING:POOL [CREATE|UPDATE|READ]`
- Env vars: `AGS_BASE_URL`, `AGS_CLIENT_ID`, `AGS_CLIENT_SECRET`,
  `AGS_NAMESPACE`. Same values for the client: `AB_BASE_URL`, `AB_CLIENT_ID`,
  `AB_CLIENT_SECRET`, `AB_NAMESPACE`.

## 2. Build the dedicated game-server image

The repo currently has Dockerfiles for `server-extend` and `matchmaker` but
**not yet for the main `server`** (the dedicated game server JVM that AMS
provisions). Create `server/Dockerfile` mirroring the `server-extend`
pattern:

```dockerfile
FROM eclipse-temurin:24-jre

RUN set -eux; \
    apt-get update; \
    apt-get install -y --no-install-recommends curl ca-certificates; \
    rm -rf /var/lib/apt/lists/*

WORKDIR /opt/server

# Copy the installDist output (script-style run + lib + conf). The Gradle
# task to run first: ./gradlew :server:installDist
COPY build/install/server/ /opt/server/

# Drop an entrypoint wrapper that execs the launcher as PID 1 so SIGTERM
# from AMS reaches the JVM.
COPY docker/entrypoint.sh /opt/server/bin/entrypoint.sh
RUN chmod +x /opt/server/bin/entrypoint.sh /opt/server/bin/autogenesis-server

EXPOSE 9080/tcp

# Health: Server.kt registers a `/health` endpoint on the embedded Ktor
# server. If yours doesn't yet, add a simple `get("/health") { call.respondText("ok") }`
# before deploying.
HEALTHCHECK --interval=10s --timeout=3s --start-period=30s --retries=3 \
    CMD curl -fsS http://127.0.0.1:9080/health || exit 1

ENV SERVER_PORT=9080

ENTRYPOINT ["/opt/server/bin/entrypoint.sh"]
```

Add `server/docker/entrypoint.sh` (mirror of `server-extend/docker/entrypoint.sh`)
and a `server/.dockerignore`. Then build the image:

```bash
./gradlew :server:installDist
docker build -t ghcr.io/cage/autogenesis-game-server:<tag> -f server/Dockerfile server/
docker push ghcr.io/cage/autogenesis-game-server:<tag>
```

## 3. Register the image in AMS

```bash
# (Use the ams CLI; commands may vary by version. Verify with `ams image --help`.)
ams image create \
    --namespace "$AGS_NAMESPACE" \
    --name autogenesis-game-server \
    --image ghcr.io/cage/autogenesis-game-server:<tag> \
    --container-port 9080
# → returns imageId; record it.
```

## 4. Create the AMS fleet

The fleet is the pool of DS instances AMS provisions. Pick a region and
instance type that match your AGS environment.

```bash
ams fleet create \
    --namespace "$AGS_NAMESPACE" \
    --name autogenesis-game-server \
    --image-id "<imageId from step 3>" \
    --region us-east-1 \
    --instance-type c5.large \
    --min-buffer 1 \
    --max-buffer 5
# → returns fleetId; record it.
```

## 5. Create the session templates

Three session templates, one per PvP tier. The match2 service uses the
template's `maxPlayers` to populate `GameSessionStatus.maxPlayers`, which
`MatchPoolBootstrap` reads to size the match.

```bash
for tier in 4 3 2; do
    ags session templates create \
        --namespace "$AGS_NAMESPACE" \
        --json "{
          \"name\": \"pvp-${tier}p-session\",
          \"maxPlayers\": ${tier},
          \"minPlayers\": 1,
          \"fleetId\": \"<fleetId from step 4>\",
          \"gameMode\": \"pvp\"
        }" \
        --format json --no-input --api-scope admin --api-version v1
done
```

> If the `ags session templates` command path differs in your installed
> version, use the equivalent REST call:
> `POST /session/v1/admin/namespaces/{ns}/templates` with the same body. The
> body schema is the same; only the CLI wrapper changes.

## 6. Deploy `server-extend` and `matchmaker` to Extend

Use the existing `extend-helper-cli image-upload + deploy-app` flow. The
Gradle tasks `registerExtendApp`, `pushExtendImage`, `deployExtend` in
`build.gradle.kts` already do this via `ags csm`; the new `extend-helper-cli`
binary is a one-step equivalent.

```bash
~/Desktop/Workspaces/AMS/extend-helper-cli image-upload \
    --namespace "$AGS_NAMESPACE" \
    --app autogenesis-server-extend \
    --image-tag "$(git rev-parse --short HEAD)" \
    --work-dir server-extend \
    --login

~/Desktop/Workspaces/AMS/extend-helper-cli image-upload \
    --namespace "$AGS_NAMESPACE" \
    --app autogenesis-matchmaker \
    --image-tag "$(git rev-parse --short HEAD)" \
    --work-dir matchmaker \
    --login

~/Desktop/Workspaces/AMS/extend-helper-cli deploy-app \
    --namespace "$AGS_NAMESPACE" \
    --app autogenesis-server-extend \
    --image-tag "$(git rev-parse --short HEAD)" --wait

~/Desktop/Workspaces/AMS/extend-helper-cli deploy-app \
    --namespace "$AGS_NAMESPACE" \
    --app autogenesis-matchmaker \
    --image-tag "$(git rev-parse --short HEAD)" --wait
```

## 7. Wire match2 to the deployed matchmaker

`extend-helper-cli` does not wire match2 — that's a separate `ags` call. The
body is verified against the AccelByte Java SDK source (see
`~/.codex/skills/accelbyte-extend/references/wire-schemas.md`).

```bash
ags matchmaking match-functions create \
    --namespace "$AGS_NAMESPACE" \
    --json '{
      "match_function":  "autogenesis-matchmaker",
      "url":             "autogenesis-matchmaker:9095",
      "serviceAppName":  "autogenesis-matchmaker"
    }' \
    --format json --no-input --api-scope admin --api-version v1
```

The match2 wiring is **one-time per namespace**. After a re-deploy, update
the URL in place with `ags matchmaking match-functions update` rather than
re-creating.

## 8. Flip the server-extend deployment to live mode

The Extend deployment runs the same `server-extend` image regardless of
mode; the mode is selected at runtime by the `SERVER_EXTEND_LIVE_MODE` env
var. Set it as a non-secret Extend variable (Extend injects it as an env
var on the Pod):

```bash
~/Desktop/Workspaces/AMS/extend-helper-cli update-var \
    --namespace "$AGS_NAMESPACE" \
    --app autogenesis-server-extend \
    --key SERVER_EXTEND_LIVE_MODE \
    --value "true"

# Restart the deployment so the new env var takes effect (the
# ExtendConfig.debugMode resolution runs once at JVM init).
~/Desktop/Workspaces/AMS/extend-helper-cli deploy-app \
    --namespace "$AGS_NAMESPACE" \
    --app autogenesis-server-extend \
    --image-tag "$(git rev-parse --short HEAD)" --wait
```

## 9. Build and ship the live-mode KVision bundle

```bash
./gradlew :kvisionApp:browserProductionWebpack -Pkvision.liveMode=true
# Or for the dev server: :kvisionApp:jsBrowserDevelopmentRun -Pkvision.liveMode=true
```

The `KVISION_LIVE_MODE=true` define is read at Kotlin/JS compile time and
exposed as a JS global that `globals/ClientDebug.debugMode` resolves. The
KVision `ServerExtendConfig` then picks the live URLs (and the live
match2-backed `requestMultiplayerMatch` path) instead of the localhost
ones. Ship the resulting `build/dist/js/productionExecutable/` artifacts
to your CDN / static host.

> The env var on the server is `SERVER_EXTEND_LIVE_MODE=true`; the build
> flag on the client is `-Pkvision.liveMode=true`. Both must be true at
> runtime for end-to-end live mode. Mixing (e.g. live server with dev
> client) is supported but probably not what you want.

## 10. Smoke test (two-tab live match)

1. Open two browser tabs logged in as different AGS users (any role; they
   need to be in the same namespace).
2. In tab 1, request a multiplayer match. The client should hit the live
   `server-extend` URL (look for `MatchmakingClient.requestMultiplayerMatch`
   in the browser console; it should log a `server.extend.invokeMatchMaking`
   call).
3. In tab 2, do the same. With two players in the `pvp-4` pool, the
   matchmaker should fire — the deployed `matchmaker` pod logs
   `Matchmaker: ... match_attributes max_players=4` when the gRPC stream
   is processed.
4. Both tabs should:
   - Receive `MatchOutcome.Ready(serverUrl, sessionId)` from
     `requestMultiplayerMatch`.
   - Reconnect the WebSocket to the AMS-provisioned DS URL (the
     `serverUrl` from match2's session — typically `<ds-ip>:9080`).
   - Show the arrival gate and start the game.
5. On match end, the game server's `~/.autogenesis/logs/server-*.log` should
   contain a `Server: session storage write succeeded for sessionId=...
   outcome=...` line — that's the `onMatchEnded → writeSessionStorage` hook
   firing.
6. For crash-recovery: stop the DS Pod mid-match, then bring it back up;
   the new pod's `~/.autogenesis/logs/server-*.log` should contain a
   `Server: Crash recovery detected activeSessionId=...` line, followed
   by `readSessionStorage(...)` returning the previously-saved `GameState`.

## 11. Rollback

```bash
# Stop the deployments (does not delete the App or image)
~/Desktop/Workspaces/AMS/extend-helper-cli stop-app \
    --namespace "$AGS_NAMESPACE" --app autogenesis-server-extend --wait
~/Desktop/Workspaces/AMS/extend-helper-cli stop-app \
    --namespace "$AGS_NAMESPACE" --app autogenesis-matchmaker --wait

# Flip back to dev mode by deleting the env var
~/Desktop/Workspaces/AMS/extend-helper-cli update-var \
    --namespace "$AGS_NAMESPACE" --app autogenesis-server-extend \
    --key SERVER_EXTEND_LIVE_MODE --value "false" --force
# (Or use `ags csm deployment update` / re-deploy without the env var set.)

# Rebuild the KVision bundle with -Pkvision.liveMode unset (or =false)
./gradlew :kvisionApp:browserProductionWebpack
```

The App registration, match-function wiring, AMS fleet, and session
templates persist across rollback. Re-enabling live mode is just
re-deploying with the env var set.

## 12. Failure modes (and what to check)

| Symptom | First place to look |
|---|---|
| Match tickets created but never matched | Is the `match-function` URL pointing at a running matchmaker pod? `ags matchmaking match-functions get --namespace $AGS_NAMESPACE --name autogenesis-matchmaker` should show the right URL. Then `kubectl exec` into the matchmaker pod and check `grpc_health_probe -addr=:9095`. |
| Match resolved but `MatchOutcome.Ready` has a blank `serverUrl` | The session template's `fleetId` is wrong, or AMS can't provision the DS. Look in the Admin Portal → Extend → Deployment → Events for the AMS error. |
| `notifyMatchEnded` log line never appears | The game ended via `resetState` rather than a real `evaluateEndGame` / `dispatchForcedGameOver`. The match-end hook only fires on real game-end sites (deliberate, so test resets don't pollute session storage). |
| `writeSessionStorage` line says "no active sessionId" | The match ended before `WorldManager.activeSessionId` was set, or after `clearSession()`. Check that the `bindSession` flow ran for this match in the logs. |
| `extend-helper-cli image-upload` fails with "The stub received bad data" | Linux Docker credential helper rejecting the ECR token. Remove `"credsStore"` from `~/.docker/config.json` (see `accelbyte-extend` skill's `troubleshooting.md`). |
