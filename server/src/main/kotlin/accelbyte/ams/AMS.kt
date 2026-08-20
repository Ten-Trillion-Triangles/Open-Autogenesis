package accelbyte.ams

import accelbyte.AccelByteSdkProvider
import kotlinx.serialization.json.JsonElement
import net.accelbyte.sdk.api.ams.models.*
import net.accelbyte.sdk.api.ams.operations.account.*
import net.accelbyte.sdk.api.ams.operations.ams_info.*
import net.accelbyte.sdk.api.ams.operations.ams_qo_s.*
import net.accelbyte.sdk.api.ams.operations.artifacts.*
import net.accelbyte.sdk.api.ams.operations.auth.AuthCheck
import net.accelbyte.sdk.api.ams.operations.development.*
import net.accelbyte.sdk.api.ams.operations.fleet_commander.*
import net.accelbyte.sdk.api.ams.operations.fleets.*
import net.accelbyte.sdk.api.ams.operations.images.*
import net.accelbyte.sdk.api.ams.operations.servers.*
import net.accelbyte.sdk.api.ams.operations.watchdogs.*
import net.accelbyte.sdk.api.ams.wrappers.*

object AMS
{
    private val sdk get() = AccelByteSdkProvider.sdk
    private val namespace get() = AccelByteSdkProvider.namespace

    private val accountWrapper by lazy { Account(sdk) }
    private val infoWrapper by lazy { AMSInfo(sdk) }
    private val qoSWrapper by lazy { AMSQoS(sdk) }
    private val artifactsWrapper by lazy { Artifacts(sdk) }
    private val authWrapper by lazy { Auth(sdk) }
    private val developmentWrapper by lazy { Development(sdk) }
    private val fleetCommanderWrapper by lazy { FleetCommander(sdk) }
    private val fleetsWrapper by lazy { Fleets(sdk) }
    private val imagesWrapper by lazy { Images(sdk) }
    private val serversWrapper by lazy { Servers(sdk) }
    private val watchdogsWrapper by lazy { Watchdogs(sdk) }

    private fun runJson(action: () -> Any): Result<JsonElement> =
        runCatching { action().toJsonElement() }

    private fun runUnit(action: () -> Unit): Result<Unit> = runCatching { action() }

    // Account
    fun accountGet(): Result<JsonElement> =
        runJson { accountWrapper.accountGet(AccountGet.builder().namespace(namespace).build()) }

    fun adminAccountGet(): Result<JsonElement> =
        runJson {
            accountWrapper.adminAccountGet(AdminAccountGet.builder().namespace(namespace).build())
        }

    fun adminAccountCreate(payload: JsonElement): Result<JsonElement> = runJson {
        val body = payload.toModel<ApiAccountCreateRequest>()
        accountWrapper.adminAccountCreate(
            AdminAccountCreate.builder().namespace(namespace).body(body).build()
        )
    }

    fun adminAccountLinkToken(): Result<JsonElement> = runJson {
        accountWrapper.adminAccountLinkTokenGet(
            AdminAccountLinkTokenGet.builder().namespace(namespace).build()
        )
    }

    fun adminAccountLink(payload: JsonElement): Result<JsonElement> = runJson {
        val body = payload.toModel<ApiAccountLinkRequest>()
        accountWrapper.adminAccountLink(
            AdminAccountLink.builder().namespace(namespace).body(body).build()
        )
    }

    // AMS Info
    fun infoRegions(): Result<JsonElement> = runJson {
        infoWrapper.infoRegions(InfoRegions.builder().namespace(namespace).build())
    }

    fun supportedInstances(): Result<JsonElement> = runJson {
        infoWrapper.infoSupportedInstances(
            InfoSupportedInstances.builder().namespace(namespace).build()
        )
    }

    fun requestUploadUrl(): Result<Unit> = runUnit {
        infoWrapper.uploadURLGet(UploadURLGet.builder().build())
    }

    // QoS
    fun qosRegions(status: String? = null): Result<JsonElement> = runJson {
        qoSWrapper.qoSRegionsGet(
            QoSRegionsGet.builder().namespace(namespace).status(status).build()
        )
    }

