@file:JsModule("@accelbyte/sdk-gametelemetry")
@file:JsNonModule

package org.ttt.autogenesis.accelbyte.modules

import kotlin.js.Json
import kotlin.js.Promise
import org.ttt.autogenesis.accelbyte.AccelByteSDK
import org.ttt.autogenesis.accelbyte.SdkSetConfigParam

external object GameTelemetryModulePackage {
    val GameTelemetry: GameTelemetryNamespace
}

external interface GameTelemetryNamespace {
    val GametelemetryOperationsApi: GametelemetryOperationsApiFactory
    val TelemetryAdminApi: TelemetryAdminApiFactory
}

external interface GametelemetryOperationsApiFactory {
    operator fun invoke(sdk: AccelByteSDK, args: SdkSetConfigParam = definedExternally): GametelemetryOperationsApi
}

external interface GametelemetryOperationsApi {
    fun createProtectedEvent(data: Array<Json>): Promise<Json>
    fun getPlaytimeProtected_BySteamId(steamId: String): Promise<Json>
    fun updatePlaytimeProtected_BySteamId_ByPlaytime(steamId: String, playtime: String): Promise<Json>
}

external interface TelemetryAdminApiFactory {
    operator fun invoke(sdk: AccelByteSDK, args: SdkSetConfigParam = definedExternally): TelemetryAdminApi
}

external interface TelemetryAdminApi {
    fun getNamespaces(): Promise<Json>
    fun getEvents(queryParams: Json? = definedExternally): Promise<Json>
}