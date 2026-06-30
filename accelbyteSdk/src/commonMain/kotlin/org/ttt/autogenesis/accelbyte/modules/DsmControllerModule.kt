@file:JsModule("@accelbyte/sdk-dsmcontroller")
@file:JsNonModule

package org.ttt.autogenesis.accelbyte.modules

import kotlin.js.Json
import kotlin.js.Promise
import org.ttt.autogenesis.accelbyte.AccelByteSDK
import org.ttt.autogenesis.accelbyte.SdkSetConfigParam

external object DsmControllerModulePackage {
    val DsmController: DsmControllerNamespace
}

external interface DsmControllerNamespace {
    val DsmcOperationsApi: DsmcOperationsApiFactory
    val ServerApi: ServerApiFactory
    val DeploymentConfigApi: DeploymentConfigApiFactory
    val SessionApi: SessionApiFactory
}

external interface DsmcOperationsApiFactory {
    operator fun invoke(sdk: AccelByteSDK, args: SdkSetConfigParam = definedExternally): DsmcOperationsApi
}

external interface DsmcOperationsApi {
    fun getMessages(): Promise<Json>
}

external interface ServerApiFactory {
    operator fun invoke(sdk: AccelByteSDK, args: SdkSetConfigParam = definedExternally): ServerApi
}

external interface ServerApi {
    fun getServers(queryParams: Json): Promise<Json>
    fun createServerRegister(data: Json): Promise<Json>
    fun createServerShutdown(data: Json): Promise<Json>
    fun updateServerHeartbeat(data: Json): Promise<Json>
    fun getServersCountDetailed(queryParams: Json? = definedExternally): Promise<Json>
    fun createServerLocalRegister(data: Json): Promise<Json>
    fun createServerLocalDeregister(data: Json): Promise<Json>
    fun getSession_ByPodName(podName: String): Promise<Json>
    fun getConfigSessiontimeout_ByPodName(podName: String): Promise<Json>
}

external interface DeploymentConfigApiFactory {
    operator fun invoke(sdk: AccelByteSDK, args: SdkSetConfigParam = definedExternally): DeploymentConfigApi
}

external interface DeploymentConfigApi {
    fun getConfigsDeployments(queryParams: Json): Promise<Json>
    fun deleteConfigDeployment_ByDeployment(deployment: String): Promise<Json>
    fun getConfigDeployment_ByDeployment(deployment: String): Promise<Json>
    fun createConfigDeployment_ByDeployment(deployment: String, data: Json): Promise<Json>
}

external interface SessionApiFactory {
    operator fun invoke(sdk: AccelByteSDK, args: SdkSetConfigParam = definedExternally): SessionApi
}

external interface SessionApi {
    fun createSession(data: Json): Promise<Json>
    fun createSessionClaim(data: Json): Promise<Json>
    fun getSession_BySessionId(sessionID: String): Promise<Json>
    fun deleteCancel_BySessionId(sessionID: String): Promise<Json>
}
