package org.ttt.autogenesis.accelbyte.models

import kotlin.js.Json

/**
 * A single chat message returned by the chat service.
 *
 * @property id The unique identifier of this message.
 * @property topic The chat topic or channel this message belongs to.
 * @property userId The unique identifier of the user who sent the message.
 * @property message The content of the message.
 * @property createdAt Timestamp when the message was created.
 * @property updatedAt Timestamp when the message was last updated.
 * @property type The type of message (e.g., TEXT, SYSTEM).
 * @property metadata Additional metadata associated with the message.
 */
data class ChatMessage(
    val id : String,
    val topic : String?,
    val userId : String?,
    val message : String?,
    val createdAt : String?,
    val updatedAt : String?,
    val type : String?,
    val metadata : Json?
) : AccelByteResponse {
    companion object
    {
        fun fromJson(json : Json) : ChatMessage = ChatMessage(
            id = json.requireString("id"),
            topic = json.optString("topic"),
            userId = json.optString("userId"),
            message = json.optString("message") ?: json.optString("content"),
            createdAt = json.optString("createdAt"),
            updatedAt = json.optString("updatedAt"),
            type = json.optString("type"),
            metadata = json.optJson("metadata")
        )
    }
}

/**
 * A paginated page of chat messages.
 *
 * @property messages The list of chat messages on this page.
 * @property paging Pagination metadata for navigating additional pages.
 */
data class ChatMessagesPage(
    val messages : List<ChatMessage>,
    val paging : PagingInfo
) : AccelByteResponse {
    companion object
    {
        fun fromJson(json : Json) : ChatMessagesPage = ChatMessagesPage(
            messages = json.optJsonList("data")
                .takeIf { it.isNotEmpty() }
                ?.map(ChatMessage::fromJson)
                ?: json.optJsonList("messages").map(ChatMessage::fromJson),
            paging = PagingInfo.fromJson(json.requireJson("paging"))
        )
    }
}

/**
 * Represents a muted topic channel.
 *
 * @property topic The chat topic that is muted.
 * @property mutedAt Timestamp when the topic was muted.
 * @property reason The reason for muting the topic.
 * @property mutedBy The user who muted the topic.
 * @property expiresAt Timestamp when the mute expires.
 * @property status The current status of the mute.
 */
data class MutedTopic(
    val topic : String,
    val mutedAt : String?,
    val reason : String?,
    val mutedBy : String?,
    val expiresAt : String?,
    val status : String?
) : AccelByteResponse {
    companion object
    {
        fun fromJson(json : Json) : MutedTopic = MutedTopic(
            topic = json.requireString("topic"),
            mutedAt = json.optString("mutedAt"),
            reason = json.optString("reason"),
            mutedBy = json.optString("mutedBy"),
            expiresAt = json.optString("expiresAt"),
            status = json.optString("status")
        )
    }
}

/**
 * A list of muted topic channels.
 *
 * @property topics The list of muted topics.
 */
data class MutedTopicsResponse(
    val topics : List<MutedTopic>
) : AccelByteResponse {
    companion object
    {
        fun fromJson(json : Json) : MutedTopicsResponse = MutedTopicsResponse(
            topics = json.optJsonList("topics").map(MutedTopic::fromJson)
        )
    }
}
