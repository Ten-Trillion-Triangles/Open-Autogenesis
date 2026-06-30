package org.ttt.autogenesis.accelbyte.models

import kotlin.js.Json
import kotlin.js.json

/**
 * Request to join an existing game session using its short join code.
 *
 * @property code the alphanumeric join code that uniquely identifies the session.
 */
data class JoinCodeRequest(val code : String) : AccelByteRequest
{
    override fun toJson() : Json = json("code" to code)
}

/**
 * A comprehensive filter specification for the game session browser.
 *
 * Combine any number of optional fields to narrow the session list. The SDK only
 * includes fields with non-null values in the query string, so omitting a field
 * is equivalent to not filtering by that dimension.
 *
 * @property configurationName filter by the deployment/configuration name.
 * @property dsPodName filter by the pod name to which the session is currently deployed.
 * @property fromTime ISO-8601 timestamp — return sessions created on or after this time.
 * @property gameMode filter by the game mode variant (e.g., "DEATHMATCH", "CTF").
 * @property isPersistent when true, returns persistent sessions; false returns temporary ones.
 *                        Null = no persistence filter.
 * @property isSoftDeleted when true, includes soft-deleted sessions. Null (default) excludes them.
 * @property joinability filter by joinability state (e.g., "OPEN", "CLOSED", "INVITE_ONLY").
 * @property limit maximum number of session records per page.
 * @property matchPool filter by the match pool name used to route matchmaking for the session.
 * @property memberId filter to sessions that contain this user as a member.
 * @property offset number of records to skip for pagination.
 * @property order sort direction: "ASC" or "DESC".
 * @property orderBy field to sort results by (e.g., "createdAt", "startTime").
 * @property sessionId return only the session with this exact ID.
 * @property status filter by the session lifecycle status (e.g., "IN_PROGRESS", "ENDED").
 * @property statusV2 alternate status field used by newer API versions.
 * @property toTime ISO-8601 timestamp — return sessions created on or before this time.
 */
data class SessionBrowserFilter(
    val configurationName : String? = null,
    val dsPodName : String? = null,
    val fromTime : String? = null,
    val gameMode : String? = null,
    val isPersistent : Boolean? = null,
    val isSoftDeleted : Boolean? = null,
    val joinability : String? = null,
    val limit : Int? = null,
    val matchPool : String? = null,
    val memberId : String? = null,
    val offset : Int? = null,
    val order : String? = null,
    val orderBy : String? = null,
    val sessionId : String? = null,
    val status : String? = null,
    val statusV2 : String? = null,
    val toTime : String? = null
) : AccelByteRequest {
    override fun toJson() : Json = buildQuery().first

    /**
     * Returns the query as a standalone URL query string, or null if no filters are set.
     *
     * This is the variant to use when the SDK method accepts query params separately from
     * the request body. If all filter fields are null, returns null so callers can skip
     * passing a filter object altogether.
     *
     * @return a [Json] query fragment, or null when every field is null (no-op filter).
     */
    fun toQueryParams() : Json? = buildQuery().let { (query, hasFilter) -> if (hasFilter) query else null }

    private fun buildQuery() : Pair<Json, Boolean>
    {
        val query = json()
        var hasFilter = false

        fun Json.addIfNotNull(key : String, value : Any?)
        {
            if (value != null) {
                this[key] = value
                hasFilter = true
            }
        }

        query.addIfNotNull("configurationName", configurationName)
        query.addIfNotNull("dsPodName", dsPodName)
        query.addIfNotNull("fromTime", fromTime)
        query.addIfNotNull("gameMode", gameMode)
        query.addIfNotNull("isPersistent", isPersistent)
        query.addIfNotNull("isSoftDeleted", isSoftDeleted)
        query.addIfNotNull("joinability", joinability)
        query.addIfNotNull("limit", limit)
        query.addIfNotNull("matchPool", matchPool)
        query.addIfNotNull("memberID", memberId)
        query.addIfNotNull("offset", offset)
        query.addIfNotNull("order", order)
        query.addIfNotNull("orderBy", orderBy)
        query.addIfNotNull("sessionID", sessionId)
        query.addIfNotNull("status", status)
        query.addIfNotNull("statusV2", statusV2)
        query.addIfNotNull("toTime", toTime)

        return query to hasFilter
    }
}

/**
 * Request payload for creating a new game session via [org.ttt.autogenesis.accelbyte.facades.SessionFacade.createGameSession].
 *
 * All fields are required. The session is created in the specified namespace, region,
 * and game mode, with the specified maximum player capacity.
 *
 * @property gameMode the game mode variant for this session (e.g., "DEATHMATCH", "CTF").
 * @property maxPlayers the maximum number of players that may join this session.
 * @property namespace the platform namespace in which to create the session.
 * @property region the deployment region for this session (e.g., "us-west", "eu-central").
 */
data class CreateGameSessionRequest(
    val gameMode : String,
    val maxPlayers : Int,
    val namespace : String,
    val region : String
) : AccelByteRequest {
    override fun toJson() : Json = json(
        "game_mode" to gameMode,
        "max_players" to maxPlayers,
        "namespace" to namespace,
        "region" to region
    )
}

/**
 * Request to append a list of users as members (or an entire team roster) to an existing session.
 *
 * Used when pre-populating a session roster before it is opened to general join requests.
 *
 * @property members a list of user identifiers to add to the session as members.
 */
data class AppendTeamRequest(val members : List<String>) : AccelByteRequest
{
    override fun toJson() : Json = json("members" to members)
}

/**
 * Request body for sending an invitation to a specific user to join a game session.
 *
 * @property userId the target user to invite.
 * @property note an optional personal note attached to the invitation.
 */
data class SessionInviteRequest(val userId : String, val note : String? = null) : AccelByteRequest
{
    override fun toJson() : Json = json("user_id" to userId, "note" to note)
}
