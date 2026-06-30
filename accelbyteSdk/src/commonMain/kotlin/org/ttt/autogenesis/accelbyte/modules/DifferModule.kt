@file:JsModule("@accelbyte/sdk-differ")
@file:JsNonModule

package org.ttt.autogenesis.accelbyte.modules

import kotlin.js.Json
import kotlin.js.Promise
import org.ttt.autogenesis.accelbyte.AccelByteSDK
import org.ttt.autogenesis.accelbyte.SdkSetConfigParam

external object DifferModulePackage {
    val Differ: DifferNamespace
}

external interface DifferNamespace {
    val DiffCalculationApi: DiffCalculationApiFactory
}

external interface DiffCalculationApiFactory {
    operator fun invoke(sdk: AccelByteSDK, args: SdkSetConfigParam = definedExternally): DiffCalculationApi
}

external interface DiffCalculationApi {
    fun createDiff(data: Json): Promise<Json>
    fun getPing(): Promise<Json>
    fun createDiff_v2(data: Json): Promise<Json>
}
