# Deploying server-extend to AccelByte Extend

This module ships as a single-container Custom Service that hosts the
Ktor/Netty REST+SSE listener on `7070` and a gRPC/grpc-web listener on
`9092`, backed by the AccelByte Java SDK.

## Container contract

| Concern | Value |
|---|---|
| Base image | `eclipse-temurin:24-jre` |
| Entrypoint | `/opt/server-extend/bin/entrypoint.sh` (execs `/opt/server-extend/bin/autogenesis-server-extend` — the beryx launcher) |
| Ports | `7070/tcp` (REST/SSE), `9092/tcp` (gRPC/grpc-web) |
| Health | `GET /player` returns `200 {"status":"ok","server":"local"}` from `ServerExtend.kt`'s routing block |
| Env (injected by Extend) | `AB_NAMESPACE`, `AB_CLIENT_ID`, `AB_CLIENT_SECRET`, `AB_BASE_URL`, `SERVER_EXTEND_GRPC_PORT` |

The Extend deployment profile matches the
`apimodel.CreateDeploymentV2Request` schema (`ags csm deployments create`
→ `POST /csm/v2/admin/namespaces/{ns}/apps/{app}/deployments`). The body
is just `{"imageTag":"<registry>/<image>:<tag>"}`; runtime `commandLine`
and `portConfigurations` are derived from the image's `ENTRYPOINT` and
`EXPOSE` directives, not sent in the body.

## Credentials (hybrid)

`AccelByteConfig` reads `AB_*` from `System.getenv()` first, falling back
to a properties file at one of:

- `./accelbyte.local.properties` (cwd)
- `./accelbyte.properties`
- `~/.autogenesis/config/accelbyte.local.properties`
- `~/.autogenesis/config/accelbyte.properties`
- a classpath resource

For Extend deployments the platform injects `AB_*` as env vars, so the
"hybrid" is: env vars win, and a Secret-backed volume mount at
`/home/.autogenesis/config/accelbyte.local.properties` is the documented
fallback (no code change to `AccelByteConfig.kt`).

## Prerequisites

- Docker, with push access to the target registry
  (default: `ghcr.io/cage/autogenesis-server-extend`).
- `ags` CLI on `PATH` (https://github.com/AccelByte/accelbyte-ags-cli/releases).
- A confidential IAM client with the permissions:
  - `ADMIN:NAMESPACE:{namespace}:EXTEND:APP [CREATE]` and `[READ]`
    (for `registerExtendApp` and `verifyExtendDeployment`)
  - `ADMIN:NAMESPACE:{namespace}:EXTEND:IMAGE [READ]`
    (for `verifyExtendDeployment`)
  - `ADMIN:NAMESPACE:{namespace}:EXTEND:DEPLOYMENT [READ]`
    (for `verifyExtendDeployment`)
  - `ADMIN:NAMESPACE:{namespace}:EXTEND:DEPLOYMENT [CREATE]`
    (for `deployExtend`)
- The env vars `AGS_BASE_URL`, `AGS_CLIENT_ID`, `AGS_CLIENT_SECRET`,
  `AGS_NAMESPACE`.

## Build → push → deploy (operator flow)

```bash
# 0. (one time) authenticate the ags CLI.
./gradlew extendLogin

# 0.5. (one time per namespace) register the App with Extend. Idempotent:
#      no-ops if the App already exists. The body is built from the
#      `ExtendModule` data class — `scenario: service-extension`, plus
#      resource requests that mirror `k8s/server-extend.yaml`.
./gradlew registerExtendApp -Pmodule=server-extend

# 1. (optional) sanity-check the prereqs.
./gradlew verifyExtendPrereqs

# 2. Build the image. Depends on `:server-extend:runtime` (the beryx
#    runtime task) producing `build/server-extend-runtime/server-extend-linux-x64/`.
./gradlew :server-extend:dockerBuildImage
# Prints: ghcr.io/cage/autogenesis-server-extend:<short-sha>

# 3. Push the image to the configured registry.
TAG=$(git rev-parse --short HEAD)
./gradlew pushExtendImage -Pmodule=server-extend -Ptag=$TAG

# 4. Create (or replace) the Extend deployment.
./gradlew deployExtend -Pmodule=server-extend -Ptag=$TAG

# 5. Verify: confirm App + image + deployment are all in place. Pass/fail
#    per check; soft-passes the image check when the deployment is up but
#    the image lives in an external registry like ghcr.io.
./gradlew verifyExtendDeployment -Pmodule=server-extend
```

Both `pushExtendImage` and `deployExtend` accept `-PdryRun=true` to print
the underlying `docker` / `ags` commands without executing them.

## Local smoke test

```bash
# Build the beryx runtime image first so the Dockerfile has something to COPY.
./gradlew :server-extend:runtime

docker build -t autogenesis-server-extend:dev -f Dockerfile .
docker run --rm \
    -p 7070:7070 -p 9092:9092 \
    -e AB_NAMESPACE=autogenesis-dev \
    -e AB_CLIENT_ID=... -e AB_CLIENT_SECRET=... -e AB_BASE_URL=https://demo.accelbyte.io \
    autogenesis-server-extend:dev

# in another shell:
curl -fsS http://127.0.0.1:7070/player
# -> {"status":"ok","server":"local"}
```

## Self-hosted k8s fallback

If you are running outside AccelByte Extend, use
`k8s/server-extend.yaml` after replacing the image tag, namespace, and
`AB_*` secret references. The Extend deployment is the production path;
the YAML is a reference for self-hosted clusters only.

## Rollback

```bash
ags csm deployments list --namespace "$AGS_NAMESPACE" --format json --no-input
ags csm deployments delete --namespace "$AGS_NAMESPACE" --app autogenesis-server-extend \
    --deployment-id <id> --no-input
```

Then re-run `deployExtend` against the previous known-good tag.

## Switching to live mode

The `server-extend` image is the same in dev and live; the mode is selected at
runtime by the `SERVER_EXTEND_LIVE_MODE` env var. The default is dev mode
(fast-path, no AccelByte match2 traffic, `serverUrl = "127.0.0.1:9080"`).
Live mode takes the match2 + AMS path: create ticket → poll → resolve
`dsInformation.server.ip:port` → forward to the dedicated game server.

To go live, set `SERVER_EXTEND_LIVE_MODE=true` on the Extend deployment env
and restart the deployment. The full operator flow (AMS image, fleet,
session templates, match2 wiring, smoke test) is documented in
`docs/LIVE_MODE.md` at the repo root.

```bash
# Flip to live mode and re-deploy
~/Desktop/Workspaces/AMS/extend-helper-cli update-var \
    --namespace "$AGS_NAMESPACE" \
    --app autogenesis-server-extend \
    --key SERVER_EXTEND_LIVE_MODE \
    --value "true"

~/Desktop/Workspaces/AMS/extend-helper-cli deploy-app \
    --namespace "$AGS_NAMESPACE" \
    --app autogenesis-server-extend \
    --image-tag "$(git rev-parse --short HEAD)" --wait
```

`verifyExtendDeployment` runs in both modes; the only change is which branch
`ServerConnector.requestGame` / `invokeMatchMaking` take.