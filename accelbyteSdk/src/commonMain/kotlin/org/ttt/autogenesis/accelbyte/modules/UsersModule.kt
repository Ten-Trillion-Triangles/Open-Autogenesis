@file:JsModule("@accelbyte/sdk-iam")
@file:JsNonModule

package org.ttt.autogenesis.accelbyte.modules

import kotlin.js.Json
import kotlin.js.Promise
import org.ttt.autogenesis.accelbyte.AccelByteSDK
import org.ttt.autogenesis.accelbyte.AxiosResponse
import org.ttt.autogenesis.accelbyte.SdkSetConfigParam

external fun UsersApi(sdk: AccelByteSDK, args: SdkSetConfigParam = definedExternally): UsersApi

external interface UsersApi {
    fun createUserCodeVerify_v3(data: Json): Promise<AxiosResponse>
    fun createUser_v3(data: Json): Promise<AxiosResponse>
    fun createUser_v2(data: Json): Promise<AxiosResponse>
    fun createUserCodeRequest_v3(data: Json): Promise<AxiosResponse>
    fun createUserForgot_ByNS_v3(data: Json): Promise<AxiosResponse>
    fun createUserReset_v3(data: Json): Promise<AxiosResponse>
}