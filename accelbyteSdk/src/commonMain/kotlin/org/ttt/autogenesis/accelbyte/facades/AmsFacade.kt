package org.ttt.autogenesis.accelbyte.facades

import kotlin.js.Json
import kotlin.js.json
import kotlin.js.Promise
import org.ttt.autogenesis.accelbyte.AccelByteSdkInstance
import org.ttt.autogenesis.accelbyte.modules.AmsModulePackage
import org.ttt.autogenesis.accelbyte.util.propagateJsErrors
import org.ttt.autogenesis.accelbyte.util.mapJson
import org.ttt.autogenesis.accelbyte.util.mapToUnit

/**
 * A facade over the AccelByte AMS (Asset Management System) module. Provides access
 * to fleet management, server orchestration, and artifact handling for game server
 * infrastructure. AMS manages the lifecycle of dedicated game servers: define fleets
 * of identically configured servers, monitor individual server health, manage server
 * images, and retrieve signed artifact download URLs.
 *
 * @property sdk the SDK instance used for API calls
 */
class AmsFacade(private val sdk : AccelByteSdkInstance)
{
    // Admin APIs
    private val accountAdminApi = AmsModulePackage.Ams.AccountAdminApi(sdk.rawSdk)
    private val amsInfoAdminApi = AmsModulePackage.Ams.AmsInfoAdminApi(sdk.rawSdk)
    private val fleetsAdminApi = AmsModulePackage.Ams.FleetsAdminApi(sdk.rawSdk)
    private val serversAdminApi = AmsModulePackage.Ams.ServersAdminApi(sdk.rawSdk)
    private val imagesAdminApi = AmsModulePackage.Ams.ImagesAdminApi(sdk.rawSdk)
    private val artifactsAdminApi = AmsModulePackage.Ams.ArtifactsAdminApi(sdk.rawSdk)
    private val developmentAdminApi = AmsModulePackage.Ams.DevelopmentAdminApi(sdk.rawSdk)
    private val amsQoSAdminApi = AmsModulePackage.Ams.AmsQoSAdminApi(sdk.rawSdk)

    // Public APIs
    private val accountApi = AmsModulePackage.Ams.AccountApi(sdk.rawSdk)
    private val amsInfoApi = AmsModulePackage.Ams.AmsInfoApi(sdk.rawSdk)
    private val fleetsApi = AmsModulePackage.Ams.FleetsApi(sdk.rawSdk)
    private val fleetCommanderApi = AmsModulePackage.Ams.FleetCommanderApi(sdk.rawSdk)
    private val watchdogsApi = AmsModulePackage.Ams.WatchdogsApi(sdk.rawSdk)
    private val authApi = AmsModulePackage.Ams.AuthApi(sdk.rawSdk)

    /**
     * Retrieves the current AMS account details.
     *
     * @return raw JSON account object via propagateJsErrors
     */
    fun getAccount() : Promise<Json> = accountAdminApi.getAccount().propagateJsErrors()

    /**
     * Creates a new AMS account.
     *
     * @param data account creation JSON body
     * @return created account JSON
     */
    fun createAccount(data : Json) : Promise<Json> = accountAdminApi.createAccount(data).propagateJsErrors()

    /**
     * Gets the link information for the current account.
     *
     * @return link information JSON
     */
    fun getAccountLink() : Promise<Json> = accountAdminApi.getAccountLink().propagateJsErrors()

    /**
     * Lists all available regions for AMS deployment.
     *
     * @return JSON list of available deployment regions
     */
    fun getRegions() : Promise<Json> = amsInfoAdminApi.getRegions().propagateJsErrors()

    /**
     * Lists all supported instance types for AMS.
     *
     * @return JSON list of supported instance type definitions
     */
    fun getSupportedInstances() : Promise<Json> = amsInfoAdminApi.getSupportedInstances().propagateJsErrors()

    /**
     * Lists all fleets for the namespace.
     *
     * @param queryParams optional JSON filter (namespace-scoped)
     * @return fleet list JSON
     */
    fun getFleets(queryParams : Json = json()) : Promise<Json> = fleetsAdminApi.getFleets(queryParams).propagateJsErrors()

    /**
     * Gets details for a specific fleet.
     *
     * @param fleetId target fleet
     * @return fleet detail JSON
     */
    fun getFleet(fleetId : String) : Promise<Json> = fleetsAdminApi.getFleet_ByFleetId(fleetId).propagateJsErrors()

    /**
     * Creates a new fleet.
     *
     * @param data fleet creation JSON
     * @return created fleet JSON
     */
    fun createFleet(data : Json) : Promise<Json> = fleetsAdminApi.createFleet(data).propagateJsErrors()

    /**
     * Updates an existing fleet.
     *
     * @param fleetId target fleet
     * @param data patch JSON
     * @return updated fleet JSON
     */
    fun updateFleet(fleetId : String, data : Json) : Promise<Json> = fleetsAdminApi.updateFleet_ByFleetId(fleetId, data).propagateJsErrors()

    /**
     * Deletes a fleet.
     *
     * @param fleetId target fleet
     * @return deleted fleet JSON
     */
    fun deleteFleet(fleetId : String) : Promise<Json> = fleetsAdminApi.deleteFleet_ByFleetId(fleetId).propagateJsErrors()

    /**
     * Lists all servers within a fleet.
     *
     * @param fleetId parent fleet
     * @param queryParams optional filter JSON
     * @return server list JSON
     */
    fun getServers(fleetId : String, queryParams : Json = json()) : Promise<Json> = fleetsAdminApi.getServers_ByFleetId(fleetId, queryParams).propagateJsErrors()

    /**
     * Gets details for a specific server.
     *
     * @param serverId target server
     * @return server detail JSON
     */
    fun getServer(serverId : String) : Promise<Json> = serversAdminApi.getServer_ByServerId(serverId).propagateJsErrors()

    /**
     * Gets the connection info for a specific server.
     *
     * @param serverId target server
     * @return connection info JSON (IP, port, credentials)
     */
    fun getConnectionInfo(serverId : String) : Promise<Json> = serversAdminApi.getConnectioninfo_ByServerId(serverId).propagateJsErrors()

    /**
     * Lists all server images.
     *
     * @param queryParams optional filter JSON
     * @return server image list JSON
     */
    fun getImages(queryParams : Json = json()) : Promise<Json> = imagesAdminApi.getImages(queryParams).propagateJsErrors()

    /**
     * Lists all available artifacts.
     *
     * @param queryParams optional filter JSON
     * @return artifact list JSON
     */
    fun getArtifacts(queryParams : Json = json()) : Promise<Json> = artifactsAdminApi.getArtifacts(queryParams).propagateJsErrors()

    /**
     * Gets the signed URL for downloading an artifact.
     *
     * @param artifactId target artifact; a signed download URL is returned
     * @return URL JSON
     */
    fun getArtifactUrl(artifactId : String) : Promise<Json> = artifactsAdminApi.getUrl_ByArtifactId(artifactId).propagateJsErrors()

    /**
     * Gets development server configurations.
     *
     * @param queryParams optional filter JSON
     * @return dev server config JSON
     */
    fun getDevelopmentServerConfigurations(queryParams : Json = json()) : Promise<Json> = developmentAdminApi.getDevelopmentServerConfigurations(queryParams).propagateJsErrors()

    /**
     * Gets the current login queue status.
     *
     * @return status JSON from the AMS QoS endpoint (login queue state)
     */
    fun getStatus() : Promise<Json> = amsQoSAdminApi.getQos().propagateJsErrors()
}
