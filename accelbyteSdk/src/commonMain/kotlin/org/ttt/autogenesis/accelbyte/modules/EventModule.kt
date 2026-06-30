@file:JsModule("@accelbyte/sdk-event")
@file:JsNonModule

package org.ttt.autogenesis.accelbyte.modules

import kotlin.js.Json
import kotlin.js.Promise
import org.ttt.autogenesis.accelbyte.AccelByteSDK
import org.ttt.autogenesis.accelbyte.SdkSetConfigParam

external object EventModulePackage {
    val Event: EventNamespace
}

external interface EventNamespace {
    val EventApi: EventApiFactory
}

external interface EventApiFactory {
    operator fun invoke(sdk: AccelByteSDK, args: SdkSetConfigParam = definedExternally): EventApi
}

external interface EventApi {
    fun getNamespace_ByNamespace(queryParams: Json): Promise<Json>
    fun getUser_ByUserId(userId: String, queryParams: Json): Promise<Json>
    fun getEventId_ByEventId(eventId: Number, queryParams: Json): Promise<Json>
}
