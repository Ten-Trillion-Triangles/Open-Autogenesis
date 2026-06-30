package accelbyte.session

import accelbyte.AccelByteSdkProvider
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.buildJsonObject
import net.accelbyte.sdk.api.session.models.ApimodelsAppendTeamGameSessionRequest
import net.accelbyte.sdk.api.session.models.ApimodelsJoinByCodeRequest
import net.accelbyte.sdk.api.session.models.ApimodelsPagination
import net.accelbyte.sdk.api.session.models.ApimodelsPartyQueryResponse
import net.accelbyte.sdk.api.session.models.ApimodelsPartySessionResponse
import net.accelbyte.sdk.api.session.models.ApimodelsPromoteLeaderRequest
import net.accelbyte.sdk.api.session.models.ApimodelsRequestMember
import net.accelbyte.sdk.api.session.models.ApimodelsServerSecret
import net.accelbyte.sdk.api.session.models.ApimodelsSessionInviteRequest
import net.accelbyte.sdk.api.session.models.ApimodelsUpdateGameSessionBackfillRequest
import net.accelbyte.sdk.api.session.models.ApimodelsUpdateGameSessionMemberStatusResponse
import net.accelbyte.sdk.api.session.models.ModelsPartyMembers
import net.accelbyte.sdk.api.session.models.ModelsTeam
import net.accelbyte.sdk.api.session.operations.game_session.AdminDeleteBulkGameSessions
import net.accelbyte.sdk.api.session.operations.game_session.AdminKickGameSessionMember
import net.accelbyte.sdk.api.session.operations.game_session.AdminQueryGameSessions
import net.accelbyte.sdk.api.session.operations.game_session.AdminQueryGameSessionsByAttributes
import net.accelbyte.sdk.api.session.operations.game_session.AdminSetDSReady
import net.accelbyte.sdk.api.session.operations.game_session.AdminUpdateDSInformation
import net.accelbyte.sdk.api.session.operations.game_session.AdminUpdateGameSessionMember
import net.accelbyte.sdk.api.session.operations.game_session.AppendTeamGameSession
import net.accelbyte.sdk.api.session.operations.game_session.CreateGameSession
import net.accelbyte.sdk.api.session.operations.game_session.DeleteGameSession
import net.accelbyte.sdk.api.session.operations.game_session.GameSessionGenerateCode
import net.accelbyte.sdk.api.session.operations.game_session.GetGameSession
import net.accelbyte.sdk.api.session.operations.game_session.GetGameSessionByPodName
import net.accelbyte.sdk.api.session.operations.game_session.GetSessionServerSecret
import net.accelbyte.sdk.api.session.operations.game_session.JoinGameSession
import net.accelbyte.sdk.api.session.operations.game_session.LeaveGameSession
import net.accelbyte.sdk.api.session.operations.game_session.PatchUpdateGameSession
import net.accelbyte.sdk.api.session.operations.game_session.PublicGameSessionCancel
import net.accelbyte.sdk.api.session.operations.game_session.PublicGameSessionInvite
import net.accelbyte.sdk.api.session.operations.game_session.PublicGameSessionReject
import net.accelbyte.sdk.api.session.operations.game_session.PublicKickGameSessionMember
import net.accelbyte.sdk.api.session.operations.game_session.PublicPromoteGameSessionLeader
import net.accelbyte.sdk.api.session.operations.game_session.PublicQueryGameSessionsByAttributes
import net.accelbyte.sdk.api.session.operations.game_session.PublicQueryMyGameSessions
import net.accelbyte.sdk.api.session.operations.game_session.PublicRevokeGameSessionCode
import net.accelbyte.sdk.api.session.operations.game_session.PublicSessionJoinCode
import net.accelbyte.sdk.api.session.operations.game_session.UpdateGameSession
import net.accelbyte.sdk.api.session.operations.game_session.UpdateGameSessionBackfillTicketID
import net.accelbyte.sdk.api.session.operations.party.PublicCreateParty
import net.accelbyte.sdk.api.session.operations.party.PublicGetParty
import net.accelbyte.sdk.api.session.operations.party.PublicPartyJoinCode
import net.accelbyte.sdk.api.session.operations.party.PublicPartyLeave
import net.accelbyte.sdk.api.session.wrappers.GameSession
import net.accelbyte.sdk.api.session.wrappers.Party
import org.ttt.autogenesis.logging.LogCategory
import org.ttt.autogenesis.logging.logOperation
import structs.accelbyte.common.AccelByteJson
import structs.accelbyte.common.AccelByteSerializable
import structs.accelbyte.common.CursorPagination
import structs.accelbyte.session.CreateGameSessionRequest
import structs.accelbyte.session.CreatePartyRequest
import structs.accelbyte.session.DsInformationModel
import structs.accelbyte.session.GameServerModel
import structs.accelbyte.session.GameSessionDetailResponse
import structs.accelbyte.session.GameSessionListResponse
import structs.accelbyte.session.NativeSessionSettingModel
import structs.accelbyte.session.PartyMembersModel
import structs.accelbyte.session.PartyQueryResponse
import structs.accelbyte.session.PartySessionResponse
import structs.accelbyte.session.PublicConfigurationModel
import structs.accelbyte.session.SessionBrowserFilter
import structs.accelbyte.session.TeamModel
import structs.accelbyte.session.UpdateDSInfoRequest
import structs.accelbyte.session.UpdateGameSessionRequest
import structs.accelbyte.session.UserResponseModel

