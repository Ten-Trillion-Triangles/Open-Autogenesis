package accelbyte.dsm

import accelbyte.AccelByteSdkProvider
import accelbyte.session.toJsonElement
import accelbyte.session.toModel
import kotlinx.serialization.json.JsonElement
import net.accelbyte.sdk.api.ams.models.ApiFleetParameters
import net.accelbyte.sdk.api.ams.models.ApiFleetClaimByKeysReq
import net.accelbyte.sdk.api.ams.models.ApiFleetClaimReq
import net.accelbyte.sdk.api.ams.operations.fleets.*
import net.accelbyte.sdk.api.ams.operations.servers.*
import net.accelbyte.sdk.api.ams.wrappers.Fleets
import net.accelbyte.sdk.api.ams.wrappers.Servers
import net.accelbyte.sdk.api.lobby.operations.admin.AdminGetGlobalConfig
import net.accelbyte.sdk.api.lobby.operations.admin.AdminUpdateGlobalConfig
import net.accelbyte.sdk.api.lobby.models.ModelPutGlobalConfigurationRequest
import net.accelbyte.sdk.api.lobby.wrappers.Admin as LobbyAdmin
import net.accelbyte.sdk.api.platform.operations.payment_dedicated.CreatePaymentOrderByDedicated
import net.accelbyte.sdk.api.platform.operations.payment_dedicated.RefundPaymentOrderByDedicated
import net.accelbyte.sdk.api.platform.operations.payment_dedicated.SyncPaymentOrders
import net.accelbyte.sdk.api.platform.wrappers.PaymentDedicated
import net.accelbyte.sdk.api.platform.models.ExternalPaymentOrderCreate
import net.accelbyte.sdk.api.platform.models.PaymentOrderRefund
import net.accelbyte.sdk.api.session.operations.configuration_template.AdminGetDSMCConfiguration
import net.accelbyte.sdk.api.session.operations.configuration_template.AdminSyncDSMCConfiguration
import net.accelbyte.sdk.api.session.operations.dsmc_default_configuration.AdminGetDSMCConfigurationDefault
import net.accelbyte.sdk.api.session.wrappers.ConfigurationTemplate
import net.accelbyte.sdk.api.session.wrappers.DSMCDefaultConfiguration
import structs.accelbyte.dsm.*

object DSM
{
    private val sdk get() = AccelByteSdkProvider.sdk
    private val namespace get() = AccelByteSdkProvider.namespace

    private val configurationTemplate by lazy { ConfigurationTemplate(sdk) }
    private val defaultConfiguration by lazy { DSMCDefaultConfiguration(sdk) }
    private val lobbyAdmin by lazy { LobbyAdmin(sdk) }
    private val paymentDedicated by lazy { PaymentDedicated(sdk) }
    private val fleetsWrapper by lazy { Fleets(sdk) }
    private val serversWrapper by lazy { Servers(sdk) }

    private inline fun runJson(action: () -> Any): Result<JsonElement> =
        runCatching { action().toJsonElement() }

    private fun runUnit(action: () -> Unit): Result<Unit> = runCatching { action() }

    private inline fun <reified T> Result<JsonElement>.mapToModel(): Result<T> =
        map { it.toModel<T>() }

    fun getDsmcConfiguration(): Result<JsonElement> = runJson {
        configurationTemplate.adminGetDSMCConfiguration(
            AdminGetDSMCConfiguration.builder().namespace(namespace).build()
        )
    }

    fun syncDsmcConfiguration(): Result<JsonElement> = runJson {
        configurationTemplate.adminSyncDSMCConfiguration(
            AdminSyncDSMCConfiguration.builder().namespace(namespace).build()
        )
    }

    fun getDefaultDsmcConfiguration(): Result<JsonElement> = runJson {
        defaultConfiguration.adminGetDSMCConfigurationDefault(
            AdminGetDSMCConfigurationDefault.builder().build()
        )
    }

    fun getGlobalConfiguration(): Result<JsonElement> = runJson {
        lobbyAdmin.adminGetGlobalConfig(AdminGetGlobalConfig.builder().build())
    }

    fun updateGlobalConfiguration(payload: JsonElement): Result<JsonElement> = runJson {
        val body = payload.toModel<ModelPutGlobalConfigurationRequest>()
        lobbyAdmin.adminUpdateGlobalConfig(AdminUpdateGlobalConfig.builder().body(body).build())
    }

