package org.ttt.autogenesis.accelbyte.models

import kotlin.js.Json

/**
 * A customer support message returned by the CSM service.
 *
 * @property id The unique identifier of this message.
 * @property content The content of the message.
 * @property type The type of message.
 * @property createdAt Timestamp when the message was created.
 * @property status The current status of the message.
 * @property priority The priority level of the message.
 */
data class CsmMessage(
    val id : String,
    val content : String,
    val type : String,
    val createdAt : String,
    val status : String,
    val priority : String
) : AccelByteResponse {
    companion object
    {
        fun fromJson(json : Json) : CsmMessage = CsmMessage(
            id = json.requireString("id"),
            content = json.requireString("content"),
            type = json.requireString("type"),
            createdAt = json.requireString("createdAt"),
            status = json.requireString("status"),
            priority = json.requireString("priority")
        )
    }
}

/**
 * A paginated list of customer support messages.
 *
 * @property messages The list of CSM messages.
 * @property paging Pagination metadata.
 */
data class CsmMessageListResponse(
    val messages : List<CsmMessage>,
    val paging : PagingInfo
) : AccelByteResponse {
    companion object
    {
        fun fromJson(json : Json) : CsmMessageListResponse = CsmMessageListResponse(
            messages = json.optJsonList("messages").map(CsmMessage::fromJson),
            paging = PagingInfo.fromJson(json.requireJson("paging"))
        )
    }
}
