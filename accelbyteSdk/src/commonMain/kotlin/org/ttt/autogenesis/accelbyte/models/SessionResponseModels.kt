package org.ttt.autogenesis.accelbyte.models

import kotlin.js.Json

/**
 * Port configuration for AMS (AccelByte Multiplayer Service).
 *
 * @property name The name of the port configuration.
 * @property protocol The protocol for this port (e.g., TCP, UDP).
 */
data class PortConfigurationAmsModel(
    val name : String,
    val protocol : String
) : AccelByteResponse {
    companion object
    {
        fun fromJson(json : Json) : PortConfigurationAmsModel = PortConfigurationAmsModel(
            name = json.requireString("name"),
            protocol = json.requireString("protocol")
        )
    }
}

/**
 * A game server instance in the session system.
 *
 * @property alias The alias of this game server.
 * @property ip The IP address of the server.
 * @property port The port number of the server.
 * @property region The region where the server is located.
 * @property status The current status of the server.
 * @property lastUpdate Timestamp of the last update to this server.
 * @property namespace The namespace where this server exists.
 * @property sessionId The session ID associated with this server.
 * @property provider The cloud provider for this server.
 * @property description The description of this server.
 * @property deployment The deployment configuration.
 * @property podName The Kubernetes pod name.
 * @property protocol The protocol being used.
 * @property imageVersion The container image version.
 * @property gameVersion The game version running on this server.
 * @property source The source of this server entry.
 * @property customAttribute Custom attributes as a string.
 * @property extendRegion Extended region information.
 */
data class GameServerModel(
    val alias : String,
    val ip : String?,
    val port : Int?,
    val region : String,
    val status : String,
    val lastUpdate : String,
    val namespace : String,
    val sessionId : String,
    val provider : String?,
    val description : String?,
    val deployment : String?,
    val podName : String?,
    val protocol : String?,
    val imageVersion : String?,
    val gameVersion : String?,
    val source : String,
    val customAttribute : String?,
    val extendRegion : String?
) : AccelByteResponse {
    companion object
    {
        fun fromJson(json : Json) : GameServerModel = GameServerModel(
            alias = json.requireString("alias"),
            ip = json.optString("ip"),
            port = json.optInt("port"),
            region = json.requireString("region"),
            status = json.requireString("status"),
            lastUpdate = json.requireString("last_update"),
            namespace = json.requireString("namespace"),
            sessionId = json.requireString("session_id"),
            provider = json.optString("provider"),
            description = json.optString("description"),
            deployment = json.optString("deployment"),
            podName = json.optString("pod_name"),
            protocol = json.optString("protocol"),
            imageVersion = json.optString("image_version"),
            gameVersion = json.optString("game_version"),
            source = json.requireString("source"),
            customAttribute = json.optString("custom_attribute"),
            extendRegion = json.optString("extend_region")
        )
    }
}

/**
 * Detailed information about a dedicated server allocation.
 *
 * @property createdAt Timestamp when this allocation was created.
 * @property requestedAt Timestamp when the server was requested.
 * @property status The current status of the allocation.
 * @property statusV2 The V2 status of the allocation.
 * @property server The game server details.
 */
data class DsInformationModel(
    val createdAt : String,
    val requestedAt : String,
    val status : String?,
    val statusV2 : String?,
    val server : GameServerModel?
) : AccelByteResponse {
    companion object
    {
        fun fromJson(json : Json) : DsInformationModel = DsInformationModel(
            createdAt = json.requireString("CreatedAt"),
            requestedAt = json.requireString("RequestedAt"),
            status = json.optString("Status"),
            statusV2 = json.optString("StatusV2"),
            server = json.optJson("Server")?.let(GameServerModel::fromJson)
        )
    }
}

/**
 * Members of a party in a game session.
 *
 * @property partyId The unique identifier of this party.
 * @property userIds List of user IDs in this party.
 */
