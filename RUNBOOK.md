# Autogenesis — Operator Runbook

This document covers everything an operator (or new engineer on-call) needs to provision the **external** dependencies, configure secrets, deploy the servers, and recover from common failure modes.

For developer documentation (build commands, internal architecture, contributing) see [`README.md`](README.md).

> **Audience:** Platform engineers, SRE, AccelByte / AWS administrators, game operations.
>
> **Source of truth precedence:** per-module READMEs/DEPLOY.md override this file when they conflict, because they live next to the code that reads the relevant env vars. This runbook consolidates and links out.

---

## 1. External Dependencies — What You Must Provision

Autogenesis is not self-contained. Before any of the three JVM services can boot, you need accounts and resources at **five** external providers:

| # | Provider | What it provides | Account needed | Used by |
|---|---|---|---|---|
| 1 | **AccelByte Gaming Services (AGS)** | Namespace, OAuth, match2, session, CloudSave, DSMC, Multiplayer Server (AMS/DS Hub) | AccelByte Studio account + namespace | All three JVMs |
| 2 | **Amazon Web Services — Bedrock** | LLM inference (Claude, Nova, etc.) for AI players + Lord Maple Tree persona | AWS account with Bedrock model access enabled in target region(s) | `:server` (per-user or platform credentials) |
| 3 | **AWS account — general** | IAM roles for DSMC registration, optional S3/CloudWatch | Same AWS account | `:server`, all JVMs |
| 4 | **`protoc` (Protocol Buffers compiler)** | Generates gRPC-Web / Connect stubs into the JS bundle | Local install on each dev/CI host | `:kvisionApp` build (via `grpcBridgeProto`) |
| 5 | **Node.js 22.12.0+** | Runs the Kotlin/JS Gradle plugin, Yarn, Dukat, protoc plugins | Local install | `:kvisionApp` build |
| 6 | **JRE 24** | Runtime for the three JVM services | Bundled via `:server:runtime`, `:server-extend:runtime` | All JVMs |
| 7 | **`ags` CLI** | Extends deployment tool (AccelByte Custom Service deployment) | Local install on operator workstation | Optional — only if deploying to AGS Extend vs. self-hosted k8s |

### 1.1 Things you do **NOT** need despite what you might think

- ❌ Elasticsearch (mentioned in older drafts — not used by this codebase)
- ❌ Kafka / event bus (not used)
- ❌ MongoDB / PostgreSQL (not used; state lives in AccelByte CloudSave + `~/.autogenesis/vfs`)
- ❌ Redis (not used)

The state model is: AccelByte CloudSave records + a local VFS under `~/.autogenesis/vfs/`. See [`server/README.md`](server/README.md) for the local-VFS config (`AUTOGEN_VFS_PATH`).

---

## 2. AccelByte Namespace — One-Time Setup

Perform these steps **once per AccelByte namespace** before deploying any module. Repeat for every new environment (staging, prod-eu, etc.) unless you intend to share.

### 2.1 Create the OAuth client

Create one IAM OAuth client for the server-side modules with these permissions:

- `match2:ADMIN`
- `session:ADMIN`
- `cloudsave:ADMIN:[USER]`
- `dsm:ADMIN`
- `iam:READ`

Record the client id and secret — these become `AB_CLIENT_ID` and `AB_CLIENT_SECRET`.

