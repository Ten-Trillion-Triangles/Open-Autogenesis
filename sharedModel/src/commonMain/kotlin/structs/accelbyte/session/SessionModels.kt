package structs.accelbyte.session

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import structs.accelbyte.common.AccelByteJson
import structs.accelbyte.common.AccelByteSerializable
import structs.accelbyte.common.CursorPagination

@Serializable
data class JoinCodeRequest(
    val code: String
) : AccelByteSerializable {
    override fun toJsonString(): String = AccelByteJson.encodeToString(serializer(), this)
}

@Serializable
data class SessionBrowserFilter(
    @SerialName("configuration_name") val configurationName: String? = null,
    @SerialName("ds_pod_name") val dsPodName: String? = null,
    @SerialName("from_time") val fromTime: String? = null,
    @SerialName("game_mode") val gameMode: String? = null,
    @SerialName("is_persistent") val isPersistent: Boolean? = null,
    @SerialName("is_soft_deleted") val isSoftDeleted: Boolean? = null,
    val joinability: String? = null,
    val limit: Int? = null,
    @SerialName("match_pool") val matchPool: String? = null,
    @SerialName("member_id") val memberId: String? = null,
    val offset: Int? = null,
    val order: String? = null,
    @SerialName("order_by") val orderBy: String? = null,
    @SerialName("session_id") val sessionId: String? = null,
    val status: String? = null,
    @SerialName("status_v2") val statusV2: String? = null,
    @SerialName("to_time") val toTime: String? = null
) : AccelByteSerializable {
    override fun toJsonString(): String = AccelByteJson.encodeToString(serializer(), this)
}

@Serializable
data class CreateGameSessionRequest(
    @SerialName("game_mode") val gameMode: String,
    @SerialName("max_players") val maxPlayers: Int,
    val namespace: String,
    val region: String,
    @SerialName("min_players") val minPlayers: Int? = null,
    @SerialName("configuration_name") val configurationName: String? = null,
    @SerialName("client_version") val clientVersion: String? = null,
    val deployment: String? = null,
    @SerialName("match_pool") val matchPool: String? = null,
    @SerialName("backfill_ticket_id") val backfillTicketId: String? = null,
    @SerialName("inactive_timeout") val inactiveTimeout: Int? = null,
    @SerialName("invite_timeout") val inviteTimeout: Int? = null,
    val joinability: String? = null,
    val type: String? = null,
    @SerialName("ds_source") val dsSource: String? = null,
    @SerialName("auto_join") val autoJoin: Boolean? = null,
    @SerialName("text_chat") val textChat: Boolean? = null,
    @SerialName("text_chat_mode") val textChatMode: String? = null,
    val password: String? = null,
    @SerialName("tie_teams_session_lifetime") val tieTeamsSessionLifetime: Int? = null,
    @SerialName("server_name") val serverName: String? = null,
    val storage: JsonElement? = null,
    @SerialName("ticket_ids") val ticketIds: List<String>? = null,
    @SerialName("preferred_claim_keys") val preferredClaimKeys: List<String>? = null,
    @SerialName("fallback_claim_keys") val fallbackClaimKeys: List<String>? = null,
    @SerialName("requested_regions") val requestedRegions: List<String>? = null,
    val teams: List<TeamModel>? = null
) : AccelByteSerializable {
    override fun toJsonString(): String = AccelByteJson.encodeToString(serializer(), this)
}

@Serializable
data class UpdateGameSessionRequest(
    @SerialName("game_mode") val gameMode: String? = null,
    @SerialName("max_players") val maxPlayers: Int? = null,
    @SerialName("min_players") val minPlayers: Int? = null,
    val deployment: String? = null,
    @SerialName("match_pool") val matchPool: String? = null,
    @SerialName("backfill_ticket_id") val backfillTicketId: String? = null,
    @SerialName("inactive_timeout") val inactiveTimeout: Int? = null,
    @SerialName("invite_timeout") val inviteTimeout: Int? = null,
    val joinability: String? = null,
    @SerialName("text_chat") val textChat: Boolean? = null,
    @SerialName("text_chat_mode") val textChatMode: String? = null,
    val password: String? = null,
    val storage: JsonElement? = null,
    val attributes: JsonElement? = null,
    @SerialName("ticket_ids") val ticketIds: List<String>? = null
) : AccelByteSerializable {
    override fun toJsonString(): String = AccelByteJson.encodeToString(serializer(), this)
}

