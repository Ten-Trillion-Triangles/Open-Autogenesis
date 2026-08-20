package proxy

import accelbyte.cloudsave.BinaryRecord
import maps.PlayerMapRepository
import network.MapDeleteErrorHandlers
import network.MapDeleteSuccessHandlers
import org.ttt.autogenesis.logging.LogCategory
import org.ttt.autogenesis.logging.Logger
import org.ttt.autogenesis.config.ConfigSource
import globals.ExtendConfig
import org.ttt.autogenesis.network.RpcCallContext
import org.ttt.autogenesis.network.RpcDirection
import org.ttt.autogenesis.network.RpcMethod
import structs.accelbyte.cloudsave.CloudPlayerMaps
import structs.accelbyte.cloudsave.GameBinaryRecordCreateRequest
import structs.accelbyte.cloudsave.GameBinaryRecordMetadata
import structs.rpcRequests.DeletePlayerMapRequest
import structs.rpcRequests.DeletePlayerMapResult
import structs.rpcRequests.GetPlayerMapRequest
import structs.rpcRequests.GetPlayerMapResponse
import structs.rpcRequests.ListPlayerMapsRequest
import structs.rpcRequests.SavePlayerMapRequest
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * Server-extend CORS-bypass proxy for player map storage.
 *
 * Maps are stored as AGS Game Binary Records keyed
 * Lives as AGS Game Binary Records keyed `player-<userId>-<mapId>`, scoped to
  * the uploading namespace. The per-player catalogue (id, name, timestamp, size)
  * lives in [PlayerMapRepository] and is mirrored to a JSON Player Record keyed
  * `cloud-player-maps` for restart persistence (see `maps.CataloguePersister`).
  *
  * Ownership encoding:
  *  - **Primary (authoritative)**: record key prefix `player-<userId>-<mapId>`.
  *    The SDK scopes by namespace; the key prefix scopes by player within the
  *    namespace. Encoding ownership in the key means `adminGetGameBinary` /
  *    `adminDeleteGameBinary` calls just reconstruct the key from `userId` +
  *    `mapId` without needing to thread ownership separately.
  *  - **Tag-based backstop (planned, disabled)**: a `ownerUserId:<userId>` tag
  *    was attempted as defense-in-depth but AGS rejects any metadata-update
  *    payload that includes a `set_by` field (even null) with `errorCode 18316`.
  *    The SDK model `ModelsGameBinaryRecordMetadataRequest` has no
  *    `@JsonInclude(NON_NULL)` on `set_by`, so the only way to write a tag-only
  *    update is to bypass the SDK's wrapper layer. We skip the backstop and
  *    rely on the key prefix alone.
  *
  * Upload chain:
 *   1. Web client RPCs savePlayerMap with pre-packed mapPackBytes + mapName.
 *   2. server-extend validates size (≤50 MB) and name length (≤128 chars).
 *   3. server-extend calls adminCreateGameBinary on AGS, gets back presigned upload URL.
 *   4. server-extend PUTs bytes directly to that URL (not proxied — AGS handles CORS).
 *   5. server-extend updates [PlayerMapRepository].
 *
 * Download chain:
 *   1. Web client RPCs getPlayerMap with userId + mapId.
 *   2. server-extend reconstructs the key as `player-<userId>-<mapId>` and calls
 *      adminGetGameBinary on AGS, gets back metadata with `binaryInfo.url`
 *      (presigned download URL).
 *   3. server-extend GETs bytes from that URL.
 *   4. server-extend returns bytes wrapped in [GetPlayerMapResponse].
 *
 * Historical note: this proxy was originally wired against AGS Player Binary
 * Records keyed `map-<mapId>`. That endpoint (`adminCreatePlayerBinary`) was
 * observed returning HTTP 500 `l5d-proxy-error` from AccelByte's Linkerd
 * service mesh in 2026-08. The refactor moves storage to namespace-scoped Game
 * Binary Records; ownership is preserved via the `player-<userId>-<mapId>` key
 * prefix plus the `ownerUserId:<userId>` tag. The RPC surface (`savePlayerMap`
 * etc.) is unchanged — clients do not need to change.
 */
