@file:JsModule("@accelbyte/sdk-session")
@file:JsNonModule

package org.ttt.autogenesis.accelbyte.modules

import kotlin.js.Json
import kotlin.js.Promise
import org.ttt.autogenesis.accelbyte.AccelByteSDK
import org.ttt.autogenesis.accelbyte.SdkSetConfigParam

external object SessionModulePackage {
    val Session: SessionNamespace
}

external interface SessionNamespace {
    val GameSessionApi: GameSessionApiFactory
    val GameSessionAdminApi: GameSessionAdminApiFactory
}

external interface GameSessionApiFactory {
    operator fun invoke(sdk: AccelByteSDK, args: SdkSetConfigParam = definedExternally): GameSessionApi
}

external interface GameSessionAdminApiFactory {
    operator fun invoke(sdk: AccelByteSDK, args: SdkSetConfigParam = definedExternally): GameSessionAdminApi
}

external interface GameSessionAdminApi {
    fun getGamesessions(queryParams: Json? = definedExternally): Promise<Json>
}

external interface GameSessionApi {
    fun createGamesession(data: Json, queryParams: Json = definedExternally): Promise<Json>
    fun createGamesessionJoinCode(data: Json): Promise<Json>
    fun updateTeam_BySessionId(sessionId: String, data: Json): Promise<Json>
    fun createInvite_BySessionId(sessionId: String, data: Json): Promise<Json>
    fun getGamesession_BySessionId(sessionId: String): Promise<Json>
    fun createJoin_BySessionId(sessionId: String): Promise<Json>
}