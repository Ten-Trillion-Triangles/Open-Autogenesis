@file:JsModule("@accelbyte/sdk-qosmanager")
@file:JsNonModule

package org.ttt.autogenesis.accelbyte.modules

import kotlin.js.Json
import kotlin.js.Promise
import org.ttt.autogenesis.accelbyte.AccelByteSDK
import org.ttt.autogenesis.accelbyte.SdkSetConfigParam

external object QosmModulePackage {
    val Qosmanager: QosmNamespace
}

external interface QosmNamespace {
    val AdminAdminApi: AdminAdminApiFactory
    val PublicApi: PublicApiFactory
    val ServerApi: QosmServerApiFactory
}

external interface AdminAdminApiFactory {
    operator fun invoke(sdk: AccelByteSDK, args: SdkSetConfigParam = definedExternally): AdminAdminApi
}

external interface AdminAdminApi {
    fun deleteServer_ByRegion(region: String): Promise<Json>
    fun createAlia_ByRegion(region: String, data: Json): Promise<Json>
    fun patchServer_ByRegion(region: String, data: Json): Promise<Json>
}

external interface PublicApiFactory {
    operator fun invoke(sdk: AccelByteSDK, args: SdkSetConfigParam = definedExternally): PublicApi
}

external interface PublicApi {
    fun getQos(): Promise<Json>
    fun getQos_ByNS(queryParams: Json? = definedExternally): Promise<Json>
}

external interface QosmServerApiFactory {
    operator fun invoke(sdk: AccelByteSDK, args: SdkSetConfigParam = definedExternally): QosmServerApi
}

external interface QosmServerApi {
    fun createServerHeartbeat(data: Json): Promise<Json>
}