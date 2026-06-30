package structs.accelbyte.ams

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import structs.accelbyte.common.AccelByteJson
import structs.accelbyte.common.AccelByteSerializable

// =============================================================================
// Account DTOs
// =============================================================================

@Serializable
data class AccountLimitsResponse(
    @SerialName("allowed_node_classes") val allowedNodeClasses: List<String>? = null,
    @SerialName("allowed_regions") val allowedRegions: List<String>? = null,
    @SerialName("fleet_count") val fleetCount: Int? = null,
    @SerialName("fleet_vm_count") val fleetVmCount: Int? = null,
    @SerialName("image_storage_quota_bytes") val imageStorageQuotaBytes: Long? = null
) : AccelByteSerializable {
    override fun toJsonString(): String = AccelByteJson.encodeToString(serializer(), this)
}

@Serializable
data class AccountResponse(
    val limits: AccountLimitsResponse? = null,
    val id: String? = null,
    val name: String? = null,
    val namespaces: List<String>? = null
) : AccelByteSerializable {
    override fun toJsonString(): String = AccelByteJson.encodeToString(serializer(), this)
}

@Serializable
data class AccountLinkTokenResponse(
    val token: String? = null
) : AccelByteSerializable {
    override fun toJsonString(): String = AccelByteJson.encodeToString(serializer(), this)
}

@Serializable
data class AccountLinkResponse(
    val limits: AccountLimitsResponse? = null,
    val id: String? = null,
    val name: String? = null,
    val namespaces: List<String>? = null
) : AccelByteSerializable {
    override fun toJsonString(): String = AccelByteJson.encodeToString(serializer(), this)
}

// =============================================================================
// AMS Info DTOs
// =============================================================================

@Serializable
data class RegionsResponse(
    val regions: List<String>? = null
) : AccelByteSerializable {
    override fun toJsonString(): String = AccelByteJson.encodeToString(serializer(), this)
}

@Serializable
data class CapacityResponse(
    val region: String? = null,
    @SerialName("vm_count") val vmCount: Int? = null
) : AccelByteSerializable {
    override fun toJsonString(): String = AccelByteJson.encodeToString(serializer(), this)
}

@Serializable
data class AvailableInstanceTypeResponse(
    val capacity: List<CapacityResponse>? = null,
    val description: String? = null,
    val id: String? = null,
    @SerialName("memory_gi_b") val memoryGiB: Float? = null,
    @SerialName("min_speed") val minSpeed: String? = null,
    val name: String? = null,
    @SerialName("owner_account_id") val ownerAccountId: String? = null,
    val provider: String? = null,
    @SerialName("virtual_cpu") val virtualCpu: Int? = null
) : AccelByteSerializable {
    override fun toJsonString(): String = AccelByteJson.encodeToString(serializer(), this)
}

@Serializable
data class SupportedInstancesResponse(
    @SerialName("available_instance_types") val availableInstanceTypes: List<AvailableInstanceTypeResponse>? = null
) : AccelByteSerializable {
    override fun toJsonString(): String = AccelByteJson.encodeToString(serializer(), this)
}

@Serializable
data class UploadUrlResponse(
    val url: String? = null
) : AccelByteSerializable {
    override fun toJsonString(): String = AccelByteJson.encodeToString(serializer(), this)
}

// =============================================================================
// QoS DTOs
// =============================================================================

@Serializable
data class QoSServerResponse(
    val alias: String? = null,
    val ip: String? = null,
    @SerialName("last_update") val lastUpdate: String? = null,
    val port: Int? = null,
    val region: String? = null,
    val status: String? = null
) : AccelByteSerializable {
    override fun toJsonString(): String = AccelByteJson.encodeToString(serializer(), this)
}

@Serializable
data class QoSRegionsResponse(
    val servers: List<QoSServerResponse>? = null
) : AccelByteSerializable {
    override fun toJsonString(): String = AccelByteJson.encodeToString(serializer(), this)
}

// =============================================================================
// Artifact DTOs
// =============================================================================

