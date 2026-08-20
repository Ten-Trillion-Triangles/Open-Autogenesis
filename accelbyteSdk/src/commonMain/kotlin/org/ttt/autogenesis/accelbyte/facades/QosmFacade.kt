package org.ttt.autogenesis.accelbyte.facades

import kotlin.js.Json
import kotlin.js.Promise
import org.ttt.autogenesis.accelbyte.AccelByteSdkInstance
import org.ttt.autogenesis.accelbyte.models.QosmAliasRequest
import org.ttt.autogenesis.accelbyte.models.QosmRegionQuery
import org.ttt.autogenesis.accelbyte.models.QosmServerListResponse
import org.ttt.autogenesis.accelbyte.models.QosmServerResponse
import org.ttt.autogenesis.accelbyte.modules.QosmModulePackage
import org.ttt.autogenesis.accelbyte.util.mapJson
import org.ttt.autogenesis.accelbyte.util.propagateJsErrors

/**
 * QoS services helpers dealing with heartbeat registration and region enumeration.
 */
class QosmFacade(private val sdk : AccelByteSdkInstance)
{
    private val module get() = QosmModulePackage.Qosmanager

    private val adminApi get() = module.AdminAdminApi(sdk.rawSdk)
    private val publicApi get() = module.PublicApi(sdk.rawSdk)
    private val serverApi get() = module.ServerApi(sdk.rawSdk)

    /**
     * @param payload JSON heartbeat body as defined by the server (region, IP, status)
     * @return [QosmServerResponse] confirming registration
     */
    fun registerHeartbeat(payload : Json) : Promise<QosmServerResponse> =
        serverApi.createServerHeartbeat(payload)
            .propagateJsErrors()
            .mapJson(QosmServerResponse::fromJson)

    /**
     * @return [QosmServerListResponse] of all available regions (no namespace filter)
     */
    fun listAllRegions() : Promise<QosmServerListResponse> =
        publicApi.getQos()
            .propagateJsErrors()
            .mapJson(QosmServerListResponse::fromJson)

    /**
     * Lists namespace-specific QoS endpoints, optionally filtered by status.
     */
    fun listNamespaceRegions(params : QosmRegionQuery = QosmRegionQuery()) : Promise<QosmServerListResponse> =
        publicApi.getQos_ByNS(params.toJson())
            .propagateJsErrors()
            .mapJson(QosmServerListResponse::fromJson)

    /**
     * @param region region identifier
     * @return Promise<Unit> when the region entry is removed
     */
    fun deleteRegion(region : String) : Promise<Unit> =
        adminApi.deleteServer_ByRegion(region)
            .propagateJsErrors()
            .mapJson { }

    /**
     * Sets an override alias for a region.
     */
    fun setRegionAlias(region : String, alias : String) : Promise<Unit> =
        adminApi.createAlia_ByRegion(region, QosmAliasRequest(alias).toJson())
            .propagateJsErrors()
            .mapJson { }

    /**
     * @param region region identifier
     * @param payload JSON patch body for region config
     * @return [QosmServerResponse]
     */
    fun updateRegion(region : String, payload : Json) : Promise<QosmServerResponse> =
        adminApi.patchServer_ByRegion(region, payload)
            .propagateJsErrors()
            .mapJson(QosmServerResponse::fromJson)
}