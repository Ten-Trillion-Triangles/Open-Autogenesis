package org.ttt.autogenesis.accelbyte.facades

import kotlin.js.Promise
import org.ttt.autogenesis.accelbyte.AccelByteSdkInstance
import org.ttt.autogenesis.accelbyte.models.AppendTeamRequest
import org.ttt.autogenesis.accelbyte.models.CreateGameSessionRequest
import org.ttt.autogenesis.accelbyte.models.GameSessionDetailResponse
import org.ttt.autogenesis.accelbyte.models.JoinCodeRequest
import org.ttt.autogenesis.accelbyte.models.SessionInviteRequest
import org.ttt.autogenesis.accelbyte.models.SessionInviteResponseModel
import org.ttt.autogenesis.accelbyte.modules.GameSessionApi
import org.ttt.autogenesis.accelbyte.modules.SessionModulePackage
import org.ttt.autogenesis.accelbyte.util.mapJson
import org.ttt.autogenesis.accelbyte.util.propagateJsErrors

class SessionFacade(private val sdk : AccelByteSdkInstance)
{
    private val gameSessionApi : GameSessionApi
        get() = SessionModulePackage.Session.GameSessionApi(sdk.rawSdk)

    /**
     * Creates a game session and returns the session metadata, including members, teams, and server details.
     */
    fun createGameSession(request : CreateGameSessionRequest) : Promise<GameSessionDetailResponse> =
        gameSessionApi.createGamesession(request.toJson())
            .propagateJsErrors()
            .mapJson(GameSessionDetailResponse::fromJson)

    /**
     * Joins a session via join code and returns the updated session metadata.
     */
    fun joinByCode(material : JoinCodeRequest) : Promise<GameSessionDetailResponse> =
        gameSessionApi.createGamesessionJoinCode(material.toJson())
            .propagateJsErrors()
            .mapJson(GameSessionDetailResponse::fromJson)

    /**
     * Appends members or teams to an active session and returns the session details after the update.
     */
    fun appendTeam(sessionId : String, request : AppendTeamRequest) : Promise<GameSessionDetailResponse> =
        gameSessionApi.updateTeam_BySessionId(sessionId, request.toJson())
            .propagateJsErrors()
            .mapJson(GameSessionDetailResponse::fromJson)

    /**
     * Sends an invite to another player and returns the invite confirmation data.
     */
    fun inviteToSession(sessionId : String, invite : SessionInviteRequest) : Promise<SessionInviteResponseModel> =
        gameSessionApi.createInvite_BySessionId(sessionId, invite.toJson())
            .propagateJsErrors()
            .mapJson(SessionInviteResponseModel::fromJson)
}