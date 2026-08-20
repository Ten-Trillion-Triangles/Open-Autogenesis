package org.ttt.autogenesis.accelbyte.models

import kotlin.js.Json

/**
 * Response model for a GDPR data retrieval request.
 *
 * @property namespace The namespace where this request was made.
 * @property requestDate The date when the request was submitted.
 * @property userId The user whose data is being retrieved.
 */
data class GdprDataRetrievalResponse(
    val namespace : String,
    val requestDate : String,
    val userId : String
) : AccelByteResponse {
    companion object
    {
        fun fromJson(json : Json) : GdprDataRetrievalResponse = GdprDataRetrievalResponse(
            namespace = json.requireString("Namespace"),
            requestDate = json.requireString("RequestDate"),
            userId = json.requireString("UserID")
        )
    }
}

/**
 * Response model for a GDPR S2S data retrieval request.
 *
 * @property namespace The namespace where this request was made.
 * @property requestDate The date when the request was submitted.
 * @property requestId The unique identifier of this request.
 * @property userId The user whose data is being retrieved.
 */
data class GdprS2SDataRetrievalResponse(
    val namespace : String,
    val requestDate : String,
    val requestId : String,
    val userId : String
) : AccelByteResponse {
    companion object
    {
        fun fromJson(json : Json) : GdprS2SDataRetrievalResponse = GdprS2SDataRetrievalResponse(
            namespace = json.requireString("namespace"),
            requestDate = json.requireString("requestDate"),
            requestId = json.requireString("requestId"),
            userId = json.requireString("userId")
        )
    }
}

/**
 * Response model for a completed GDPR data deletion.
 *
 * @property requestDate The date when the deletion request was submitted.
 * @property finishedDate The date when the deletion was completed.
 * @property status The current status of the deletion.
 * @property userId The user whose data was deleted.
 * @property failedMessage Error message if the deletion failed.
 */
data class GdprFinishedDataDeletion(
    val requestDate : String,
    val finishedDate : String,
    val status : String,
    val userId : String,
    val failedMessage : String?
) : AccelByteResponse {
    companion object
    {
        fun fromJson(json : Json) : GdprFinishedDataDeletion = GdprFinishedDataDeletion(
            requestDate = json.requireString("requestDate"),
            finishedDate = json.requireString("finishedDate"),
            status = json.requireString("status"),
            userId = json.requireString("userId"),
            failedMessage = json.optString("failedMessage")
        )
    }
}

/**
 * A paginated list of GDPR data deletion records.
 *
 * @property data The list of deletion records.
 */
data class GdprFinishedDataDeletionListResponse(
    val data : List<GdprFinishedDataDeletion>
) : AccelByteResponse {
    companion object
    {
        fun fromJson(json : Json) : GdprFinishedDataDeletionListResponse = GdprFinishedDataDeletionListResponse(
            data = json.optJsonList("data").map(GdprFinishedDataDeletion::fromJson)
        )
    }
}