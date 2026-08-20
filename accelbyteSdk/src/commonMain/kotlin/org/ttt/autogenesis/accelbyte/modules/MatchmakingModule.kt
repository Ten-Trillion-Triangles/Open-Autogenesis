@file:JsModule("@accelbyte/sdk-matchmaking")
@file:JsNonModule

package org.ttt.autogenesis.accelbyte.modules

import kotlin.js.Json
import kotlin.js.Promise
import org.ttt.autogenesis.accelbyte.AccelByteSDK
import org.ttt.autogenesis.accelbyte.SdkSetConfigParam

external object MatchmakingModulePackage {
    val Matchmaking: MatchmakingNamespace
}

external interface MatchmakingNamespace {
    val MatchTicketsApi: MatchTicketsApiFactory
}

external interface MatchTicketsApiFactory {
    operator fun invoke(sdk: AccelByteSDK, args: SdkSetConfigParam = definedExternally): MatchTicketsApi
}

external interface MatchTicketsApi {
    fun createMatchTicket(data: Json): Promise<Json>
    fun getMatchTicketsMe(queryParams: Json = definedExternally): Promise<Json>
    fun deleteMatchTicket_ByTicketid(ticketid: String): Promise<Json>
    fun getMatchTicket_ByTicketid(ticketid: String): Promise<Json>
}