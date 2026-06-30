@file:JsModule("@accelbyte/sdk-ams")
@file:JsNonModule

package org.ttt.autogenesis.accelbyte.modules

import kotlin.js.Json
import kotlin.js.Promise
import org.ttt.autogenesis.accelbyte.AccelByteSDK
import org.ttt.autogenesis.accelbyte.SdkSetConfigParam

external val Ams : AmsNamespace

external object AmsModulePackage {
    val Ams: AmsNamespace
}

external interface AmsNamespace
{
    fun AccountAdminApi(sdk : AccelByteSDK, args : SdkSetConfigParam = definedExternally) : AccountAdminApi
    fun AmsInfoAdminApi(sdk : AccelByteSDK, args : SdkSetConfigParam = definedExternally) : AmsInfoAdminApi
    fun AmsQoSAdminApi(sdk : AccelByteSDK, args : SdkSetConfigParam = definedExternally) : AmsQoSAdminApi
    fun ArtifactsAdminApi(sdk : AccelByteSDK, args : SdkSetConfigParam = definedExternally) : ArtifactsAdminApi
    fun DevelopmentAdminApi(sdk : AccelByteSDK, args : SdkSetConfigParam = definedExternally) : DevelopmentAdminApi
    fun FleetsAdminApi(sdk : AccelByteSDK, args : SdkSetConfigParam = definedExternally) : FleetsAdminApi
    fun ImagesAdminApi(sdk : AccelByteSDK, args : SdkSetConfigParam = definedExternally) : ImagesAdminApi
    fun ServersAdminApi(sdk : AccelByteSDK, args : SdkSetConfigParam = definedExternally) : ServersAdminApi
    fun AccountApi(sdk : AccelByteSDK, args : SdkSetConfigParam = definedExternally) : AccountApi
    fun AmsInfoApi(sdk : AccelByteSDK, args : SdkSetConfigParam = definedExternally) : AmsInfoApi
    fun AuthApi(sdk : AccelByteSDK, args : SdkSetConfigParam = definedExternally) : AuthApi
    fun FleetCommanderApi(sdk : AccelByteSDK, args : SdkSetConfigParam = definedExternally) : FleetCommanderApi
    fun FleetsApi(sdk : AccelByteSDK, args : SdkSetConfigParam = definedExternally) : FleetsApi
    fun WatchdogsApi(sdk : AccelByteSDK, args : SdkSetConfigParam = definedExternally) : WatchdogsApi
}

external interface AccountAdminApi
{
    fun getAccount() : Promise<Json>
    fun createAccount(data : Json) : Promise<Json>
    fun getAccountLink() : Promise<Json>
    fun createAccountLink(data : Json) : Promise<Json>
}

external interface AmsInfoAdminApi
{
    fun getRegions() : Promise<Json>
    fun getSupportedInstances() : Promise<Json>
}

external interface AmsQoSAdminApi
{
    fun getQos(queryParams : Json = definedExternally) : Promise<Json>
    fun patchQo_ByRegion(region : String, data : Json) : Promise<Json>
}

external interface ArtifactsAdminApi
{
    fun deleteArtifact(queryParams : Json = definedExternally) : Promise<Json>
    fun getArtifacts(queryParams : Json = definedExternally) : Promise<Json>
    fun getArtifactsUsage() : Promise<Json>
    fun deleteArtifact_ByArtifactId(artifactID : String) : Promise<Json>
    fun getUrl_ByArtifactId(artifactID : String) : Promise<Json>
    fun getArtifactsSamplingRules_ByFleetId(fleetID : String) : Promise<Json>
    fun updateArtifactsSamplingRule_ByFleetId(fleetID : String, data : Json) : Promise<Json>
}

external interface DevelopmentAdminApi
{
    fun getDevelopmentServerConfigurations(queryParams : Json = definedExternally) : Promise<Json>
    fun createDevelopmentServerConfiguration(data : Json) : Promise<Json>
    fun deleteDevelopmentServerConfiguration_ByDevelopmentServerConfigId(id : String) : Promise<Json>
    fun getDevelopmentServerConfiguration_ByDevelopmentServerConfigId(id : String) : Promise<Json>
    fun patchDevelopmentServerConfiguration_ByDevelopmentServerConfigId(id : String, data : Json) : Promise<Json>
}

external interface FleetsAdminApi
{
    fun getFleets(queryParams : Json = definedExternally) : Promise<Json>
    fun createFleet(data : Json) : Promise<Json>
    fun deleteFleet_ByFleetId(fleetID : String) : Promise<Json>
    fun getFleet_ByFleetId(fleetID : String) : Promise<Json>
    fun updateFleet_ByFleetId(fleetID : String, data : Json) : Promise<Json>
    fun getServers_ByFleetId(fleetID : String, queryParams : Json = definedExternally) : Promise<Json>
}

external interface ImagesAdminApi
{
    fun getImages(queryParams : Json = definedExternally) : Promise<Json>
    fun getImagesStorage() : Promise<Json>
    fun deleteImage_ByImageId(imageID : String) : Promise<Json>
    fun getImage_ByImageId(imageID : String) : Promise<Json>
    fun patchImage_ByImageId(imageID : String, data : Json) : Promise<Json>
    fun createRestore_ByImageId(imageID : String) : Promise<Json>
}

external interface ServersAdminApi
{
    fun getServer_ByServerId(serverID : String) : Promise<Json>
    fun getHistory_ByServerId(serverID : String) : Promise<Json>
    fun getServersHistory_ByFleetId(fleetID : String, queryParams : Json = definedExternally) : Promise<Json>
    fun getConnectioninfo_ByServerId(serverID : String) : Promise<Json>
}

external interface AccountApi
{
    fun getAccount() : Promise<Json>
}

external interface AmsInfoApi
{
    fun getUploadUrl() : Promise<Json>
}

external interface AuthApi
{
    fun getAuth() : Promise<Json>
}

external interface FleetCommanderApi
{
    fun getVersion() : Promise<Json>
}

external interface FleetsApi
{
    fun updateServerClaim(data : Json) : Promise<Json>
    fun updateClaim_ByFleetId(fleetID : String, data : Json) : Promise<Json>
}

external interface WatchdogsApi
{
    fun getConnect_ByWatchdogId(watchdogID : String) : Promise<Json>
    fun getConnect_ByWatchdogId_ByNS(watchdogID : String) : Promise<Json>
}