data class PartyMembersModel(
    val partyId : String?,
    val userIds : List<String>?
) : AccelByteResponse {
    companion object
    {
        fun fromJson(json : Json) : PartyMembersModel = PartyMembersModel(
            partyId = json.optString("partyID"),
            userIds = json.optJsonArray("userIDs")?.mapNotNull { it?.toString() }
        )
    }
}

/**
 * A team within a game session containing users and parties.
 *
 * @property teamId The unique identifier of this team.
 * @property userIds List of user IDs in this team.
 * @property parties List of parties in this team.
 */
data class TeamModel(
    val teamId : String?,
    val userIds : List<String>?,
    val parties : List<PartyMembersModel>?
) : AccelByteResponse {
    companion object
    {
        fun fromJson(json : Json) : TeamModel = TeamModel(
            teamId = json.optString("teamID"),
            userIds = json.optJsonArray("userIDs")?.mapNotNull { it?.toString() },
            parties = json.optJsonList("parties").map(PartyMembersModel::fromJson).takeIf { it.isNotEmpty() }
        )
    }
}

/**
 * A user in the context of a game session.
 *
 * @property id The unique identifier of this user.
 * @property platformId The platform identifier.
 * @property platformUserId The platform-specific user identifier.
 * @property status The current status of this user.
 * @property statusV2 The V2 status of this user.
 * @property updatedAt Timestamp when this user's status was last updated.
 * @property previousStatus The previous status before the current one.
 */
data class UserResponseModel(
    val id : String,
    val platformId : String,
    val platformUserId : String,
    val status : String,
    val statusV2 : String,
    val updatedAt : String,
    val previousStatus : String?
) : AccelByteResponse {
    companion object
    {
        fun fromJson(json : Json) : UserResponseModel = UserResponseModel(
            id = json.requireString("id"),
            platformId = json.requireString("platformID"),
            platformUserId = json.requireString("platformUserID"),
            status = json.requireString("status"),
            statusV2 = json.requireString("statusV2"),
            updatedAt = json.requireString("updatedAt"),
            previousStatus = json.optString("previousStatus")
        )
    }
}

/**
 * Configuration for asynchronous dedicated server processing.
 *
 * @property async Whether the operation is asynchronous.
 * @property timeout The timeout value in seconds.
 */
data class AsyncProcessDsRequestModel(
    val async : Boolean?,
    val timeout : Int?
) : AccelByteResponse {
    companion object
    {
        fun fromJson(json : Json) : AsyncProcessDsRequestModel = AsyncProcessDsRequestModel(
            async = json.optBoolean("async"),
            timeout = json.optInt("timeout")
        )
    }
}

/**
 * Extended configuration settings for a session.
 *
 * @property appName The application name.
 * @property customURL A custom URL for this configuration.
 * @property functionFlag A flag for function configuration.
 */
data class ExtendConfigurationModel(
    val appName : String?,
    val customURL : String?,
    val functionFlag : Int?
) : AccelByteResponse {
    companion object
    {
        fun fromJson(json : Json) : ExtendConfigurationModel = ExtendConfigurationModel(
            appName = json.optString("appName"),
            customURL = json.optString("customURL"),
            functionFlag = json.optInt("functionFlag")
        )
    }
}

/**
 * Native session settings for console platforms (PSN, Xbox).
 *
 * @property psnDisableSystemUIMenu List of PSN system UI menus to disable.
 * @property psnServiceLabel The PSN service label.
 * @property psnSupportedPlatforms List of supported PSN platforms.
 * @property sessionTitle The title of the session.
 * @property shouldSync Whether to sync session state.
 * @property xboxAllowCrossPlatform Whether cross-platform play is allowed.
 * @property xboxSandboxId The Xbox sandbox ID.
 * @property xboxServiceConfigId The Xbox service configuration ID.
 * @property xboxSessionTemplateName The Xbox session template name.
 * @property xboxTitleId The Xbox title ID.
 * @property localizedSessionName Localized session names as JSON.
 */
