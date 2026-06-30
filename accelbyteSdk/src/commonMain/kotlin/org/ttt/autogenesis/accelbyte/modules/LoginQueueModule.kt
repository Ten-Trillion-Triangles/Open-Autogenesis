@file:JsModule("@accelbyte/sdk-login-queue")
@file:JsNonModule

package org.ttt.autogenesis.accelbyte.modules

import kotlin.js.Json
import kotlin.js.Promise
import org.ttt.autogenesis.accelbyte.AccelByteSDK
import org.ttt.autogenesis.accelbyte.SdkSetConfigParam

external val LoginQueue : LoginQueueNamespace

external object LoginQueueModulePackage {
    val LoginQueue: LoginQueueNamespace
}

external interface LoginQueueNamespace
{
    fun V1AdminApi(sdk : AccelByteSDK, args : SdkSetConfigParam = definedExternally) : V1AdminApi
    fun TicketV1Api(sdk : AccelByteSDK, args : SdkSetConfigParam = definedExternally) : TicketV1Api
}

external interface V1AdminApi
{
    fun getConfig() : Promise<Json>
    fun updateConfig(data : Json) : Promise<Json>
    fun getStatus() : Promise<Json>
}

external interface TicketV1Api
{
    fun deleteTicket() : Promise<Json>
    fun getTicket() : Promise<Json>
}
