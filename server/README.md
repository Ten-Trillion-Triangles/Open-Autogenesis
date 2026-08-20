# server

The main dedicated game server JVM. Hosts `WorldManager`, `TurnHarness`,
`PlayerConnectionManager`, accepts player WebSockets on **port 9080**, runs the
AMS arrival-gate, the gameplay orchestrator, and (when wired up) per-user
inference via the platform Bedrock credentials or a BYO API key resolved
through `server-extend`.

## Build & run

```bash
./gradlew :server:build
./gradlew :server:runtime          # custom JRE image
./gradlew :server:runtimeZip       # portable zip
./gradlew :server:test             # unit + integration tests
```

Local development entry point: `org.ttt.autogenesis.server.Server` (port `9080`,
gRPC bridge `9091`).

## Required configuration

The server reads configuration from environment variables **and/or** a properties
file. The properties file is searched in this order — first match wins:

1. `./accelbyte.local.properties` (working dir)
2. `./accelbyte.properties`
3. `~/.autogenesis/config/accelbyte.local.properties`
4. `~/.autogenesis/config/accelbyte.properties`
5. Bundled `accelbyte.local.properties` resource
6. Bundled `accelbyte.properties` resource

`AccelByteConfig` then reflects values into the JVM environment map so the
AccelByte SDK can read them. See
`server/src/main/kotlin/org/ttt/autogenesis/server/config/AccelByteConfig.kt`.

### AccelByte environment variables

| Variable           | Required | Purpose                                                                 |
| ------------------ | -------- | ----------------------------------------------------------------------- |
| `WALLET_ADAPTER`  | no       | `local`               | Wallet adapter for FREE-class credit deductions. `local` writes the local `BillingStatus.credits` field (the system as it ships today). `accelbyte` would route to the AccelByte platform wallet (Workstream 2; not yet implemented). |
| `AB_NAMESPACE`     | yes      | AccelByte namespace the DS is registered in.                            |
| `AB_CLIENT_ID`     | yes      | OAuth client id for server-to-server calls (DSMC, CloudSave, match2).   |
| `AB_CLIENT_SECRET` | yes      | OAuth client secret. Treat as a secret — never commit.                  |
| `AB_BASE_URL`      | yes      | AccelByte API base URL (e.g. `https://<your-namespace>.prod.gamingservices.accelbyte.io`). |
| `AB_DS_ID`         | yes      | Dedicated server id assigned by AMS at boot. Used by the DSMC registration lifecycle. |
| `AB_DS_HUB_URL`    | yes      | WebSocket URL of the DS Hub. The DS connects here on startup.            |
| `AB_WATCHDOG_URL`  | yes      | AMS watchdog URL the DS heartbeats to.                                  |
| `AB_REGION`        | yes      | Region this DS instance is running in (e.g. `us-east-1`).                |

### Example `accelbyte.local.properties`

```properties
AB_NAMESPACE=autogenesis
AB_CLIENT_ID=<your-client-id>
AB_CLIENT_SECRET=<your-client-secret>
AB_BASE_URL=https://<your-namespace>.prod.gamingservices.accelbyte.io
AB_DS_ID=<ds-id-from-ams>
AB_DS_HUB_URL=wss://<your-dshub-host>
AB_WATCHDOG_URL=https://<your-watchdog-host>
AB_REGION=us-east-1
```

### AWS Bedrock

| Variable                                | Required | Default                   | Purpose                                |
| --------------------------------------- | -------- | ------------------------- | -------------------------------------- |
| `BEDROCK_AWS_CREDENTIAL_RECORD_KEY`     | no       | `bedrock-aws-credentials` | Game-record key for the platform AWS Bedrock credentials. Override only if you split the credential record into a per-environment game record. |

The actual AWS access is via the standard `AWS_ACCESS_KEY_ID` / `AWS_SECRET_ACCESS_KEY` /
`AWS_REGION` chain when running locally, or the instance/role credentials in production.
The DS does **not** read BYO keys directly — those are resolved through
`server-extend` (see [`server-extend/README.md`](../server-extend/README.md)).

### Virtual filesystem

| Variable           | Required | Default                                | Purpose                                                                                          |
| ------------------ | -------- | -------------------------------------- | ------------------------------------------------------------------------------------------------ |
| `AUTOGEN_VFS_PATH` | no       | `~/.autogenesis/vfs` (per-user)        | Override the root directory for the local VFS. Useful for tests or sandboxed CI runs.            |

### Web Push (VAPID)

The server can send turn-start push notifications to a player whose WebSocket
session is disconnected. The pipeline is:

