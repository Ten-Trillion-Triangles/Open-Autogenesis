package org.ttt.autogenesis.network

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Identifies the origin of an inbound RPC so the receiving process can
 * distinguish traffic from the player (game client) versus the
 * dedicated game server (the `:server` JVM).
 *
 * The tag travels as a query-string parameter (`?origin=…`) on the
 * REST/SSE transport, alongside the existing `playerId` and
 * `guestMode` parameters. The receiver uses it to feed per-origin
 * idle trackers (see `RpcUsageTracker` in `:server-extend`).
 *
 * Wire format on the URL:
 *   - [GAME_CLIENT] is the default; the query parameter is omitted
 *     for backwards compatibility with existing callers.
 *   - [GAME_SERVER] is emitted as `?origin=server` by callers that
 *     originate from the dedicated game server.
 */
@Serializable
enum class RpcOrigin
{
    @SerialName("client")
    GAME_CLIENT,

    @SerialName("server")
    GAME_SERVER;

    companion object
    {
        /**
         * Parses a query-string value (case-insensitive) into an
         * [RpcOrigin], falling back to [GAME_CLIENT] for blank or
         * unrecognised values. Mirrors [ServerExtendTransport.fromValue]'s
         * "be liberal in what you accept" stance.
         *
         * @param value Raw query-string value (`null` allowed).
         * @return The matching origin, or [GAME_CLIENT] when no match.
         */
        fun fromValue(value : String?) : RpcOrigin
        {
            val normalized = value?.trim()?.lowercase()
            return entries.firstOrNull { it.name.equals(normalized, ignoreCase = true) }
                ?: GAME_CLIENT
        }
    }
}