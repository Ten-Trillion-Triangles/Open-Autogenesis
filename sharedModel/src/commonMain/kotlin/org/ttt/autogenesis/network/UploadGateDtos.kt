package org.ttt.autogenesis.network

import kotlinx.serialization.Serializable
import structs.accelbyte.cloudsave.CloudPlayerMapEntry

/**
 * Response for the `server.extend.uploadMapGate` RPC.
 *
 * The gate synchronously returns once the request has been either accepted
 * (safety pass + persistence) or rejected (safety failure OR persistence
 * failure). The notification pair — `Map.Upload.Error` and `Map.Upload.Success`
 * — is the asynchronous contract; the synchronous response is the latency-
 * critical ack so the client can correlate the request with the eventual
 * notification and updates the UI to its "completed" state in the meantime.
 *
 * The contract is all-or-nothing: either the safety pass AND the persistence
 * step both succeed (and the response carries the full catalogue entry), or
 * the entire upload is rolled back and the response carries only a failure
 * reason with no catalogue entry. There is no partial-success shape.
 *
 * @param accepted True when the safety pass succeeded AND the persistence
 *   step succeeded. False on any failure path.
 * @param mapId Populated when `accepted == true`. UUID generated server-side
 *   and used as the binary record identifier. Null on any failure path.
 * @param mapName Populated when `accepted == true`. The human-readable display
 *   name echoed from the request (after blank-replacement with "Untitled Map").
 *   Null on any failure path.
 * @param metadata Populated when `accepted == true`. The full catalogue entry
 *   shape the player UI renders the new map row from — same struct as a single
 *   entry returned by `server.extend.listPlayerMaps`. Carrying the entry on
 *   the gate response lets the UI render the row immediately on success
 *   without a follow-up list round-trip. Null on any failure path.
 * @param reason Populated when `accepted == false`. Human-readable
 *   description of the failure (safety classifier rejection OR persistence
 *   failure). Null on the success path.
 */
@Serializable
data class MapUploadGateResponse(
    val accepted: Boolean,
    val mapId: String? = null,
    val mapName: String? = null,
    val metadata: CloudPlayerMapEntry? = null,
    val reason: String? = null
)
