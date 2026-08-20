@file:JsModule("@accelbyte/sdk-csm")
@file:JsNonModule

package org.ttt.autogenesis.accelbyte.modules

import kotlin.js.Json
import kotlin.js.Promise
import org.ttt.autogenesis.accelbyte.AccelByteSDK
import org.ttt.autogenesis.accelbyte.SdkSetConfigParam

external object CsmModulePackage {
    val Csm: CsmNamespace
}

external interface CsmNamespace {
    val MessagesApi: MessagesApiFactory
}

external interface MessagesApiFactory {
    operator fun invoke(sdk: AccelByteSDK, args: SdkSetConfigParam = definedExternally): MessagesApi
}

external interface MessagesApi {
    fun getMessages(): Promise<Json>
}