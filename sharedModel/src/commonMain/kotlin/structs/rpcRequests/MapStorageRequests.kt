package structs.rpcRequests

import kotlinx.serialization.Serializable

/**
 * Request types for the server-extend MapStorageProxy RPC surface.
 *
 * The web client calls these RPCs to persist player map packs to AGS Player
 * Binary Records (bytes) and to a JSON Player Record (catalogue). server-extend
 * is the validation + audit gate; uploads MUST go through it.
 *
 * For reads, the main `server` module can also fetch maps directly from AGS
 * using its own AccelByteSdkProvider — see `maps.PlayerMapDownloader`.
 */

/**
 * Upload a pre-packed map pack for the given user.
 *
 * @param userId AccelByte user ID whose namespace the record lives under.
 * @param mapId Client-generated UUID that identifies this map (binary record key = `map-<mapId>`).
 * @param mapName Human-friendly display name (≤128 chars; rejected otherwise by the proxy).
 * @param mapPackBytes The pre-packed binary pack — typically produced by `MapPackManager.pack` on the client.
 * @param contentType MIME type of the packed bytes; defaults to `application/zip`.
 */
@Serializable
data class SavePlayerMapRequest(
    val userId: String,
    val mapId: String,
    val mapName: String,
    val mapPackBytes: ByteArray,
    val contentType: String = "application/zip"
)

/**
 * List the per-player map catalogue. Returns a [structs.accelbyte.cloudsave.CloudPlayerMaps].
 *
 * @param userId AccelByte user ID whose catalogue to read.
 */
@Serializable
data class ListPlayerMapsRequest(val userId: String)

/**
 * Fetch the raw map pack bytes for one map.
 *
 * @param userId AccelByte user ID who owns the map.
 * @param mapId The mapId returned by the original save (binary record key = `map-<mapId>`).
 */
@Serializable
data class GetPlayerMapRequest(
    val userId: String,
    val mapId: String
)

/**
 * Delete a player map (binary record + catalogue entry).
 *
 * @param userId AccelByte user ID who owns the map.
 * @param mapId The mapId to delete.
 */
@Serializable
data class DeletePlayerMapRequest(
    val userId: String,
    val mapId: String
)

/**
 * Result of a delete RPC. `deleted` reflects whether the AGS binary record was
 * successfully removed (true) or whether the AGS call failed (false). The catalogue
 * entry is always removed locally on best-effort basis regardless.
 */
@Serializable
data class DeletePlayerMapResult(val deleted: Boolean)

/**
 * Response of [GetPlayerMapRequest]. Carries the raw bytes in a
 * `@Serializable` data class so the KSP-generated RPC binding can
 * serialize the result — bare `kotlin.ByteArray` is not supported as
 * an RPC return type (the KSP generator would emit
 * `kotlin.ByteArray.serializer()` which is not in scope).
 *
 * @param mapPackBytes Raw packed map bytes fetched from AGS Player
 *   Binary Record. BINARY TRANSPORT WAS REPLACED — the upstream server
 *   now proxies bytes through a dedicated binary-stream endpoint
 *   (`GET /download-map?userId=...&mapId=...`) whose URL is returned as
 *   [downloadUrl]. The browser calls `fetch(downloadUrl)` directly
 *   instead of accepting a 6.3 MB base64-encoded JSON payload over the
 *   SSE channel — which was the softlock point: kotlinx.serialization's
 *   `ByteArray` encoder base64-decodes on the JVM main thread, blocking
 *   the SSE writer and starving every other connected session of
 *   round-trip events. [mapPackBytes] is retained as an empty default
 *   so the contract compiles; new callers must use [downloadUrl].
 *
 * @param downloadUrl Presigned server-extend URL the browser can
 *   `fetch()` directly to stream the raw bytes back as
 *   `application/octet-stream`. CORS headers on the route permit the
 *   dev front-end origins (localhost / 127.0.0.1) which are the only
 *   ones that ever need this RPC in dev.
 */
@Serializable
data class GetPlayerMapResponse(
    val mapPackBytes: ByteArray = ByteArray(0),
    val downloadUrl: String? = null
)
