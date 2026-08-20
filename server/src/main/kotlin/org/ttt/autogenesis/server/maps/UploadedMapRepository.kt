package org.ttt.autogenesis.server.maps

import kotlinx.serialization.Serializable
import java.util.LinkedHashMap
import java.util.UUID

/**
 * Metadata about a previously uploaded map that can be exposed to clients without leaking the raw bytes.
 *
 * @param mapId Generated UUID for deduplication and future lookup via [getMapBytes].
 * @param mapName Original file name (with a unique fallback) so UI can label the entry.
 * @param uploadedBy Connection identifier (if available) that triggered the upload.
 * @param uploadedAt Wall-clock epoch milliseconds from when [registerMap] stored the pack in memory.
 * @param sizeBytes Size of the stored pack so callers can show payload size without loading the bytes.
 */
@Serializable
data class UploadedMapMetadata(
    val mapId: String,
    val mapName: String,
    val uploadedBy: String?,
    val uploadedAt: Long,
    val sizeBytes: Int
)

/**
 * In-memory registry that retains uploaded map packs while the server is running.
 *
 * This keeps a copy of every pack together with enough metadata to answer listing queries
 * and to re-fetch the bytes later when broadcasting the map to new clients.
 *
 * Persistence across restarts is intentionally out of scope because the registry only lives
 * in process memory.
 */
object UploadedMapRepository
{
    private const val DEFAULT_MAP_PREFIX = "uploaded-map"

    private data class Record(
        val metadata: UploadedMapMetadata,
        val bytes: ByteArray
    )

    private val lock = Any()
    private val records = LinkedHashMap<String, Record>()

    /**
     * Stores a copy of the uploaded bytes and returns metadata for client consumption.
     *
     * @param mapName Optional original file name (blank values are replaced with a generated label).
     * @param uploadedBy Optional connection identifier that helps auditors trace the upload.
     * @param packBytes The raw ZIP content from the client; a defensive copy is stored to avoid later mutations.
     */
    fun registerMap(mapName: String?, uploadedBy: String?, packBytes: ByteArray): UploadedMapMetadata
    {
        val id = UUID.randomUUID().toString()
        val normalizedName = mapName?.takeIf { it.isNotBlank() } ?: "$DEFAULT_MAP_PREFIX-$id"
        val metadata = UploadedMapMetadata(
            mapId = id,
            mapName = normalizedName,
            uploadedBy = uploadedBy,
            uploadedAt = System.currentTimeMillis(),
            sizeBytes = packBytes.size
        )

        synchronized(lock) {
            records[id] = Record(metadata, packBytes.copyOf())
        }

        return metadata
    }

    /**
     * Returns metadata for every map that has been uploaded so far, in insertion order.
     */
    fun listUploadedMaps(): List<UploadedMapMetadata>
    {
        synchronized(lock) {
            return records.values.map { it.metadata }
        }
    }

    /**
     * Returns a copy of the packed bytes for the provided ID, or null if the map is unknown.
     *
     * A defensive copy ensures callers cannot mutate the stored payload.
     */
    fun getMapBytes(mapId: String): ByteArray?
    {
        synchronized(lock) {
            return records[mapId]?.bytes?.copyOf()
        }
    }

    /**
     * For tests only: removes every stored record.
     */
    internal fun clear()
    {
        synchronized(lock) {
            records.clear()
        }
    }
}