object Session
{
    private val sdk get() = AccelByteSdkProvider.sdk
    private val namespace get() = AccelByteSdkProvider.namespace

    private val gameSession by lazy { GameSession(sdk) }
    private val party by lazy { Party(sdk) }

    fun createSession(request: CreateGameSessionRequest): Result<GameSessionDetailResponse> =
        logOperation(LogCategory.SYSTEM, "Session.createSession", "gameMode=${request.gameMode}")
        {
            runCatching {
                val body = request.toJsonElement()
                val op = CreateGameSession.builder()
                    .namespace(namespace)
                    .body(body)
                    .build()
                gameSession.createGameSession(op).toShared()
            }
        }

    fun getSession(sessionId: String): Result<GameSessionDetailResponse> = runCatching {
        val op = GetGameSession.builder()
            .namespace(namespace)
            .sessionId(sessionId)
            .build()
        gameSession.getGameSession(op).toShared()
    }

    /**
     * Alias for [getSession] for backwards compatibility with test mocking.
     *
     * @param sessionId The session ID to retrieve
     * @return [Result] containing the game session detail response
     */
    fun getGameSession(sessionId: String): Result<GameSessionDetailResponse> = getSession(sessionId)

    fun updateSession(sessionId: String, request: UpdateGameSessionRequest): Result<GameSessionDetailResponse> =
        logOperation(LogCategory.SYSTEM, "Session.updateSession", "sessionId=$sessionId")
        {
            runCatching {
                val body = request.toJsonElement()
                val op = UpdateGameSession.builder()
                    .namespace(namespace)
                    .sessionId(sessionId)
                    .body(body)
                    .build()
                gameSession.updateGameSession(op).toShared()
            }
        }

    fun patchSession(sessionId: String, patchBytes: ByteArray): Result<GameSessionDetailResponse> =
        logOperation(LogCategory.SYSTEM, "Session.patchSession", "sessionId=$sessionId")
        {
            runCatching {
                val jsonStr = String(patchBytes, Charsets.UTF_8)
                val request = net.accelbyte.sdk.api.session.models.ApimodelsUpdateGameSessionRequest.builder().build()
                val op = PatchUpdateGameSession.builder()
                    .namespace(namespace)
                    .sessionId(sessionId)
                    .body(request)
                    .build()
                gameSession.patchUpdateGameSession(op).toShared()
            }
        }

    fun deleteSession(sessionId: String): Result<Unit> =
        logOperation(LogCategory.SYSTEM, "Session.deleteSession", "sessionId=$sessionId")
        {
            runCatching {
                val op = DeleteGameSession.builder()
                    .namespace(namespace)
                    .sessionId(sessionId)
                    .build()
                gameSession.deleteGameSession(op)
            }
        }

