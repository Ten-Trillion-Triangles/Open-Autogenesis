package org.ttt.autogenesis.accelbyte.models

import kotlin.js.Json

/**
 * Response model for a game record stored in the cloud save service.
 *
 * @property key The unique key identifying this game record.
 * @property value The JSON value stored in this record.
 * @property version The version number of this record for conflict detection.
 * @property createdAt Timestamp when this record was created.
 * @property updatedAt Timestamp when this record was last updated.
 * @property namespace The namespace where this record is stored.
 * @property userId The user ID if this is a user-scoped record, null for global records.
 * @property isPublic Whether this record is publicly accessible.
 * @property checksum The checksum of the stored value.
 * @property metadata Additional metadata associated with this record.
 */
data class GameRecordResponse(
    val key : String,
    val value : Json?,
    val version : Int?,
    val createdAt : String?,
    val updatedAt : String?,
    val namespace : String?,
    val userId : String?,
    val isPublic : Boolean?,
    val checksum : String?,
    val metadata : Json?
) : AccelByteResponse {
    companion object
    {
        fun fromJson(json : Json) : GameRecordResponse = GameRecordResponse(
            key = json.requireString("key"),
            value = json.optJson("value"),
            version = json.optInt("version"),
            createdAt = json.optString("createdAt"),
            updatedAt = json.optString("updatedAt"),
            namespace = json.optString("namespace"),
            userId = json.optString("userId"),
            isPublic = json.optBoolean("isPublic"),
            checksum = json.optString("checksum"),
            metadata = json.optJson("metadata")
        )
    }
}

/**
 * A list of game records returned by the cloud save service.
 *
 * @property records The list of game records.
 */
data class GameRecordListResponse(
    val records : List<GameRecordResponse>
) : AccelByteResponse {
    companion object
    {
        fun fromJson(json : Json) : GameRecordListResponse = GameRecordListResponse(
            records = json.optJsonList("records").map(GameRecordResponse::fromJson)
        )
    }
}

/**
 * Response for a bulk game record fetch operation.
 *
 * @property records The list of successfully fetched game records.
 * @property failed The list of records that failed to fetch.
 */
data class BulkGameRecordResponse(
    val records : List<GameRecordResponse>,
    val failed : List<Json>?
) : AccelByteResponse {
    companion object
    {
        fun fromJson(json : Json) : BulkGameRecordResponse = BulkGameRecordResponse(
            records = json.optJsonList("records").map(GameRecordResponse::fromJson),
            failed = json.optJsonList("failed").ifEmpty { null }
        )
    }
}