data class NativeSessionSettingModel(
    val psnDisableSystemUIMenu : List<String>?,
    val psnServiceLabel : Int,
    val psnSupportedPlatforms : List<String>?,
    val sessionTitle : String,
    val shouldSync : Boolean?,
    val xboxAllowCrossPlatform : Boolean,
    val xboxSandboxId : String,
    val xboxServiceConfigId : String,
    val xboxSessionTemplateName : String,
    val xboxTitleId : String,
    val localizedSessionName : Json?
) : AccelByteResponse {
    companion object
    {
        fun fromJson(json : Json) : NativeSessionSettingModel = NativeSessionSettingModel(
            psnDisableSystemUIMenu = json.optJsonArray("PSNDisableSystemUIMenu")?.mapNotNull { it?.toString() },
            psnServiceLabel = json.requireInt("PSNServiceLabel"),
            psnSupportedPlatforms = json.optJsonArray("PSNSupportedPlatforms")?.mapNotNull { it?.toString() },
            sessionTitle = json.requireString("SessionTitle"),
            shouldSync = json.optBoolean("ShouldSync"),
            xboxAllowCrossPlatform = json.requireBoolean("XboxAllowCrossPlatform"),
            xboxSandboxId = json.requireString("XboxSandboxID"),
            xboxServiceConfigId = json.requireString("XboxServiceConfigID"),
            xboxSessionTemplateName = json.requireString("XboxSessionTemplateName"),
            xboxTitleId = json.requireString("XboxTitleID"),
            localizedSessionName = json.optJson("localizedSessionName")
        )
    }
}

/**
 * Public configuration for a game session.
 *
 * @property name The name of this configuration.
 * @property minPlayers The minimum number of players required.
 * @property maxPlayers The maximum number of players allowed.
 * @property matchPool The match pool this session belongs to.
 * @property deployment The deployment configuration.
 * @property namespace The namespace where this configuration exists.
 * @property customAttributes Custom attributes as JSON.
 * @property attributes Session attributes as JSON.
 * @property autoJoin Whether players can auto-join.
 * @property persistent Whether the session persists.
 * @property textChat Whether text chat is enabled.
 * @property textChatMode The text chat mode.
 * @property ttlHours The time-to-live in hours.
 * @property configVersion The configuration version.
 * @property asyncProcessDsRequest Asynchronous DS request configuration.
 * @property extendConfiguration Extended configuration settings.
 * @property nativeSessionSetting Native platform session settings.
 */
data class PublicConfigurationModel(
    val name : String,
    val minPlayers : Int,
    val maxPlayers : Int,
    val matchPool : String?,
    val deployment : String,
    val namespace : String?,
    val customAttributes : Json?,
    val attributes : Json?,
    val autoJoin : Boolean,
    val persistent : Boolean,
    val textChat : Boolean,
    val textChatMode : String?,
    val ttlHours : Int?,
    val configVersion : String?,
    val asyncProcessDsRequest : AsyncProcessDsRequestModel?,
    val extendConfiguration : ExtendConfigurationModel?,
    val nativeSessionSetting : NativeSessionSettingModel?
) : AccelByteResponse {
    companion object
    {
        fun fromJson(json : Json) : PublicConfigurationModel = PublicConfigurationModel(
            name = json.requireString("name"),
            minPlayers = json.requireInt("minPlayers"),
            maxPlayers = json.requireInt("maxPlayers"),
            matchPool = json.optString("matchPool"),
            deployment = json.requireString("deployment"),
            namespace = json.optString("namespace"),
            customAttributes = json.optJson("custom_attribute"),
            attributes = json.optJson("attributes"),
            autoJoin = json.requireBoolean("autoJoin"),
            persistent = json.requireBoolean("persistent"),
            textChat = json.requireBoolean("textChat"),
            textChatMode = json.optString("textChatMode"),
            ttlHours = json.optInt("ttlHours"),
            configVersion = json.optString("type"),
            asyncProcessDsRequest = json.optJson("asyncProcessDSRequest")?.let(AsyncProcessDsRequestModel::fromJson),
            extendConfiguration = json.optJson("grpcSessionConfig")?.let(ExtendConfigurationModel::fromJson),
            nativeSessionSetting = json.optJson("nativeSessionSetting")?.let(NativeSessionSettingModel::fromJson)
        )
    }
}