    fun joinSession(sessionId: String): Result<GameSessionDetailResponse> =
        logOperation(LogCategory.SYSTEM, "Session.joinSession", "sessionId=$sessionId")
        {
            runCatching {
                val op = JoinGameSession.builder()
                    .namespace(namespace)
                    .sessionId(sessionId)
                    .build()
                gameSession.joinGameSession(op).toShared()
            }
        }

    fun leaveSession(sessionId: String): Result<Unit> =
        logOperation(LogCategory.SYSTEM, "Session.leaveSession", "sessionId=$sessionId")
        {
            runCatching {
                val op = LeaveGameSession.builder()
                    .namespace(namespace)
                    .sessionId(sessionId)
                    .build()
                gameSession.leaveGameSession(op)
            }
        }

    fun joinByCode(code: String): Result<GameSessionDetailResponse> =
        logOperation(LogCategory.SYSTEM, "Session.joinByCode", "code=$code")
        {
            runCatching {
                val op = PublicSessionJoinCode.builder()
                    .namespace(namespace)
                    .body(ApimodelsJoinByCodeRequest.builder().code(code).build())
                    .build()
                gameSession.publicSessionJoinCode(op).toShared()
            }
        }

    fun generateCode(sessionId: String): Result<GameSessionDetailResponse> =
        logOperation(LogCategory.SYSTEM, "Session.generateCode", "sessionId=$sessionId")
        {
            runCatching {
                val op = GameSessionGenerateCode.builder()
                    .namespace(namespace)
                    .sessionId(sessionId)
                    .build()
                gameSession.gameSessionGenerateCode(op).toShared()
            }
        }

    fun revokeCode(sessionId: String): Result<Unit> =
        logOperation(LogCategory.SYSTEM, "Session.revokeCode", "sessionId=$sessionId")
        {
            runCatching {
                val op = PublicRevokeGameSessionCode.builder()
                    .namespace(namespace)
                    .sessionId(sessionId)
                    .build()
                gameSession.publicRevokeGameSessionCode(op)
            }
        }

    

    fun inviteUser(sessionId: String, userId: String, platformId: String? = null): Result<Unit> =
        logOperation(LogCategory.SYSTEM, "Session.inviteUser", "sessionId=$sessionId, userId=$userId")
        {
            runCatching {
                val op = PublicGameSessionInvite.builder()
                    .namespace(namespace)
                    .sessionId(sessionId)
                    .body(ApimodelsSessionInviteRequest.builder()
                        .userID(userId)
                        .platformID(platformId)
                        .build())
                    .build()
                gameSession.publicGameSessionInvite(op)
            }
        }

    fun rejectInvite(sessionId: String): Result<Unit> =
        logOperation(LogCategory.SYSTEM, "Session.rejectInvite", "sessionId=$sessionId")
        {
            runCatching {
                val op = PublicGameSessionReject.builder()
                    .namespace(namespace)
                    .sessionId(sessionId)
                    .build()
                gameSession.publicGameSessionReject(op)
            }
        }

    fun cancelInvite(sessionId: String): Result<Unit> =
        logOperation(LogCategory.SYSTEM, "Session.cancelInvite", "sessionId=$sessionId")
        {
            runCatching {
                val op = PublicGameSessionCancel.builder()
                    .namespace(namespace)
                    .sessionId(sessionId)
                    .build()
                gameSession.publicGameSessionCancel(op)
            }
        }

    fun promoteLeader(sessionId: String, userId: String): Result<GameSessionDetailResponse> =
        logOperation(LogCategory.SYSTEM, "Session.promoteLeader", "sessionId=$sessionId, userId=$userId")
        {
            runCatching {
                val op = PublicPromoteGameSessionLeader.builder()
                    .namespace(namespace)
                    .sessionId(sessionId)
                    .body(ApimodelsPromoteLeaderRequest.builder().leaderID(userId).build())
                    .build()
                gameSession.publicPromoteGameSessionLeader(op).toShared()
            }
        }