@Serializable
data class ArtifactResponse(
    @SerialName("artifact_type") val artifactType: String? = null,
    @SerialName("created_on") val createdOn: String? = null,
    @SerialName("ds_id") val dsId: String? = null,
    @SerialName("expires_on") val expiresOn: String? = null,
    val filename: String? = null,
    @SerialName("fleet_id") val fleetId: String? = null,
    val id: String? = null,
    @SerialName("image_id") val imageId: String? = null,
    val namespace: String? = null,
    val region: String? = null,
    @SerialName("size_bytes") val sizeBytes: Long? = null,
    val status: String? = null
) : AccelByteSerializable {
    override fun toJsonString(): String = AccelByteJson.encodeToString(serializer(), this)
}

@Serializable
data class ArtifactListResponse(
    val data: List<ArtifactResponse>? = null,
    @SerialName("total_data") val totalData: Int? = null
) : AccelByteSerializable {
    override fun toJsonString(): String = AccelByteJson.encodeToString(serializer(), this)
}

@Serializable
data class ArtifactUsageResponse(
    @SerialName("quota_bytes") val quotaBytes: Long? = null,
    @SerialName("remaining_bytes") val remainingBytes: Long? = null,
    @SerialName("used_bytes") val usedBytes: Long? = null
) : AccelByteSerializable {
    override fun toJsonString(): String = AccelByteJson.encodeToString(serializer(), this)
}

@Serializable
data class ArtifactUrlResponse(
    val url: String? = null
) : AccelByteSerializable {
    override fun toJsonString(): String = AccelByteJson.encodeToString(serializer(), this)
}

@Serializable
data class ArtifactSamplingRuleResponse(
    val collect: Boolean? = null,
    val percentage: Long? = null
) : AccelByteSerializable {
    override fun toJsonString(): String = AccelByteJson.encodeToString(serializer(), this)
}

@Serializable
data class ArtifactTypeSamplingRulesResponse(
    val crashed: ArtifactSamplingRuleResponse? = null,
    val success: ArtifactSamplingRuleResponse? = null
) : AccelByteSerializable {
    override fun toJsonString(): String = AccelByteJson.encodeToString(serializer(), this)
}

@Serializable
data class SamplingRulesResponse(
    val coredumps: ArtifactTypeSamplingRulesResponse? = null,
    val logs: ArtifactTypeSamplingRulesResponse? = null
) : AccelByteSerializable {
    override fun toJsonString(): String = AccelByteJson.encodeToString(serializer(), this)
}

// =============================================================================
// Development Server Configuration DTOs
// =============================================================================

@Serializable
data class DevServerConfigResponse(
    @SerialName("command_line_arguments") val commandLineArguments: String? = null,
    @SerialName("created_at") val createdAt: String? = null,
    @SerialName("expires_at") val expiresAt: String? = null,
    @SerialName("image_id") val imageId: String? = null,
    val name: String? = null
) : AccelByteSerializable {
    override fun toJsonString(): String = AccelByteJson.encodeToString(serializer(), this)
}

@Serializable
data class DevServerConfigListResponse(
    val data: List<DevServerConfigResponse>? = null,
    @SerialName("total_data") val totalData: Int? = null
) : AccelByteSerializable {
    override fun toJsonString(): String = AccelByteJson.encodeToString(serializer(), this)
}

// =============================================================================
// Fleet DTOs
// =============================================================================

@Serializable
data class PortConfigurationResponse(
    val name: String? = null,
    val protocol: String? = null
) : AccelByteSerializable {
    override fun toJsonString(): String = AccelByteJson.encodeToString(serializer(), this)
}

@Serializable
data class TimeoutResponse(
    val creation: Long? = null,
    val drain: Long? = null,
    val session: Long? = null,
    val unresponsive: Long? = null
) : AccelByteSerializable {
    override fun toJsonString(): String = AccelByteJson.encodeToString(serializer(), this)
}

