package accelbyte.tests

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import structs.accelbyte.auth.GrantType
import structs.accelbyte.auth.OAuthTokenRequest
import structs.accelbyte.common.AccelByteJson
import structs.accelbyte.dsm.DsmServerQueryParams
import structs.accelbyte.leaderboard.LeaderboardQueryParams
import structs.accelbyte.leaderboard.LeaderboardRankingEntry
import structs.accelbyte.platform.ItemInfoResponse
import structs.accelbyte.platform.StoreInfoResponse
import structs.accelbyte.session.SessionBrowserFilter
import structs.accelbyte.social.StatItem
import structs.accelbyte.social.StatItemQueryParams
import structs.accelbyte.social.StatItemsResponse
import structs.accelbyte.reporting.ReportResponse
import structs.accelbyte.reporting.TicketQueryParams
import structs.accelbyte.reporting.TicketStatisticResponse
import structs.accelbyte.ugc.ContentDownloadResponseModel
import structs.accelbyte.ugc.CreatorOverviewResponseModel
import structs.accelbyte.basic.NamespaceInfo
import structs.accelbyte.basic.NamespaceListResponse
import structs.accelbyte.basic.NamespaceQueryParams
import structs.accelbyte.config.ConfigEntry
import structs.accelbyte.config.ConfigListParams
import structs.accelbyte.config.ConfigListResponse
import structs.accelbyte.config.EmailTemplate
import structs.accelbyte.config.LinkedSender
import structs.accelbyte.gdpr.GdprDataRetrievalResponse
import structs.accelbyte.gdpr.GdprPasswordRequest
import structs.accelbyte.gdpr.GdprRequestListParams
import structs.accelbyte.achievement.Achievement
import structs.accelbyte.achievement.AchievementQueryParams
import structs.accelbyte.chat.ChatMessage
import structs.accelbyte.chat.ChatQueryParams
import structs.accelbyte.cloudsave.BulkGameRecordRequest
import structs.accelbyte.cloudsave.GameRecordRequest
import structs.accelbyte.cloudsave.GameRecordResponse
import structs.accelbyte.content.ContentCreateRequest
import structs.accelbyte.content.ContentFilterParams
import structs.accelbyte.content.ContentQueryParams
import structs.accelbyte.event.EventEntry
import structs.accelbyte.event.EventListResponse
import structs.accelbyte.event.EventMetadata
import structs.accelbyte.event.EventQueryParams
import structs.accelbyte.gameTelemetry.BlockUrlParams
import structs.accelbyte.gameTelemetry.TelemetryEventListResponse
import structs.accelbyte.gameTelemetry.TelemetryEventRecord
import structs.accelbyte.gameTelemetry.TelemetryNamespaceListResponse
import structs.accelbyte.gameTelemetry.TelemetryPlaytimeResponse
import structs.accelbyte.gameTelemetry.TelemetryProtectedEventResult
import structs.accelbyte.gameTelemetry.TelemetryQueryParams
import structs.accelbyte.gameTelemetry.VersionHistoryParams
import structs.accelbyte.legal.AcceptedAgreementResponse
import structs.accelbyte.legal.AgreementConfirmationResponse
import structs.accelbyte.legal.LegalCountryListResponse
import structs.accelbyte.legal.LegalPolicyListResponse
import structs.accelbyte.legal.LegalPolicyResponse
import structs.accelbyte.legal.LegalPolicyVersionResponse
import structs.accelbyte.legal.LegalReadinessResponse
import structs.accelbyte.legal.LocalizedPolicyVersionResponse
import structs.accelbyte.matchmaking.MatchTicketCancellationResponse
import structs.accelbyte.matchmaking.MatchTicketDetailsResponse
import structs.accelbyte.matchmaking.MatchTicketFilter
import structs.accelbyte.matchmaking.MatchTicketListResponse
import structs.accelbyte.matchmaking.MatchTicketRequest
import structs.accelbyte.matchmaking.MatchTicketResponse
import structs.accelbyte.matchmaking.TicketInfo
import structs.accelbyte.csm.CsmMessage
import structs.accelbyte.csm.CsmMessageListResponse
import structs.accelbyte.differ.DiffRequest
import structs.accelbyte.differ.DiffResultResponse
import structs.accelbyte.differ.DiffResultV2Response
import structs.accelbyte.differ.DifferFileChange
import structs.accelbyte.differ.DifferHealthResponse
import structs.accelbyte.query.BooleanFilterParams
import structs.accelbyte.query.ForceActionParams
import structs.accelbyte.query.LimitOnlyParams
import structs.accelbyte.query.NamespaceParams
import structs.accelbyte.query.PaginationParams
import structs.accelbyte.query.StringFilterParams
import structs.accelbyte.query.UserIdParams
import structs.accelbyte.query.UserIdsParams
import structs.accelbyte.buildinfo.BuildVersionEntry
import structs.accelbyte.buildinfo.BlockDownloadUrlResponse
import structs.accelbyte.buildinfo.CacheDiffFile
import structs.accelbyte.buildinfo.CacheDiffResponse
import structs.accelbyte.buildinfo.DiffStatusResponse
import structs.accelbyte.buildinfo.VersionHistoryResponse
import structs.accelbyte.seasonpass.ClaimableRewardsResponse
import structs.accelbyte.seasonpass.ClaimableUserSeasonInfoResponse
import structs.accelbyte.seasonpass.ExpGrantHistoryPageResponse
import structs.accelbyte.seasonpass.ExpGrantHistoryResponse
import structs.accelbyte.seasonpass.ItemReferenceInfoResponse
import structs.accelbyte.seasonpass.ItemReferenceResponse
import structs.accelbyte.seasonpass.SeasonClaimRequest
import structs.accelbyte.seasonpass.SeasonInfoResponse
import structs.accelbyte.seasonpass.SeasonListEntryResponse
import structs.accelbyte.seasonpass.SeasonListResponse
import structs.accelbyte.seasonpass.SeasonPassInfoResponse
import structs.accelbyte.seasonpass.SeasonPassListResponse
import structs.accelbyte.seasonpass.SeasonQueryParams
import structs.accelbyte.seasonpass.SeasonRewardInfoResponse
import structs.accelbyte.seasonpass.SeasonRewardListResponse
import structs.accelbyte.seasonpass.SeasonRewardQueryParams
import structs.accelbyte.seasonpass.SeasonSummaryResponse
import structs.accelbyte.seasonpass.SeasonTierPageResponse
import structs.accelbyte.seasonpass.SeasonTierResponse
import structs.accelbyte.seasonpass.SeasonpassExportResponse
import structs.accelbyte.qosm.QosmAliasRequest
import structs.accelbyte.qosm.QosmRegionQuery
import structs.accelbyte.qosm.QosmServerListResponse
import structs.accelbyte.qosm.QosmServerResponse
import structs.accelbyte.lobby.LobbyLimitRequest
import structs.accelbyte.lobby.LobbyMessageListResponse
import structs.accelbyte.lobby.LobbyMessageResponse
import structs.accelbyte.lobby.PartyDataResponse
import structs.accelbyte.group.GroupCreateRequest
import structs.accelbyte.group.GroupQueryParams
import structs.accelbyte.group.GroupResponseModel
import structs.accelbyte.group.RuleResponseModel
import structs.accelbyte.group.RuleInformationResponse