    fun kickMember(sessionId: String, userId: String): Result<Unit> =
        logOperation(LogCategory.SYSTEM, "Session.kickMember", "sessionId=$sessionId, userId=$userId")
        {
            runCatching {
                val op = PublicKickGameSessionMember.builder()
                    .namespace(namespace)
                    .sessionId(sessionId)
                    .memberId(userId)
                    .build()
                gameSession.publicKickGameSessionMember(op)
            }
        }

    fun queryMySessions(
        order: String? = "DESC",
        orderBy: String? = "created_at",
        limit: Int? = 20,
        offset: Int? = null,
        status: String? = null
    ): Result<GameSessionListResponse> = runCatching {
        val op = PublicQueryMyGameSessions.builder()
            .namespace(namespace)
            .order(order)
            .orderBy(orderBy)
            .status(status)
            .build()
        gameSession.publicQueryMyGameSessions(op).toSharedList()
    }

    fun queryByAttributes(attributes: JsonElement, limit: Int? = 20, offset: Int? = null): Result<GameSessionListResponse> =
        runCatching {
            val op = PublicQueryGameSessionsByAttributes.builder()
                .namespace(namespace)
                .body(emptyMap<String, Any>())
                .build()
            gameSession.publicQueryGameSessionsByAttributes(op).toSharedList()
        }

    fun createParty(request: CreatePartyRequest): Result<PartySessionResponse> =
        logOperation(LogCategory.SYSTEM, "Session.createParty", "maxPlayers=${request.maxPlayers}")
        {
            runCatching {
                val body = request.toJsonElement()
                val op = PublicCreateParty.builder()
                    .namespace(namespace)
                    .body(body)
                    .build()
                party.publicCreateParty(op).toSharedParty()
            }
        }

    fun getParty(partyId: String): Result<PartySessionResponse> = runCatching {
        val op = PublicGetParty.builder()
            .namespace(namespace)
            .partyId(partyId)
            .build()
        party.publicGetParty(op).toSharedParty()
    }

    fun joinPartyByCode(code: String): Result<PartySessionResponse> =
        logOperation(LogCategory.SYSTEM, "Session.joinPartyByCode", "code=$code")
        {
            runCatching {
                val op = PublicPartyJoinCode.builder()
                    .namespace(namespace)
                    .body(ApimodelsJoinByCodeRequest.builder().code(code).build())
                    .build()
                party.publicPartyJoinCode(op).toSharedParty()
            }
        }

    fun leaveParty(partyId: String): Result<Unit> =
        logOperation(LogCategory.SYSTEM, "Session.leaveParty", "partyId=$partyId")
        {
            runCatching {
                val op = PublicPartyLeave.builder()
                    .namespace(namespace)
                    .partyId(partyId)
                    .build()
                party.publicPartyLeave(op)
            }
        }

    fun adminQuerySessions(
        filter: SessionBrowserFilter? = null,
        limit: Int? = 20,
        offset: Int? = null
    ): Result<GameSessionListResponse> = runCatching {
        val op = AdminQueryGameSessions.builder()
            .namespace(namespace)
            .configurationName(filter?.configurationName)
            .dsPodName(filter?.dsPodName)
            .fromTime(filter?.fromTime)
            .gameMode(filter?.gameMode)
            .isPersistent(filter?.isPersistent?.toString())
            .isSoftDeleted(filter?.isSoftDeleted?.toString())
            .joinability(filter?.joinability)
            .limit(limit)
            .matchPool(filter?.matchPool)
            .memberID(filter?.memberId)
            .offset(offset)
            .order(filter?.order)
            .orderBy(filter?.orderBy)
            .sessionID(filter?.sessionId)
            .status(filter?.status)
            .statusV2(filter?.statusV2)
            .toTime(filter?.toTime)
            .build()
        gameSession.adminQueryGameSessions(op).toSharedList()
    }

    fun adminQueryByAttributes(attributes: JsonElement, limit: Int? = 20, offset: Int? = null): Result<GameSessionListResponse> =
        runCatching {
            val op = AdminQueryGameSessionsByAttributes.builder()
                .namespace(namespace)
                .body(emptyMap<String, Any>())
                .build()
            gameSession.adminQueryGameSessionsByAttributes(op).toSharedList()
        }