```
TurnHarness.executeSingleTurn   (server/src/main/kotlin/.../TurnHarness.kt:1393)
  → UiSignalRpcHandlers.pushNotificationService?.sendTurnStart(accelByteId, actor, round)
  → PushNotificationService.sendTurnStart  (server/src/main/kotlin/.../push/PushNotificationService.kt:79)
  → VAPID-signed POST to the subscription's endpoint
  → web-push (nl.martijndwars:web-push:5.1.2) + BouncyCastle
```

#### Required environment variables (consumed by `PushNotificationService`)

| Variable                              | Required | Default                                              | Purpose                                                                                                                                                                                                                                                               |
| ------------------------------------- | -------- | ---------------------------------------------------- | -----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `AUTOGENESIS_DEV_PUSH_MOCK_PORT`      | no       | unset (real FCM/Mozilla/APNs endpoint)              | Dev/test ONLY. When set to a port number, the subscription's endpoint **host:port** is rewritten to `http://127.0.0.1:<port>` at push-send time (the original path component is preserved, so e.g. `https://fcm.googleapis.com/fcm/send/<token>` becomes `http://127.0.0.1:9099/fcm/send/<token>`). Lets the kvisionApp-e2e `push-turn-start` probe intercept the push without going through a real push provider. |
| `AUTOGENESIS_PUSH_TEST_ENDPOINT`      | no       | unset (endpoints disabled)                           | Dev/test ONLY. When set to any non-blank value, mounts three HTTP debug routes under `/debug/` (see "Debug endpoints" below). Forwarded to the JVM as `-Dpush.test.endpoint=$value` automatically by `server/build.gradle.kts`. |

There is **no production env var** for VAPID credentials — the VAPID
private/public keypair is provisioned per-environment via the
`./gradlew :kvisionApp:generateVapidKeys` task, which writes:

| File                                                                            | Purpose                                                                  |
| ------------------------------------------------------------------------------- | ------------------------------------------------------------------------ |
| `~/.autogenesis/vapid_private.pem`                                             | P-256 EC private key, SEC1 (PEM), per-environment, **gitignored**.        |
| `kvisionApp/build/generated/vapid/vapid_public.json`                          | Public key as Base64URL (no padding), served at `/vapid_public.json` from the KVision static bundle. |

The server reads `~/.autogenesis/vapid_private.pem` at boot via
`PushVapidConfig.loadKeypair()`. If the file is missing or unreadable,
the server logs `VAPID keypair not loaded — push notifications disabled`
and continues without push support. No silent fallback to test/demo
keys — push is either provisioned for the environment or it doesn't exist.

> **Important**. The pair is per-environment on purpose. Production envs
> get a fresh keypair at deployment time, dev envs get a different one,
> CI gets yet another. Do not copy the PEM between machines and do not
> commit it.

#### PEM format requirements

`PushVapidConfig.loadKeypair()` reads `~/.autogenesis/vapid_private.pem` via:

```
KeyFactory("EC").generatePrivate(PKCS8EncodedKeySpec(der))
```

That means **PKCS#8 format only**, not SEC1. The keypair generated by
`./gradlew :kvisionApp:generateVapidKeys` is SEC1 (it's emitted by
`openssl ecparam -name prime256v1 -genkey -noout`). On boot, the server
runs it through `openssl pkcs8 -topk8 -nocrypt` to canonicalize before
parsing. If you provision the file out of band, **emit it as PKCS#8** or
the server will fail with `Unable to decode key` on every boot.

One-shot conversion from an existing SEC1 file:

```bash
openssl pkcs8 -topk8 -nocrypt \
  -in ~/.autogenesis/vapid_private.pem.sec1 \
  -out ~/.autogenesis/vapid_private.pem
chmod 600 ~/.autogenesis/vapid_private.pem
```

#### BouncyCastle provider

The push library hard-codes the BC provider name:

```kotlin
nl.martijndwars.webpush.Utils.loadPublicKey(publicKeyBase64Url)
```

so the JVM needs `org.bouncycastle.jce.provider.BouncyCastleProvider`
registered before any push call. `PushVapidConfig` does this once in its
`init {}` block — the gradle dep `org.bouncycastle:bcprov-jdk18on:1.78.1` is
already on the runtime classpath (see `server/build.gradle.kts`), nothing
extra to configure.

#### Debug endpoints (gated by `AUTOGENESIS_PUSH_TEST_ENDPOINT`)

When the env var is set to any non-blank value (e.g. `true`), the server
exposes three test-only HTTP routes. **All three are mounted under
`/debug/` and are NOT mounted in production unless that env var is set.**