    fun updateQosRegion(region: String, payload: JsonElement): Result<Unit> = runUnit {
        val body = payload.toModel<ApiUpdateServerRequest>()
        qoSWrapper.qoSRegionsUpdate(
            QoSRegionsUpdate.builder().namespace(namespace).region(region).body(body).build()
        )
    }

    // Artifacts
    fun listArtifacts(
        artifactType: String? = null,
        count: Int? = null,
        endDate: String? = null,
        fleetId: String? = null,
        imageId: String? = null,
        maxSize: Int? = null,
        minSize: Int? = null,
        offset: Int? = null,
        region: String? = null,
        serverId: String? = null,
        sortBy: String? = null,
        sortDirection: String? = null,
        startDate: String? = null,
        status: String? = null
    ): Result<JsonElement> = runJson {
        val op = ArtifactGet.builder()
            .namespace(namespace)
            .artifactType(artifactType)
            .count(count)
            .endDate(endDate)
            .fleetID(fleetId)
            .imageID(imageId)
            .maxSize(maxSize)
            .minSize(minSize)
            .offset(offset)
            .region(region)
            .serverId(serverId)
            .sortBy(sortBy)
            .sortDirection(sortDirection)
            .startDate(startDate)
            .status(status)
            .build()
        artifactsWrapper.artifactGet(op)
    }

    fun bulkDeleteArtifacts(
        artifactType: String? = null,
        fleetId: String? = null,
        uploadedBefore: String? = null
    ): Result<Unit> = runUnit {
        val op = ArtifactBulkDelete.builder()
            .namespace(namespace)
            .artifactType(artifactType)
            .fleetId(fleetId)
            .uploadedBefore(uploadedBefore)
            .build()
        artifactsWrapper.artifactBulkDelete(op)
    }

    fun artifactUsage(): Result<JsonElement> = runJson {
        artifactsWrapper.artifactUsageGet(
            ArtifactUsageGet.builder().namespace(namespace).build()
        )
    }

    fun deleteArtifact(artifactId: String): Result<Unit> = runUnit {
        artifactsWrapper.artifactDelete(
            ArtifactDelete.builder().artifactID(artifactId).namespace(namespace).build()
        )
    }

    fun artifactUrl(artifactId: String): Result<JsonElement> = runJson {
        artifactsWrapper.artifactGetURL(
            ArtifactGetURL.builder().artifactID(artifactId).namespace(namespace).build()
        )
    }

    fun fleetArtifactSamplingRules(fleetId: String): Result<JsonElement> = runJson {
        artifactsWrapper.fleetArtifactSamplingRulesGet(
            FleetArtifactSamplingRulesGet.builder().fleetID(fleetId).namespace(namespace).build()
        )
    }

    fun updateFleetArtifactSamplingRules(fleetId: String, payload: JsonElement): Result<JsonElement> =
        runJson {
            val body = payload.toModel<ApiFleetArtifactsSampleRules>()
            artifactsWrapper.fleetArtifactSamplingRulesSet(
                FleetArtifactSamplingRulesSet.builder()
                    .fleetID(fleetId)
                    .namespace(namespace)
                    .body(body)
                    .build()
            )
        }

    // Auth
    fun authCheck(): Result<Unit> = runUnit { authWrapper.authCheck(AuthCheck.builder().build()) }

    // Development
    fun listDevelopmentServerConfigurations(
        count: Int? = null,
        imageId: String? = null,
        name: String? = null,
        offset: Int? = null,
        sortBy: String? = null,
        sortDirection: String? = null
    ): Result<JsonElement> = runJson {
        developmentWrapper.developmentServerConfigurationList(
            DevelopmentServerConfigurationList.builder()
                .namespace(namespace)
                .count(count)
                .imageId(imageId)
                .name(name)
                .offset(offset)
                .sortBy(sortBy)
                .sortDirection(sortDirection)
                .build()
        )
    }

    fun createDevelopmentServerConfiguration(payload: JsonElement): Result<JsonElement> = runJson {
        val body = payload.toModel<ApiDevelopmentServerConfigurationCreateRequest>()
        developmentWrapper.developmentServerConfigurationCreate(
            DevelopmentServerConfigurationCreate.builder().namespace(namespace).body(body).build()
        )
    }

