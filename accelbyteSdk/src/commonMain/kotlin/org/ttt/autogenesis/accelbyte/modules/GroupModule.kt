@file:JsModule("@accelbyte/sdk-groups")
@file:JsNonModule

package org.ttt.autogenesis.accelbyte.modules

import kotlin.js.Json
import kotlin.js.Promise
import org.ttt.autogenesis.accelbyte.AccelByteSDK
import org.ttt.autogenesis.accelbyte.SdkSetConfigParam

external object GroupModulePackage {
    val Group: GroupNamespace
}

external interface GroupNamespace {
    val GroupApi: GroupApiFactory
}

external interface GroupApiFactory {
    operator fun invoke(sdk: AccelByteSDK, args: SdkSetConfigParam = definedExternally): GroupApi
}

external interface GroupApi {
    fun getGroups(queryParams: Json = definedExternally): Promise<Json>
    fun createGroup(data: Json): Promise<Json>
    fun deleteGroup_ByGroupId(groupId: String): Promise<Json>
    fun getGroup_ByGroupId(groupId: String): Promise<Json>
}