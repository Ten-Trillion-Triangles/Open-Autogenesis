@file:JsModule("@accelbyte/sdk-extend-app-ui")
@file:JsNonModule

package org.ttt.autogenesis.accelbyte.modules

import kotlin.js.Json
import kotlin.js.Promise
import org.ttt.autogenesis.accelbyte.AccelByteSDK
import org.ttt.autogenesis.accelbyte.SdkSetConfigParam

external val ExtendAppUi : ExtendAppUiNamespace

external object ExtendAppUiModulePackage {
    val ExtendAppUi: ExtendAppUiNamespace
}

external interface ExtendAppUiNamespace
{
    fun UtilityAdminApi(sdk : AccelByteSDK, args : SdkSetConfigParam = definedExternally) : UtilityAdminApiFromEhs
    fun AccessApi(sdk : AccelByteSDK, args : SdkSetConfigParam = definedExternally) : AccessApiFromEhs
}

external interface UtilityAdminApiFromEhs
{
    fun getReflection(queryParams : Json = definedExternally) : Promise<Json>
}

external interface AccessApiFromEhs
{
    fun getToken_ByApp(app : String) : Promise<Json>
}