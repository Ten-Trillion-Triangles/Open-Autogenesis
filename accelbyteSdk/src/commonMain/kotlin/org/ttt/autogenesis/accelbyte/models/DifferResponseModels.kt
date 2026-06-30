package org.ttt.autogenesis.accelbyte.models

import kotlin.js.Json

/**
 * A single file change within a differential download manifest.
 *
 * @property path The file path within the differential.
 * @property action The action to perform (e.g., ADD, MODIFY, DELETE).
 * @property size The size of the file in bytes.
 * @property checksum The SHA checksum of the file.
 */
data class DifferFileChange(
    val path : String,
    val action : String,
    val size : Int?,
    val checksum : String?
) : AccelByteResponse {
    companion object
    {
        fun fromJson(json : Json) : DifferFileChange = DifferFileChange(
            path = json.requireString("path"),
            action = json.requireString("action"),
            size = json.optInt("size"),
            checksum = json.optString("checksum")
        )
    }
}

/**
 * Response from the differ service containing a differential download manifest.
 *
 * @property diffId The unique identifier of this differential.
 * @property status The current status of the differential.
 * @property progress The completion percentage.
 * @property files The list of file changes in this differential.
 * @property createdAt Timestamp when this differential was created.
 */
data class DiffResultResponse(
    val diffId : String,
    val status : String,
    val progress : Int?,
    val files : List<DifferFileChange>,
    val createdAt : String?
) : AccelByteResponse {
    companion object
    {
        fun fromJson(json : Json) : DiffResultResponse = DiffResultResponse(
            diffId = json.requireString("diffId"),
            status = json.requireString("status"),
            progress = json.optInt("progress"),
            files = json.optJsonList("files").map(DifferFileChange::fromJson),
            createdAt = json.optString("createdAt")
        )
    }
}

/**
 * Version 2 response from the differ service with additional metadata.
 *
 * @property diffId The unique identifier of this differential.
 * @property status The current status of the differential.
 * @property progress The completion percentage.
 * @property files The list of file changes in this differential.
 * @property metadata Additional metadata for this differential.
 * @property createdAt Timestamp when this differential was created.
 */
data class DiffResultV2Response(
    val diffId : String,
    val status : String,
    val progress : Int?,
    val files : List<DifferFileChange>,
    val metadata : Json?,
    val createdAt : String?
) : AccelByteResponse {
    companion object
    {
        fun fromJson(json : Json) : DiffResultV2Response = DiffResultV2Response(
            diffId = json.requireString("diffId"),
            status = json.requireString("status"),
            progress = json.optInt("progress"),
            files = json.optJsonList("files").map(DifferFileChange::fromJson),
            metadata = json.optJson("metadata"),
            createdAt = json.optString("createdAt")
        )
    }
}

/**
 * Health check response from the differ service.
 *
 * @property status The current status of the service.
 * @property timestamp Current server timestamp.
 * @property version The version of the differ service.
 * @property uptime The service uptime in seconds.
 */
data class DifferHealthResponse(
    val status : String,
    val timestamp : String,
    val version : String,
    val uptime : Int?
) : AccelByteResponse {
    companion object
    {
        fun fromJson(json : Json) : DifferHealthResponse = DifferHealthResponse(
            status = json.requireString("status"),
            timestamp = json.requireString("timestamp"),
            version = json.requireString("version"),
            uptime = json.optInt("uptime")
        )
    }
}