Reference: [AccelByte OAuth client creation](https://docs.accelbyte.io/gaming-services/services/access/iam/how-to/create-oauth-client).

### 2.2 Provision the three match pools

Create the PvP match pools used by the matchmaking algorithm:

| Pool name | Coverage | Notes |
|---|---|---|
| `pvp-4` | 4 players | Default 4-player match |
| `pvp-3` | 3 players | 3-player fallback |
| `pvp-2` | 2 players | Solo duel |

Each pool uses the `default` match function. The custom gRPC matchmaker at `MATCHMAKER_GRPC_PORT=9095` is registered as the override — see [`matchmaker/DEPLOY.md`](matchmaker/DEPLOY.md) for the match-function wiring.

### 2.3 Configure the DS Hub

Provision the Dedicated Server Hub for the target AccelByte cluster and capture:

- `AB_DS_HUB_URL` — WebSocket URL the DS connects to on startup
- `AB_WATCHDOG_URL` — AMS watchdog URL for heartbeats
- `AB_REGION` — must match the AWS region you run the DS in (e.g. `us-east-1`)

### 2.4 CloudSave ACL for the BYO slot

The BYO (Bring-Your-Own) AWS credentials flow stores per-user AWS access keys, AES-256 encrypted server-side, in a CloudSave slot named `byo_credentials_v1`.

**Configure the slot with admin-only read + admin-only write.** Without this, a determined KVision client could in principle `GET` the slot via the standard AccelByte CloudSave REST API and decrypt locally if the master key ever leaked. Server-side encryption is defense-in-depth, not the primary control.

Step-by-step in [`server-extend/README.md`](server-extend/README.md#cloudsave-acl-for-byo-credentials).

### 2.5 Generate the BYO master key

Run on your **operator workstation** (not on the server):

```bash
openssl rand -base64 32
```

This is the value you'll set as `BYO_KEY_MASTER_KEY` on every `server-extend` instance.

**Rotation semantics:** rotating this key invalidates every existing BYO key — every player must re-submit. Plan rotations carefully. See [§6.1](#61-rotating-byo_key_master_key) for the playbook.

---

## 3. AWS Bedrock — One-Time Setup

### 3.1 Enable model access

In each AWS region you intend to operate `:server` in:

1. Open the Bedrock console → **Model access**
2. Request access for the models you plan to use (Claude 3.7 Sonnet, Nova Pro, etc.)
3. Wait for `Access granted` status (often immediate, sometimes hours)

Capture the **inference profile ARN** for each model (Bedrock console → **Cross-region inference** or **Provisioned throughput**). You will paste these into `~/.aws/inference.txt` (see §3.3).

### 3.2 Platform credentials (the default path)

By default, `:server` uses platform-managed Bedrock credentials — AccelByte writes a record into the user's CloudSave, and the game server picks it up. You only need to enable the Bedrock models in each region you support; the model ID is looked up by region (`us.*` profile vs. `eu.*` profile).

Override the game-record key via:

```
BEDROCK_AWS_CREDENTIALS_FILE=/abs/path/to/credentials.json   # optional override for the record key
BEDROCK_AWS_CREDENTIAL_RECORD_KEY=bedrock-aws-credentials   # default; rarely needs override
```

### 3.3 Inference profile mapping

Fill `~/.aws/inference.txt` with the inference profile ARNs you provisioned in §3.1. Example format:

```
# model-id                          profile-arn
us.anthropic.claude-3-7-sonnet-20250219-v1:0   arn:aws:bedrock:us-east-1:123456789012:inference-profile/...
eu.anthropic.claude-3-7-sonnet-20250219-v1:0   arn:aws:bedrock:eu-west-1:123456789012:inference-profile/...
```

`:server` reads this file on boot; missing entries cause a graceful fallback to on-demand inference in that region.

### 3.4 BYO AWS keys (the user-pays path)

Players can optionally provide their own AWS access key + secret in the game's Account Settings UI. The server-side flow:

1. Client encrypts the key under `BYO_KEY_MASTER_KEY` (AES-256-GCM)
2. Client writes the ciphertext to CloudSave slot `byo_credentials_v1`
3. `:server-extend` resolves the slot at runtime and hands the decrypted key to `:server`
4. `:server` uses the user's key for that user's inference turn only

No AWS-side configuration needed for this path — it uses the player's credentials, not yours.

---

## 4. Configuration Matrix (All Env Vars)

The complete set of environment variables the three JVM services read. Defaults shown are the values the system was tested against; overrides are safe **unless flagged**.

### 4.1 Required in production

| Variable | Module | Purpose | Notes |
|---|---|---|---|
| `AB_NAMESPACE` | server, server-extend | AccelByte namespace | Identical value across modules |
| `AB_CLIENT_ID` | server, server-extend | OAuth client id for server-to-server calls | From §2.1 |
| `AB_CLIENT_SECRET` | server, server-extend | OAuth client secret | **Secret. Never commit.** |
| `AB_BASE_URL` | server, server-extend | AccelByte API base URL | `https://<your-namespace>.prod.gamingservices.accelbyte.io` |
| `AB_DS_ID` | server | Dedicated server id assigned by AMS at boot | Captured at allocation time |
| `AB_DS_HUB_URL` | server | DS Hub WebSocket URL | From §2.3 |
| `AB_WATCHDOG_URL` | server | AMS watchdog URL for heartbeats | From §2.3 |
| `AB_REGION` | server | Region this DS instance runs in | Must match AWS region for non-BYO users |
| `BYO_KEY_MASTER_KEY` | server-extend | Base64-encoded 32-byte AES-256 key for BYO credential encryption | **Required in non-debug prod** |

### 4.2 Optional with defaults

| Variable | Module | Default | Purpose |
|---|---|---|---|
| `BEDROCK_AWS_CREDENTIAL_RECORD_KEY` | server | `bedrock-aws-credentials` | Override the game-record key for platform Bedrock credentials |
| `BEDROCK_AWS_CREDENTIALS_FILE` | server | (none) | Path to a JSON file with `keyId`, `accessKey`, `secretKey` for the platform record |
| `BEDROCK_AWS_PROFILE` | server | `default` | AWS profile for fallback resolution |
| `AUTOGEN_VFS_PATH` | server | `~/.autogenesis/vfs` | Override the local VFS root (sandboxed / CI) |
| `SERVER_EXTEND_MAIN_SERVER_WS_URL` | server-extend | (auto) | Override the WebSocket URL for `:server` if non-default |
| `SERVER_EXTEND_GRPC_PORT` | server-extend | `9092` | gRPC listener port (override when running multiple instances on one host) |
| `MATCHMAKER_GRPC_PORT` | matchmaker | `9095` | gRPC listener port for the custom matchmaker |
| `MATCHMAKING_SUBSIDY_BYO` | server-extend | `4` | Per-cost-class subsidy capacity. **Do not override without sign-off** |
| `MATCHMAKING_SUBSIDY_PRO` | server-extend | `2` | |
| `MATCHMAKING_SUBSIDY_CASUAL` | server-extend | `1` | |
| `MATCHMAKING_SUBSIDY_CREDIT` | server-extend | `0` | |
| `MATCHMAKING_SUBSIDY_FREE` | server-extend | `0` | |
| `MATCHMAKING_CREDIT_SUBSIDY_PER_1000` | server-extend | `1` | Extra subsidy per 1000 credit-balance units (capped by `MATCHMAKING_CREDIT_CAP`) |
| `MATCHMAKING_CREDIT_CAP` | server-extend | `2` | Hard cap on credit-derived subsidy |
| `MATCHMAKING_REQUIRE_COVERAGE_4` | server-extend | `true` | Whether matched groups must reach pool coverage |
| `MATCHMAKING_REQUIRE_COVERAGE_3` | server-extend | `true` | |
| `MATCHMAKING_REQUIRE_COVERAGE_2` | server-extend | `false` | |
| `MATCHMAKING_MAX_SUBSIDY` | server-extend | `4` | Global cap on combined subsidy per match |

### 4.3 Test / debug only

| Variable | Module | Purpose |
|---|---|---|
| `RIG` | server | Force these AI opponents. Comma-separated names. |
| `MAP` | server | Pin map selection to this map name. |
| `AUTOGENESIS_DEBUG_SEED` | server | Reproducible RNG seed for tests |
| `AUTOGENESIS_DEBUG_SHORT_TURN_TIMEOUT_MS` | server | Shorten turn timeout for test runs |
| `AUTOGENESIS_DISABLE_AUTO_RESTORE` | server | Skip auto-restore on boot (test only) |
| `AUTOGENESIS_DEV_PUSH_MOCK_PORT` | server | Mock push notification port for E2E tests |
| `AUTOGENESIS_PUSH_TEST_ENDPOINT` | server | Override the push endpoint URL for tests |
| `AUTOGENESIS_SHUTDOWN_DELAY_MS` | server | Delay graceful shutdown for E2E cleanup |

### 4.4 Properties file fallback

Each server reads configuration from env vars **and/or** a properties file. The properties file is searched in this order — first match wins:

1. `./accelbyte.local.properties` (working dir — **primary dev location**)
2. `./accelbyte.properties`
3. `~/.autogenesis/config/accelbyte.local.properties`
4. `~/.autogenesis/config/accelbyte.properties`
5. Bundled `accelbyte.local.properties` resource
6. Bundled `accelbyte.properties` resource

The root `.gitignore` already excludes `*.local.properties`, so these files never reach git history.

---

## 5. Per-Environment Rollout

When standing up a new environment (staging, prod-eu, prod-ap, etc.):

- [ ] **Generate a fresh `BYO_KEY_MASTER_KEY` for the environment.** Reusing the same key across environments means a leak in one compromises all.
- [ ] **Create `accelbyte.local.properties`** for both `server/` and `server-extend/` with the environment's `AB_NAMESPACE`, `AB_CLIENT_ID`, `AB_CLIENT_SECRET`, `AB_BASE_URL`. **Do not commit these files.**
- [ ] **Inject secrets** (`AB_CLIENT_SECRET`, `BYO_KEY_MASTER_KEY`) via your orchestrator's secret store (k8s Secret, AWS Secrets Manager, Vault). Never bake them into a Docker image.
- [ ] **Run `server-extend` first** with `ExtendConfig.debugMode = false` compiled in. Verify the boot logs show `MatchPoolBootstrap: pools reconciled` and that `ByoCredentialStore: loaded master key` appears exactly once.
- [ ] **Run the dedicated `server` instances** with the `AB_DS_ID` value AMS prints at allocation time. Verify the DS Hub WebSocket connects and the watchdog heartbeats land.
- [ ] **Deploy the matchmaker** (gRPC at `MATCHMAKER_GRPC_PORT` = 9095). Verify a sample match2 ticket gets routed to the custom matchmaker (look for the `make_matches` RPC in the matchmaker logs).
- [ ] **Smoke-test the BYO flow** end-to-end: submit a key, submit a match2 ticket, confirm the slot's `lastUsedAt` advances when the DS runs an inference turn for that user.
- [ ] **Sanity-check the matchmaking ladder** by inspecting `~/.autogenesis/logs/server-extend-*.log` for `SubsidyPolicy: reloaded from env` lines that confirm the env-overrides (if any) took effect.

---

## 6. Secret Rotation Playbook

### 6.1 Rotating `BYO_KEY_MASTER_KEY`

Use when: key compromised, planned rotation, or migrating to KMS-backed keys.

1. Generate the new key: `openssl rand -base64 32`
2. **Dual-deploy period**: deploy a new `server-extend` build that accepts *both* the old and new master keys (small follow-up change to `ByoCredentialsCrypto.loadMasterKeyFromEnv` to read `BYO_KEY_MASTER_KEY` and `BYO_KEY_MASTER_KEY_OLD`). Keep the old build running until every player re-submits their key.
3. Re-prompt every active player to re-submit their key (in-game modal: *"We rotated our encryption key — please re-enter your AWS key"*).
4. Once every active player has re-submitted, decommission the old master key and remove the dual-read code path.

### 6.2 Rotating `AB_CLIENT_SECRET`

Simpler flow — no player-facing impact:

1. Rotate in the AccelByte admin portal (IAM → OAuth clients → rotate secret).
2. Redeploy `server-extend` with the new secret.
3. The next match request picks it up. Players do not need to re-submit anything.

### 6.3 Rotating AWS Bedrock platform credentials

If you stop using the platform-managed record and switch to BYO for everyone:

1. Disable the `bedrock-aws-credentials` game record via the AccelByte admin portal.
2. All users default to "must provide BYO key" — surface a banner in the KVision UI.
3. New users who skip BYO see a "Cannot play" message until they provide credentials.

---

## 7. Port & Network Surface

The five TCP ports the three JVM services bind to (defaults — all overridable via env):

| Port | Service | Protocol | Purpose |
|---|---|---|---|
| `7070` | `:server-extend` | REST | KVision UI → session-router RPC (`RestRpcBridgeConfig.development(7070)`) |
| `8080` | `:server` | REST | Internal admin RPC |
| `9080` | `:server` | WebSocket | Player WebSocket connections (the game traffic) |
| `9091` | `:server` | gRPC | `:server-extend` ↔ `:server` bridge |
| `9092` | `:server-extend` | gRPC + gRPC-Web | Browser clients (gRPC-Web) + native clients (gRPC) connect here |

You also need outbound HTTPS from your hosts to:

- `*.accelbyte.io` (the AccelByte Gaming Services API surface — `AB_BASE_URL`)
- `bedrock-runtime.<region>.amazonaws.com` (Bedrock model invocation)
- `github.com` and `ghcr.io` if using the `ghcr.io/cage/...` image registry default

If you self-host in k8s, the bundled manifests in `server-extend/k8s/` and `matchmaker/k8s/` already declare the right NetworkPolicies and Services — review before applying.

---

## 8. gRPC Bridge Tooling

The browser-side gRPC-Web stubs are **not** checked into the repo. They are generated at build time from `grpcBridgeProto/`. To regenerate:

### 8.1 Install `protoc` (one-time)

`protoc` is **not bundled** with the repo. Install it via your package manager or download from https://github.com/protocolbuffers/protobuf/releases:

```bash
# macOS
brew install protobuf

# Debian/Ubuntu
apt install protobuf-compiler

# Or download a release and point PROTOC_BIN at the binary
```

Verify:

```bash
protoc --version   # libprotoc 3.x.x or higher
```

### 8.2 Regenerate stubs

```bash
cd grpcBridgeProto
npm install                         # installs @connectrpc/protoc-gen-connect-es and @bufbuild/protoc-gen-es
PROTOC_BIN=/path/to/protoc scripts/generate-grpc-web.sh
```

The script emits `rpc_bridge_connect.js`, `rpc_bridge_pb.js`, and `grpc-helpers.js` into `kvisionApp/src/jsMain/resources/grpc/`.

### 8.3 Refresh the Yarn lockfile

After changing npm dependencies or regenerating stubs:

```bash
./gradlew kotlinUpgradeYarnLock
./gradlew :kvisionApp:browserProductionWebpack
```

### 8.4 Useful Gradle tasks

- `./gradlew :kvisionApp:installGrpcProtoPlugins` — refresh npm plugins
- `./gradlew :kvisionApp:generateGrpcWeb` — rerun the protoc script manually
- `./gradlew :kvisionApp:browserProductionWebpack` — bundle for deployment

Gradle also runs the gRPC generation automatically before `jsProcessResources`, so day-to-day dev work doesn't require running it manually.

---

## 9. Electron Packaging

Build desktop installers that bundle the JVM runtimes + frontend + config files.

### 9.1 Prerequisites

Set workspace-local caches **before** running `npm install` (Gradle's node plugin does this if you let it):

```bash
export ELECTRON_CACHE="$PWD/.cache/electron"
export XDG_CACHE_HOME="$PWD/.cache"
export npm_config_cache="$PWD/.cache/npm"
```

### 9.2 Required local files

Before packaging, you must have valid credentials in:

- `server/accelbyte.local.properties`
- `server-extend/accelbyte.local.properties`

The build **fails** if these files still contain placeholder values (`your_client_id_here`, etc.).

### 9.3 Build commands

```bash
./gradlew :electronApp:packageLinux     # AppImage, .deb, .rpm + dir layout
./gradlew :electronApp:packageWindows   # NSIS, portable + dir layout (must run on Windows)
./gradlew :electronApp:packageMac       # DMG, zip + dir layout (must run on macOS / Apple Silicon CI)
./gradlew :electronApp:packageElectronAll  # alias for Linux + Windows
```

### 9.4 Chrome sandbox fix

If the Linux installer fails to start with a sandbox error:

```bash
sudo chown root /opt/Autogenesis/chrome-sandbox
sudo chmod 4755 /opt/Autogenesis/chrome-sandbox
```

### 9.5 Signing

The macOS and Windows installers are unsigned by default. Sign them with your developer certificate before distribution (`electron-builder` accepts `CSC_LINK` / `CSC_KEY_PASSWORD` for macOS and `CSC_WIN_LINK` / `CSC_WIN_KEY_PASSWORD` for Windows).

---

## 10. Deployment Targets — Self-Hosted vs AccelByte Extend

You have two deployment paths for `:server-extend` and `:matchmaker`:

### 10.1 Self-hosted Kubernetes

The manifests under `server-extend/k8s/server-extend.yaml` and `matchmaker/k8s/matchmaker.yaml` are the source of truth for self-hosted deployments. Resource shapes match what Extend expects (see `build.gradle.kts` `ExtendModule` data class).

Apply with `kubectl apply -f` after filling in the namespace + secret references.

### 10.2 AccelByte Extend (Custom Service)

```bash
# One-time: install ags CLI
# https://github.com/AccelByte/accelbyte-ags-cli/releases

# Login (interactive)
./gradlew extendLogin

# Or set env vars manually:
export AGS_BASE_URL=https://<your-namespace>.prod.gamingservices.accelbyte.io
export AGS_CLIENT_ID=<iam-client-with-EXTEND:IMAGE-and-EXTEND:DEPLOYMENT>
export AGS_CLIENT_SECRET=<confidential-client-secret>
export AGS_NAMESPACE=<game-namespace>

# Build + push image, then deploy
./gradlew pushExtendImage -Pmodule=matchmaker -Ptag=<short-sha>
./gradlew deployExtend -Pmodule=matchmaker -Ptag=<short-sha>

./gradlew pushExtendImage -Pmodule=server-extend -Ptag=<short-sha>
./gradlew deployExtend -Pmodule=server-extend -Ptag=<short-sha>
```

Pass `-PdryRun=true` to print the underlying `docker`/`ags` commands without executing.

The matchmaker deploys as `scenario=function-override` (it implements the match2 `Service` gRPC contract). The server-extend deploys as `scenario=service-extension` (REST + gRPC hosted inside Extend).

---

## 11. What Can Go Wrong (Troubleshooting Matrix)

| Missed step | Symptom |
|---|---|
| `BYO_KEY_MASTER_KEY` not set in non-debug prod | `ByoCredentialStore: BYO_KEY_MASTER_KEY env var is required` at the first BYO submit, then the store fails closed and `AccountSettingsLookup` downgrades every claimed BYO user to PRO. |
| `debugMode` left at `true` in prod | All PvP requests go to the in-process dev DS at `127.0.0.1:9080` instead of the AMS-allocated DS. Match2 tickets never form real matches. |
| CloudSave ACL not set to admin-only | A determined KVision client could in principle `GET` the `byo_credentials_v1` slot via the standard AccelByte CloudSave REST API and decrypt locally if the master key ever leaked. Encryption is defense-in-depth, not the primary control. |
| `AB_DS_ID` stale or missing | `DedicatedServerRegistration` logs `WARN` and the DS row never appears in the AMS dashboard. The DS still works for player traffic, but AMS cannot drain it on autoscaling events. |
| `MATCHMAKING_*` overrides left at defaults | No-op. The defaults are the values the algorithm was tested against. Only override if your operator-economics team has signed off on the new numbers. |
| `accelbyte.local.properties` committed | Real client credentials end up in git history. The Electron build fails on placeholder values but does **not** detect committed real credentials — use a pre-commit secret scanner. |
| Bedrock model access not enabled in target region | `:server` boot succeeds, but the first LLM call returns `AccessDeniedException`. Verify in Bedrock console → Model access. |
| Inference profile mapping missing for a region | On-demand inference fallback kicks in. Higher latency and cost. |
| `protoc` missing on the build host | `./gradlew :kvisionApp:generateGrpcWeb` fails with `protoc: command not found`. Install or set `PROTOC_BIN`. |
| Wrong Node.js version | The Kotlin/JS Gradle plugin requires Node 22.12.0+. Older versions cause cryptic npm/yarn errors during `kvisionApp` builds. |
| `ags` CLI not on PATH | `./gradlew verifyExtendPrereqs` fails with a clear message; install from the AccelByte GitHub releases. |

---

## 12. Observability

### 12.1 Logs

- Default log directory: `~/.autogenesis/logs/`
- File pattern: `server-*.log`, `server-extend-*.log`, `matchmaker-*.log` (one per JVM, rotating)

Key lines to grep for in healthy operation:

- `MatchPoolBootstrap: pools reconciled` — `server-extend` boot success
- `ByoCredentialStore: loaded master key` — should appear **exactly once** per `server-extend` boot (if more, your dual-deploy code path is still active)
- `DedicatedServerRegistration: registered with dsId=...` — `server` boot success
- `SubsidyPolicy: reloaded from env` — confirms `MATCHMAKING_*` overrides took effect
- `make_matches RPC invoked` — matchmaker is routing match2 tickets

### 12.2 Metrics (TBD)

Bedrock token usage is tracked per-user via the `Usage` CloudSave record. Other JVM metrics are emitted to stdout in human-readable format; production scraping should wrap stdout with your platform's log-to-metrics pipeline (CloudWatch, Datadog, etc.).

### 12.3 Tracing

The codebase uses TPipe's built-in tracing. Enable with:

```kotlin
pipeline.enableTracing(TraceConfig(enabled = true, detailLevel = TraceDetailLevel.DEBUG))
```

Export traces from the JVMs with `PipeTracer.exportTrace(pipelineId, TraceFormat.HTML/JSON/MARKDOWN)`.

---

## 13. Recovery Procedures

### 13.1 Restarting a `server-extend` after a crash

`server-extend` is stateless beyond the BYO master key and the match-pool config, both of which reload on boot. A crash restart is safe — players mid-match will see a "session lost" error and have to rejoin.

### 13.2 Restarting a `server` (dedicated game server)

A DS restart will drop all connected players mid-match. The AccelByte AMS will replace it automatically if `AB_DS_HUB_URL` and `AB_WATCHDOG_URL` are correct. No manual intervention needed beyond watching for new `AB_DS_ID` values.

### 13.3 Rolling back a deployment

1. Identify the last known-good image tag (your CI should emit these as immutable short-SHAs).
2. Redeploy the prior tag: `./gradlew deployExtend -Pmodule=<matchmaker|server-extend> -Ptag=<good-sha>`.
3. For self-hosted k8s, `kubectl rollout undo deployment/<name>`.
4. Verify the same health-check lines from §12.1 appear in the new boot logs.

### 13.4 Compromised `BYO_KEY_MASTER_KEY` — emergency rotation

Treat as a security incident. Follow §6.1 but accelerate every step. **Do not** wait for the dual-deploy window to drain naturally — assume the attacker has already harvested encrypted ciphertext and is working on cracking it offline.

---

## 14. Where to Look Next

| Topic | Document |
|---|---|
| Build commands, dev workflow | [`README.md`](README.md) |
| `:server` module internals | [`server/README.md`](server/README.md) |
| `:server-extend` module internals | [`server-extend/README.md`](server-extend/README.md) |
| `:server-extend` deployment specifics | [`server-extend/DEPLOY.md`](server-extend/DEPLOY.md) |
| `:matchmaker` module internals | [`matchmaker/README.md`](matchmaker/README.md) |
| `:matchmaker` deployment specifics | [`matchmaker/DEPLOY.md`](matchmaker/DEPLOY.md) |
| Push notification runbook | [`server/RUNBOOK_PUSH.md`](server/RUNBOOK_PUSH.md) |
| KVision frontend | [`kvisionApp/README.md`](kvisionApp/README.md) |
| Live mode architecture | [`docs/LIVE_MODE.md`](docs/LIVE_MODE.md) |
| Operations log | [`docs/OPERATIONS.md`](docs/OPERATIONS.md) |

---

*Last updated alongside the PolyForm Noncommercial 1.0.0 license addition — 2026-06-30.*