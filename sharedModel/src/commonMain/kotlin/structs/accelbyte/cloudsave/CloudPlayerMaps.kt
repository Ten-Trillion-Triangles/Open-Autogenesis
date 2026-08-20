package structs.accelbyte.cloudsave

import kotlinx.serialization.Serializable

/**
 * Per-player map catalogue stored as a JSON Player Record (key = 'cloud-player-maps').
 *
 * Each entry holds the metadata for one map; the bytes are stored separately as
 * a Player Binary Record keyed 'map-<mapId>'. This separation lets the web client
 * list maps quickly (small JSON read) without fetching large binary packs.
 *
 * @property maps The catalogue entries for this player, in insertion order.
 */
@Serializable
data class CloudPlayerMaps(
    val maps: List<CloudPlayerMapEntry> = emptyList()
)

/**
 * One entry in a [CloudPlayerMaps] catalogue.
 *
 * @property mapId UUID generated at first upload; identifies the binary record (key = `map-<mapId>`).
 * @property mapName Original human-friendly name supplied at upload time.
 * @property uploadedAt Wall-clock epoch milliseconds when the map was first saved.
 * @property sizeBytes Size of the packed map bytes on AGS — surfaced for UI hints without re-fetching the record.
 */
@Serializable
data class CloudPlayerMapEntry(
    val mapId: String,
    val mapName: String,
    val uploadedAt: Long,
    val sizeBytes: Int
)
