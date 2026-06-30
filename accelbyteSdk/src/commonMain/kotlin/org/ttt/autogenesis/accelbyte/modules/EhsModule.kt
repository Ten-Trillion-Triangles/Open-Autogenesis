@file:JsModule("@accelbyte/sdk-ehs")
@file:JsNonModule

package org.ttt.autogenesis.accelbyte.modules

import kotlin.js.Json
import kotlin.js.Promise
import org.ttt.autogenesis.accelbyte.AccelByteSDK
import org.ttt.autogenesis.accelbyte.SdkSetConfigParam

external val Ehs : EhsNamespace

external interface EhsNamespace
{
    fun UtilityAdminApi(sdk : AccelByteSDK, args : SdkSetConfigParam = definedExternally) : UtilityAdminApi
    fun AccessApi(sdk : AccelByteSDK, args : SdkSetConfigParam = definedExternally) : AccessApi
}

external interface UtilityAdminApi
{
    fun getReflection(queryParams : Json = definedExternally) : Promise<Json>
}

external interface AccessApi
{
    fun getToken_ByApp(app : String) : Promise<Json>
}