| Method | Path                                | Purpose                                                                                                                                         |
| ------ | ----------------------------------- | ---------------------------------------------------------------------------------------------------------------------------------------------- |
| POST   | `/debug/seed-push-subscription`     | Writes a synthetic push subscription for a user into the VFS. Body is JSON `PushSubscriptionDto {endpoint, p256dh, auth}`. Query: `userId=<accelbyte-uuid>`. The endpoint host gets rewritten by `AUTOGENESIS_DEV_PUSH_MOCK_PORT` on push send. |
| POST   | `/debug/advance-turn`              | Completes any pending `awaitPlayerAction` for `?player=<name>` with a synthetic AI-takeover submission. Useful for tests that need to skip the LLM-driven AI takeover path.                                |
| POST   | `/debug/trigger-push-now`           | Calls `PushNotificationService.sendTurnStart(userId, actor, round)` synchronously. Query: `userId=<uuid>&actor=<name>&round=<int>`. Same wire path the in-game trigger uses.                  |

The kvisionApp-e2e `push-turn-start.mjs` probe uses all three together
to verify the push pipeline end-to-end without running a full LLM turn.

> The store wrapper is path-sensitive to CloudSave's one-deep wrapping
> behavior: `get()` tries a direct decode first, then unwraps a trailing
> `"value": {...}` layer if needed. Don't change the unwrap heuristic
> in `PushSubscriptionStore.get()` without coordinating with the JS-side
> `client.registerPushSubscription` RPC handler — they store the DTO
> in the same VFS key.

### Operator setup checklist (Web Push)

When you stand up a new DS deployment that needs turn notifications:

- [ ] `./gradlew :kvisionApp:generateVapidKeys` was run on the deploy host —
      `~/.autogenesis/vapid_private.pem` exists with 0600 perms.
- [ ] `kvisionApp/build/generated/vapid/vapid_public.json` was rebuilt and
      the KVision bundle deployed alongside the DS.
- [ ] The VAPID public key in the bundle matches the private key on the DS
      host (the bundle ships `endpoint = http://127.0.0.1:<mockport>` when
      `AUTOGENESIS_DEV_PUSH_MOCK_PORT` is set, but **production sets
      `AUTOGENESIS_DEV_PUSH_MOCK_PORT` to empty/blank** so endpoint rewriting
      is a no-op and the subscription goes to the real FCM/APNs endpoint).
- [ ] `AUTOGENESIS_DEV_PUSH_MOCK_PORT` is unset in production. Setting it
      routes every push to a local mock — silent regression hazard.
- [ ] `AUTOGENESIS_PUSH_TEST_ENDPOINT` is unset in production. Setting it
      mounts `/debug/seed-push-subscription`, `/debug/advance-turn`, and
      `/debug/trigger-push-now` — anyone with DS network reach can plant
      subscriptions, complete turns, and fire arbitrary pushes.

For the full day-2 runbook (file paths, shell commands, fail-mode
diagnostics, manual acceptance test recipe), see
[`server/RUNBOOK_PUSH.md`](./RUNBOOK_PUSH.md).

### Test-fixture environment variables (consumed by `ServerConfig`)

These are **not** required in production. They exist so QA and integration tests
can force specific AI opponents and a specific map.

| Variable | Format                       | Example                                | Effect                                                              |
| -------- | ---------------------------- | -------------------------------------- | ------------------------------------------------------------------- |
| `RIG`    | comma-separated AI names     | `RIG=zara,vance`                       | Always pick these AI opponents when filling out a match's AI roster. |
| `MAP`    | single map name              | `MAP=Laurasiagondwana`                 | Pin the map selection to this map regardless of the usual pick.       |

Equivalent CLI flags (parsed from `args`): `--rig=name1,name2`, `--map=Name`.

### Ports

| Port | Direction | Purpose                                                                                |
| ---- | --------- | -------------------------------------------------------------------------------------- |
| 9080 | inbound   | HTTP / WebSocket (`/events`) for player RPCs and notifications.                         |
| 9091 | inbound   | gRPC bridge for cross-JVM calls from `server-extend` (notifyGameServer, etc.).          |

Override at runtime via the standard `org.gradle` build or by editing
`ServerConfig.port` / `ServerConfig.grpcPort` defaults.

### Logger

`org.ttt.autogenesis.server.Server` calls:

```kotlin
Logger.configure(LogPriority.DEBUG, true, maxLogFiles = 1, serverType = "server")
```

Logs land in `~/.autogenesis/logs/server-YYYY-MM-DD-HHmmss.log`. Browser-side
`LogCategory.UI` and `LogCategory.NETWORK` events from connected KVision
clients are POSTed to `http://localhost:9080/api/browser-log` and co-located
in `browser-*.log` (keeps the 10 most recent files).

### Tenant / namespace configuration

