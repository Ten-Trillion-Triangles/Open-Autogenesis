package structs.accelbyte.dsm

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import structs.accelbyte.common.AccelByteJson
import structs.accelbyte.common.AccelByteSerializable

@Serializable
data class DsmControllerMessage(
    @SerialName("Attributes") val attributes: List<String>,
    @SerialName("Code") val code: String,
    @SerialName("CodeName") val codeName: String,
    @SerialName("Section") val section: String,
    @SerialName("Service") val service: String,
    @SerialName("Text") val text: String
) : AccelByteSerializable {
    override fun toJsonString(): String = AccelByteJson.encodeToString(serializer(), this)
}

@Serializable
data class DsmPagingCursor(
    @SerialName("next") val next: String,
    @SerialName("previous") val previous: String
) : AccelByteSerializable {
    override fun toJsonString(): String = AccelByteJson.encodeToString(serializer(), this)
}

@Serializable
data class DsmServerDetails(
    @SerialName("allocation_id") val allocationId: String,
    @SerialName("pod_name") val podName: String,
    @SerialName("ip") val ip: String? = null,
    @SerialName("port") val port: Int? = null,
    @SerialName("region") val region: String? = null,
    @SerialName("status") val status: String? = null,
    @SerialName("session_id") val sessionId: String? = null,
    @SerialName("deployment") val deployment: String? = null,
    @SerialName("namespace") val namespace: String? = null,
    @SerialName("created_at") val createdAt: String? = null,
    @SerialName("last_update") val lastUpdate: String? = null,
    @SerialName("cpu_limit") val cpuLimit: Int? = null,
    @SerialName("mem_limit") val memLimit: Int? = null,
    @SerialName("game_version") val gameVersion: String? = null,
    @SerialName("image_version") val imageVersion: String? = null,
    @SerialName("params") val params: String? = null,
    @SerialName("protocol") val protocol: String? = null
) : AccelByteSerializable {
    override fun toJsonString(): String = AccelByteJson.encodeToString(serializer(), this)
}

@Serializable
data class DsmServerListResponse(
    @SerialName("paging") val paging: DsmPagingCursor,
    @SerialName("servers") val servers: List<DsmServerDetails>
) : AccelByteSerializable {
    override fun toJsonString(): String = AccelByteJson.encodeToString(serializer(), this)
}

@Serializable
data class DsmDetailedCountResponse(
    @SerialName("busy_count") val busyCount: Int,
    @SerialName("creating_count") val creatingCount: Int,
    @SerialName("ready_count") val readyCount: Int,
    @SerialName("unreachable_count") val unreachableCount: Int
) : AccelByteSerializable {
    override fun toJsonString(): String = AccelByteJson.encodeToString(serializer(), this)
}

@Serializable
data class DsmSessionLookupResponse(
    @SerialName("session_id") val sessionId: String
) : AccelByteSerializable {
    override fun toJsonString(): String = AccelByteJson.encodeToString(serializer(), this)
}

@Serializable
data class DsmSessionTimeoutResponse(
    @SerialName("session_timeout") val sessionTimeout: Int
) : AccelByteSerializable {
    override fun toJsonString(): String = AccelByteJson.encodeToString(serializer(), this)
}

@Serializable
data class DsmPodCountConfigOverride(
    @SerialName("buffer_count") val bufferCount: Int,
    @SerialName("buffer_percent") val bufferPercent: Int,
    @SerialName("max_count") val maxCount: Int,
    @SerialName("min_count") val minCount: Int,
    @SerialName("name") val name: String,
    @SerialName("unlimited") val unlimited: Boolean,
    @SerialName("use_buffer_percent") val useBufferPercent: Boolean
) : AccelByteSerializable {
    override fun toJsonString(): String = AccelByteJson.encodeToString(serializer(), this)
}

@Serializable
data class DsmDeploymentConfigOverride(
    @SerialName("buffer_count") val bufferCount: Int,
    @SerialName("buffer_percent") val bufferPercent: Int,
    @SerialName("configuration") val configuration: String,
    @SerialName("enable_region_overrides") val enableRegionOverrides: Boolean,
    @SerialName("extendable_session") val extendableSession: Boolean? = null,
    @SerialName("game_version") val gameVersion: String,
    @SerialName("max_count") val maxCount: Int,
    @SerialName("min_count") val minCount: Int,
    @SerialName("name") val name: String,
    @SerialName("region_overrides") val regionOverrides: Map<String, DsmPodCountConfigOverride>? = null,
    @SerialName("regions") val regions: List<String>? = null,
    @SerialName("session_timeout") val sessionTimeout: Int? = null,
    @SerialName("unlimited") val unlimited: Boolean,
    @SerialName("use_buffer_percent") val useBufferPercent: Boolean
) : AccelByteSerializable {
    override fun toJsonString(): String = AccelByteJson.encodeToString(serializer(), this)
}

@Serializable
data class DsmDeploymentWithOverride(
    @SerialName("allow_version_override") val allowVersionOverride: Boolean,
    @SerialName("buffer_count") val bufferCount: Int,
    @SerialName("buffer_percent") val bufferPercent: Int,
    @SerialName("configuration") val configuration: String,
    @SerialName("createdAt") val createdAt: String,
    @SerialName("enable_region_overrides") val enableRegionOverrides: Boolean,
    @SerialName("extendable_session") val extendableSession: Boolean? = null,
    @SerialName("game_version") val gameVersion: String,
    @SerialName("max_count") val maxCount: Int,
    @SerialName("min_count") val minCount: Int,
    @SerialName("modifiedBy") val modifiedBy: String,
    @SerialName("name") val name: String,
    @SerialName("namespace") val namespace: String,
    @SerialName("overrides") val overrides: Map<String, DsmDeploymentConfigOverride>? = null,
    @SerialName("region_overrides") val regionOverrides: Map<String, DsmPodCountConfigOverride>? = null,
    @SerialName("regions") val regions: List<String>? = null,
    @SerialName("session_timeout") val sessionTimeout: Int? = null,
    @SerialName("unlimited") val unlimited: Boolean,
    @SerialName("updatedAt") val updatedAt: String,
    @SerialName("use_buffer_percent") val useBufferPercent: Boolean
) : AccelByteSerializable {
    override fun toJsonString(): String = AccelByteJson.encodeToString(serializer(), this)
}

@Serializable
data class DsmDeploymentListResponse(
    @SerialName("deployments") val deployments: List<DsmDeploymentWithOverride>,
    @SerialName("paging") val paging: DsmPagingCursor
) : AccelByteSerializable {
    override fun toJsonString(): String = AccelByteJson.encodeToString(serializer(), this)
}

@Serializable
data class DsmSession(
    @SerialName("Server") val server: DsmServerDetails,
    @SerialName("id") val id: String,
    @SerialName("namespace") val namespace: String,
    @SerialName("provider") val provider: String,
    @SerialName("region") val region: String
) : AccelByteSerializable {
    override fun toJsonString(): String = AccelByteJson.encodeToString(serializer(), this)
}

@Serializable
data class DsmSessionResponse(
    @SerialName("session") val session: DsmSession
) : AccelByteSerializable {
    override fun toJsonString(): String = AccelByteJson.encodeToString(serializer(), this)
}
