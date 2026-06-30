package org.ttt.autogenesis.accelbyte.models

import kotlin.js.Json
import kotlin.js.json

/**
 * Parameters for paging through topic chat history.
 *
 * @property limit maximum number of chat messages to fetch per request. Defaults to 20.
 */
data class ChatQueryParams(val limit : Int = 20) : AccelByteRequest
{
    override fun toJson() : Json = json("limit" to limit)
}

/**
 * Payload for banning or unbanning a member from a topic chat channel.
 *
 * @property userId the identifier of the user to ban or unban.
 * @property reason optional free-text reason for the moderation action. Null for unbans.
 */
data class TopicModerationRequest(val userId : String, val reason : String? = null) : AccelByteRequest
{
    override fun toJson() : Json = jsonOf("userId" to userId, "reason" to reason)
}

/**
 * Payload for muting or unmuting a member in a topic chat channel.
 *
 * @property userId the identifier of the user to mute or unmute.
 */
data class MuteRequest(val userId : String) : AccelByteRequest
{
    override fun toJson() : Json = json("userId" to userId)
}

/**
 * Payload for permanent ban flows that require an explicit reason string.
 *
 * This differs from [TopicModerationRequest] in that [reason] is required, not optional.
 *
 * @property userId the identifier of the user being banned.
 * @property reason the mandatory reason string for the ban action.
 */
data class BanRequest(val userId : String, val reason : String) : AccelByteRequest
{
    override fun toJson() : Json = json("userId" to userId, "reason" to reason)
}