There are no production tenant identifiers hardcoded anywhere in this module.
All AccelByte tenant settings (`AB_NAMESPACE`, `AB_CLIENT_ID`,
`AB_CLIENT_SECRET`, `AB_BASE_URL`) are loaded at runtime from
`server/accelbyte.local.properties` via `ConfigSource.property(...)`, which
in turn reads `~/.autogenesis/config/accelbyte.local.properties` if the
working-tree copy is absent. See `server-extend/AccelByteConfig.kt` for the
JVM-side loader and `kvisionApp/.../iam/Iam.kt` for the Kotlin/JS-side
loader. Operators point the property file at whichever tenant they want
to test against; no code changes are required to retarget a deployment.

## Operator setup checklist

When you stand up a new DS deployment:

- [ ] `AB_NAMESPACE`, `AB_CLIENT_ID`, `AB_CLIENT_SECRET`, `AB_BASE_URL` set to the
      target AccelByte environment.
- [ ] `AB_DS_ID` set to the value AMS prints to stdout at allocation time.
- [ ] `AB_DS_HUB_URL` and `AB_WATCHDOG_URL` set to the correct cluster endpoints.
- [ ] `AB_REGION` matches the AWS region the DS is running in (affects Bedrock
      region pinning for users who don't supply a BYO key).
- [ ] AWS credentials for the platform Bedrock pool available via the standard
      instance role / env-var chain.
- [ ] The `accelbyte.local.properties` file is **not** committed to source control.

## What the server does NOT configure

The server does **not** manage BYO API keys. The encryption master key
(`BYO_KEY_MASTER_KEY`) lives in the `server-extend` JVM, which also owns the
`byo_credentials_v1` CloudSave slot. The server-extend `ByoCredentialStore`
exposes the decrypted material to the game server via
`account.ByoCredentialStore.getDecrypted(userId)`.

### Sandbox tests

`./gradlew :server:test` runs the fast unit + integration suite. Tests that
require a real AccelByte namespace (DSMC, match2, wallet) are tagged
`@Tag("sandbox")` and are excluded from the default `test` task so CI stays
green.

Run the sandbox tests with:

```bash
./gradlew :server:testSandbox
```

The sandbox test runner expects the following keys in
`server/accelbyte.local.properties` (or in env vars):

| Key                 | Purpose                                                        |
| ------------------- | -------------------------------------------------------------- |
| `AB_DS_ID`          | Dedicated server id assigned by AMS for the test.             |
| `AB_NAMESPACE`      | AccelByte namespace the test should hit.                      |
| `AB_BASE_URL`       | AccelByte API base URL.                                        |
| `AB_CLIENT_ID`      | OAuth client id (sandbox-only).                                |
| `AB_CLIENT_SECRET`  | OAuth client secret (sandbox-only).                            |
| `AB_REGION`         | Region the test DS is registered in.                           |

The first test the sandbox suite runs is
`accelbyte.dsm.DedicatedServerRegistrationSandboxTest`, which exercises the
full `start -> heartbeat -> drain -> stop` lifecycle against the real DSMC
endpoint. See Phase 2 of `feature/live-pvp-and-billing` for context.

### Per-turn BYO inference path (Phase 6 of `feature/live-pvp-and-billing`)

`globals.BedrockCredentialResolver` swaps the global `bedrockEnv` credentials
to the active player's BYO key for the duration of their turn, then restores
the platform credentials before the next turn starts:

1. `TurnHarness.executeSingleTurn` resolves the actor's `accelByteUserId` and
   calls `BedrockCredentialResolver.snapshot(actorAccelByteId)` *before* the
   turn's inference runs. The snapshot captures the current platform keys.
2. The resolver consults `AccountSettings.bringYourOwnApiKey` and the
   `ByoCredentialStore` slot. When both are present and the slot's `provider`
   is `aws-bedrock`, the resolver swaps `bedrockEnv.setKeys(byoAccessKey,
   byoSecret)` so the turn's Bedrock calls bill the player's AWS account.
3. After `BillingSync.flushTurnUsage` runs in the `finally` block, the harness
   calls `BedrockCredentialResolver.restore(snapshot)` to put the platform
   keys back. The next turn is therefore never billed under the previous
   player's BYO key.

The resolver never crashes a turn. Any failure (missing slot, decrypt error,
non-AWS provider) falls back to the platform credentials and logs WARN. The
fallback mirrors `matchmaking.AccountSettingsLookup.costClassFor`'s PRO
downgrade so a "flag set, slot missing" player is consistently ranked as PRO
on the matchmaking ladder and billed under the platform for inference.

The corresponding `UsageEntry.byoKey` flag is set to `true` for BYO-billed
turns, which the operator dashboard reads to reconcile per-game cost against
AWS invoicing (the player's AWS account, not the operator's).