class JsonCompatibilityTest {
    @Test
    fun testOAuthTokenRequestCompatibility() {
        val jsonString = """{"grant_type":"password","username":"testuser","password":"testpass"}"""
        val shared = AccelByteJson.decodeFromString<OAuthTokenRequest>(jsonString)

        assertEquals(GrantType.PASSWORD, shared.grantType)
        assertEquals("testuser", shared.username)
        assertEquals("testpass", shared.password)

        val sharedJson = shared.toJsonString()
        val roundTrip = AccelByteJson.decodeFromString<OAuthTokenRequest>(sharedJson)
        assertEquals(shared, roundTrip)
    }

    @Test
    fun testSessionBrowserFilterCompatibility() {
        val jsonString =
            """{"configurationName":"cheese","memberID":"member-42","sessionID":"session-99","limit":8,"order":"desc","status":"active"}"""
        val shared = AccelByteJson.decodeFromString<SessionBrowserFilter>(jsonString)

        assertEquals("cheese", shared.configurationName)
        assertEquals("member-42", shared.memberId)
        assertEquals("session-99", shared.sessionId)
        assertEquals(8, shared.limit)
        assertEquals("desc", shared.order)
        assertEquals("active", shared.status)

        val sharedJson = shared.toJsonString()
        val roundTrip = AccelByteJson.decodeFromString<SessionBrowserFilter>(sharedJson)
        assertEquals(shared, roundTrip)
    }

    @Test
    fun testContentQueryParamsCompatibility() {
        val jsonString = """{"limit":15}"""
        val shared = AccelByteJson.decodeFromString<ContentQueryParams>(jsonString)

        assertEquals(15, shared.limit)

        val sharedJson = shared.toJsonString()
        val roundTrip = AccelByteJson.decodeFromString<ContentQueryParams>(sharedJson)
        assertEquals(shared, roundTrip)
    }

    @Test
    fun testContentFilterParamsCompatibility() {
        val jsonString = """{"tags":["a","b"],"type":"map"}"""
        val shared = AccelByteJson.decodeFromString<ContentFilterParams>(jsonString)

        assertEquals(listOf("a","b"), shared.tags)

        val sharedJson = shared.toJsonString()
        val roundTrip = AccelByteJson.decodeFromString<ContentFilterParams>(sharedJson)
        assertEquals(shared, roundTrip)
    }

    @Test
    fun testContentCreateRequestCompatibility() {
        val jsonString = """{"name":"map","type":"world","data":{"level":3}}"""
        val shared = AccelByteJson.decodeFromString<ContentCreateRequest>(jsonString)

        assertEquals("map", shared.name)

        val sharedJson = shared.toJsonString()
        val roundTrip = AccelByteJson.decodeFromString<ContentCreateRequest>(sharedJson)
        assertEquals(shared, roundTrip)
    }

    @Test
    fun testCsmMessageCompatibility() {
        val jsonString =
            """{"id":"msg","content":"hello","type":"info","createdAt":"2025-01-01T00:00:00Z","status":"ok","priority":"low"}"""
        val shared = AccelByteJson.decodeFromString<CsmMessage>(jsonString)

        assertEquals("msg", shared.id)

        val sharedJson = shared.toJsonString()
        val roundTrip = AccelByteJson.decodeFromString<CsmMessage>(sharedJson)
        assertEquals(shared, roundTrip)
    }

    @Test
    fun testCsmMessageListCompatibility() {
        val jsonString =
            """{"messages":[{"id":"msg","content":"hi","type":"info","createdAt":"2025-01-01T00:00:00Z","status":"ok","priority":"low"}],"paging":{"total":1,"offset":0,"limit":20}}"""
        val shared = AccelByteJson.decodeFromString<CsmMessageListResponse>(jsonString)

        assertEquals(1, shared.messages.size)

        val sharedJson = shared.toJsonString()
        val roundTrip = AccelByteJson.decodeFromString<CsmMessageListResponse>(sharedJson)
        assertEquals(shared, roundTrip)
    }

    @Test
    fun testDiffRequestCompatibility() {
        val jsonString =
            """{"sourceBuildId":"1","destinationBuildId":"2","priority":true}"""
        val shared = AccelByteJson.decodeFromString<DiffRequest>(jsonString)

        assertEquals("1", shared.sourceBuildId)

        val sharedJson = shared.toJsonString()
        val roundTrip = AccelByteJson.decodeFromString<DiffRequest>(sharedJson)
        assertEquals(shared, roundTrip)
    }

    @Test
    fun testDiffResultResponseCompatibility() {
        val jsonString =
            """{"diffId":"d1","status":"done","files":[],"createdAt":"2025-01-01T00:00:00Z"}"""
        val shared = AccelByteJson.decodeFromString<DiffResultResponse>(jsonString)

        assertEquals("d1", shared.diffId)

        val sharedJson = shared.toJsonString()
        val roundTrip = AccelByteJson.decodeFromString<DiffResultResponse>(sharedJson)
        assertEquals(shared, roundTrip)
    }

    @Test
    fun testDiffResultV2Compatibility() {
        val jsonString =
            """{"diffId":"d2","status":"processing","files":[],"metadata":{},"createdAt":"2025-01-02T00:00:00Z"}"""
        val shared = AccelByteJson.decodeFromString<DiffResultV2Response>(jsonString)

        assertEquals("d2", shared.diffId)

        val sharedJson = shared.toJsonString()
        val roundTrip = AccelByteJson.decodeFromString<DiffResultV2Response>(sharedJson)
        assertEquals(shared, roundTrip)
    }

    @Test
    fun testDifferHealthCompatibility() {
        val jsonString = """{"status":"ok","timestamp":"123","version":"1","uptime":10}"""
        val shared = AccelByteJson.decodeFromString<DifferHealthResponse>(jsonString)

        assertEquals("ok", shared.status)

        val sharedJson = shared.toJsonString()
        val roundTrip = AccelByteJson.decodeFromString<DifferHealthResponse>(sharedJson)
        assertEquals(shared, roundTrip)
    }

