# server-extend

The matchmaking / session-router JVM. Sits between the KVision browser and the
AccelByte platform. Drives the match2 ticket → poll → DS URL flow, the per-user
BYO API key store, the match-pool bootstrap, and the matchmaking algorithm
attributes stamped on every ticket.

In production this JVM runs alongside the `server` JVM. The browser talks to
`server-extend` over gRPC (port **9092**) and REST (port **7070**); `server-extend`
talks to the dedicated game server (`server`) via gRPC at **9091** and to AccelByte
match2 / session / CloudSave over HTTPS.

## Build & run

```bash
./gradlew :server-extend:build
./gradlew :server-extend:runtime
./gradlew :server-extend:runtimeZip
./gradlew :server-extend:test
```

Entry point: `org.ttt.autogenesis.serverextend.ServerExtend`.

## Required configuration

Configuration is read from environment variables **and/or** a properties file.
The properties file is searched in this order — first match wins:

1. `./accelbyte.local.properties` (working dir)
2. `./accelbyte.properties`
3. `~/.autogenesis/config/accelbyte.local.properties`
4. `~/.autogenesis/config/accelbyte.properties`
5. Bundled `accelbyte.local.properties` resource
6. Bundled `accelbyte.properties` resource

See `server-extend/src/main/kotlin/org/ttt/autogenesis/serverextend/config/AccelByteConfig.kt`.

### AccelByte environment variables

| Variable           | Required | Purpose                                                                                       |
| ------------------ | -------- | --------------------------------------------------------------------------------------------- |
| `WALLET_ADAPTER`  | no       | `local`               | Wallet adapter for FREE-class credit deductions. `local` writes the local `BillingStatus.credits` field. `accelbyte` would route to the AccelByte platform wallet via `net.accelbyte.sdk.api.platform.wrappers.Wallet` (deferred to follow-up PR). |
| `AB_NAMESPACE`     | yes      | AccelByte namespace. Required for `match2`, `session`, CloudSave, and the match-pool bootstrap. |
| `AB_CLIENT_ID`     | yes      | OAuth client id for server-to-server calls.                                                   |
| `AB_CLIENT_SECRET` | yes      | OAuth client secret. Treat as a secret.                                                       |
| `AB_BASE_URL`      | yes      | AccelByte API base URL.                                                                       |

### Server-extend mode toggle (dev vs. live)

| Variable / property           | Default | Purpose |
| ----------------------------- | ------- | ------- |
| `SERVER_EXTEND_LIVE_MODE`     | unset   | When `true` (or `1`/`yes`/`on`, case-insensitive), `ExtendConfig.debugMode` is forced to `false` and the matchmaking path takes the AccelByte match2 + AMS branch. When unset, the local dev fast-path (`serverUrl = "127.0.0.1:9080"`) is used. Set this on the Extend deployment env to go live; see `docs/LIVE_MODE.md` for the full runbook. The JVM-property equivalent is `-DserverExtend.liveMode=true`. |
| `SERVER_EXTEND_GRPC_PORT`     | `9092`  | gRPC listener port. Mirrors the existing gRPC port resolution. |
| `SERVER_EXTEND_IDLE_THRESHOLD_MINUTES` | `15` | Idle threshold for the RpcUsageTracker. |

The live-mode flag is resolved **once at JVM init**. There is no runtime RPC
to flip a running service from live to dev; restart the JVM to change it.

### Example `accelbyte.local.properties`

```properties
AB_NAMESPACE=autogenesis
AB_CLIENT_ID=<your-client-id>
AB_CLIENT_SECRET=<your-client-secret>
AB_BASE_URL=https://<your-namespace>.prod.gamingservices.accelbyte.io
```

### BYO API key storage (Workstream 1)

The `ByoCredentialStore` encrypts every player's BYO AWS credentials with a
server-side master key and stores the ciphertext in a per-user VFS slot
(`byo_credentials_v1`).

| Variable             | Required             | Default                | Purpose                                                                                                |
| -------------------- | -------------------- | ---------------------- | ------------------------------------------------------------------------------------------------------ |
| `BYO_KEY_MASTER_KEY` | **yes in non-debug** | debug-only fallback    | Base64-encoded 32-byte AES-256 key. Generate with `openssl rand -base64 32`. **Rotating this invalidates every existing BYO key — every player must re-submit.** |

If `BYO_KEY_MASTER_KEY` is missing and `ExtendConfig.debugMode == true`, the
store logs `WARN` and falls back to a deterministic dev key derived from a
hardcoded constant. **Never** rely on the dev fallback in production — it means
anyone with the source can decrypt every player's secret.

The master key is read on first use and cached for the JVM lifetime. The
encryption primitive is AES-256-GCM with a fresh random 12-byte IV per record;
the GCM auth tag is appended to the ciphertext (JCE standard).

### gRPC listener

| Variable                  | Required | Default | Purpose                                                  |
| ------------------------- | -------- | ------- | -------------------------------------------------------- |
| `SERVER_EXTEND_GRPC_PORT` | no       | `9092`  | gRPC listener port. Override when running multiple instances on one host. |

Equivalent JVM property: `-DserverExtend.grpcPort=N`.

Equivalent CLI: `--serverExtend.grpcPort=N` (parsed by `ExtendConfig.resolveGrpcPort`).

### Matchmaking algorithm tunables (read by `SubsidyPolicy`)

These are read at JVM startup and cached. Changing them requires a restart
(matching is on the hot path; the policy is not hot-reloaded).