@Serializable
data class ImageDeploymentProfileResponse(
    val commandLine: String? = null,
    @SerialName("image_id") val imageId: String? = null,
    @SerialName("port_configurations") val portConfigurations: List<PortConfigurationResponse>? = null,
    val timeout: TimeoutResponse? = null
) : AccelByteSerializable {
    override fun toJsonString(): String = AccelByteJson.encodeToString(serializer(), this)
}

@Serializable
data class DSHostConfigurationResponse(
    @SerialName("instance_id") val instanceId: String? = null,
    @SerialName("instance_provider") val instanceProvider: String? = null,
    @SerialName("instance_type") val instanceType: String? = null,
    @SerialName("servers_per_vm") val serversPerVm: Int? = null
) : AccelByteSerializable {
    override fun toJsonString(): String = AccelByteJson.encodeToString(serializer(), this)
}

@Serializable
data class RegionConfigResponse(
    @SerialName("buffer_size") val bufferSize: Int? = null,
    @SerialName("dynamic_buffer") val dynamicBuffer: Boolean? = null,
    @SerialName("max_server_count") val maxServerCount: Int? = null,
    @SerialName("min_server_count") val minServerCount: Int? = null,
    val region: String? = null
) : AccelByteSerializable {
    override fun toJsonString(): String = AccelByteJson.encodeToString(serializer(), this)
}

@Serializable
data class FleetListItemResponse(
    val active: Boolean? = null,
    val counts: List<FleetRegionalServerCountsResponse>? = null,
    val id: String? = null,
    val image: String? = null,
    @SerialName("instance_provider") val instanceProvider: String? = null,
    @SerialName("is_local") val isLocal: Boolean? = null,
    val name: String? = null,
    @SerialName("on_demand") val onDemand: Boolean? = null,
    val regions: List<String>? = null
) : AccelByteSerializable {
    override fun toJsonString(): String = AccelByteJson.encodeToString(serializer(), this)
}

@Serializable
data class FleetRegionalServerCountsResponse(
    @SerialName("claimed_server_count") val claimedServerCount: Int? = null,
    @SerialName("ready_server_count") val readyServerCount: Int? = null,
    val region: String? = null,
    @SerialName("running_vm_count") val runningVmCount: Int? = null,
    @SerialName("target_ds_count") val targetDsCount: Int? = null,
    @SerialName("target_vm_count") val targetVmCount: Int? = null
) : AccelByteSerializable {
    override fun toJsonString(): String = AccelByteJson.encodeToString(serializer(), this)
}

@Serializable
data class FleetListResponse(
    val fleets: List<FleetListItemResponse>? = null
) : AccelByteSerializable {
    override fun toJsonString(): String = AccelByteJson.encodeToString(serializer(), this)
}

@Serializable
data class FleetResponse(
    val active: Boolean? = null,
    @SerialName("claim_keys") val claimKeys: List<String>? = null,
    @SerialName("ds_host_configuration") val dsHostConfiguration: DSHostConfigurationResponse? = null,
    val id: String? = null,
    @SerialName("image_deployment_profile") val imageDeploymentProfile: ImageDeploymentProfileResponse? = null,
    @SerialName("is_local") val isLocal: Boolean? = null,
    val name: String? = null,
    @SerialName("on_demand") val onDemand: Boolean? = null,
    val regions: List<RegionConfigResponse>? = null,
    @SerialName("sampling_rules") val samplingRules: SamplingRulesResponse? = null
) : AccelByteSerializable {
    override fun toJsonString(): String = AccelByteJson.encodeToString(serializer(), this)
}

@Serializable
data class FleetServerInfoResponse(
    @SerialName("created_at") val createdAt: String? = null,
    @SerialName("fleet_id") val fleetId: String? = null,
    @SerialName("fleet_name") val fleetName: String? = null,
    @SerialName("image_cmd") val imageCmd: String? = null,
    @SerialName("image_id") val imageId: String? = null,
    @SerialName("instance_type") val instanceType: String? = null,
    @SerialName("ip_address") val ipAddress: String? = null,
    @SerialName("port_configuration") val portConfiguration: List<PortConfigurationResponse>? = null,
    val ports: Map<String, Int>? = null,
    val region: String? = null,
    @SerialName("server_configuration") val serverConfiguration: String? = null,
    @SerialName("server_id") val serverId: String? = null,
    @SerialName("session_id") val sessionId: String? = null,
    val status: String? = null
) : AccelByteSerializable {
    override fun toJsonString(): String = AccelByteJson.encodeToString(serializer(), this)
}

