package org.ttt.autogenesis.accelbyte.facades

import kotlin.js.Promise
import kotlin.js.undefined
import org.ttt.autogenesis.accelbyte.AccelByteSdkInstance
import org.ttt.autogenesis.accelbyte.models.GameSessionDetailResponse
import org.ttt.autogenesis.accelbyte.models.GameSessionListResponse
import org.ttt.autogenesis.accelbyte.models.SessionBrowserFilter
import org.ttt.autogenesis.accelbyte.modules.SessionModulePackage
import org.ttt.autogenesis.accelbyte.util.mapJson
import org.ttt.autogenesis.accelbyte.util.propagateJsErrors

/**
 * A thin facade over the AccelByte Session module's game session browser endpoints.
 * Provides admin and public access to game session discovery, detail retrieval, and
 * session joining via ID.
 */
class SessionBrowserFacade(private val sdk : AccelByteSdkInstance)
{
    private val module get() = SessionModulePackage.Session
    private val adminApi get() = module.GameSessionAdminApi(sdk.rawSdk)
    private val publicApi get() = module.GameSessionApi(sdk.rawSdk)

    /**
     * @param filter [SessionBrowserFilter]; supply null fields to leave them unfiltered; @return [GameSessionListResponse]
     */
    fun getGameSessions(filter : SessionBrowserFilter = SessionBrowserFilter()) : Promise<GameSessionListResponse> {
        val params = filter.toQueryParams()
        return adminApi.getGamesessions(params ?: undefined)
            .propagateJsErrors()
            .mapJson(GameSessionListResponse::fromJson)
    }

    /**
     * @param sessionId the session to retrieve; @return [GameSessionDetailResponse]
     */
    fun getGameSessionDetails(sessionId : String) : Promise<GameSessionDetailResponse> =
        publicApi.getGamesession_BySessionId(sessionId)
            .propagateJsErrors()
            .mapJson(GameSessionDetailResponse::fromJson)

    /**
     * Sends a join request for the given session. The SDK handles the current user's identity automatically.
     * @param sessionId the session to join; @return the updated [GameSessionDetailResponse] after joining;
     * @throws propagateJsErrors on join failure (e.g., session full, locked)
     */
    fun joinGameSession(sessionId : String) : Promise<GameSessionDetailResponse> =
        publicApi.createJoin_BySessionId(sessionId)
            .propagateJsErrors()
            .mapJson(GameSessionDetailResponse::fromJson)
}