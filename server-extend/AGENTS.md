# server-extend AGENTS.md

## OVERVIEW
Auxiliary JVM service — matchmaking, cloud save proxy, commander creation pipeline

## WHERE TO LOOK
- `org/ttt/autogenesis/serverextend/ServerExtend.kt` — Main entry, REST on 7070, gRPC on 9092
- `org/ttt/autogenesis/serverextend/config/AccelByteConfig.kt` — Credentials from properties/env
- `globals/ExtendConfig.kt` — gRPC port override, debugMode flag (default true)
- `matchmaking/ServerConnector.kt` — Game request + matchmaking; dev mode bypasses AccelByte SDK
- `proxy/CloudSaveProxy.kt` — CORS-bypass proxy for master record, commander records, account settings
- `agent/runners/CommanderRunner.kt` — Commander creation pipeline via TTT agent; saves to VFS
- `org/ttt/autogenesis/serverextend/RestPlayerConnectionManager.kt` — SSE-based session tracking
- `matchmaking/UrlHandoverRegistry.kt` — sessionId -> URL map for the `server.extend.resolveUrl` hand-off; cleared by vanish/disconnect/AGS-deletion listeners

## CONVENTIONS
- REST + SSE transport (vs main server WebSocket)
- AccelByte SDK lazy-initialized via `AccelByteConfig` before any SDK class access
- Dev mode (`ExtendConfig.debugMode = true`) spoofs matchmaking and skips cloud API calls
- Same Logger system as main server; `serverType="server-extend"`, port 7070, gRPC 9092
- RPC registry registered via internal `*RpcHandlersProvider` vars to avoid classpath collisions

## ANTI-PATTERNS
None specific — follow root AGENTS.md rules plus module transport expectations

## TRANSPORT SUMMARY
| Aspect | server-extend | main server |
|--------|--------------|-------------|
| REST port | 7070 | 9080 |
| gRPC port | 9092 | 9091 |
| Client transport | REST + SSE | WebSocket |
| Dev mode | Bypasses AccelByte | Full integration |