| Variable                              | Type | Default | Effect                                                                                                        |
| ------------------------------------- | ---- | ------- | ------------------------------------------------------------------------------------------------------------- |
| `MATCHMAKING_SUBSIDY_BYO`             | int  | `4`     | Subsidy capacity for `CostClass.BYO_KEY` (max number of FREE players a single BYO_KEY user can cover).         |
| `MATCHMAKING_SUBSIDY_PRO`             | int  | `2`     | Subsidy capacity for `CostClass.PRO`.                                                                          |
| `MATCHMAKING_SUBSIDY_CASUAL`          | int  | `1`     | Subsidy capacity for `CostClass.CASUAL`.                                                                       |
| `MATCHMAKING_SUBSIDY_CREDIT`          | int  | `0`     | Subsidy capacity for `CostClass.CREDIT`.                                                                       |
| `MATCHMAKING_SUBSIDY_FREE`            | int  | `0`     | Subsidy capacity for `CostClass.FREE`.                                                                         |
| `MATCHMAKING_CREDIT_SUBSIDY_PER_1000` | int  | `1`     | Extra subsidy per 1000 credit-balance units, on top of the class base. Capped by `MATCHMAKING_CREDIT_CAP`.    |
| `MATCHMAKING_CREDIT_CAP`              | int  | `2`     | Hard cap on the credit-derived subsidy (so high-balance players can't reach BYO_KEY's 4).                      |
| `MATCHMAKING_REQUIRE_COVERAGE_4`      | bool | `true`  | When matching for a 4-player pool, the matched group must have combined subsidy ≥ 4 (or at least one BYO_KEY). |
| `MATCHMAKING_REQUIRE_COVERAGE_3`      | bool | `true`  | Same rule for the 3-player pool.                                                                               |
| `MATCHMAKING_REQUIRE_COVERAGE_2`      | bool | `false` | Same rule for the 2-player pool. Default off so we don't reject 2-player matches over coverage.                |
| `MATCHMAKING_MAX_SUBSIDY`             | int  | `4`     | Global cap on combined subsidy per match. The matchmaker rejects any proposal exceeding this.                  |

### Dev mode

| Variable      | Type | Default | Purpose                                                                                                  |
| ------------- | ---- | ------- | -------------------------------------------------------------------------------------------------------- |
| `debugMode`   | bool | `true`  | Set via `ExtendConfig.debugMode = false` in code (no env-var). When true: dev fast-path is used (no real match2, all games route to the in-process DS at `127.0.0.1:9080`), the `BYO_KEY_MASTER_KEY` fallback is allowed, and `MatchPoolBootstrap` is skipped. **Always set `false` in production.** |

### Ports

| Port | Direction | Purpose                                                                                |
| ---- | --------- | -------------------------------------------------------------------------------------- |
| 7070 | inbound   | REST / RPC bridge for the KVision browser (CloudSaveProxy, requestGame, matchmaking).  |
| 9092 | inbound   | gRPC bridge for cross-JVM calls (notifyGameServer → `server`'s gRPC at 9091).          |

### Logger

`org.ttt.autogenesis.serverextend.ServerExtend` calls:

```kotlin
Logger.configure(LogPriority.DEBUG, true, maxLogFiles = 1, serverType = "server-extend")
```

Logs land in `~/.autogenesis/logs/server-extend-YYYY-MM-DD-HHmmss.log`.

## Operator setup checklist

When you stand up a new `server-extend` deployment:

- [ ] `AB_NAMESPACE`, `AB_CLIENT_ID`, `AB_CLIENT_SECRET`, `AB_BASE_URL` set to
      the target AccelByte environment.
- [ ] `BYO_KEY_MASTER_KEY` generated with `openssl rand -base64 32` and set in
      the environment. **Do not** commit it. **Do not** reuse the dev fallback
      in production.
- [ ] `ExtendConfig.debugMode = false` (compile-time; set this in the
      production build profile, not via env var).
- [ ] If running multiple instances on one host, set `SERVER_EXTEND_GRPC_PORT`
      per instance.
- [ ] (Optional) Override `MATCHMAKING_*` policy env vars only if your
      operator-economics team has signed off on the new numbers.
- [ ] The `accelbyte.local.properties` file is **not** committed to source control.

### CloudSave ACL for BYO credentials

The `byo_credentials_v1` record is written through the per-user VFS namespace.
In production, **the operator must configure the AccelByte IAM role for this
record to be admin-only read + admin-only write**, so the KVision client
cannot fetch another user's (or its own) ciphertext via the standard CloudSave
API. Without this ACL, the server-side encryption is the only thing protecting
the secret — if the KVision client ever grew a direct CloudSave call path, the
secret would be reachable.

Configuration steps:

1. In the AccelByte admin portal, open **IAM → Roles**.
2. Find the role bound to `server-extend`'s OAuth client.
3. Add a CloudSave permission for the `byo_credentials_v1` record with
   `read = admin`, `write = admin`, and **no** user-level grant.
4. Verify the KVision client cannot `GET` the record via the CloudSave REST API
   while signed in as a regular user.

If you cannot set admin-only ACL, the next-best mitigation is to encrypt
the `keyId` field as well (currently plaintext per AWS convention) — but the
right answer is the ACL change.

## Related documentation

- [`server/README.md`](../server/README.md) — main DS JVM, the eventual
  consumer of `ByoCredentialsRpc.decryptByoKey` for the BYO inference path.
- [`matchmaker/README.md`](../matchmaker/README.md) — custom gRPC matchmaker
  that consumes the cost attributes stamped by `ServerConnector`.
- [AccelByte CloudSave admin API](https://docs.accelbyte.io/) — for the IAM
  role configuration step.