    @Test
    fun testQueueOfQueryModels() {
        val paginationJson = """{"limit":10,"offset":2}"""
        val pagination = AccelByteJson.decodeFromString<PaginationParams>(paginationJson)
        assertEquals(10, pagination.limit)

        val limitJson = """{"limit":5}"""
        val limitOnly = AccelByteJson.decodeFromString<LimitOnlyParams>(limitJson)
        assertEquals(5, limitOnly.limit)

        val booleanJson = """{"activeOnly":false}"""
        val booleanParams = AccelByteJson.decodeFromString<BooleanFilterParams>(booleanJson)
        assertFalse(booleanParams.activeOnly)

        val stringJson = """{"filter":"test"}"""
        val stringParams = AccelByteJson.decodeFromString<StringFilterParams>(stringJson)
        assertEquals("test", stringParams.filter)

        val userJson = """{"userId":"user"}"""
        val userParams = AccelByteJson.decodeFromString<UserIdParams>(userJson)
        assertEquals("user", userParams.userId)

        val userIdsJson = """{"userIds":["a","b"]}"""
        val userIds = AccelByteJson.decodeFromString<UserIdsParams>(userIdsJson)
        assertEquals(2, userIds.userIds.size)

        val namespaceJson = """{"namespace":"ns"}"""
        val namespace = AccelByteJson.decodeFromString<NamespaceParams>(namespaceJson)
        assertEquals("ns", namespace.namespace)

        val forceJson = """{"force":true}"""
        val forceParams = AccelByteJson.decodeFromString<ForceActionParams>(forceJson)
        assertTrue(forceParams.force)
    }

    @Test
    fun testDsmServerQueryParamsCompatibility() {
        val jsonString = """{"count":50,"offset":10,"region":"europe"}"""
        val shared = AccelByteJson.decodeFromString<DsmServerQueryParams>(jsonString)

        assertEquals(50, shared.count)
        assertEquals(10, shared.offset)
        assertEquals("europe", shared.region)

        val sharedJson = shared.toJsonString()
        val roundTrip = AccelByteJson.decodeFromString<DsmServerQueryParams>(sharedJson)
        assertEquals(shared, roundTrip)
    }

    @Test
    fun testLeaderboardQueryParamsCompatibility() {
        val jsonString = """{"limit":15}"""
        val shared = AccelByteJson.decodeFromString<LeaderboardQueryParams>(jsonString)

        assertEquals(15, shared.limit)

        val sharedJson = shared.toJsonString()
        val roundTrip = AccelByteJson.decodeFromString<LeaderboardQueryParams>(sharedJson)
        assertEquals(shared, roundTrip)
    }

    @Test
    fun testLeaderboardRankingEntryCompatibility() {
        val jsonString =
            """{"userId":"player","username":"hero","score":3500.0,"rank":2,"additionalData":null,"updatedAt":"2025-10-01T12:00:00Z","firstScoreTime":"2025-09-01T12:00:00Z","lastScoreTime":"2025-10-01T12:00:00Z"}"""
        val shared = AccelByteJson.decodeFromString<LeaderboardRankingEntry>(jsonString)

        assertEquals("player", shared.userId)
        assertEquals("hero", shared.username)
        assertEquals(3500.0, shared.score)
        assertEquals(2, shared.rank)
        assertEquals("2025-10-01T12:00:00Z", shared.updatedAt)
        assertEquals("2025-09-01T12:00:00Z", shared.firstScoreTime)
        assertEquals("2025-10-01T12:00:00Z", shared.lastScoreTime)

        val sharedJson = shared.toJsonString()
        val roundTrip = AccelByteJson.decodeFromString<LeaderboardRankingEntry>(sharedJson)
        assertEquals(shared, roundTrip)
    }

    @Test
    fun testStoreInfoResponseCompatibility() {
        val jsonString =
            """{"storeId":"store-1","title":"shop","namespace":"ns","defaultLanguage":"en","defaultRegion":"US","supportedLanguages":["en"],"supportedRegions":["US"],"published":true,"createdAt":"2025-01-01T00:00:00Z","updatedAt":"2025-06-01T00:00:00Z"}"""
        val shared = AccelByteJson.decodeFromString<StoreInfoResponse>(jsonString)

        assertEquals("store-1", shared.storeId)
        assertEquals("shop", shared.title)
        assertEquals("en", shared.defaultLanguage)
        assertEquals(true, shared.published)

        val sharedJson = shared.toJsonString()
        val roundTrip = AccelByteJson.decodeFromString<StoreInfoResponse>(sharedJson)
        assertEquals(shared, roundTrip)
    }

    @Test
    fun testItemInfoResponseCompatibility() {
        val jsonString =
            """{"itemId":"item-1","name":"Cheese","namespace":"ns","status":"ACTIVE","itemType":"DLC","entitlementType":"DISTRIBUTION","categoryPath":"/food","createdAt":"2025-01-01T00:00:00Z","updatedAt":"2025-06-01T00:00:00Z","features":["tasty"],"images":[{"imageUrl":"https://img","smallImageUrl":"https://img/small","as":"alt","caption":"cap","width":64,"height":64}]}"""
        val shared = AccelByteJson.decodeFromString<ItemInfoResponse>(jsonString)

        assertEquals("item-1", shared.itemId)
        assertEquals("Cheese", shared.name)
        assertEquals(1, shared.images?.size)
        assertEquals(1, shared.features.size)

        val sharedJson = shared.toJsonString()
        val roundTrip = AccelByteJson.decodeFromString<ItemInfoResponse>(sharedJson)
        assertEquals(shared, roundTrip)
    }

    @Test
    fun testContentDownloadResponseCompatibility() {
        val jsonString =
            """{"id":"content-1","name":"Map","userId":"user","namespace":"ns","channelId":"channel","createdTime":"2025-01-01T00:00:00Z","downloadCount":5,"likeCount":2,"shareCode":"code","tags":["tag"],"isOfficial":true,"isHidden":false,"payloadURL":[{"source":"src","url":"https://payload"}],"previewURL":[{"source":"src","url":"https://preview"}],"screenshots":[{"screenshotId":"shot","source":"src","url":"https://shot","description":"desc","fileExtension":"png"}],"creatorFollowState":{"userId":"user","state":true},"likeState":{"userId":"user","state":false}}"""
        val shared = AccelByteJson.decodeFromString<ContentDownloadResponseModel>(jsonString)

        assertEquals("content-1", shared.id)
        assertTrue(shared.isOfficial)
        assertEquals(1, shared.payloadUrls?.size)

        val sharedJson = shared.toJsonString()
        val roundTrip = AccelByteJson.decodeFromString<ContentDownloadResponseModel>(sharedJson)
        assertEquals(shared, roundTrip)
    }