@Serializable
data class JoinSessionRequest(
    val password: String? = null
) : AccelByteSerializable {
    override fun toJsonString(): String = AccelByteJson.encodeToString(serializer(), this)
}

@Serializable
data class JoinByCodeRequest(
    val code: String
) : AccelByteSerializable {
    override fun toJsonString(): String = AccelByteJson.encodeToString(serializer(), this)
}

@Serializable
data class AppendTeamRequest(
    val members: List<String>
) : AccelByteSerializable {
    override fun toJsonString(): String = AccelByteJson.encodeToString(serializer(), this)
}

@Serializable
data class SessionInviteRequest(
    @SerialName("user_id") val userId: String,
    val note: String? = null
) : AccelByteSerializable {
    override fun toJsonString(): String = AccelByteJson.encodeToString(serializer(), this)
}

@Serializable
data class UpdateBackfillTicketRequest(
    @SerialName("backfill_ticket_id") val backfillTicketId: String
) : AccelByteSerializable {
    override fun toJsonString(): String = AccelByteJson.encodeToString(serializer(), this)
}

@Serializable
data class UpdateDSInfoRequest(
    val status: String? = null,
    @SerialName("status_v2") val statusV2: String? = null
) : AccelByteSerializable {
    override fun toJsonString(): String = AccelByteJson.encodeToString(serializer(), this)
}

@Serializable
data class CreatePartyRequest(
    @SerialName("max_players") val maxPlayers: Int,
    @SerialName("min_players") val minPlayers: Int? = null,
    @SerialName("configuration_name") val configurationName: String? = null,
    @SerialName("inactive_timeout") val inactiveTimeout: Int? = null,
    @SerialName("invite_timeout") val inviteTimeout: Int? = null,
    val joinability: String? = null,
    @SerialName("text_chat") val textChat: Boolean? = null,
    val type: String? = null,
    val password: String? = null,
    val members: List<RequestMember>? = null,
    val attributes: JsonElement? = null
) : AccelByteSerializable {
    override fun toJsonString(): String = AccelByteJson.encodeToString(serializer(), this)
}

@Serializable
data class RequestMember(
    val id: String,
    @SerialName("platform_id") val platformId: String? = null,
    @SerialName("platform_user_id") val platformUserId: String? = null
) : AccelByteSerializable {
    override fun toJsonString(): String = AccelByteJson.encodeToString(serializer(), this)
}

@Serializable
data class GameServerModel(
    val alias: String,
    val ip: String? = null,
    val port: Int? = null,
    val region: String,
    val status: String,
    @SerialName("last_update") val lastUpdate: String,
    val namespace: String,
    @SerialName("session_id") val sessionId: String,
    val provider: String? = null,
    val description: String? = null,
    val deployment: String? = null,
    @SerialName("pod_name") val podName: String? = null,
    val protocol: String? = null,
    @SerialName("image_version") val imageVersion: String? = null,
    @SerialName("game_version") val gameVersion: String? = null,
    val source: String,
    @SerialName("custom_attribute") val customAttribute: JsonElement? = null,
    @SerialName("extend_region") val extendRegion: String? = null
) : AccelByteSerializable {
    override fun toJsonString(): String = AccelByteJson.encodeToString(serializer(), this)
}

@Serializable
data class DsInformationModel(
    @SerialName("created_at") val createdAt: String,
    @SerialName("requested_at") val requestedAt: String,
    val status: String? = null,
    @SerialName("status_v2") val statusV2: String? = null,
    val server: GameServerModel? = null
) : AccelByteSerializable {
    override fun toJsonString(): String = AccelByteJson.encodeToString(serializer(), this)
}

