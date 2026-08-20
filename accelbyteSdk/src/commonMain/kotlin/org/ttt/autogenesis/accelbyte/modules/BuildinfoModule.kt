@file:JsModule("@accelbyte/sdk-buildinfo")
@file:JsNonModule

package org.ttt.autogenesis.accelbyte.modules

import kotlin.js.Json
import kotlin.js.Promise
import org.ttt.autogenesis.accelbyte.AccelByteSDK
import org.ttt.autogenesis.accelbyte.SdkSetConfigParam

external object BuildinfoModulePackage {
    val Buildinfo: BuildinfoNamespace
}

external interface BuildinfoNamespace {
    val DownloaderApi: DownloaderApiFactory
    val CachingApi: CachingApiFactory
}

external interface DownloaderApiFactory {
    operator fun invoke(sdk: AccelByteSDK, args: SdkSetConfigParam = definedExternally): DownloaderApi
}

external interface DownloaderApi {
    fun getVersionHistory(queryParams: Json): Promise<Json>
    fun getDiff_BySourceBuildId_ByDestinationBuildId(sourceBuildId: String, destinationBuildId: String): Promise<Json>
    fun createBlockUrl_ByBuildId(buildId: String, queryParams: Json): Promise<Json>
}

external interface CachingApiFactory {
    operator fun invoke(sdk: AccelByteSDK, args: SdkSetConfigParam = definedExternally): CachingApi
}

external interface CachingApi {
    fun getDestCacheDiff_BySourceBuildId_ByDestinationBuildId(sourceBuildId: String, destinationBuildId: String): Promise<Json>
}