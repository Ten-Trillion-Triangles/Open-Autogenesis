@file:JsModule("@accelbyte/sdk-lobby")
@file:JsNonModule

package org.ttt.autogenesis.accelbyte.modules

import kotlin.js.Json
import kotlin.js.Promise
import org.ttt.autogenesis.accelbyte.AccelByteSDK
import org.ttt.autogenesis.accelbyte.SdkSetConfigParam

external object LobbyModulePackage {
    val Lobby: LobbyNamespace
}

external interface LobbyNamespace {
    val PartyApi: PartyApiFactory
    val LobbyOperationsApi: LobbyOperationsApiFactory
}

external interface PartyApiFactory {
    operator fun invoke(sdk: AccelByteSDK, args: SdkSetConfigParam = definedExternally): PartyApi
}

external interface PartyApi {
    fun createParty(data: Json): Promise<Json>
    fun getPartyParty_ByPartyId(partyId: String): Promise<Json>
    fun updateLimitParty_ByPartyId(partyId: String, data: Json): Promise<Json>
    fun updateAttributeParty_ByPartyId(partyId: String, data: Json): Promise<Json>
    fun joinParty(partyId: String, userId: String): Promise<Json>
    fun leaveParty(partyId: String, userId: String): Promise<Json>
}

external interface LobbyOperationsApiFactory {
    operator fun invoke(sdk: AccelByteSDK, args: SdkSetConfigParam = definedExternally): LobbyOperationsApi
}

external interface LobbyOperationsApi {
    fun getMessages(): Promise<Json>
}