    @Test
    fun testCreatorOverviewResponseCompatibility() {
        val jsonString =
            """{"id":"creator","namespace":"ns","parentNamespace":"parent","followCount":100,"followingCount":20,"totalLikedContent":300}"""
        val shared = AccelByteJson.decodeFromString<CreatorOverviewResponseModel>(jsonString)

        assertEquals("creator", shared.id)
        assertEquals(100, shared.followCount)

        val sharedJson = shared.toJsonString()
        val roundTrip = AccelByteJson.decodeFromString<CreatorOverviewResponseModel>(sharedJson)
        assertEquals(shared, roundTrip)
    }

    @Test
    fun testStatItemQueryParamsCompatibility() {
        val jsonString = """{"limit":25}"""
        val shared = AccelByteJson.decodeFromString<StatItemQueryParams>(jsonString)

        assertEquals(25, shared.limit)

        val sharedJson = shared.toJsonString()
        val roundTrip = AccelByteJson.decodeFromString<StatItemQueryParams>(sharedJson)
        assertEquals(shared, roundTrip)
    }

    @Test
    fun testStatItemsResponseCompatibility() {
        val jsonString =
            """{"data":[{"statCode":"kda","value":3.5,"namespace":"ns","userId":"user-1"}],"paging":{"total":1,"offset":0,"limit":10}}"""
        val shared = AccelByteJson.decodeFromString<StatItemsResponse>(jsonString)

        assertEquals(1, shared.data.size)
        assertEquals("kda", shared.data.first().statCode)
        assertEquals(1, shared.paging.total)

        val sharedJson = shared.toJsonString()
        val roundTrip = AccelByteJson.decodeFromString<StatItemsResponse>(sharedJson)
        assertEquals(shared, roundTrip)
    }

    @Test
    fun testTicketQueryParamsCompatibility() {
        val jsonString = """{"status":"open","assignee":"agent","limit":5}"""
        val shared = AccelByteJson.decodeFromString<TicketQueryParams>(jsonString)

        assertEquals("open", shared.status)
        assertEquals("agent", shared.assignee)
        assertEquals(5, shared.limit)

        val sharedJson = shared.toJsonString()
        val roundTrip = AccelByteJson.decodeFromString<TicketQueryParams>(sharedJson)
        assertEquals(shared, roundTrip)
    }

    @Test
    fun testReportResponseCompatibility() {
        val jsonString =
            """{"additionalInfo":{"detail":"ok"},"category":"cheating","comment":"report","createdAt":"2025-01-01T00:00:00Z","id":"report-1","namespace":"ns","objectId":"obj","objectType":"type","reason":"cheat","reporterId":"reporter","ticketId":"ticket-1","updatedAt":"2025-01-02T00:00:00Z","userId":"user"}"""
        val shared = AccelByteJson.decodeFromString<ReportResponse>(jsonString)

        assertEquals("report-1", shared.id)
        assertEquals("reporter", shared.reporterId)

        val sharedJson = shared.toJsonString()
        val roundTrip = AccelByteJson.decodeFromString<ReportResponse>(sharedJson)
        assertEquals(shared, roundTrip)
    }

    @Test
    fun testTicketStatisticCompatibility() {
        val jsonString = """{"moderatedCount":3,"openCount":1,"totalCount":4}"""
        val shared = AccelByteJson.decodeFromString<TicketStatisticResponse>(jsonString)

        assertEquals(3, shared.moderatedCount)
        assertEquals(4, shared.totalCount)

        val sharedJson = shared.toJsonString()
        val roundTrip = AccelByteJson.decodeFromString<TicketStatisticResponse>(sharedJson)
        assertEquals(shared, roundTrip)
    }

    @Test
    fun testNamespaceQueryParamsCompatibility() {
        val jsonString = """{"activeOnly":true}"""
        val shared = AccelByteJson.decodeFromString<NamespaceQueryParams>(jsonString)

        assertTrue(shared.activeOnly == true)

        val sharedJson = shared.toJsonString()
        val roundTrip = AccelByteJson.decodeFromString<NamespaceQueryParams>(sharedJson)
        assertEquals(shared, roundTrip)
    }

    @Test
    fun testNamespaceInfoCompatibility() {
        val jsonString =
            """{"id":"ns-1","displayName":"Autogenesis","status":"ACTIVE","createdAt":"2025-01-01T00:00:00Z","permissions":["read"]}"""
        val shared = AccelByteJson.decodeFromString<NamespaceInfo>(jsonString)

        assertEquals("ns-1", shared.id)
        assertEquals("read", shared.permissions.first())

        val sharedJson = shared.toJsonString()
        val roundTrip = AccelByteJson.decodeFromString<NamespaceInfo>(sharedJson)
        assertEquals(shared, roundTrip)
    }

    @Test
    fun testNamespaceListCompatibility() {
        val jsonString =
            """{"data":[{"id":"ns-1","displayName":"Autogenesis","status":"ACTIVE","createdAt":"2025-01-01T00:00:00Z","permissions":["read"]}],"paging":{"total":1,"offset":0,"limit":10}}"""
        val shared = AccelByteJson.decodeFromString<NamespaceListResponse>(jsonString)

        assertEquals(1, shared.data.size)
        assertEquals(1, shared.paging.total)

        val sharedJson = shared.toJsonString()
        val roundTrip = AccelByteJson.decodeFromString<NamespaceListResponse>(sharedJson)
        assertEquals(shared, roundTrip)
    }

    @Test
    fun testConfigListParamsCompatibility() {
        val jsonString = """{"limit":10,"offset":2}"""
        val shared = AccelByteJson.decodeFromString<ConfigListParams>(jsonString)

        assertEquals(10, shared.limit)
        assertEquals(2, shared.offset)

        val sharedJson = shared.toJsonString()
        val roundTrip = AccelByteJson.decodeFromString<ConfigListParams>(sharedJson)
        assertEquals(shared, roundTrip)
    }

    @Test
    fun testGdprRequestListCompatibility() {
        val jsonString = """{"limit":5}"""
        val shared = AccelByteJson.decodeFromString<GdprRequestListParams>(jsonString)

        assertEquals(5, shared.limit)

        val sharedJson = shared.toJsonString()
        val roundTrip = AccelByteJson.decodeFromString<GdprRequestListParams>(sharedJson)
        assertEquals(shared, roundTrip)
    }

