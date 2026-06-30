# Deploying the matchmaker to AccelByte Extend

This module ships as a single-container Custom Service that implements the
AccelByte match2 `Service` gRPC contract. The Docker image wraps the JVM
binary in a thin entrypoint so PID 1 == the JVM and signal handling works
under Extend.

## Container contract

| Concern | Value |
|---|---|
| Base image | `eclipse-temurin:24-jre` |
| Entrypoint | `/opt/matchmaker/bin/entrypoint.sh` (execs `/opt/matchmaker/bin/matchmaker`) |
| Ports | `9095/tcp` (gRPC) |
| Health | `grpc.health.v1.Health` on `:9095`; `grpc_health_probe` returns `SERVING` after startup |
| Env (injected by Extend) | `AB_NAMESPACE`, `AB_CLIENT_ID`, `AB_CLIENT_SECRET`, `AB_BASE_URL`, `MATCHMAKER_GRPC_PORT` |

The Extend deployment profile matches the
`apimodel.CreateDeploymentV2Request` schema (`ags csm deployments create`
→ `POST /csm/v2/admin/namespaces/{ns}/apps/{app}/deployments`). The body
is just `{"imageTag":"<registry>/<image>:<tag>"}`; runtime `commandLine`
and `portConfigurations` are derived from the image's `ENTRYPOINT` and
`EXPOSE` directives, not sent in the body.

## Prerequisites

- Docker, with push access to the target registry
  (default: `ghcr.io/cage/autogenesis-matchmaker`).
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
  - `NAMESPACE:{namespace}:MATCHMAKING:FUNCTIONS [CREATE]` / `[UPDATE]` / `[READ]`
    (for the match2 match-function wiring)
- The env vars `AGS_BASE_URL`, `AGS_CLIENT_ID`, `AGS_CLIENT_SECRET`,
  `AGS_NAMESPACE`.

## Build → push → deploy (operator flow)

```bash
# 0. (one time) authenticate the ags CLI. Re-runs are no-ops if already
# logged in.
./gradlew extendLogin

# 0.5. (one time per namespace) register the App with Extend. Idempotent:
#      no-ops if the App already exists. The body is built from the
#      `ExtendModule` data class — `scenario: function-override`, plus
#      resource requests that mirror `k8s/matchmaker.yaml`.
./gradlew registerExtendApp -Pmodule=matchmaker

# 1. (optional) sanity-check the prereqs.
./gradlew verifyExtendPrereqs

# 2. Build the image. Default tag = <git short SHA>; override with
#    `-Ptag=…` if you want a release-style tag.
./gradlew :matchmaker:dockerBuildImage
# Prints: ghcr.io/cage/autogenesis-matchmaker:<short-sha>

# 3. Push the image to the configured registry. The tag becomes
#    addressable by `imageTag` in step 4.
TAG=$(git rev-parse --short HEAD)
./gradlew pushExtendImage -Pmodule=matchmaker -Ptag=$TAG

# 4. Create (or replace) the Extend deployment.
./gradlew deployExtend -Pmodule=matchmaker -Ptag=$TAG

# 5. Verify: confirm App + image + deployment (and match2 wiring below)
#    are all in place. Pass/fail per check; soft-passes the image check
#    when the deployment is up but the image lives in an external registry
#    like ghcr.io.
./gradlew verifyExtendDeployment -Pmodule=matchmaker
```

### Wire the matchmaker into match2

Deploying the image is not enough — match2 only routes tickets to the
matchmaker once a match function is registered that points at the
matchmaker's gRPC endpoint. The match2 wiring goes through the match2
`match-functions` resource (`ags matchmaking match-functions …`), not
through `ags platform plugin-config` (which is for catalog/payment
plugins only).

```bash
# Register the deployed matchmaker as a match2 match function.
#   * `match_function`  — a stable name the match pool will reference.
#   * `url`             — DNS name + port Extend routes to the matchmaker
#                          pod. The conventional in-Extend form is
#                          `<app-name>:<port>`, but the actual value
#                          depends on the `portConfigurations[]` mapping
#                          that landed at deploy time. Confirm via
#                          `ags csm deployments get` on the deployed
#                          matchmaker.
#   * `serviceAppName`  — names the Custom Service that hosts the
#                          match function, so match2 can resolve the
#                          URL against the right Extend deployment.
ags matchmaking match-functions create \
    --namespace "$AGS_NAMESPACE" \
    --json '{
      "match_function": "autogenesis-matchmaker",
      "url":            "autogenesis-matchmaker:9095",
      "serviceAppName": "autogenesis-matchmaker"
    }' \
    --format json --no-input --api-scope admin --api-version v1
```

After a re-deploy, update the `url` in place rather than re-creating:

```bash
ags matchmaking match-functions update \
    --namespace "$AGS_NAMESPACE" \
    --name autogenesis-matchmaker \
    --json '{
      "match_function": "autogenesis-matchmaker",
      "url":            "autogenesis-matchmaker:9095",
      "serviceAppName": "autogenesis-matchmaker"
    }' \
    --format json --no-input --api-scope admin --api-version v1
```

List what's currently registered:

```bash
ags matchmaking match-functions list \
    --namespace "$AGS_NAMESPACE" \
    --format json --no-input --api-scope admin --api-version v1
```

The gRPC contract the matchmaker implements is the vendored
`accelbyte_matchmaker.proto` `Service` interface (two RPCs:
`Validate(Request) → Response` and `MakeMatches(stream Req) → stream Res`).
match2 will only call those methods correctly if the matchmaker image is
running and `grpc_health_probe -addr=<host>:9095` reports `SERVING`.

Both `pushExtendImage` and `deployExtend` accept `-PdryRun=true` to print
the underlying `docker` / `ags` commands without executing them.

## Local smoke test

```bash
docker build -t autogenesis-matchmaker:dev -f Dockerfile .
docker run --rm -p 9095:9095 autogenesis-matchmaker:dev
# in another shell:
grpc_health_probe -addr=127.0.0.1:9095
# -> status: SERVING
```

## Self-hosted k8s fallback

If you are running outside AccelByte Extend (for local cluster work), use
`k8s/matchmaker.yaml` after replacing the image tag, namespace, and `AB_*`
secret references. The Extend deployment is the production path; the YAML
is a reference for self-hosted clusters only.

## Rollback

```bash
# list deployments
ags csm deployments list --namespace "$AGS_NAMESPACE" --format json --no-input
# delete the current one (returns when deletion completes)
ags csm deployments delete --namespace "$AGS_NAMESPACE" --app autogenesis-matchmaker \
    --deployment-id <id> --no-input
```

Then re-run `deployExtend` against the previous known-good tag.

## Live mode and the matchmaker

The matchmaker is mode-agnostic: it just services the match2 `Service` gRPC
contract and emits match proposals with `match_attributes["max_players"]`.
Whether the calling code path is dev (local `server-extend` fast-path) or
live (`server-extend` talking to match2) does not change what the matchmaker
does. The mode toggle is on `server-extend` (`SERVER_EXTEND_LIVE_MODE`); the
matchmaker image and deployment are identical in both modes.

For the full end-to-end live flow (AMS image, fleet, session templates,
match2 wiring, KVision live bundle), see `docs/LIVE_MODE.md` at the repo root.