    fun createPaymentOrderByDedicated(payload: JsonElement): Result<JsonElement> = runJson {
        val body = payload.toModel<ExternalPaymentOrderCreate>()
        paymentDedicated.createPaymentOrderByDedicated(
            CreatePaymentOrderByDedicated.builder().namespace(namespace).body(body).build()
        )
    }

    fun refundPaymentOrderByDedicated(paymentOrderNo: String, payload: JsonElement): Result<JsonElement> = runJson {
        val body = payload.toModel<PaymentOrderRefund>()
        paymentDedicated.refundPaymentOrderByDedicated(
            RefundPaymentOrderByDedicated.builder()
                .namespace(namespace)
                .paymentOrderNo(paymentOrderNo)
                .body(body)
                .build()
        )
    }

    fun syncPaymentOrders(end: String, start: String, nextEvaluatedKey: String? = null): Result<JsonElement> =
        runJson {
            paymentDedicated.syncPaymentOrders(
                SyncPaymentOrders.builder()
                    .end(end)
                    .start(start)
                    .nextEvaluatedKey(nextEvaluatedKey)
                    .build()
            )
        }

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

    fun historyForFleetServers(
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

    fun serverHistory(serverId: String): Result<JsonElement> = runJson {
        serversWrapper.serverHistory(
            ServerHistory.builder().namespace(namespace).serverID(serverId).build()
        )
    }

    fun getDsmMessages(): Result<List<DsmControllerMessage>> =
        DsmControllerRest.fetchMessages().mapToModel()

    fun listDedicatedServers(count: Int = 20, offset: Int = 0, region: String? = null): Result<DsmServerListResponse> =
        DsmControllerRest.listServers(count, offset, region).mapToModel()

    fun registerDedicatedServer(payload: JsonElement): Result<DsmServerDetails> =
        DsmControllerRest.registerServer(payload).mapToModel()

    fun shutdownDedicatedServer(payload: JsonElement): Result<Unit> =
        DsmControllerRest.shutdownServer(payload).map { }

    fun heartbeatDedicatedServer(payload: JsonElement): Result<Unit> =
        DsmControllerRest.heartbeatServer(payload).map { }

    fun countDedicatedServers(region: String? = null): Result<DsmDetailedCountResponse> =
        DsmControllerRest.countDetailed(region).mapToModel()

    fun registerLocalDedicatedServer(payload: JsonElement): Result<DsmServerDetails> =
        DsmControllerRest.registerLocalServer(payload).mapToModel()

    fun deregisterLocalDedicatedServer(payload: JsonElement): Result<Unit> =
        DsmControllerRest.deregisterLocalServer(payload).map { }

    fun getDsmSessionByPod(podName: String): Result<DsmSessionLookupResponse> =
        DsmControllerRest.getSessionByPod(podName).mapToModel()

    fun getDsmSessionTimeout(podName: String): Result<DsmSessionTimeoutResponse> =
        DsmControllerRest.getSessionTimeout(podName).mapToModel()

    fun listDsmDeployments(count: Int = 20, offset: Int = 0, name: String? = null): Result<DsmDeploymentListResponse> =
        DsmControllerRest.listDeployments(count, offset, name).mapToModel()

    fun getDsmDeployment(deployment: String): Result<DsmDeploymentWithOverride> =
        DsmControllerRest.getDeployment(deployment).mapToModel()

    fun createDsmDeployment(deployment: String, payload: JsonElement): Result<DsmDeploymentWithOverride> =
        DsmControllerRest.createDeployment(deployment, payload).mapToModel()

    fun deleteDsmDeployment(deployment: String): Result<Unit> =
        DsmControllerRest.deleteDeployment(deployment).map { }

    fun createDsmSession(payload: JsonElement): Result<DsmSessionResponse> =
        DsmControllerRest.createSession(payload).mapToModel()

    fun claimDsmSession(payload: JsonElement): Result<Unit> =
        DsmControllerRest.claimSession(payload).map { }

    fun fetchDsmSession(sessionId: String): Result<DsmSessionResponse> =
        DsmControllerRest.getSession(sessionId).mapToModel()

    fun cancelDsmSession(sessionId: String): Result<Unit> =
        DsmControllerRest.cancelSession(sessionId).map { }
}