/**
 * Detailed information about a game session.
 *
 * @property dsInformation Dedicated server information.
 * @property configuration The session configuration.
 * @property attributes Session attributes as JSON.
 * @property storage Storage data as JSON.
 * @property backfillTicketId The backfill ticket ID for this session.
 * @property code The session code.
 * @property createdAt Timestamp when this session was created.
 * @property createdBy The user who created this session.
 * @property expiredAt Timestamp when this session expires.
 * @property id The unique identifier of this session.
 * @property isActive Whether this session is currently active.
 * @property isFull Whether this session is full.
 * @property leaderId The leader user ID of this session.
 * @property matchPool The match pool for this session.
 * @property members List of users in this session.
 * @property namespace The namespace where this session exists.
 * @property teams List of teams in this session.
 * @property ticketIds List of ticket IDs associated with this session.
 * @property updatedAt Timestamp when this session was last updated.
 * @property version The version of this session.
 */
data class GameSessionDetailResponse(
    val dsInformation : DsInformationModel,
    val configuration : PublicConfigurationModel,
    val attributes : Json?,
    val storage : Json?,
    val backfillTicketId : String?,
    val code : String?,
    val createdAt : String,
    val createdBy : String,
    val expiredAt : String?,
    val id : String,
    val isActive : Boolean,
    val isFull : Boolean,
    val leaderId : String,
    val matchPool : String,
    val members : List<UserResponseModel>,
    val namespace : String,
    val teams : List<TeamModel>?,
    val ticketIds : List<String>?,
    val updatedAt : String,
    val version : Int
) : AccelByteResponse {
    companion object
    {
        fun fromJson(json : Json) : GameSessionDetailResponse = GameSessionDetailResponse(
            dsInformation = DsInformationModel.fromJson(json.requireJson("DSInformation")),
            configuration = PublicConfigurationModel.fromJson(json.requireJson("configuration")),
            attributes = json.optJson("attributes"),
            storage = json.optJson("storage"),
            backfillTicketId = json.optString("backfillTicketID"),
            code = json.optString("code"),
            createdAt = json.requireString("createdAt"),
            createdBy = json.requireString("createdBy"),
            expiredAt = json.optString("expiredAt"),
            id = json.requireString("id"),
            isActive = json.requireBoolean("isActive"),
            isFull = json.requireBoolean("isFull"),
            leaderId = json.requireString("leaderID"),
            matchPool = json.requireString("matchPool"),
            members = json.optJsonList("members").map(UserResponseModel::fromJson),
            namespace = json.requireString("namespace"),
            teams = json.optJsonList("teams").map(TeamModel::fromJson).takeIf { it.isNotEmpty() },
            ticketIds = json.optJsonArray("ticketIDs")?.mapNotNull { it?.toString() },
            updatedAt = json.requireString("updatedAt"),
            version = json.requireInt("version")
        )
    }
}

/**
 * A paginated list of game sessions.
 *
 * @property data The list of game session details on this page.
 * @property paging Pagination metadata for navigating additional pages.
 */
data class GameSessionListResponse(
    val data : List<GameSessionDetailResponse>,
    val paging : CursorPagination
) : AccelByteResponse {
    companion object
    {
        fun fromJson(json : Json) : GameSessionListResponse = GameSessionListResponse(
            data = json.optJsonList("data").map(GameSessionDetailResponse::fromJson),
            paging = CursorPagination.fromJson(json.requireJson("paging"))
        )
    }
}

/**
 * Response for a session invite request.
 *
 * @property platformUserId The platform user ID of the invited user.
 */
data class SessionInviteResponseModel(
    val platformUserId : String
) : AccelByteResponse {
    companion object
    {
        fun fromJson(json : Json) : SessionInviteResponseModel = SessionInviteResponseModel(
            platformUserId = json.requireString("platformUserID")
        )
    }
}