    fun adminDeleteBulk(sessionIds: List<String>): Result<BulkDeleteResponse> =
        logOperation(LogCategory.SYSTEM, "Session.adminDeleteBulk", "count=${sessionIds.size}")
        {
            runCatching {
                val op = AdminDeleteBulkGameSessions.builder()
                    .namespace(namespace)
                    .body(net.accelbyte.sdk.api.session.models.ApimodelsDeleteBulkGameSessionRequest.builder()
                        .ids(sessionIds)
                        .build())
                    .build()
                gameSession.adminDeleteBulkGameSessions(op).toSharedBulk()
            }
        }

    fun adminKickMember(sessionId: String, userId: String): Result<Unit> =
        logOperation(LogCategory.SYSTEM, "Session.adminKickMember", "sessionId=$sessionId, userId=$userId")
        {
            runCatching {
                val op = AdminKickGameSessionMember.builder()
                    .namespace(namespace)
                    .sessionId(sessionId)
                    .memberId(userId)
                    .build()
                gameSession.adminKickGameSessionMember(op)
            }
        }

    fun adminUpdateMemberStatus(sessionId: String, userId: String, status: String): Result<UpdateMemberStatusResponse> =
        logOperation(LogCategory.SYSTEM, "Session.adminUpdateMemberStatus", "sessionId=$sessionId, userId=$userId")
        {
            runCatching {
                val op = AdminUpdateGameSessionMember.builder()
                    .namespace(namespace)
                    .sessionId(sessionId)
                    .memberId(userId)
                    .statusType(status)
                    .build()
                gameSession.adminUpdateGameSessionMember(op).toShared()
            }
        }

    fun adminSetDSReady(sessionId: String): Result<Unit> =
        logOperation(LogCategory.SYSTEM, "Session.adminSetDSReady", "sessionId=$sessionId")
        {
            runCatching {
                val op = AdminSetDSReady.builder()
                    .namespace(namespace)
                    .sessionId(sessionId)
                    .build()
                gameSession.adminSetDSReady(op)
            }
        }

    fun adminUpdateDSInfo(sessionId: String, request: UpdateDSInfoRequest): Result<Unit> =
        logOperation(LogCategory.SYSTEM, "Session.adminUpdateDSInfo", "sessionId=$sessionId")
        {
            runCatching {
                val bodyBuilder = net.accelbyte.sdk.api.session.models.ApimodelsUpdateGamesessionDSInformationRequest.builder()
                request.status?.let { bodyBuilder.status(it) }
                val op = AdminUpdateDSInformation.builder()
                    .namespace(namespace)
                    .sessionId(sessionId)
                    .body(bodyBuilder.build())
                    .build()
                gameSession.adminUpdateDSInformation(op)
            }
        }

    fun appendTeam(sessionId: String, additionalMembers: List<String>, proposedTeams: List<TeamModel>? = null): Result<GameSessionDetailResponse> =
        logOperation(LogCategory.SYSTEM, "Session.appendTeam", "sessionId=$sessionId, members=${additionalMembers.size}")
        {
            runCatching {
                val body = ApimodelsAppendTeamGameSessionRequest.builder()
                    .additionalMembers(additionalMembers.map { ModelsPartyMembers.builder().userIDs(listOf(it)).build() })
                    .proposedTeams(proposedTeams?.map { it.toSdk() })
                    .build()
                val op = AppendTeamGameSession.builder()
                    .namespace(namespace)
                    .sessionId(sessionId)
                    .body(body)
                    .build()
                gameSession.appendTeamGameSession(op).toShared()
            }
        }

    fun updateBackfillTicket(sessionId: String, backfillTicketId: String): Result<GameSessionDetailResponse> =
        logOperation(LogCategory.SYSTEM, "Session.updateBackfillTicket", "sessionId=$sessionId")
        {
            runCatching {
                val op = UpdateGameSessionBackfillTicketID.builder()
                    .namespace(namespace)
                    .sessionId(sessionId)
                    .body(ApimodelsUpdateGameSessionBackfillRequest.builder().backfillTicketID(backfillTicketId).build())
                    .build()
                gameSession.updateGameSessionBackfillTicketID(op).toShared()
            }
        }

