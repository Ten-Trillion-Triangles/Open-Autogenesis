package org.ttt.autogenesis.accelbyte.facades

import kotlin.js.Json
import kotlin.js.Promise
import org.ttt.autogenesis.accelbyte.AccelByteSdkInstance
import org.ttt.autogenesis.accelbyte.models.TelemetryEventListResponse
import org.ttt.autogenesis.accelbyte.models.TelemetryNamespaceListResponse
import org.ttt.autogenesis.accelbyte.models.TelemetryPlaytimeResponse
import org.ttt.autogenesis.accelbyte.models.TelemetryProtectedEventResult
import org.ttt.autogenesis.accelbyte.models.TelemetryQueryParams
import org.ttt.autogenesis.accelbyte.modules.GameTelemetryModulePackage
import org.ttt.autogenesis.accelbyte.util.mapJson
import org.ttt.autogenesis.accelbyte.util.propagateJsErrors

/**
 * Telemetry helper wrapping protected events and administrator queries.
 */
class GameTelemetryFacade(private val sdk : AccelByteSdkInstance)
{
    private val module get() = GameTelemetryModulePackage.GameTelemetry

    private val operationsApi get() = module.GametelemetryOperationsApi(sdk.rawSdk)
    private val adminApi get() = module.TelemetryAdminApi(sdk.rawSdk)

    /**
     * Sends a list of protected telemetry events.
     */
    fun sendProtectedEvents(events : List<Json>) : Promise<TelemetryProtectedEventResult> =
        operationsApi.createProtectedEvent(events.toTypedArray())
            .propagateJsErrors()
            .mapJson(TelemetryProtectedEventResult::fromJson)

    /**
     * Retrieves cached playtime data for a Steam ID.
     */
    fun getPlaytime(steamId : String) : Promise<TelemetryPlaytimeResponse> =
        operationsApi.getPlaytimeProtected_BySteamId(steamId)
            .propagateJsErrors()
            .mapJson(TelemetryPlaytimeResponse::fromJson)

    /**
     * Updates the cached playtime value for a Steam ID.
     */
    fun updatePlaytime(steamId : String, playtime : String) : Promise<TelemetryPlaytimeResponse> =
        operationsApi.updatePlaytimeProtected_BySteamId_ByPlaytime(steamId, playtime)
            .propagateJsErrors()
            .mapJson(TelemetryPlaytimeResponse::fromJson)

    /**
     * Lists telemetry namespaces (admin scope).
     */
    fun listNamespaces() : Promise<TelemetryNamespaceListResponse> =
        adminApi.getNamespaces()
            .propagateJsErrors()
            .mapJson(TelemetryNamespaceListResponse::fromJson)

    /**
     * Searches captured events using a typed query that includes optional filters such as event name, time range, or user ID.
     */
    fun searchEvents(params : TelemetryQueryParams = TelemetryQueryParams()) : Promise<TelemetryEventListResponse> =
        adminApi.getEvents(params.toJson())
            .propagateJsErrors()
            .mapJson(TelemetryEventListResponse::fromJson)
}
