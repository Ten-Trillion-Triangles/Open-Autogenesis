@file:JsModule("@accelbyte/sdk-ugc")
@file:JsNonModule

package org.ttt.autogenesis.accelbyte.modules

import kotlin.js.Json
import kotlin.js.Promise
import org.ttt.autogenesis.accelbyte.AccelByteSDK
import org.ttt.autogenesis.accelbyte.SdkSetConfigParam

external object UgcModulePackage {
    val Ugc: UgcNamespace
}

external interface UgcNamespace {
    val PublicContentV2Api: PublicContentV2ApiFactory
    val PublicFollowApi: PublicFollowApiFactory
}

external interface PublicContentV2ApiFactory {
    operator fun invoke(sdk: AccelByteSDK, args: SdkSetConfigParam = definedExternally): PublicContentV2Api
}

external interface PublicContentV2Api {
    fun getContentById(contentId: String): Promise<Json>
    fun getContents(queryParams: Json = definedExternally): Promise<Json>
}

external interface PublicFollowApiFactory {
    operator fun invoke(sdk: AccelByteSDK, args: SdkSetConfigParam = definedExternally): PublicFollowApi
}

external interface PublicFollowApi {
    fun follow(authorId: String): Promise<Json>
    fun unfollow(authorId: String): Promise<Json>
}