    fun getByPodName(podName: String): Result<GameSessionDetailResponse> = runCatching {
        val op = GetGameSessionByPodName.builder()
            .namespace(namespace)
            .podName(podName)
            .build()
        gameSession.getGameSessionByPodName(op).toShared()
    }

    fun getServerSecret(sessionId: String): Result<ServerSecretResponse> = runCatching {
        val op = GetSessionServerSecret.builder()
            .namespace(namespace)
            .sessionId(sessionId)
            .build()
        gameSession.getSessionServerSecret(op).toShared()
    }

    private fun net.accelbyte.sdk.api.session.models.ApimodelsGameSessionResponse.toShared(): GameSessionDetailResponse
    {
        return GameSessionDetailResponse(
            dsInformation = dsInformation?.toSharedDsInfo(),
            configuration = configuration?.toSharedConfig(),
            attributes = attributes?.toJsonElement(),
            storage = storage?.toJsonElement(),
            backfillTicketId = backfillTicketID,
            code = code,
            createdAt = createdAt ?: "",
            createdBy = createdBy ?: "",
            expiredAt = expiredAt,
            id = id ?: "",
            isActive = isActive ?: false,
            isFull = isFull ?: false,
            leaderId = leaderID ?: "",
            members = members?.map { it.toShared() },
            namespace = namespace ?: "",
            teams = teams?.map { it.toSharedTeam() },
            ticketIds = ticketIDs,
            updatedAt = updatedAt ?: "",
            version = version ?: 0
        )
    }

    private fun net.accelbyte.sdk.api.session.models.ApimodelsGameSessionQueryResponse.toSharedList(): GameSessionListResponse =
        GameSessionListResponse(
            data = data?.map { it.toShared() } ?: emptyList(),
            paging = paging?.toSharedPaging() ?: CursorPagination("", "", "", "")
        )

    private fun net.accelbyte.sdk.api.session.models.ApimodelsUserResponse.toShared(): UserResponseModel =
        UserResponseModel(
            id = id ?: "",
            platformId = platformID ?: "",
            platformUserId = platformUserID ?: "",
            status = status ?: "",
            statusV2 = statusV2 ?: "",
            updatedAt = updatedAt ?: "",
            previousStatus = previousStatus
        )

    private fun net.accelbyte.sdk.api.session.models.ApimodelsDSInformationResponse.toSharedDsInfo(): DsInformationModel =
        DsInformationModel(
            createdAt = createdAt ?: "",
            requestedAt = requestedAt ?: "",
            status = status,
            statusV2 = statusV2,
            server = server?.toSharedServer()
        )

    private fun net.accelbyte.sdk.api.session.models.ModelsGameServer.toSharedServer(): GameServerModel =
        GameServerModel(
            alias = "",
            ip = ip,
            port = port,
            region = region ?: "",
            status = status ?: "",
            lastUpdate = lastUpdate ?: "",
            namespace = namespace ?: "",
            sessionId = sessionId ?: "",
            provider = provider,
            description = description,
            deployment = deployment,
            podName = podName,
            protocol = protocol,
            imageVersion = imageVersion,
            gameVersion = gameVersion,
            source = source ?: ""
        )

    private fun net.accelbyte.sdk.api.session.models.ApimodelsPublicConfiguration.toSharedConfig(): PublicConfigurationModel =
        PublicConfigurationModel(
            name = name ?: "",
            minPlayers = minPlayers ?: 0,
            maxPlayers = maxPlayers ?: 0,
            deployment = deployment ?: "",
            namespace = namespace,
            customAttributes = customURLGRPC?.let { AccelByteJson.parseToJsonElement(it.toString()) },
            attributes = attributes?.toJsonElement(),
            autoJoin = autoJoin,
            persistent = persistent,
            textChat = textChat,
            textChatMode = textChatMode,
            ttlHours = ttlHours,
            asyncProcessDsRequest = asyncProcessDSRequest?.toSharedAsync(),
            extendConfiguration = grpcSessionConfig?.toSharedExt(),
            nativeSessionSetting = nativeSessionSetting?.toSharedNative(),
            clientVersion = clientVersion,
            dsManualSetReady = dsManualSetReady,
            disableCodeGeneration = disableCodeGeneration,
            disableResendInvite = disableResendInvite,
            enableSecret = enableSecret,
            immutableStorage = immutableStorage,
            inactiveTimeout = inactiveTimeout,
            inviteTimeout = inviteTimeout,
            leaderElectionGracePeriod = leaderElectionGracePeriod,
            manualRejoin = manualRejoin,
            maxActiveSession = maxActiveSession,
            partyCodeGeneratorString = partyCodeGeneratorString,
            partyCodeLength = partyCodeLength,
            preferredClaimKeys = preferredClaimKeys,
            requestedRegions = requestedRegions,
            tieTeamsSessionLifetime = tieTeamsSessionLifetime?.let { if (it) 1 else 0 },
            type = type,
            
        )

