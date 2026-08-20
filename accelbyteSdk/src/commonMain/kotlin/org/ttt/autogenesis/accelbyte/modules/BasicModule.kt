@file:JsModule("@accelbyte/sdk-basic")
@file:JsNonModule

package org.ttt.autogenesis.accelbyte.modules

import kotlin.js.Json
import kotlin.js.Promise
import org.ttt.autogenesis.accelbyte.AccelByteSDK
import org.ttt.autogenesis.accelbyte.SdkSetConfigParam

external val Basic : BasicNamespace

external interface BasicNamespace {
    fun NamespaceApi(sdk: AccelByteSDK, args: SdkSetConfigParam = definedExternally): NamespaceApi
    fun FileUploadApi(sdk: AccelByteSDK, args: SdkSetConfigParam = definedExternally): FileUploadApi
    fun UserProfileApi(sdk: AccelByteSDK, args: SdkSetConfigParam = definedExternally): UserProfileApi
}

external interface NamespaceApi {
    fun getNamespaces(queryParams: Json = definedExternally): Promise<Json>
    fun getNamespace_ByNamespace(): Promise<Json>
    fun getPublisher(): Promise<Json>
}

external interface FileUploadApi {
    fun createFile_ByUserId(userId: String, queryParams: Json): Promise<Json>
    fun createFile_ByFolder(folder: String, queryParams: Json): Promise<Json>
}

external interface UserProfileApi {
    fun getProfilesPublic(queryParams: Json): Promise<Json>
    fun getUsersMeProfiles(): Promise<Json>
}