    @Test
    fun testGdprPasswordRequestCompatibility() {
        val jsonString = """{"password":"hunter2"}"""
        val shared = AccelByteJson.decodeFromString<GdprPasswordRequest>(jsonString)

        assertEquals("hunter2", shared.password)

        val sharedJson = shared.toJsonString()
        val roundTrip = AccelByteJson.decodeFromString<GdprPasswordRequest>(sharedJson)
        assertEquals(shared, roundTrip)
    }

    @Test
    fun testGdprDataRetrievalResponseCompatibility() {
        val jsonString =
            """{"Namespace":"ns","RequestDate":"2025-01-01","UserID":"user"}"""
        val shared = AccelByteJson.decodeFromString<GdprDataRetrievalResponse>(jsonString)

        assertEquals("user", shared.userId)
        assertEquals("ns", shared.namespace)

        val sharedJson = shared.toJsonString()
        val roundTrip = AccelByteJson.decodeFromString<GdprDataRetrievalResponse>(sharedJson)
        assertEquals(shared, roundTrip)
    }

    @Test
    fun testGroupQueryParamsCompatibility() {
        val jsonString = """{"groupName":"guild","limit":10}"""
        val shared = AccelByteJson.decodeFromString<GroupQueryParams>(jsonString)

        assertEquals("guild", shared.groupName)
        assertEquals(10, shared.limit)

        val sharedJson = shared.toJsonString()
        val roundTrip = AccelByteJson.decodeFromString<GroupQueryParams>(sharedJson)
        assertEquals(shared, roundTrip)
    }

    @Test
    fun testGroupResponseCompatibility() {
        val jsonString =
            """{"groupId":"grp","groupName":"Guild","groupDescription":"desc","groupType":"type","groupRegion":"US","configurationCode":"cfg","groupIcon":"url","groupMaxMember":50,"createdAt":"2025-01-01T00:00:00Z","groupMembers":[{"userId":"user","memberRoleId":["role1"]}],"groupRules":{"groupCustomRule":null,"groupPredefinedRules":[{"allowedAction":"act","ruleDetail":[{"ruleAttribute":"attr","ruleCriteria":"crit","ruleValue":1}]}]},"customAttributes":{}}"""
        val shared = AccelByteJson.decodeFromString<GroupResponseModel>(jsonString)

        assertEquals("grp", shared.groupId)
        assertEquals(1, shared.groupMembers.size)
        assertEquals(1, shared.groupRules.groupPredefinedRules.size)

        val sharedJson = shared.toJsonString()
        val roundTrip = AccelByteJson.decodeFromString<GroupResponseModel>(sharedJson)
        assertEquals(shared, roundTrip)
    }

    @Test
    fun testLegalPolicyResponseCompatibility() {
        val jsonString =
            """{"id":"policy","policyName":"Terms","policyType":"type","namespace":"ns","basePolicyId":"base","countryCode":"US","isMandatory":true,"isDefaultSelection":false,"isDefaultOpted":false,"shouldNotifyOnUpdate":true,"createdAt":"2025-01-01T00:00:00Z","groupRules":null,"baseUrls":["https://example.com"],"readableId":"terms"}"""
        val shared = AccelByteJson.decodeFromString<LegalPolicyResponse>(jsonString)

        assertEquals("policy", shared.id)
        assertEquals("terms", shared.readableId)

        val sharedJson = shared.toJsonString()
        val roundTrip = AccelByteJson.decodeFromString<LegalPolicyResponse>(sharedJson)
        assertEquals(shared, roundTrip)
    }

    @Test
    fun testAgreementConfirmationCompatibility() {
        val jsonString = """{"comply":true,"proceed":false}"""
        val shared = AccelByteJson.decodeFromString<AgreementConfirmationResponse>(jsonString)

        assertTrue(shared.comply)
        assertFalse(shared.proceed)

        val sharedJson = shared.toJsonString()
        val roundTrip = AccelByteJson.decodeFromString<AgreementConfirmationResponse>(sharedJson)
        assertEquals(shared, roundTrip)
    }

    @Test
    fun testAcceptedAgreementResponseCompatibility() {
        val jsonString =
            """{"id":"accept","policyId":"policy","policyName":"Terms","localizedPolicyVersion":{},"tags":["t1"]}"""
        val shared = AccelByteJson.decodeFromString<AcceptedAgreementResponse>(jsonString)

        assertEquals("accept", shared.id)
        assertEquals(listOf("t1"), shared.tags)

        val sharedJson = shared.toJsonString()
        val roundTrip = AccelByteJson.decodeFromString<AcceptedAgreementResponse>(sharedJson)
        assertEquals(shared, roundTrip)
    }

    @Test
    fun testLegalCountryListCompatibility() {
        val jsonString = """{"countries":["US","EU"]}"""
        val shared = AccelByteJson.decodeFromString<LegalCountryListResponse>(jsonString)

        assertEquals(listOf("US", "EU"), shared.countries)

        val sharedJson = shared.toJsonString()
        val roundTrip = AccelByteJson.decodeFromString<LegalCountryListResponse>(sharedJson)
        assertEquals(shared, roundTrip)
    }

    @Test
    fun testLegalPolicyListCompatibility() {
        val jsonString =
            """{"policies":[{"id":"policy","policyName":"Terms","policyType":"type","namespace":"ns","basePolicyId":"base","countryCode":"US","isMandatory":true,"isDefaultSelection":false,"isDefaultOpted":false,"shouldNotifyOnUpdate":true,"baseUrls":["https://example.com"],"readableId":"terms"}]}"""
        val shared = AccelByteJson.decodeFromString<LegalPolicyListResponse>(jsonString)

        assertEquals(1, shared.policies.size)

        val sharedJson = shared.toJsonString()
        val roundTrip = AccelByteJson.decodeFromString<LegalPolicyListResponse>(sharedJson)
        assertEquals(shared, roundTrip)
    }

    @Test
    fun testLegalPolicyVersionCompatibility() {
        val jsonString =
            """{"id":"version","displayVersion":"1.0","isCommitted":true,"isInEffect":false,"localizedPolicyVersions":[{"id":"loc","localeCode":"en","isDefaultSelection":true}],"createdAt":"2025-01-01"}"""
        val shared = AccelByteJson.decodeFromString<LegalPolicyVersionResponse>(jsonString)

        assertEquals("version", shared.id)

        val sharedJson = shared.toJsonString()
        val roundTrip = AccelByteJson.decodeFromString<LegalPolicyVersionResponse>(sharedJson)
        assertEquals(shared, roundTrip)
    }

