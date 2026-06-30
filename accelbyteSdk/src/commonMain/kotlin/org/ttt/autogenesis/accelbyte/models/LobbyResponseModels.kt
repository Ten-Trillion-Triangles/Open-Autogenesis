package org.ttt.autogenesis.accelbyte.models

import kotlin.js.Json

/**
 * Response model for AccelByte API operation.
 */
data class PartyDataResponse(
    val partyId : String,
    val namespace : String,
    val leader : String,
    val members : List<String>,
    val invitees : List<String>,
    val customAttributes : Json,
    val updatedAt : Int
) : AccelByteResponse {
    companion object
    {
        fun fromJson(json : Json) : PartyDataResponse = PartyDataResponse(
            partyId = json.requireString("partyId"),
            namespace = json.requireString("namespace"),
            leader = json.requireString("leader"),
            members = json.optJsonArray("members")?.mapNotNull { it?.toString() } ?: emptyList(),
            invitees = json.optJsonArray("invitees")?.mapNotNull { it?.toString() } ?: emptyList(),
            customAttributes = json.requireJson("custom_attribute"),
            updatedAt = json.requireInt("updatedAt")
        )
    }
}

/**
 * Response model for AccelByte API operation.
 */
data class LobbyMessageResponse(
    val code : String,
    val codeName : String,
    val section : String,
    val service : String,
    val text : String,
    val attributes : List<String>
) : AccelByteResponse {
    companion object
    {
        fun fromJson(json : Json) : LobbyMessageResponse = LobbyMessageResponse(
            code = json.requireString("Code"),
            codeName = json.requireString("CodeName"),
            section = json.requireString("Section"),
            service = json.requireString("Service"),
            text = json.requireString("Text"),
            attributes = json.optJsonArray("Attributes")?.mapNotNull { it?.toString() } ?: emptyList()
        )
    }
}

/**
 * Response model for AccelByte API operation.
 */
data class LobbyMessageListResponse(
    val messages : List<LobbyMessageResponse>
) : AccelByteResponse {
    companion object
    {
        fun fromJson(json : Json) : LobbyMessageListResponse =
            fromJsonArray((json as? Array<Json>) ?: emptyArray())

        fun fromJsonArray(jsonArray : Array<Json>) : LobbyMessageListResponse =
            LobbyMessageListResponse(jsonArray.map(LobbyMessageResponse::fromJson))
    }
}

data class LobbyCreatePartyResponse(
    val partyId : String,
    val partyName : String?,
    val members : List<String>
) : AccelByteResponse {
    companion object
    {
        fun fromJson(json : Json) : LobbyCreatePartyResponse = LobbyCreatePartyResponse(
            partyId = json.requireString("partyId"),
            partyName = json.optString("partyName"),
            members = json.optJsonArray("members")?.mapNotNull { it?.toString() } ?: emptyList()
        )
    }
}