    fun getDevelopmentServerConfiguration(configId: String): Result<JsonElement> = runJson {
        developmentWrapper.developmentServerConfigurationGet(
            DevelopmentServerConfigurationGet.builder()
                .namespace(namespace)
                .developmentServerConfigID(configId)
                .build()
        )
    }

    fun deleteDevelopmentServerConfiguration(configId: String): Result<Unit> = runUnit {
        developmentWrapper.developmentServerConfigurationDelete(
            DevelopmentServerConfigurationDelete.builder()
                .namespace(namespace)
                .developmentServerConfigID(configId)
                .build()
        )
    }

    fun patchDevelopmentServerConfiguration(configId: String, payload: JsonElement): Result<Unit> {
        return runUnit {
            val body = payload.toModel<ApiDevelopmentServerConfigurationUpdateRequest>()
            developmentWrapper.developmentServerConfigurationPatch(
                DevelopmentServerConfigurationPatch.builder()
                    .namespace(namespace)
                    .developmentServerConfigID(configId)
                    .body(body)
                    .build()
            )
        }
    }

    // Fleet Commander
    fun portalHealthCheck(): Result<Unit> = runUnit {
        fleetCommanderWrapper.portalHealthCheck(PortalHealthCheck.builder().build())
    }

    fun runFunc1(): Result<Unit> = runUnit {
        fleetCommanderWrapper.func1(Func1.builder().build())
    }

    fun basicHealthCheck(): Result<Unit> = runUnit {
        fleetCommanderWrapper.basicHealthCheck(BasicHealthCheck.builder().build())
    }

    // Fleets
    fun listFleets(
        active: Boolean? = null,
        count: Int? = null,
        name: String? = null,
        offset: Int? = null,
        region: String? = null,
        sortBy: String? = null,
        sortDirection: String? = null
    ): Result<JsonElement> = runJson {
        fleetsWrapper.fleetList(
            FleetList.builder()
                .namespace(namespace)
                .active(active)
                .count(count)
                .name(name)
                .offset(offset)
                .region(region)
                .sortBy(sortBy)
                .sortDirection(sortDirection)
                .build()
        )
    }

    fun createFleet(payload: JsonElement): Result<JsonElement> = runJson {
        val body = payload.toModel<ApiFleetParameters>()
        fleetsWrapper.fleetCreate(
            FleetCreate.builder().namespace(namespace).body(body).build()
        )
    }

    fun getFleet(fleetId: String): Result<JsonElement> = runJson {
        fleetsWrapper.fleetGet(
            FleetGet.builder().namespace(namespace).fleetID(fleetId).build()
        )
    }

    fun updateFleet(fleetId: String, payload: JsonElement): Result<Unit> = runUnit {
        val body = payload.toModel<ApiFleetParameters>()
        fleetsWrapper.fleetUpdate(
            FleetUpdate.builder().namespace(namespace).fleetID(fleetId).body(body).build()
        )
    }

    fun deleteFleet(fleetId: String): Result<Unit> = runUnit {
        fleetsWrapper.fleetDelete(
            FleetDelete.builder().namespace(namespace).fleetID(fleetId).build()
        )
    }

    fun listFleetServers(
        fleetId: String,
        count: Int? = null,
        offset: Int? = null,
        region: String? = null,
        serverId: String? = null,
        sortBy: String? = null,
        sortDirection: String? = null,
        status: String? = null
    ): Result<JsonElement> = runJson {
        fleetsWrapper.fleetServers(
            FleetServers.builder()
                .namespace(namespace)
                .fleetID(fleetId)
                .count(count)
                .offset(offset)
                .region(region)
                .serverId(serverId)
                .sortBy(sortBy)
                .sortDirection(sortDirection)
                .status(status)
                .build()
        )
    }

    fun claimFleetById(fleetId: String, payload: JsonElement): Result<JsonElement> = runJson {
        val body = payload.toModel<ApiFleetClaimReq>()
        fleetsWrapper.fleetClaimByID(
            FleetClaimByID.builder().namespace(namespace).fleetID(fleetId).body(body).build()
        )
    }

