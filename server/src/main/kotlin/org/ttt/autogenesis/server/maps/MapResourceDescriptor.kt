package org.ttt.autogenesis.server.maps

import kotlinx.serialization.Serializable

/**
 * Indicates whether the descriptor corresponds to a packaged resource or an uploaded map.
 */
@Serializable
enum class MapResourceSource
{
    /** Maps shipped with the server JAR or unloaded from the filesystem. */
    PACKAGED,

    /** Maps a player uploaded during the current server session. */
    UPLOADED
}

/**
 * Metadata that tells clients how to display each map and how to re-open it if needed.
 *
 * @param path Resource path that can either be resolved via the classpath (PACKAGED) or
 *   looked up with [UploadedMapRepository.getMapBytes] (UPLOADED).
 * @param source Declares which list provided this descriptor so the UI can show badges.
 * @param metadata When the map originates from an upload, this holds the audit details; it is null for packaged assets.
 */
@Serializable
data class MapResourceDescriptor(
    val path: String,
    val source: MapResourceSource,
    val metadata: UploadedMapMetadata? = null
)