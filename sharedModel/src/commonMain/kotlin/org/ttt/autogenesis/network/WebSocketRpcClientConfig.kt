package org.ttt.autogenesis.network

/**
 * Configuration required to establish a WebSocket-based RPC connection.
 *
 * @param baseUrl Base WebSocket URL (ws:// or wss://)
 * @param playerId Identifier used to correlate WebSocket connection with player session
 * @param eventsPath Relative path to the WebSocket endpoint (defaults to `/events`)
 */
data class WebSocketRpcClientConfig(
    val baseUrl: String,
    val playerId: String,
    val accelbyteId: String? = null,
    val isGuestMode: Boolean = false,
    val eventsPath: String = "/events",
    /**
     * Role to advertise to the server on the `role=` query param. The main
     * server (server/src/.../Server.kt:579) defaults to SessionRole.PRIMARY
     * unless the URL carries `role=CONTROLLER`. server-extend's internal
     * WS connections (notifyGameServer, ResumeAvailabilityPushService)
     * MUST pass [Role.CONTROLLER] so the main server's
     * [org.ttt.autogenesis.server.PlayerConnectionManager.hasAnyPrimarySession]
     * does not count them — otherwise a long-lived server-extend connection
     * would block the snapshot-on-disconnect path and the resume push
     * would silently never fire on shutdown.
     *
     * Default null = omit the `role=` param entirely = server defaults to
     * PRIMARY (preserves backwards-compat with every existing browser caller).
     */
    val role: Role? = null
) {
    enum class Role {
        /** Browser-facing human player session. */
        PRIMARY,
        /** Server-extend / Python controller session — not a human player. */
        CONTROLLER
    }

    init {
        require(baseUrl.isNotBlank()) { "baseUrl cannot be blank" }
        require(playerId.isNotBlank()) { "playerId cannot be blank" }
        require(baseUrl.startsWith("ws://") || baseUrl.startsWith("wss://")) {
            "baseUrl must start with ws:// or wss://"
        }
    }

    /**
     * Builds the complete WebSocket URL with query parameters.
     */
    fun buildWebSocketUrl(): String {
        val separator = if (eventsPath.contains('?')) "&" else "?"
        var url = "$baseUrl$eventsPath${separator}playerId=$playerId"
        if (accelbyteId != null && accelbyteId.isNotBlank()) {
            url += "&accelbyteId=$accelbyteId"
        }
        if (isGuestMode) {
            url += "&guestMode=true"
        }
        if (role != null) {
            url += "&role=${role.name}"
        }
        return url
    }
}
