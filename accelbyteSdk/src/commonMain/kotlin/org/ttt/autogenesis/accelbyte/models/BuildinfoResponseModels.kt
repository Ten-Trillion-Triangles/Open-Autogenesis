package org.ttt.autogenesis.accelbyte.models

import kotlin.js.Json

/**
 * A single build version entry returned by the GameTelemetry version history endpoint.
 *
 * @property buildId The unique identifier for this build.
 * @property version The version string of the build.
 * @property createdAt Timestamp when this build version was created.
 * @property size The size of the build in bytes.
 */
data class BuildVersionEntry(
    val buildId : String,
    val version : String,
    val createdAt : String,
    val size : Int?
) : AccelByteResponse {
    companion object
    {
        fun fromJson(json : Json) : BuildVersionEntry = BuildVersionEntry(
            buildId = json.requireString("buildId"),
            version = json.requireString("version"),
            createdAt = json.requireString("createdAt"),
            size = json.optInt("size")
        )
    }
}

/**
 * A list of build version entries with their metadata.
 *
 * @property versions The list of build version entries.
 */
data class VersionHistoryResponse(
    val versions : List<BuildVersionEntry>
) : AccelByteResponse {
    companion object
    {
        fun fromJson(json : Json) : VersionHistoryResponse = VersionHistoryResponse(
            versions = json.optJsonList("versions").map(BuildVersionEntry::fromJson)
        )
    }
}

/**
 * Status response for a differential download operation.
 *
 * @property status The current status of the diff operation.
 * @property progress The completion percentage of the diff operation.
 * @property diffSize The size of the resulting differential in bytes.
 * @property estimatedTime Estimated time remaining in seconds.
 */
data class DiffStatusResponse(
    val status : String,
    val progress : Int?,
    val diffSize : Int?,
    val estimatedTime : Int?
) : AccelByteResponse {
    companion object
    {
        fun fromJson(json : Json) : DiffStatusResponse = DiffStatusResponse(
            status = json.requireString("status"),
            progress = json.optInt("progress"),
            diffSize = json.optInt("diffSize"),
            estimatedTime = json.optInt("estimatedTime")
        )
    }
}

/**
 * URL and metadata for downloading a block of a differential update.
 *
 * @property url The pre-signed URL for downloading the block.
 * @property expiresAt Timestamp when the download URL expires.
 * @property blockType The type of the block.
 * @property compressed Whether the block is compressed.
 */
data class BlockDownloadUrlResponse(
    val url : String,
    val expiresAt : String,
    val blockType : String,
    val compressed : Boolean
) : AccelByteResponse {
    companion object
    {
        fun fromJson(json : Json) : BlockDownloadUrlResponse = BlockDownloadUrlResponse(
            url = json.requireString("url"),
            expiresAt = json.requireString("expiresAt"),
            blockType = json.requireString("blockType"),
            compressed = json.requireBoolean("compressed")
        )
    }
}

/**
 * A single file entry within a cache differential manifest.
 *
 * @property path The file path within the differential.
 * @property action The action to perform on this file (e.g., ADD, MODIFY, DELETE).
 * @property size The size of the file in bytes.
 * @property checksum The SHA checksum of the file.
 */
data class CacheDiffFile(
    val path : String,
    val action : String,
    val size : Int?,
    val checksum : String?
) : AccelByteResponse {
    companion object
    {
        fun fromJson(json : Json) : CacheDiffFile = CacheDiffFile(
            path = json.requireString("path"),
            action = json.requireString("action"),
            size = json.optInt("size"),
            checksum = json.optString("checksum")
        )
    }
}

/**
 * Response containing a list of files in a cache differential manifest.
 *
 * @property files The list of files in the differential.
 */
data class CacheDiffResponse(
    val files : List<CacheDiffFile>
) : AccelByteResponse {
    companion object
    {
        fun fromJson(json : Json) : CacheDiffResponse = CacheDiffResponse(
            files = json.optJsonList("files").map(CacheDiffFile::fromJson)
        )
    }
}
