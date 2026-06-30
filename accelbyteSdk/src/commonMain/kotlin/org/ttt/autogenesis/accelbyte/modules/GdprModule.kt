@file:JsModule("@accelbyte/sdk-gdpr")
@file:JsNonModule

package org.ttt.autogenesis.accelbyte.modules

import kotlin.js.Json
import kotlin.js.Promise
import org.ttt.autogenesis.accelbyte.AccelByteSDK
import org.ttt.autogenesis.accelbyte.SdkSetConfigParam

external object GdprModulePackage {
    val Gdpr: GdprNamespace
}

external interface GdprNamespace {
    val DataRetrievalApi: DataRetrievalApiFactory
    val DataDeletionApi: DataDeletionApiFactory
}

external interface DataRetrievalApiFactory {
    operator fun invoke(sdk: AccelByteSDK, args: SdkSetConfigParam = definedExternally): DataRetrievalApi
}

external interface DataRetrievalApi {
    fun getRequests_ByUserId(userId: String, queryParams: Json = definedExternally): Promise<Json>
    fun postRequest_ByUserId(userId: String, data: Json): Promise<Json>
    fun deleteRequest_ByUserId_ByRequestDate(userId: String, requestDate: String): Promise<Json>
    fun postGenerate_ByUserId_ByRequestDate(userId: String, requestDate: String, data: Json): Promise<Json>
}

external interface DataDeletionApiFactory {
    operator fun invoke(sdk: AccelByteSDK, args: SdkSetConfigParam = definedExternally): DataDeletionApi
}

external interface DataDeletionApi {
    fun postRequest_ByUserId(userId: String, data: Json): Promise<Json>
    fun getRequest_ByUserId_ByRequestDate(userId: String, requestDate: String): Promise<Json>
}