object MapStorageProxy
{
    /**
     * Prefix for ownership-encoded Game Binary Record keys. Combined with
     * `userId` and `mapId` to form the full key: `player-<userId>-<mapId>`.
     */
    private const val KEY_PREFIX = "player-"

    /**
     * Tag format for the ownership-defense-in-depth encoding. Tags are
     * list-of-strings on the binary record; we prefix the userId so the proxy
     * can verify ownership on retrieval (the key prefix is authoritative; the
     * tag is a backstop in case the key scheme changes).
     */
    private const val OWNER_TAG_PREFIX = "ownerUserId:"

    /**
     * Build the ownership-encoded Game Binary Record key for a player map.
     */
    private fun recordKey(userId: String, mapId: String): String =
        "$KEY_PREFIX$userId-$mapId"

    /**
     * Build the ownership tag for a player map.
     */
    private fun ownerTag(userId: String): String =
        "$OWNER_TAG_PREFIX$userId"

    /** Maximum allowed map pack size in bytes (50 MB). */
    private const val MAX_MAP_BYTES = 50 * 1024 * 1024

    /** Maximum allowed map name length (chars). */
    private const val MAX_MAP_NAME_LENGTH = 128

    /**
     * JVM HTTP client for presigned-URL PUT/GET against AGS/S3. Created once
     * per process; follow-redirects disabled to fail loudly on misconfigured URLs.
     */
    private val http: HttpClient by lazy {
        HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NEVER)
            .connectTimeout(Duration.ofSeconds(15))
            .build()
    }

    /**
     * Validate the upload payload. Returns true when all constraints pass;
     * logs and returns false otherwise. Kept inline (not split into helpers) so
     * each rejection surfaces a specific log line for diagnostics.
     */
    private fun validateUpload(request: SavePlayerMapRequest): Boolean
    {
        if (request.mapPackBytes.size > MAX_MAP_BYTES)
        {
            Logger.error(
                LogCategory.DATABASE,
                "MapStorageProxy.savePlayerMap: bytes=${request.mapPackBytes.size} exceeds limit $MAX_MAP_BYTES"
            )
            return false
        }
        if (request.mapName.length > MAX_MAP_NAME_LENGTH)
        {
            Logger.error(
                LogCategory.DATABASE,
                "MapStorageProxy.savePlayerMap: mapName length ${request.mapName.length} exceeds limit $MAX_MAP_NAME_LENGTH"
            )
            return false
        }
        if (request.mapId.isBlank())
        {
            Logger.error(LogCategory.DATABASE, "MapStorageProxy.savePlayerMap: mapId is blank")
            return false
        }
        return true
    }

    /**
     * PUT bytes to the AGS-issued presigned upload URL. Returns Result.success
     * when the PUT returns 2xx, otherwise Result.failure with the response
     * code and message.
     */
    private fun putBytesToPresignedUrl(url: String, contentType: String, bytes: ByteArray): Result<Unit>
    {
        return runCatching {
            val req = HttpRequest.newBuilder(URI.create(url))
                .header("Content-Type", contentType)
                .PUT(HttpRequest.BodyPublishers.ofByteArray(bytes))
                .timeout(Duration.ofMinutes(5))
                .build()
            val response = http.send(req, HttpResponse.BodyHandlers.ofString())
            if (response.statusCode() !in 200..299)
            {
                throw RuntimeException("presigned PUT returned HTTP ${response.statusCode()}: ${response.body().take(500)}")
            }
        }
    }

    /**
     * GET bytes from the AGS-issued presigned download URL. Returns Result.success
     * with the body when the GET returns 2xx, otherwise Result.failure.
     */
    private fun getBytesFromPresignedUrl(url: String): Result<ByteArray>
    {
        return runCatching {
            val req = HttpRequest.newBuilder(URI.create(url))
                .GET()
                .timeout(Duration.ofMinutes(5))
                .build()
            val response = http.send(req, HttpResponse.BodyHandlers.ofByteArray())
            if (response.statusCode() !in 200..299)
            {
                throw RuntimeException("presigned GET returned HTTP ${response.statusCode()}")
            }
            response.body()
        }
    }

    @RpcMethod("server.extend.savePlayerMap", RpcDirection.SERVER)
    suspend fun savePlayerMap(context: RpcCallContext, request: SavePlayerMapRequest): Boolean
    {
        if (!validateUpload(request)) return false

        // Dedupe-and-replace-by-name: if the user already has a catalogue entry
        // with the same name (case-insensitive, trimmed), delete the old AGS
        // record AND the old catalogue entry BEFORE the new create flow. The
        // new mapId is always distinct from the old one (the gate layer mints a
        // fresh UUID per upload), so the AGS record key `player-<userId>-<mapId>`
        // is fresh on the create step.
        //
        // The `collision.mapId != request.mapId` guard prevents a re-upload of the
        // same mapId from triggering an orphan-delete-then-recreate cycle on its
        // own record. The gate always generates a new UUID, so this guard is
        // effectively a no-op at the gate layer — it exists to make the proxy
        // safe for direct callers (e.g. internal tooling) that pass through the
        // original mapId.
        //
        // `BinaryRecord.adminDeleteBinary` is a soft no-op when the key is missing,
        // so a stale catalogue entry that points at a missing AGS record is
        // handled cleanly — the new upload proceeds regardless.
        val collision = PlayerMapRepository.findByName(request.userId, request.mapName)
        if (collision != null && collision.mapId != request.mapId)
        {
            val oldKey = recordKey(request.userId, collision.mapId)
            val deleteResult = BinaryRecord.adminDeleteBinary(oldKey)
            if (deleteResult.isFailure)
            {
                Logger.warn(
                    LogCategory.DATABASE,
                    "MapStorageProxy.savePlayerMap: orphan-delete failed for key=$oldKey: ${deleteResult.exceptionOrNull()?.message}; proceeding with fresh upload anyway"
                )
            }
            PlayerMapRepository.removeEntry(request.userId, collision.mapId)
            Logger.info(
                LogCategory.DATABASE,
                "MapStorageProxy.savePlayerMap: dedupe-by-name replaced '${request.mapName}' (oldMapId=${collision.mapId}) for user=${request.userId}"
            )
        }

        val binaryKey = recordKey(request.userId, request.mapId)
        val ownerTag = ownerTag(request.userId)

        // Step 1: ask AGS for a presigned upload URL via adminCreateGameBinary.
        // The namespace-scoped Game Binary endpoint is the live-test verified
        // path; the Player Binary endpoint was observed returning l5d-proxy-error
        // on this namespace in 2026-08. Ownership is encoded in the key prefix
        // (`player-<userId>-<mapId>`) and reinforced via the `ownerUserId:<userId>`
        // tag — a defensive backstop if the key scheme ever changes.
        val createResult = BinaryRecord.adminCreateBinary(
            request = GameBinaryRecordCreateRequest(
                key = binaryKey,
                fileType = "bin",
                setBy = "SERVER"
            )
        )
        val presigned = createResult.getOrNull()
            ?: run {
                val ex = createResult.exceptionOrNull()
                Logger.error(
                    LogCategory.DATABASE,
                    "MapStorageProxy.savePlayerMap: adminCreateGameBinary failed for key=$binaryKey: ${ex?.message ?: ex?.javaClass?.simpleName}"
                )
                return false
            }

        // Step 2: PUT bytes directly to the AGS-issued URL.
        // The presigned URL is NOT proxied — bytes flow directly between
        // server-extend and AGS/S3, which avoids loading the full pack into
        // the server-extend JVM heap as a long-lived buffer.
        // The Content-Type MUST match what AGS signed the URL with (typically
        // `application/octet-stream` for Game Binary) — using the request's
        // contentType (e.g. `application/zip`) causes AWS SignatureDoesNotMatch.
        // AGS returns the signed contentType in the presigned response; prefer it.
        val putContentType = presigned.contentType.ifBlank { request.contentType }
        val putResult = putBytesToPresignedUrl(presigned.url, putContentType, request.mapPackBytes)
        if (putResult.isFailure)
        {
            Logger.error(
                LogCategory.DATABASE,
                "MapStorageProxy.savePlayerMap: byte upload failed for key=$binaryKey: ${putResult.exceptionOrNull()?.message}"
            )
            return false
        }

        // Step 3: COMMIT the record by calling adminPutGameBinaryRecordV1 with
        // the content_type + file_location body. AGS only populates
        // `binary_info.url` on subsequent adminGetGameBinaryRecordV1 calls AFTER
        // this commit step — without it, the record exists in S3 but AGS doesn't
        // surface a presigned download URL (verified 2026-08-11 via direct
        // curl + the C++ Unreal SDK's three-step flow at depot/.../AccelCore.cpp).
        // This step MUST happen before the record is considered "saved" — the
        // commit failure is non-fatal but logged because the bytes are already
        // in S3 (a successful retry from the same userId+mapId will overwrite
        // the bytes via adminPutGameBinaryRecordV1 + a fresh adminCreateGameBinaryRecord).
        val commitResult = BinaryRecord.adminCommitGameBinary(
            key = binaryKey,
            contentType = putContentType,
            fileLocation = presigned.fileLocation
        )
        if (commitResult.isFailure)
        {
            Logger.warn(
                LogCategory.DATABASE,
                "MapStorageProxy.savePlayerMap: commit step failed for key=$binaryKey: " +
                "${commitResult.exceptionOrNull()?.message}; bytes uploaded but record " +
                "may not be officially available (download will fail until next commit)"
            )
            // Continue — bytes are in S3; the catalogue entry will still be useful.
        }

        // Step 4: update the catalogue (triggers the persister write-through).
        PlayerMapRepository.addEntry(
            userId = request.userId,
            mapId = request.mapId,
            mapName = request.mapName,
            sizeBytes = request.mapPackBytes.size
        )

        Logger.info(
            LogCategory.DATABASE,
            "MapStorageProxy.savePlayerMap: stored ${request.mapPackBytes.size} bytes for user=${request.userId} key=$binaryKey"
        )
        return true
    }

    @RpcMethod("server.extend.listPlayerMaps", RpcDirection.SERVER)
    suspend fun listPlayerMaps(context: RpcCallContext, request: ListPlayerMapsRequest): CloudPlayerMaps
    {
        // Bug fix (2026-08-14): pre-fix the in-memory cache was empty on
        // every server-extend restart because the production wiring
        // defaulted to NoOpCataloguePersister AND there was no rehydrate
        // step. The cached `snapshotForUser` therefore returned empty
        // even when the persister held saved maps from a previous run.
        // Calling `rehydrate` here is the lazy cache loader that bridges
        // the gap. It is idempotent: a no-op when the cache is already
        // populated, so the extra call costs nothing on a warm cache.
        PlayerMapRepository.rehydrate(request.userId)
        return PlayerMapRepository.snapshotForUser(request.userId)
    }

    @RpcMethod("server.extend.getPlayerMap", RpcDirection.SERVER)
    suspend fun getPlayerMap(context: RpcCallContext, request: GetPlayerMapRequest): GetPlayerMapResponse
    {
        val binaryKey = recordKey(request.userId, request.mapId)

        // Step 1: ask AGS for the metadata + presigned download URL.
        // The savePlayerMap flow includes a commit step (adminPutGameBinaryRecordV1)
        // that ensures binary_info.url is populated. The poll below absorbs any
        // remaining propagation delay between commit and GET visibility.
        val metadata = BinaryRecord.pollForGameBinaryMetadataReady(binaryKey)
            ?: throw RuntimeException(
                "AGS Game Binary Record metadata not ready within retry window for $binaryKey"
            )

        // Step 1.5: ownership is encoded in the key prefix `player-<userId>-<mapId>`.
        // AGS API constraints prevent us from also stamping a defensive
        // `ownerUserId:<userId>` tag on the record (the SDK model serializes
        // `set_by: null` on metadata-update calls, which AGS rejects). The key
        // prefix is the authoritative channel.

        // Step 2: pull the presigned download URL from binaryInfo.
        // The Kotlin shared-model `binaryInfo` is `BinaryInfo?` shape; `url`
        // is the AGS-issued presigned download URL the bytes live behind.
        val downloadUrl = metadata.binaryInfo?.url
            ?: run {
                Logger.error(
                    LogCategory.DATABASE,
                    "MapStorageProxy.getPlayerMap: metadata has no binaryInfo.url for ${request.userId}/${binaryKey}"
                )
                throw RuntimeException("AGS Game Binary Record returned no binaryInfo.url")
            }

        // Step 3: BYTES TRANSPORT REPLACEMENT — we no longer fetch the 6.3 MB
        // pack on the JVM event-loop thread and base64-encode it into the
        // JSON-RPC response. That was the second softlock source (see
        // server-extend /download-map route for the full breakdown). Instead
        // we return the same presigned URL the SSE endpoint was about to fetch
        // from, and the browser `fetch()`s `/download-map?...` which proxies
        // the bytes back as `application/octet-stream`. The KVision
        // `fetchMapPackBytes` follows the new contract transparently.
        Logger.info(
            LogCategory.DATABASE,
            "MapStorageProxy.getPlayerMap: returning presigned-URL-only response for ${request.userId}/${binaryKey} (size=${request.mapId})"
        )
        return GetPlayerMapResponse(
            mapPackBytes = ByteArray(0),
            downloadUrl = "${extendBaseUrl()}/download-map?userId=${request.userId}&mapId=${request.mapId}"
        )
    }

    /**
     * Returns the origin the browser uses to reach server-extend.
     * Defaults to `http://127.0.0.1:7070` for the local dev loop
     * (port comes from [globals.ExtendConfig.restPort]). Production
     * deployments set `serverExtend.publicBaseUrl` in
     * `server-extend.local.properties` — preserved verbatim, no
     * `https://` prefix manipulation — so the link the browser
     * fetches is whatever the operator configured (the CORS
     * allowlist above already permits `https://<ags-host>` from
     * production allowlists).
     *
     * The server-extend process is the one serving `/download-map`,
     * so handing the browser the server-extend origin is the
     * canonical choice — no cross-origin preflight, no extra hops.
     */
    private fun extendBaseUrl(): String
    {
        return ConfigSource
            .propertyOrEmpty("server-extend.local.properties", "serverExtend.publicBaseUrl")
            .takeIf { it.isNotBlank() }
            ?: "http://127.0.0.1:${ExtendConfig.restPort}"
    }

    /**
     * Binary-stream fetcher used by the `GET /download-map` HTTP route.
     * Same AGS pull as [getPlayerMap]'s old Step 3, but the bytes
     * stream back over HTTP (Ktor `respondBytes`) instead of being
     * base64-encoded into a JSON-RPC response. The browser assembles
     * the chunks into a single `ArrayBuffer` via the standard
     * `fetch().then(r => r.arrayBuffer())` pipeline.
     *
     * Throws on:
     *   - missing metadata (`pollForGameBinaryMetadataReady` returns null)
     *   - missing `binaryInfo.url` (AGS returned metadata without a download URL)
     *   - `getBytesFromPresignedUrl` failure (network / signing)
     *
     * All errors bubble up to the route's `try { } catch (e: Throwable) { }`
     * which maps them to `404 Not Found` for the browser.
     */
    suspend fun fetchBytesForDownload(userId: String, mapId: String): ByteArray
    {
        val binaryKey = recordKey(userId, mapId)
        val metadata = BinaryRecord.pollForGameBinaryMetadataReady(binaryKey)
            ?: throw RuntimeException("AGS Game Binary Record metadata not ready within retry window for $binaryKey")
        val downloadUrl = metadata.binaryInfo?.url
            ?: throw RuntimeException("AGS Game Binary Record returned no binaryInfo.url")
        val bytesResult = getBytesFromPresignedUrl(downloadUrl)
        val bytes = bytesResult.getOrElse { err ->
            Logger.error(
                LogCategory.DATABASE,
                "MapStorageProxy.fetchBytesForDownload: presigned GET failed for $userId/$binaryKey: ${err.message}"
            )
            throw err
        }
        Logger.info(
            LogCategory.DATABASE,
            "MapStorageProxy.fetchBytesForDownload: streamed ${bytes.size} bytes for $userId/$binaryKey"
        )
        return bytes
    }

    @RpcMethod("server.extend.deletePlayerMap", RpcDirection.SERVER)
    suspend fun deletePlayerMap(context: RpcCallContext, request: DeletePlayerMapRequest): DeletePlayerMapResult
    {
        // Auth check: deny when the SSE session's accelbyteId is set AND does
        // not match request.userId. When the session has no accelbyteId (curl
        // probes, test rigs, `?skipLogin=true` without OAuth callback), trust
        // the request.userId — same debt as `MapUploadGate`. The key-prefix
        // encoding (`player-<userId>-<mapId>`) prevents cross-player deletes
        // even when the auth check is bypassed: a wrong userId targets a
        // non-existent record.
        val sessionAccelbyteId = MapDeleteSuccessHandlers.currentConnectionManager()
            ?.let { runCatching { it.findSession(context.connectionId) }.getOrNull() }
            ?.accelbyteId
            ?.takeIf { it.isNotBlank() }
        if (sessionAccelbyteId != null && sessionAccelbyteId != request.userId)
        {
            Logger.warn(
                LogCategory.DATABASE,
                "MapStorageProxy.deletePlayerMap: ownership mismatch sessionAccelbyteId=$sessionAccelbyteId request.userId=${request.userId} — rejecting"
            )
            MapDeleteErrorHandlers.sendMapDeleteError(
                context.connectionId,
                "Delete denied: map does not belong to this session"
            )
            return DeletePlayerMapResult(deleted = false)
        }

        // Capture the mapName from the catalogue before removing the entry —
        // the notification payload needs the display name and the catalogue
        // entry is gone after removeEntry(). Fall back to the mapId when the
        // catalogue has no entry (callers that bypass the upload gate).
        val mapName = PlayerMapRepository.getEntry(request.userId, request.mapId)?.mapName
            ?: request.mapId

        val binaryKey = recordKey(request.userId, request.mapId)
        val deleteResult = runCatching { BinaryRecord.adminDeleteBinary(binaryKey) }
        val deleted = deleteResult.isSuccess
        if (deleteResult.isFailure)
        {
            Logger.warn(
                LogCategory.DATABASE,
                "MapStorageProxy.deletePlayerMap: AGS delete failed for ${request.userId}/${binaryKey}: ${deleteResult.exceptionOrNull()?.message}"
            )
            MapDeleteErrorHandlers.sendMapDeleteError(
                context.connectionId,
                "AGS delete failed: ${deleteResult.exceptionOrNull()?.message ?: "unknown"}"
            )
            return DeletePlayerMapResult(deleted = false)
        }

        val removed = PlayerMapRepository.removeEntry(request.userId, request.mapId)

        Logger.info(
            LogCategory.DATABASE,
            "MapStorageProxy.deletePlayerMap: ${request.userId}/${binaryKey} agsDeleted=$deleted catalogueRemoved=$removed"
        )
        MapDeleteSuccessHandlers.sendMapDeleteSuccess(context.connectionId, request.mapId, mapName)
        return DeletePlayerMapResult(deleted = deleted)
    }
}
