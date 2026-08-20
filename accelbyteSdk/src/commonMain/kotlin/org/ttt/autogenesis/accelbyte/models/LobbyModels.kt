package org.ttt.autogenesis.accelbyte.models

import kotlin.js.Json
import kotlin.js.json

/**
 * Request for updating the current party member cap.
 *
 * @property maxPlayers The new maximum player count for the party.
 */
data class LobbyLimitRequest(val maxPlayers : Int) : AccelByteRequest
{
    override fun toJson() : Json = json("max_players" to maxPlayers)
}

/**
 * Request to join an existing party.
 *
 * @property partyId The unique identifier of the party to join.
 * @property userId The user identity requesting to join.
 */
data class JoinPartyRequest(val partyId : String, val userId : String) : AccelByteRequest
{
    override fun toJson() : Json = json("partyId" to partyId, "userId" to userId)
}

/**
 * Request payload for creating a new party lobby.
 *
 * @property namespace The namespace in which to create the party.
 * @property partyName Optional display name for the party.
 * @property maxPlayers The initial maximum player count.
 */
data class LobbyCreatePartyRequest(
    val namespace : String,
    val partyName : String?,
    val maxPlayers : Int
) : AccelByteRequest
{
    override fun toJson() : Json = json(
        "namespace" to namespace,
        "party_name" to partyName,
        "max_players" to maxPlayers
    )
}