    @Test
    fun testLocalizedPolicyVersionCompatibility() {
        val jsonString =
            """{"id":"loc","localeCode":"en","isDefaultSelection":true}"""
        val shared = AccelByteJson.decodeFromString<LocalizedPolicyVersionResponse>(jsonString)

        assertEquals("loc", shared.id)

        val sharedJson = shared.toJsonString()
        val roundTrip = AccelByteJson.decodeFromString<LocalizedPolicyVersionResponse>(sharedJson)
        assertEquals(shared, roundTrip)
    }

    @Test
    fun testMatchTicketListCompatibility() {
        val jsonString =
            """{"data":[{"ticketId":"ticket-1","status":"open","matchPool":"arena","estimatedWaitTime":5}],"paging":{"total":1,"offset":0,"limit":20}}"""
        val shared = AccelByteJson.decodeFromString<MatchTicketListResponse>(jsonString)

        assertEquals(1, shared.data.size)

        val sharedJson = shared.toJsonString()
        val roundTrip = AccelByteJson.decodeFromString<MatchTicketListResponse>(sharedJson)
        assertEquals(shared, roundTrip)
    }

    @Test
    fun testMatchTicketDetailsCompatibility() {
        val jsonString =
            """{"ticketId":"ticket-1","status":"matched","playerAssignments":[],"serverInfo":{},"createdAt":"2025-01-01T00:00:00Z"}"""
        val shared = AccelByteJson.decodeFromString<MatchTicketDetailsResponse>(jsonString)

        assertEquals("ticket-1", shared.ticketId)

        val sharedJson = shared.toJsonString()
        val roundTrip = AccelByteJson.decodeFromString<MatchTicketDetailsResponse>(sharedJson)
        assertEquals(shared, roundTrip)
    }

    @Test
    fun testQosmRegionQueryCompatibility() {
        val jsonString = """{"status":"online"}"""
        val shared = AccelByteJson.decodeFromString<QosmRegionQuery>(jsonString)

        assertEquals("online", shared.status)

        val sharedJson = shared.toJsonString()
        val roundTrip = AccelByteJson.decodeFromString<QosmRegionQuery>(sharedJson)
        assertEquals(shared, roundTrip)
    }

    @Test
    fun testQosmAliasRequestCompatibility() {
        val jsonString = """{"alias":"test"}"""
        val shared = AccelByteJson.decodeFromString<QosmAliasRequest>(jsonString)

        assertEquals("test", shared.alias)

        val sharedJson = shared.toJsonString()
        val roundTrip = AccelByteJson.decodeFromString<QosmAliasRequest>(sharedJson)
        assertEquals(shared, roundTrip)
    }

    @Test
    fun testQosmServerResponseCompatibility() {
        val jsonString =
            """{"alias":"s1","ip":"1.2.3.4","port":9000,"region":"us","status":"online","last_update":"2025-01-01T00:00:00Z"}"""
        val shared = AccelByteJson.decodeFromString<QosmServerResponse>(jsonString)

        assertEquals("s1", shared.alias)

        val sharedJson = shared.toJsonString()
        val roundTrip = AccelByteJson.decodeFromString<QosmServerResponse>(sharedJson)
        assertEquals(shared, roundTrip)
    }

    @Test
    fun testQosmServerListCompatibility() {
        val jsonString =
            """{"servers":[{"alias":"s1","ip":"1.2.3.4","port":9000,"region":"us","status":"online","last_update":"2025-01-01T00:00:00Z"}]}"""
        val shared = AccelByteJson.decodeFromString<QosmServerListResponse>(jsonString)

        assertEquals(1, shared.servers.size)

        val sharedJson = shared.toJsonString()
        val roundTrip = AccelByteJson.decodeFromString<QosmServerListResponse>(sharedJson)
        assertEquals(shared, roundTrip)
    }

    @Test
    fun testBuildVersionEntryCompatibility() {
        val jsonString =
            """{"buildId":"b1","version":"1.2.3","createdAt":"2025-01-01T00:00:00Z","size":1024}"""
        val shared = AccelByteJson.decodeFromString<BuildVersionEntry>(jsonString)

        assertEquals("b1", shared.buildId)

        val sharedJson = shared.toJsonString()
        val roundTrip = AccelByteJson.decodeFromString<BuildVersionEntry>(sharedJson)
        assertEquals(shared, roundTrip)
    }

    @Test
    fun testVersionHistoryCompatibility() {
        val jsonString =
            """{"versions":[{"buildId":"b1","version":"1.2.3","createdAt":"2025-01-01T00:00:00Z"}]}"""
        val shared = AccelByteJson.decodeFromString<VersionHistoryResponse>(jsonString)

        assertEquals(1, shared.versions.size)

        val sharedJson = shared.toJsonString()
        val roundTrip = AccelByteJson.decodeFromString<VersionHistoryResponse>(sharedJson)
        assertEquals(shared, roundTrip)
    }

    @Test
    fun testDiffStatusCompatibility() {
        val jsonString = """{"status":"ok","progress":50,"diffSize":2,"estimatedTime":10}"""
        val shared = AccelByteJson.decodeFromString<DiffStatusResponse>(jsonString)

        assertEquals(50, shared.progress)

        val sharedJson = shared.toJsonString()
        val roundTrip = AccelByteJson.decodeFromString<DiffStatusResponse>(sharedJson)
        assertEquals(shared, roundTrip)
    }

    @Test
    fun testBlockDownloadUrlCompatibility() {
        val jsonString = """{"url":"https://dl","expiresAt":"2025-01-01T00:00:00Z","blockType":"patch","compressed":true}"""
        val shared = AccelByteJson.decodeFromString<BlockDownloadUrlResponse>(jsonString)

        assertEquals("https://dl", shared.url)

        val sharedJson = shared.toJsonString()
        val roundTrip = AccelByteJson.decodeFromString<BlockDownloadUrlResponse>(sharedJson)
        assertEquals(shared, roundTrip)
    }

    @Test
    fun testCacheDiffCompatibility() {
        val jsonString =
            """{"files":[{"path":"/data","action":"update","size":128,"checksum":"abc"}]}"""
        val shared = AccelByteJson.decodeFromString<CacheDiffResponse>(jsonString)

        assertEquals(1, shared.files.size)

        val sharedJson = shared.toJsonString()
        val roundTrip = AccelByteJson.decodeFromString<CacheDiffResponse>(sharedJson)
        assertEquals(shared, roundTrip)
    }

