package org.ttt.autogenesis.accelbyte.facades

import kotlin.js.Json
import kotlin.js.Promise
import org.ttt.autogenesis.accelbyte.AccelByteSdkInstance
import org.ttt.autogenesis.accelbyte.models.ChatMessagesPage
import org.ttt.autogenesis.accelbyte.models.ChatQueryParams
import org.ttt.autogenesis.accelbyte.models.MuteRequest
import org.ttt.autogenesis.accelbyte.models.MutedTopicsResponse
import org.ttt.autogenesis.accelbyte.models.TopicModerationRequest
import org.ttt.autogenesis.accelbyte.modules.ChatModulePackage
import org.ttt.autogenesis.accelbyte.modules.TopicApi
import org.ttt.autogenesis.accelbyte.util.mapJson
import org.ttt.autogenesis.accelbyte.util.propagateJsErrors

/**
 * Wraps Chat-specific topic operations (history, mute/ban). Methods return raw JSON, typically containing
 * message arrays, operation results, or error payloads; callers should map those into their UI models.
 *
 * @param sdk AccelByte SDK instance used to resolve chat APIs.
 */
class ChatFacade(private val sdk : AccelByteSdkInstance)
{
    private val topicApi : TopicApi
        get() = ChatModulePackage.Chat.TopicApi(sdk.rawSdk)

    /**
     * Retrieves all muted topics for the current namespace.
     *
     * @return `Promise<MutedTopicsResponse>` resolving to the JSON list of muted topics.
     * @throws NetworkException on connection failures
     * @throws AuthenticationException if session token is invalid
     * @throws AuthorizationException if the caller lacks permission to view muted topics
     */
    fun getMutedTopics() : Promise<MutedTopicsResponse> =
        topicApi.getMuted()
            .propagateJsErrors()
            .mapJson(MutedTopicsResponse::fromJson)

    /**
     * Fetches chat messages for a topic. The response consists of a page of chat entries.
     *
     * @param topic topic identifier (e.g., `global-chat`).
     * @param params paging arguments.
     * @return `Promise<ChatMessagesPage>` resolving to the requested page of chat messages.
     * @throws NetworkException on connection failures
     * @throws AuthenticationException if session token is invalid
     * @throws AuthorizationException if the topic cannot be accessed with the current session
     */
    fun getTopicMessages(topic : String, params : ChatQueryParams = ChatQueryParams()) : Promise<ChatMessagesPage> =
        topicApi.getChats_ByTopic(topic, params.toJson())
            .propagateJsErrors()
            .mapJson(ChatMessagesPage::fromJson)

    /**
     * Mutes a specific topic member.
     *
     * @param topic topic identifier where the mute should be applied
     * @param request request containing the `userId` to mute.
     * @return `Promise<Unit>` that completes when the mute is acknowledged.
     * @throws NetworkException on connection failures
     * @throws AuthenticationException if session token is invalid
     * @throws AuthorizationException if the caller cannot perform mutes on the topic
     */
    fun muteTopic(topic : String, request : MuteRequest) : Promise<Unit> =
        topicApi.updateMute_ByTopic(topic, request.toJson())
            .propagateJsErrors()
            .mapJson { }

    /**
     * Unmutes a previously muted member.
     *
     * @param topic topic identifier where the unmute should be applied
     * @param request request identifying the member to unmute
     * @return `Promise<Unit>` after the unmute completes.
     * @throws NetworkException on connection failures
     * @throws AuthenticationException if session token is invalid
     * @throws AuthorizationException if the caller cannot perform unmutes on the topic
     */
    fun unmuteTopic(topic : String, request : MuteRequest) : Promise<Unit> =
        topicApi.updateUnmute_ByTopic(topic, request.toJson())
            .propagateJsErrors()
            .mapJson { }

    /**
     * Bans a topic member with an optional reason.
     *
     * @param topic topic identifier to apply the ban
     * @param data moderation request including userId and optional reason
     * @return `Promise<Unit>` once the API acknowledges the ban.
     * @throws NetworkException on connection failures
     * @throws AuthenticationException if session token is invalid
     * @throws AuthorizationException if the caller cannot ban members on the topic
     */
    fun banMember(topic : String, data : TopicModerationRequest) : Promise<Unit> =
        topicApi.updateBanMember_ByTopic(topic, data.toJson())
            .propagateJsErrors()
            .mapJson { }

    /**
     * Restores a member previously banned from a topic.
     *
     * @param topic topic identifier to apply the unban
     * @param data moderation request including userId and optional reason
     * @return `Promise<Unit>` once the unban completes.
     * @throws NetworkException on connection failures
     * @throws AuthenticationException if session token is invalid
     * @throws AuthorizationException if the caller cannot unban members on the topic
     */
    fun unbanMember(topic : String, data : TopicModerationRequest) : Promise<Unit> =
        topicApi.updateUnbanMember_ByTopic(topic, data.toJson())
            .propagateJsErrors()
            .mapJson { }

    /**
     * Deletes a single chat message from a topic.
     *
     * @param topic topic identifier containing the chat entry
     * @param chatId identifier of the chat message to delete
     * @return `Promise<Unit>` once the deletion completes
     * @throws NetworkException on connection failures
     * @throws AuthenticationException if session token is invalid
     * @throws AuthorizationException if the caller is not permitted to delete the message
     */
    fun deleteMessage(topic : String, chatId : String) : Promise<Unit> =
        topicApi.deleteChat_ByTopic_ByChatId(topic, chatId)
            .propagateJsErrors()
            .mapJson { }
}
