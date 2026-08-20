# gRPC RPC Bridge Implementation Plan

## Scope & Constraints
- Follow the AGENTS.md guidance: shared logic stays in `commonMain`, JS helpers in `jsMain`, and root Gradle orchestrators remain untouched unless a plan step explicitly requires touching them.
- Treat gRPC as a sibling transport to REST/WebSocket. WebSocket connections still drive the main gameplay loop and browser clients must be able to connect to them as before; gRPC (specifically grpc-web/Connect for browsers) exists to let Extend-attached clients or back-end proxies toggle to a second transport without rewiring the RPC system.
- The browser-side bridge now ships generated JS stubs under `kvisionApp/src/jsMain/resources/grpc`, so document the script (`scripts/generate-grpc-web.sh`), npm dependencies, webpack alias (`@autogenesis/grpc/*`), and Gradle/yarn hooks that keep those artifacts up to date.
- All gRPC wiring must reuse `RpcRegistry`, `RpcInvoker`, and the `RpcMessage`/`RpcMessageHandler` plumbing already shared across transports; no new manual handler registration or proxies that replace the existing logic are permitted.
- The new gRPC transport must remain toggleable at runtime/configuration level so operators can run WebSocket and gRPC listeners side-by-side (Extend vs. other servers), and existing REST/WebSocket clients that do not need gRPC continue to operate unchanged.

## Goals & Success Criteria
1. Provide a `GrpcRpcBridge` API in `sharedModel` whose surface matches `RestRpcBridge` (same invoker, lifecycle hooks, and auto-registration) so consumer code can use it interchangeably.
2. Deliver the JS gRPC pipeline that keeps the transport implementation in JS/Kotlin layers isolated: `scripts/generate-grpc-web.sh` drives `protoc` + `protoc-gen-connect-es`/`protoc-gen-es` (from `grpcBridgeProto/package.json`) to emit `rpc_bridge_connect.js`/`rpc_bridge_pb.js`, `grpc-helpers.js`, and the webpack alias that Kotlin/JS uses to bridge into the generated client.
3. Keep the platform-specific clients focused on translation between `RpcMessage` and the generated gRPC envelopes, allowing browsers to stream JSON-wrapped `RpcMessage`s over Connect/grpc-web and servers to keep the same RPC handler dispatch as before.
4. Document the regeneration flow (which npm commands, which Gradle/yarn locks to bump) plus the runtime toggle so teams can rebuild the JS stub, configure host/port, and turn on gRPC only when the Extend proxy requires it.
5. Verify each bridge addition with targeted builds (`:sharedModel:build`, `:server:build`, `:kvisionApp:browserProductionWebpack`) and keep generated artifacts (`kvisionApp/src/jsMain/resources/grpc`, related yarn/lock files) under version control.

## Current Status
- The shared RPC API remains in `sharedModel` (`RpcRegistry`, `RpcInvoker`, `RpcMessage`, `RestRpcBridge`), and no new handler registration steps were introduced during the gRPC pilot.
- The JS/Connect path already exists: `scripts/generate-grpc-web.sh` runs the Buf-based proto pipeline to emit `rpc_bridge_connect.js`/`rpc_bridge_pb.js` plus the bespoke `grpc-helpers.js`; webpack resolves these via the `@autogenesis/grpc/*` alias so Kotlin/JS can import the generated Connect client easily.
- `kvisionApp/build.gradle.kts` now pulls runtime dependencies (`@connectrpc/connect`, `@connectrpc/connect-web`, `@bufbuild/protobuf`), and `kvisionApp/kotlin-store/yarn.lock` reflects the regenerated npm tree that these packages require.
- `GrpcRpcClientJs` in `sharedModel` has been rewritten to instantiate the Connect gRPC-web transport, stream JSON-serialized `RpcMessage`s through Kotlin channels/AsyncIterables, and present the `RpcInvoker`-compatible API to Kotlin code without touching the handler registry logic.
- `:sharedModel:build` now succeeds after refreshing the Kotlin/yarn lock, proving the multiplatform bridge still compiles with the new dependencies.

## Remaining Work
- The gRPC-Web generation path is now documented in the README (see the new "gRPC RPC Bridge" section), so focus on the remaining rollout and verification checklist.
1. **Commit generated JS artifacts and helper.** Ensure `rpc_bridge_connect.js`, `rpc_bridge_pb.js`, `grpc-helpers.js`, and any webpack/Gradle changes are tracked. Run `git status` to confirm no leftover files beyond the plan docs and generated assets.
2. **Surface the transport toggle and dependency requirements.** Highlight that gRPC is an optional transport, how to point the client at a gRPC endpoint (host/port configuration), and how Extend/deployment proxies can flip the toggle without affecting WebSocket/REST flows.
3. **Verify builds that touch the JS bridge.** After docs are refreshed, run `./gradlew :kvisionApp:browserProductionWebpack` (plus additional module builds if config/dependency changes) to confirm the final bundle includes the updated gRPC client and helper.
4. **Keep existing transports untouched.** Double-check that `RestRpcBridge`/`WebSocketRpcBridge` files remain unchanged (and `:server:build` continues to succeed) so the gRPC addition never erroneously removes the previous transport path.

## Testing & Verification
- `./gradlew :sharedModel:build` (already passing with refreshed yarn lock) after any shared API change.
- `./gradlew :server:build` to ensure the JVM side (including any gRPC server or Extend proxy wiring) compiles alongside the new dependencies.
- `./gradlew :kvisionApp:browserProductionWebpack` to make sure the JS bundle picks up the generated stubs and extra npm dependencies.
- Optional: `./gradlew clean build` once docs/plan updates are merged so the entire workspace compiles cleanly.

## Rollout & Monitoring
- Deploy the gRPC-capable stack to staging (when ready) and exercise both the WebSocket and gRPC transports to prove the optional toggle works; keep logs for `PlayerConnectionManager`/`RpcMessageHandler` to confirm both flows register handlers.
- Maintain the WebSocket path alongside gRPC during rollout, using configuration flags to enable gRPC only where Extend proxies require it.

## Communication
- Note in README/plan updates that the gRPC bridge uses the generated Connect/grpc-web client and that the transport is configurable, never replacing existing transports.
- In any PR description, mention the new npm dependencies, the script that generates the JS stub, and the Gradle commands (`:sharedModel:build`, `:kvisionApp:browserProductionWebpack`, etc.) executed to confirm the workspace builds.

## Revision Log
- `2025-12-28` – Updated plan to record the completed gRPC-Web implementation, the generation script, and the remaining documentation/build verification steps.
- `2026-04-09` – Reworked `GRPC_WEB` to target the native gRPC listener directly so the backend can serve browser grpc-web in-process.
- `2026-04-09` – Kept the REST/SSE and native gRPC paths intact while removing the browser proxy deployment requirement from the rollout plan.