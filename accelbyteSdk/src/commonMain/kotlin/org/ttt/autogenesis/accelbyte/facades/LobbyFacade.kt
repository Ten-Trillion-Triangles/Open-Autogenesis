package org.ttt.autogenesis.accelbyte.facades

import kotlin.js.Json
import kotlin.js.Promise
import org.ttt.autogenesis.accelbyte.AccelByteSdkInstance
import org.ttt.autogenesis.accelbyte.models.LobbyCreatePartyRequest
import org.ttt.autogenesis.accelbyte.models.LobbyCreatePartyResponse
import org.ttt.autogenesis.accelbyte.models.LobbyLimitRequest
import org.ttt.autogenesis.accelbyte.models.LobbyMessageListResponse
import org.ttt.autogenesis.accelbyte.models.PartyDataResponse
import org.ttt.autogenesis.accelbyte.modules.LobbyModulePackage
import org.ttt.autogenesis.accelbyte.modules.LobbyOperationsApi
import org.ttt.autogenesis.accelbyte.modules.PartyApi
import org.ttt.autogenesis.accelbyte.util.mapJson
import org.ttt.autogenesis.accelbyte.util.propagateJsErrors

/**
 * Thrown when a join request targets a party that has reached its maximum member capacity.
 */
class PartyFullException(message: String = "Party is full") : Exception(message)

/**
 * Thrown when a party operation targets an ID that does not correspond to an existing active party.
 */
class PartyNotFoundException(message: String = "Party not found") : Exception(message)

/**
 * Thrown when the current user has already joined the target party.
 */
class AlreadyJoinedException(message: String = "Already joined this party") : Exception(message)

/**
 * A thin facade over the AccelByte Lobby module's party operations.
 * Manages party lifecycle: creation, joining by ID, leaving, attribute updates, and party message retrieval.
 * Custom exceptions [PartyFullException], [PartyNotFoundException], and [AlreadyJoinedException]
 * express common failure modes from the JoinParty call.
 */
class LobbyFacade(private val sdk : AccelByteSdkInstance)
{
    private val partyApi : PartyApi
        get() = LobbyModulePackage.Lobby.PartyApi(sdk.rawSdk)

    private val lobbyOperationsApi : LobbyOperationsApi
        get() = LobbyModulePackage.Lobby.LobbyOperationsApi(sdk.rawSdk)

    /**
     * @param partyId target party
     * @return [PartyDataResponse]
     */
    fun getParty(partyId : String) : Promise<PartyDataResponse> =
        partyApi.getPartyParty_ByPartyId(partyId)
            .propagateJsErrors()
            .mapJson(PartyDataResponse::fromJson)

    /**
     * Updates the party's max player limit.
     */
    fun updatePartyLimit(partyId : String, request : LobbyLimitRequest) : Promise<PartyDataResponse> =
        partyApi.updateLimitParty_ByPartyId(partyId, request.toJson())
            .propagateJsErrors()
            .mapJson(PartyDataResponse::fromJson)

    /**
     * @param partyId target party
     * @param attributes JSON blob of custom key-value attributes
     * @return updated [PartyDataResponse]
     */
    fun updatePartyAttributes(partyId : String, attributes : Json) : Promise<PartyDataResponse> =
        partyApi.updateAttributeParty_ByPartyId(partyId, attributes)
            .propagateJsErrors()
            .mapJson(PartyDataResponse::fromJson)

    /**
     * @param request [LobbyCreatePartyRequest]
     * @return [LobbyCreatePartyResponse]
     */
    fun createParty(request : LobbyCreatePartyRequest) : Promise<LobbyCreatePartyResponse> =
        partyApi.createParty(request.toJson())
            .propagateJsErrors()
            .mapJson(LobbyCreatePartyResponse::fromJson)

    /**
     * @param partyId target party
     * @param userId current user ID
     * @return [PartyDataResponse]
     */
    fun joinParty(partyId : String, userId : String) : Promise<PartyDataResponse> =
        partyApi.joinParty(partyId, userId)
            .propagateJsErrors()
            .mapJson(PartyDataResponse::fromJson)

    /**
     * @param partyId target party
     * @param userId user leaving
     * @return [PartyDataResponse]
     */
    fun leaveParty(partyId : String, userId : String) : Promise<PartyDataResponse> =
        partyApi.leaveParty(partyId, userId)
            .propagateJsErrors()
            .mapJson(PartyDataResponse::fromJson)

    /**
     * @return [LobbyMessageListResponse] of recent lobby messages
     */
    fun getMessages() : Promise<LobbyMessageListResponse> =
        lobbyOperationsApi.getMessages()
            .propagateJsErrors()
            .mapJson(LobbyMessageListResponse::fromJson)
}