    @Test
    fun testSeasonQueryParamsCompatibility() {
        val jsonString = """{"language":"en","limit":5}"""
        val shared = AccelByteJson.decodeFromString<SeasonQueryParams>(jsonString)

        assertEquals("en", shared.language)

        val sharedJson = shared.toJsonString()
        val roundTrip = AccelByteJson.decodeFromString<SeasonQueryParams>(sharedJson)
        assertEquals(shared, roundTrip)
    }

    @Test
    fun testSeasonInfoResponseCompatibility() {
        val jsonString =
            """{"id":"s1","name":"Season","namespace":"ns","start":"2025-01-01","end":"2025-02-01","status":"active","passes":[],"rewards":{},"tiers":[]}"""
        val shared = AccelByteJson.decodeFromString<SeasonInfoResponse>(jsonString)

        assertEquals("s1", shared.id)

        val sharedJson = shared.toJsonString()
        val roundTrip = AccelByteJson.decodeFromString<SeasonInfoResponse>(sharedJson)
        assertEquals(shared, roundTrip)
    }

    @Test
    fun testSeasonClaimRequestCompatibility() {
        val jsonString = """{"passCode":"code","tierIndex":1}"""
        val shared = AccelByteJson.decodeFromString<SeasonClaimRequest>(jsonString)

        assertEquals("code", shared.passCode)

        val sharedJson = shared.toJsonString()
        val roundTrip = AccelByteJson.decodeFromString<SeasonClaimRequest>(sharedJson)
        assertEquals(shared, roundTrip)
    }

    @Test
    fun testSeasonpassExportResponseCompatibility() {
        val jsonString = """{"payload":{"foo":"bar"}}"""
        val shared = AccelByteJson.decodeFromString<SeasonpassExportResponse>(jsonString)

        assertEquals("bar", shared.payload.jsonObject["foo"]?.jsonPrimitive?.content)

        val sharedJson = shared.toJsonString()
        val roundTrip = AccelByteJson.decodeFromString<SeasonpassExportResponse>(sharedJson)
        assertEquals(shared, roundTrip)
    }

    @Test
    fun testConfigEntryCompatibility() {
        val jsonString =
            """{"key":"test","value":{"nested":true},"namespace":"ns","public":true}"""
        val shared = AccelByteJson.decodeFromString<ConfigEntry>(jsonString)

        assertEquals("test", shared.key)
        assertEquals(true, shared.isPublic)

        val sharedJson = shared.toJsonString()
        val roundTrip = AccelByteJson.decodeFromString<ConfigEntry>(sharedJson)
        assertEquals(shared, roundTrip)
    }

    @Test
    fun testEmailTemplateCompatibility() {
        val jsonString =
            """{"name":"welcome","subject":"hi","body":"hello","type":"default"}"""
        val shared = AccelByteJson.decodeFromString<EmailTemplate>(jsonString)

        assertEquals("welcome", shared.name)
        assertEquals("hi", shared.subject)

        val sharedJson = shared.toJsonString()
        val roundTrip = AccelByteJson.decodeFromString<EmailTemplate>(sharedJson)
        assertEquals(shared, roundTrip)
    }

    @Test
    fun testLinkedSenderCompatibility() {
        val jsonString = """{"email":"admin@accelbyte.io","verified":true,"createdAt":"2025-01-01T00:00:00Z"}"""
        val shared = AccelByteJson.decodeFromString<LinkedSender>(jsonString)

        assertEquals("admin@accelbyte.io", shared.email)
        assertTrue(shared.verified)

        val sharedJson = shared.toJsonString()
        val roundTrip = AccelByteJson.decodeFromString<LinkedSender>(sharedJson)
        assertEquals(shared, roundTrip)
    }

    @Test
    fun testAchievementQueryParamsCompatibility() {
        val jsonString = """{"limit":42}"""
        val shared = AccelByteJson.decodeFromString<AchievementQueryParams>(jsonString)

        assertEquals(42, shared.limit)

        val sharedJson = shared.toJsonString()
        val roundTrip = AccelByteJson.decodeFromString<AchievementQueryParams>(sharedJson)
        assertEquals(shared, roundTrip)
    }

    @Test
    fun testAchievementCompatibility() {
        val jsonString =
            """{"achievementCode":"ach-1","name":"Master","description":"desc","iconUrl":"https://icon","globalUnlockPercentage":35.5,"isHidden":false}"""
        val shared = AccelByteJson.decodeFromString<Achievement>(jsonString)

        assertEquals("ach-1", shared.achievementCode)
        assertEquals("Master", shared.name)
        assertEquals(35.5, shared.globalUnlockPercentage)

        val sharedJson = shared.toJsonString()
        val roundTrip = AccelByteJson.decodeFromString<Achievement>(sharedJson)
        assertEquals(shared, roundTrip)
    }

    @Test
    fun testChatQueryParamsCompatibility() {
        val jsonString = """{"limit":12}"""
        val shared = AccelByteJson.decodeFromString<ChatQueryParams>(jsonString)

        assertEquals(12, shared.limit)

        val sharedJson = shared.toJsonString()
        val roundTrip = AccelByteJson.decodeFromString<ChatQueryParams>(sharedJson)
        assertEquals(shared, roundTrip)
    }

    @Test
    fun testChatMessageCompatibility() {
        val jsonString =
            """{"id":"msg-1","topic":"general","userId":"user-1","message":"hello","createdAt":"2025-08-01T00:00:00Z"}"""
        val shared = AccelByteJson.decodeFromString<ChatMessage>(jsonString)

        assertEquals("msg-1", shared.id)
        assertEquals("general", shared.topic)

        val sharedJson = shared.toJsonString()
        val roundTrip = AccelByteJson.decodeFromString<ChatMessage>(sharedJson)
        assertEquals(shared, roundTrip)
    }

    @Test
    fun testGameRecordRequestCompatibility() {
        val jsonString = """{"data":{"score":10},"updateIfExists":false}"""
        val shared = AccelByteJson.decodeFromString<GameRecordRequest>(jsonString)

        assertEquals(false, shared.updateIfExists)
        assertEquals("{\"score\":10}", shared.data.toString())

        val sharedJson = shared.toJsonString()
        val roundTrip = AccelByteJson.decodeFromString<GameRecordRequest>(sharedJson)
        assertEquals(shared, roundTrip)
    }

    @Test
    fun testBulkGameRecordRequestCompatibility() {
        val jsonString = """{"keys":["a","b"]}"""
        val shared = AccelByteJson.decodeFromString<BulkGameRecordRequest>(jsonString)

        assertEquals(listOf("a", "b"), shared.keys)

        val sharedJson = shared.toJsonString()
        val roundTrip = AccelByteJson.decodeFromString<BulkGameRecordRequest>(sharedJson)
        assertEquals(shared, roundTrip)
    }