    fun claimFleetByKeys(payload: JsonElement): Result<JsonElement> = runJson {
        val body = payload.toModel<ApiFleetClaimByKeysReq>()
        fleetsWrapper.fleetClaimByKeys(
            FleetClaimByKeys.builder().namespace(namespace).body(body).build()
        )
    }

    // Images
    fun listImages(
        count: Int? = null,
        inUse: String? = null,
        isProtected: Boolean? = null,
        name: String? = null,
        offset: Int? = null,
        sortBy: String? = null,
        sortDirection: String? = null,
        status: String? = null,
        tag: String? = null,
        targetArchitecture: String? = null
    ): Result<JsonElement> = runJson {
        imagesWrapper.imageList(
            ImageList.builder()
                .namespace(namespace)
                .count(count)
                .inUse(inUse)
                .isProtected(isProtected)
                .name(name)
                .offset(offset)
                .sortBy(sortBy)
                .sortDirection(sortDirection)
                .status(status)
                .tag(tag)
                .targetArchitecture(targetArchitecture)
                .build()
        )
    }

    fun getImage(imageId: String): Result<JsonElement> = runJson {
        imagesWrapper.imageGet(
            ImageGet.builder().namespace(namespace).imageID(imageId).build()
        )
    }

    fun patchImage(imageId: String, payload: JsonElement): Result<JsonElement> = runJson {
        val body = payload.toModel<ApiImageUpdate>()
        imagesWrapper.imagePatch(
            ImagePatch.builder().namespace(namespace).imageID(imageId).body(body).build()
        )
    }

    fun markImageForDeletion(imageId: String): Result<Unit> = runUnit {
        imagesWrapper.imageMarkForDeletion(
            ImageMarkForDeletion.builder().namespace(namespace).imageID(imageId).build()
        )
    }

    fun restoreImage(imageId: String): Result<Unit> = runUnit {
        imagesWrapper.imageUnmarkForDeletion(
            ImageUnmarkForDeletion.builder().namespace(namespace).imageID(imageId).build()
        )
    }

    fun imagesStorage(): Result<JsonElement> = runJson {
        imagesWrapper.imagesStorage(ImagesStorage.builder().namespace(namespace).build())
    }

    // Servers
    fun fleetServerInfo(serverId: String): Result<JsonElement> = runJson {
        serversWrapper.fleetServerInfo(
            FleetServerInfo.builder().namespace(namespace).serverID(serverId).build()
        )
    }

    fun fleetServerConnectionInfo(serverId: String): Result<JsonElement> = runJson {
        serversWrapper.fleetServerConnectionInfo(
            FleetServerConnectionInfo.builder().namespace(namespace).serverID(serverId).build()
        )
    }

    fun fleetServerHistory(
        fleetId: String,
        count: Int? = null,
        offset: Int? = null,
        reason: String? = null,
        region: String? = null,
        serverId: String? = null,
        sortDirection: String? = null,
        status: String? = null
    ): Result<JsonElement> = runJson {
        serversWrapper.fleetServerHistory(
            FleetServerHistory.builder()
                .namespace(namespace)
                .fleetID(fleetId)
                .count(count)
                .offset(offset)
                .reason(reason)
                .region(region)
                .serverId(serverId)
                .sortDirection(sortDirection)
                .status(status)
                .build()
        )
    }

    fun serverHistory(serverId: String): Result<JsonElement> = runJson {
        serversWrapper.serverHistory(
            ServerHistory.builder().namespace(namespace).serverID(serverId).build()
        )
    }

    // Watchdogs
    fun connectLocalWatchdog(watchdogId: String): Result<Unit> = runUnit {
        watchdogsWrapper.localWatchdogConnect(
            LocalWatchdogConnect.builder().namespace(namespace).watchdogID(watchdogId).build()
        )
    }

    fun connectWatchdog(watchdogId: String): Result<Unit> = runUnit {
        watchdogsWrapper.watchdogConnect(
            WatchdogConnect.builder().namespace(namespace).watchdogID(watchdogId).build()
        )
    }
}