@Serializable
data class FleetServerListResponse(
    val paging: PagingInfoResponse? = null,
    val regions: List<FleetRegionalServerCountsResponse>? = null,
    val servers: List<FleetServerInfoResponse>? = null
) : AccelByteSerializable {
    override fun toJsonString(): String = AccelByteJson.encodeToString(serializer(), this)
}

@Serializable
data class PagingInfoResponse(
    @SerialName("current_page") val currentPage: Int? = null,
    @SerialName("has_next") val hasNext: Boolean? = null,
    @SerialName("has_pages") val hasPages: Boolean? = null,
    @SerialName("has_prev") val hasPrev: Boolean? = null,
    val next: String? = null,
    @SerialName("page_nums") val pageNums: List<Int>? = null,
    val previous: String? = null,
    val total: Int? = null
) : AccelByteSerializable {
    override fun toJsonString(): String = AccelByteJson.encodeToString(serializer(), this)
}

@Serializable
data class FleetClaimResponse(
    val ip: String? = null,
    val ports: Map<String, Int>? = null,
    val region: String? = null,
    @SerialName("server_id") val serverId: String? = null
) : AccelByteSerializable {
    override fun toJsonString(): String = AccelByteJson.encodeToString(serializer(), this)
}

// =============================================================================
// Image DTOs
// =============================================================================

@Serializable
data class TimeResponse(
    val ext: Long? = null,
    val loc: TimeLocationResponse? = null,
    val wall: Int? = null
) : AccelByteSerializable {
    override fun toJsonString(): String = AccelByteJson.encodeToString(serializer(), this)
}

@Serializable
data class TimeZoneResponse(
    @SerialName("is_dst") val isDst: Boolean? = null,
    val name: String? = null,
    val offset: Int? = null
) : AccelByteSerializable {
    override fun toJsonString(): String = AccelByteJson.encodeToString(serializer(), this)
}

@Serializable
data class TimeZoneTransResponse(
    val index: Int? = null,
    val isstd: Boolean? = null,
    val isutc: Boolean? = null,
    val when_: Long? = null
) : AccelByteSerializable {
    override fun toJsonString(): String = AccelByteJson.encodeToString(serializer(), this)
}

@Serializable
data class TimeLocationResponse(
    @SerialName("cache_end") val cacheEnd: Long? = null,
    @SerialName("cache_start") val cacheStart: Long? = null,
    @SerialName("cache_zone") val cacheZone: TimeZoneResponse? = null,
    val extend: String? = null,
    val name: String? = null,
    val tx: List<TimeZoneTransResponse>? = null,
    val zone: List<TimeZoneResponse>? = null
) : AccelByteSerializable {
    override fun toJsonString(): String = AccelByteJson.encodeToString(serializer(), this)
}

@Serializable
data class ReferencingFleetResponse(
    val environment: String? = null,
    @SerialName("fleet_id") val fleetId: String? = null,
    val namespace: String? = null
) : AccelByteSerializable {
    override fun toJsonString(): String = AccelByteJson.encodeToString(serializer(), this)
}

@Serializable
data class ImageResponse(
    @SerialName("created_at") val createdAt: String? = null,
    @SerialName("delete_at") val deleteAt: TimeResponse? = null,
    val executable: String? = null,
    val id: String? = null,
    @SerialName("is_protected") val isProtected: Boolean? = null,
    val name: String? = null,
    @SerialName("referencing_fleets") val referencingFleets: List<ReferencingFleetResponse>? = null,
    @SerialName("size_in_byte") val sizeInByte: Long? = null,
    val status: String? = null,
    val tags: List<String>? = null,
    @SerialName("uploaded_at") val uploadedAt: String? = null,
    @SerialName("uploaded_by") val uploadedBy: String? = null
) : AccelByteSerializable {
    override fun toJsonString(): String = AccelByteJson.encodeToString(serializer(), this)
}