    private fun net.accelbyte.sdk.api.session.models.ModelsAsyncProcessDSRequest.toSharedAsync(): structs.accelbyte.session.AsyncProcessDsRequestModel =
        structs.accelbyte.session.AsyncProcessDsRequestModel(async = async, timeout = timeout?.toInt())

    private fun net.accelbyte.sdk.api.session.models.ModelsExtendConfiguration.toSharedExt(): structs.accelbyte.session.ExtendConfigurationModel =
        structs.accelbyte.session.ExtendConfigurationModel(appName = appName, customUrl = customURL, functionFlag = functionFlag)

    private fun net.accelbyte.sdk.api.session.models.ModelsNativeSessionSetting.toSharedNative(): NativeSessionSettingModel =
        NativeSessionSettingModel(
            psnDisableSystemUIMenu = psnDisableSystemUIMenu,
            psnServiceLabel = psnServiceLabel,
            psnSupportedPlatforms = psnSupportedPlatforms,
            sessionTitle = sessionTitle,
            shouldSync = shouldSync,
            xboxAllowCrossPlatform = xboxAllowCrossPlatform,
            xboxSandboxId = xboxSandboxID,
            xboxServiceConfigId = xboxServiceConfigID,
            xboxSessionTemplateName = xboxSessionTemplateName,
            xboxTitleId = xboxTitleID
        )

    private fun net.accelbyte.sdk.api.session.models.ModelsTeam.toSharedTeam(): TeamModel =
        TeamModel(
            teamId = teamID,
            userIds = userIDs?.toList(),
            parties = parties?.map { PartyMembersModel(partyId = it.partyID, userIds = it.userIDs?.toList()) }
        )

    private fun ApimodelsPartySessionResponse.toSharedParty(): PartySessionResponse =
        PartySessionResponse(
            configuration = configuration?.toSharedConfig(),
            attributes = attributes?.toJsonElement(),
            code = code,
            createdAt = createdAt ?: "",
            createdBy = createdBy ?: "",
            expiredAt = expiredAt,
            id = id ?: "",
            isActive = isActive ?: false,
            isFull = isFull ?: false,
            leaderId = leaderID ?: "",
            members = members?.map { it.toShared() },
            namespace = namespace ?: "",
            storage = storage?.toJsonElement(),
            updatedAt = updatedAt ?: "",
            version = version ?: 0
        )

    private fun net.accelbyte.sdk.api.session.models.ApimodelsDeleteBulkGameSessionsAPIResponse.toSharedBulk(): BulkDeleteResponse =
        BulkDeleteResponse(
            success = success?.toList() ?: emptyList(),
            failed = failed?.map { it.id ?: "" } ?: emptyList()
        )

    private fun ApimodelsUpdateGameSessionMemberStatusResponse.toShared(): UpdateMemberStatusResponse =
        UpdateMemberStatusResponse(status = status ?: "", statusV2 = statusV2)

    private fun ApimodelsServerSecret.toShared(): ServerSecretResponse =
        ServerSecretResponse(secret = secret ?: "")

    private fun ApimodelsPagination.toSharedPaging(): CursorPagination =
        CursorPagination(first = first ?: "", last = last ?: "", next = next ?: "", previous = previous ?: "")