@Serializable
data class PartyMembersModel(
    @SerialName("party_id") val partyId: String? = null,
    @SerialName("user_ids") val userIds: List<String>? = null
) : AccelByteSerializable {
    override fun toJsonString(): String = AccelByteJson.encodeToString(serializer(), this)
}

@Serializable
data class TeamModel(
    @SerialName("team_id") val teamId: String? = null,
    @SerialName("user_ids") val userIds: List<String>? = null,
    val parties: List<PartyMembersModel>? = null
) : AccelByteSerializable {
    override fun toJsonString(): String = AccelByteJson.encodeToString(serializer(), this)
}

@Serializable
data class UserResponseModel(
    val id: String,
    @SerialName("platform_id") val platformId: String,
    @SerialName("platform_user_id") val platformUserId: String,
    val status: String,
    @SerialName("status_v2") val statusV2: String,
    @SerialName("updated_at") val updatedAt: String,
    @SerialName("previous_status") val previousStatus: String? = null
) : AccelByteSerializable {
    override fun toJsonString(): String = AccelByteJson.encodeToString(serializer(), this)
}

@Serializable
data class AsyncProcessDsRequestModel(
    val async: Boolean? = null,
    val timeout: Int? = null
) : AccelByteSerializable {
    override fun toJsonString(): String = AccelByteJson.encodeToString(serializer(), this)
}

@Serializable
data class ExtendConfigurationModel(
    @SerialName("app_name") val appName: String? = null,
    @SerialName("custom_url") val customUrl: String? = null,
    @SerialName("function_flag") val functionFlag: Int? = null
) : AccelByteSerializable {
    override fun toJsonString(): String = AccelByteJson.encodeToString(serializer(), this)
}

@Serializable
data class NativeSessionSettingModel(
    @SerialName("psn_disable_system_ui_menu") val psnDisableSystemUIMenu: List<String>? = null,
    @SerialName("psn_service_label") val psnServiceLabel: Int,
    @SerialName("psn_supported_platforms") val psnSupportedPlatforms: List<String>? = null,
    @SerialName("session_title") val sessionTitle: String,
    @SerialName("should_sync") val shouldSync: Boolean? = null,
    @SerialName("xbox_allow_cross_platform") val xboxAllowCrossPlatform: Boolean,
    @SerialName("xbox_sandbox_id") val xboxSandboxId: String,
    @SerialName("xbox_service_config_id") val xboxServiceConfigId: String,
    @SerialName("xbox_session_template_name") val xboxSessionTemplateName: String,
    @SerialName("xbox_title_id") val xboxTitleId: String
) : AccelByteSerializable {
    override fun toJsonString(): String = AccelByteJson.encodeToString(serializer(), this)
}

@Serializable
data class PublicConfigurationModel(
    val name: String,
    @SerialName("min_players") val minPlayers: Int,
    @SerialName("max_players") val maxPlayers: Int,
    @SerialName("match_pool") val matchPool: String? = null,
    val deployment: String,
    val namespace: String? = null,
    @SerialName("custom_attribute") val customAttributes: JsonElement? = null,
    val attributes: JsonElement? = null,
    @SerialName("auto_join") val autoJoin: Boolean,
    val persistent: Boolean,
    @SerialName("text_chat") val textChat: Boolean,
    @SerialName("text_chat_mode") val textChatMode: String? = null,
    @SerialName("ttl_hours") val ttlHours: Int? = null,
    val type: String? = null,
    @SerialName("async_process_ds_request") val asyncProcessDsRequest: AsyncProcessDsRequestModel? = null,
    @SerialName("grpc_session_config") val extendConfiguration: ExtendConfigurationModel? = null,
    @SerialName("native_session_setting") val nativeSessionSetting: NativeSessionSettingModel? = null,
    @SerialName("client_version") val clientVersion: String? = null,
    @SerialName("ds_manual_set_ready") val dsManualSetReady: Boolean? = null,
    @SerialName("disable_code_generation") val disableCodeGeneration: Boolean? = null,
    @SerialName("disable_resend_invite") val disableResendInvite: Boolean? = null,
    @SerialName("enable_secret") val enableSecret: Boolean? = null,
    @SerialName("immutable_storage") val immutableStorage: Boolean? = null,
    @SerialName("inactive_timeout") val inactiveTimeout: Int? = null,
    @SerialName("invite_timeout") val inviteTimeout: Int? = null,
    @SerialName("leader_election_grace_period") val leaderElectionGracePeriod: Int? = null,
    @SerialName("manual_rejoin") val manualRejoin: Boolean? = null,
    @SerialName("max_active_session") val maxActiveSession: Int? = null,
    @SerialName("party_code_generator_string") val partyCodeGeneratorString: String? = null,
    @SerialName("party_code_length") val partyCodeLength: Int? = null,
    @SerialName("preferred_claim_keys") val preferredClaimKeys: List<String>? = null,
    @SerialName("requested_regions") val requestedRegions: List<String>? = null,
    @SerialName("tie_teams_session_lifetime") val tieTeamsSessionLifetime: Int? = null
) : AccelByteSerializable {
    override fun toJsonString(): String = AccelByteJson.encodeToString(serializer(), this)
}

