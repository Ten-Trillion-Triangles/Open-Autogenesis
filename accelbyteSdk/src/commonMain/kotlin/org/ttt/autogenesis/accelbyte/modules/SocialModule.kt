@file:JsModule("@accelbyte/sdk-social")
@file:JsNonModule

package org.ttt.autogenesis.accelbyte.modules

import kotlin.js.Json
import kotlin.js.Promise
import org.ttt.autogenesis.accelbyte.AccelByteSDK
import org.ttt.autogenesis.accelbyte.SdkSetConfigParam

external object SocialModulePackage {
    val Social: SocialNamespace
}

external interface SocialNamespace {
    val UserStatisticApi: UserStatisticApiFactory
}

external interface UserStatisticApiFactory {
    operator fun invoke(sdk: AccelByteSDK, args: SdkSetConfigParam = definedExternally): UserStatisticApi
}

external interface UserStatisticApi {
    fun getUsersMeStatitems(queryParams: Json = definedExternally): Promise<Json>
    fun patchStatitemValueBulk(data: Json): Promise<Json>
    fun getStatitems_ByUserId(userId: String, queryParams: Json = definedExternally): Promise<Json>
}