@Serializable
data class ImageListItemResponse(
    @SerialName("created_at") val createdAt: String? = null,
    @SerialName("delete_at") val deleteAt: TimeResponse? = null,
    val executable: String? = null,
    val id: String? = null,
    @SerialName("is_protected") val isProtected: Boolean? = null,
    val name: String? = null,
    @SerialName("referencing_fleets") val referencingFleets: Int? = null,
    @SerialName("size_in_byte") val sizeInByte: Long? = null,
    val status: String? = null,
    val tags: List<String>? = null,
    @SerialName("uploaded_at") val uploadedAt: String? = null,
    @SerialName("uploaded_by") val uploadedBy: String? = null
) : AccelByteSerializable {
    override fun toJsonString(): String = AccelByteJson.encodeToString(serializer(), this)
}

@Serializable
data class ImageListResponse(
    val images: List<ImageListItemResponse>? = null
) : AccelByteSerializable {
    override fun toJsonString(): String = AccelByteJson.encodeToString(serializer(), this)
}

@Serializable
data class ImageStorageResponse(
    @SerialName("current_marked_for_deletion_bytes") val currentMarkedForDeletionBytes: Long? = null,
    @SerialName("current_usage_bytes") val currentUsageBytes: Long? = null,
    @SerialName("quota_bytes") val quotaBytes: Long? = null
) : AccelByteSerializable {
    override fun toJsonString(): String = AccelByteJson.encodeToString(serializer(), this)
}

// =============================================================================
// Server DTOs
// =============================================================================

@Serializable
data class FleetServerConnectionInfoResponse(
    @SerialName("expires_at") val expiresAt: TimeResponse? = null,
    val host: String? = null,
    @SerialName("logstream_port") val logstreamPort: Int? = null,
    val secret: String? = null
) : AccelByteSerializable {
    override fun toJsonString(): String = AccelByteJson.encodeToString(serializer(), this)
}

@Serializable
data class FleetServerHistoryEventResponse(
    @SerialName("created_at") val createdAt: String? = null,
    @SerialName("exit_code") val exitCode: Int? = null,
    @SerialName("fleet_id") val fleetId: String? = null,
    @SerialName("new_state") val newState: String? = null,
    @SerialName("old_state") val oldState: String? = null,
    val reason: String? = null,
    @SerialName("server_id") val serverId: String? = null
) : AccelByteSerializable {
    override fun toJsonString(): String = AccelByteJson.encodeToString(serializer(), this)
}

@Serializable
data class FleetServerHistoryListResponse(
    val events: List<FleetServerHistoryEventResponse>? = null
) : AccelByteSerializable {
    override fun toJsonString(): String = AccelByteJson.encodeToString(serializer(), this)
}

@Serializable
data class DSHistoryEventResponse(
    @SerialName("created_at") val createdAt: String? = null,
    @SerialName("exit_code") val exitCode: Int? = null,
    @SerialName("ip_address") val ipAddress: String? = null,
    val reason: String? = null,
    val region: String? = null,
    @SerialName("server_id") val serverId: String? = null,
    @SerialName("session_id") val sessionId: String? = null,
    val status: String? = null
) : AccelByteSerializable {
    override fun toJsonString(): String = AccelByteJson.encodeToString(serializer(), this)
}

@Serializable
data class ServerHistoryListResponse(
    val events: List<DSHistoryEventResponse>? = null,
    val paging: PagingInfoResponse? = null
) : AccelByteSerializable {
    override fun toJsonString(): String = AccelByteJson.encodeToString(serializer(), this)
}

@Serializable
data class ServerHistoryResponse(
    val events: List<DSHistoryEventResponse>? = null
) : AccelByteSerializable {
    override fun toJsonString(): String = AccelByteJson.encodeToString(serializer(), this)
}