@Serializable
data class GameSessionDetailResponse(
    @SerialName("ds_information") val dsInformation: DsInformationModel? = null,
    val configuration: PublicConfigurationModel? = null,
    val attributes: JsonElement? = null,
    val storage: JsonElement? = null,
    @SerialName("backfill_ticket_id") val backfillTicketId: String? = null,
    val code: String? = null,
    @SerialName("created_at") val createdAt: String = "",
    @SerialName("created_by") val createdBy: String = "",
    @SerialName("expired_at") val expiredAt: String? = null,
    val id: String = "",
    @SerialName("is_active") val isActive: Boolean = false,
    @SerialName("is_full") val isFull: Boolean = false,
    @SerialName("leader_id") val leaderId: String = "",
    @SerialName("match_pool") val matchPool: String? = null,
    val members: List<UserResponseModel>? = null,
    val namespace: String = "",
    val teams: List<TeamModel>? = null,
    @SerialName("ticket_ids") val ticketIds: List<String>? = null,
    @SerialName("updated_at") val updatedAt: String = "",
    val version: Int = 0
) : AccelByteSerializable {
    override fun toJsonString(): String = AccelByteJson.encodeToString(serializer(), this)
}

@Serializable
data class GameSessionListResponse(
    val data: List<GameSessionDetailResponse> = emptyList(),
    val paging: CursorPagination? = null
) : AccelByteSerializable {
    override fun toJsonString(): String = AccelByteJson.encodeToString(serializer(), this)
}

@Serializable
data class SessionInviteResponseModel(
    @SerialName("platform_user_id") val platformUserId: String = ""
) : AccelByteSerializable {
    override fun toJsonString(): String = AccelByteJson.encodeToString(serializer(), this)
}

@Serializable
data class PartySessionResponse(
    val configuration: PublicConfigurationModel? = null,
    val attributes: JsonElement? = null,
    val code: String? = null,
    @SerialName("created_at") val createdAt: String = "",
    @SerialName("created_by") val createdBy: String = "",
    @SerialName("expired_at") val expiredAt: String? = null,
    val id: String = "",
    @SerialName("is_active") val isActive: Boolean = false,
    @SerialName("is_full") val isFull: Boolean = false,
    @SerialName("leader_id") val leaderId: String = "",
    val members: List<UserResponseModel>? = null,
    val namespace: String = "",
    val storage: JsonElement? = null,
    @SerialName("updated_at") val updatedAt: String = "",
    val version: Int = 0
) : AccelByteSerializable {
    override fun toJsonString(): String = AccelByteJson.encodeToString(serializer(), this)
}

@Serializable
data class PartyQueryResponse(
    val data: List<PartySessionResponse>,
    val paging: CursorPagination
) : AccelByteSerializable {
    override fun toJsonString(): String = AccelByteJson.encodeToString(serializer(), this)
}