    private fun ApimodelsPartyQueryResponse.toSharedPartyList(): PartyQueryResponse =
        PartyQueryResponse(
            data = data?.map { it.toSharedParty() } ?: emptyList(),
            paging = paging?.toSharedPaging() ?: CursorPagination("", "", "", "")
        )

    private fun Map<String, *>?.toJsonElement(): JsonElement =
        when (this)
        {
            null -> JsonNull
            is Map<*, *> -> buildJsonObject {
                this@toJsonElement.forEach { (k, v) ->
                    (k as? String)?.let { key ->
                        val element: JsonElement = when (v)
                        {
                            is String -> AccelByteJson.parseToJsonElement('"' + v + '"')
                            is Number -> AccelByteJson.parseToJsonElement(v.toString())
                            is Boolean -> AccelByteJson.parseToJsonElement(v.toString())
                            else -> JsonNull
                        }
                        put(key, element)
                    }
                }
            }
            else -> JsonNull
        }

    private fun CreateGameSessionRequest.toJsonElement(): net.accelbyte.sdk.api.session.models.ApimodelsCreateGameSessionRequest
    {
        val json = AccelByteJson.encodeToString(CreateGameSessionRequest.serializer(), this)
        return com.fasterxml.jackson.databind.ObjectMapper().readValue(
            json,
            net.accelbyte.sdk.api.session.models.ApimodelsCreateGameSessionRequest::class.java
        )
    }

    private fun UpdateGameSessionRequest.toJsonElement(): net.accelbyte.sdk.api.session.models.ApimodelsUpdateGameSessionRequest
    {
        val json = AccelByteJson.encodeToString(UpdateGameSessionRequest.serializer(), this)
        return com.fasterxml.jackson.databind.ObjectMapper().readValue(
            json,
            net.accelbyte.sdk.api.session.models.ApimodelsUpdateGameSessionRequest::class.java
        )
    }

    private fun CreatePartyRequest.toJsonElement(): net.accelbyte.sdk.api.session.models.ApimodelsCreatePartyRequest
    {
        val mapper = com.fasterxml.jackson.databind.ObjectMapper()
        val req = net.accelbyte.sdk.api.session.models.ApimodelsCreatePartyRequest.builder()
            .maxPlayers(maxPlayers)
            .minPlayers(minPlayers)
            .configurationName(configurationName)
            .inactiveTimeout(inactiveTimeout)
            .inviteTimeout(inviteTimeout)
            .joinability(joinability)
            .textChat(textChat)
            .type(type)
            .build()

        val membersList = members?.filterNotNull()?.map {
                ApimodelsRequestMember.builder()
                    .id(it.id)
                    .platformID(it.platformId)
                    .platformUserID(it.platformUserId)
                    .build()
            } ?: emptyList()
        val field = req.javaClass.getDeclaredField("members")
        field.isAccessible = true
        field.set(req, membersList)
        if (attributes != null)
        {
            val jsonStr = AccelByteJson.encodeToString(attributes)
            val field = req.javaClass.getDeclaredField("attributes")
            field.isAccessible = true
            field.set(req, mapper.readTree(jsonStr))
        }
        return req
    }

    private fun TeamModel.toSdk(): ModelsTeam =
        ModelsTeam.builder()
            .teamID(teamId)
            .userIDs(userIds)
            .parties(parties?.filterNotNull()?.map {
                ModelsPartyMembers.builder().partyID(it.partyId).userIDs(it.userIds).build()
            })
            .build()
}

@Serializable
data class BulkDeleteResponse(
    val success: List<String>,
    val failed: List<String>
) : AccelByteSerializable {
    override fun toJsonString(): String = AccelByteJson.encodeToString(serializer(), this)
}

@Serializable
data class ServerSecretResponse(
    val secret: String
) : AccelByteSerializable {
    override fun toJsonString(): String = AccelByteJson.encodeToString(serializer(), this)
}

@Serializable
data class UpdateMemberStatusResponse(
    val status: String,
    val statusV2: String? = null
) : AccelByteSerializable {
    override fun toJsonString(): String = AccelByteJson.encodeToString(serializer(), this)
}