    @Test
    fun testGameRecordResponseCompatibility() {
        val jsonString =
            """{"key":"record-1","value":{"score":5},"isPublic":true,"metadata":{"mode":"solo"}}"""
        val shared = AccelByteJson.decodeFromString<GameRecordResponse>(jsonString)

        assertEquals("record-1", shared.key)
        assertEquals(true, shared.isPublicRecord)

        val sharedJson = shared.toJsonString()
        val roundTrip = AccelByteJson.decodeFromString<GameRecordResponse>(sharedJson)
        assertEquals(shared, roundTrip)
    }

    @Test
    fun testEventQueryParamsCompatibility() {
        val jsonString = """{"startDate":"2025-01-01","endDate":"2025-01-02","pageSize":50}"""
        val shared = AccelByteJson.decodeFromString<EventQueryParams>(jsonString)

        assertEquals("2025-01-01", shared.startDate)
        assertEquals(50, shared.pageSize)

        val sharedJson = shared.toJsonString()
        val roundTrip = AccelByteJson.decodeFromString<EventQueryParams>(sharedJson)
        assertEquals(shared, roundTrip)
    }

    @Test
    fun testEventEntryCompatibility() {
        val jsonString =
            """{"eventId":12345,"eventType":"login","userId":"user","timestamp":"2025-01-01T00:00:00Z","metadata":{"source":"test"}}"""
        val shared = AccelByteJson.decodeFromString<EventEntry>(jsonString)

        assertEquals(12345.0, shared.eventId)
        assertEquals("login", shared.eventType)

        val sharedJson = shared.toJsonString()
        val roundTrip = AccelByteJson.decodeFromString<EventEntry>(sharedJson)
        assertEquals(shared, roundTrip)
    }

    @Test
    fun testEventListResponseCompatibility() {
        val jsonString =
            """{"events":[{"eventId":101,"eventType":"loot","userId":"player","timestamp":"2025-01-02T00:00:00Z"}],"paging":{"total":1,"offset":0,"limit":20},"dateRange":{"startDate":"2025-01-01","endDate":"2025-01-03"},"userId":"player"}"""
        val shared = AccelByteJson.decodeFromString<EventListResponse>(jsonString)

        assertEquals(1, shared.events.size)
        assertEquals("player", shared.userId)
        assertEquals("2025-01-03", shared.dateRange?.endDate)

        val sharedJson = shared.toJsonString()
        val roundTrip = AccelByteJson.decodeFromString<EventListResponse>(sharedJson)
        assertEquals(shared, roundTrip)
    }

    @Test
    fun testVersionHistoryParamsCompatibility() {
        val jsonString = """{"appId":"app","comparedBuildId":"build"}"""
        val shared = AccelByteJson.decodeFromString<VersionHistoryParams>(jsonString)

        assertEquals("app", shared.appId)

        val sharedJson = shared.toJsonString()
        val roundTrip = AccelByteJson.decodeFromString<VersionHistoryParams>(sharedJson)
        assertEquals(shared, roundTrip)
    }

    @Test
    fun testTelemetryQueryParamsCompatibility() {
        val jsonString = """{"eventName":"login","limit":20}"""
        val shared = AccelByteJson.decodeFromString<TelemetryQueryParams>(jsonString)

        assertEquals("login", shared.eventName)

        val sharedJson = shared.toJsonString()
        val roundTrip = AccelByteJson.decodeFromString<TelemetryQueryParams>(sharedJson)
        assertEquals(shared, roundTrip)
    }

    @Test
    fun testTelemetryPlaytimeResponseCompatibility() {
        val jsonString = """{"steamId":"123","playtimeSeconds":3600,"updatedAt":"2025-01-01T00:00:00Z"}"""
        val shared = AccelByteJson.decodeFromString<TelemetryPlaytimeResponse>(jsonString)

        assertEquals(3600, shared.playtimeSeconds)

        val sharedJson = shared.toJsonString()
        val roundTrip = AccelByteJson.decodeFromString<TelemetryPlaytimeResponse>(sharedJson)
        assertEquals(shared, roundTrip)
    }

    @Test
    fun testTelemetryProtectedEventResultCompatibility() {
        val jsonString = """{"processed":true,"eventIds":["evt1"]}"""
        val shared = AccelByteJson.decodeFromString<TelemetryProtectedEventResult>(jsonString)

        assertEquals(listOf("evt1"), shared.eventIds)

        val sharedJson = shared.toJsonString()
        val roundTrip = AccelByteJson.decodeFromString<TelemetryProtectedEventResult>(sharedJson)
        assertEquals(shared, roundTrip)
    }

    @Test
    fun testTelemetryNamespaceListCompatibility() {
        val jsonString = """{"namespaces":["ns"]}"""
        val shared = AccelByteJson.decodeFromString<TelemetryNamespaceListResponse>(jsonString)

        assertEquals(listOf("ns"), shared.namespaces)

        val sharedJson = shared.toJsonString()
        val roundTrip = AccelByteJson.decodeFromString<TelemetryNamespaceListResponse>(sharedJson)
        assertEquals(shared, roundTrip)
    }

    @Test
    fun testTelemetryEventRecordCompatibility() {
        val jsonString =
            """{"eventId":"evt","eventName":"loot","eventNamespace":"game","timestamp":"2025-01-01T00:00:00Z"}"""
        val shared = AccelByteJson.decodeFromString<TelemetryEventRecord>(jsonString)

        assertEquals("evt", shared.eventId)
        assertEquals("game", shared.eventNamespace)

        val sharedJson = shared.toJsonString()
        val roundTrip = AccelByteJson.decodeFromString<TelemetryEventRecord>(sharedJson)
        assertEquals(shared, roundTrip)
    }

    @Test
    fun testTelemetryEventListCompatibility() {
        val jsonString =
            """{"data":[{"eventId":"evt","eventName":"loot","eventNamespace":"game","timestamp":"2025-01-01T00:00:00Z"}],"paging":{"total":1,"offset":0,"limit":10}}"""
        val shared = AccelByteJson.decodeFromString<TelemetryEventListResponse>(jsonString)

        assertEquals(1, shared.data.size)

        val sharedJson = shared.toJsonString()
        val roundTrip = AccelByteJson.decodeFromString<TelemetryEventListResponse>(sharedJson)
        assertEquals(shared, roundTrip)
    }
}
