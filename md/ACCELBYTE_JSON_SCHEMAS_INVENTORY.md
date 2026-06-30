# AccelByte SDK JSON Schemas Inventory

## Existing Data Classes (12)

| Data Class | Fields | Usage |
|------------|--------|-------|
| `MuteRequest` | userId: String | Chat muting operations |
| `BanRequest` | userId: String, reason: String | Chat banning operations |
| `GameRecordRequest` | data: Json, updateIfExists: Boolean | Cloud save game records |
| `BulkGameRecordRequest` | keys: List<String> | Bulk cloud save operations |
| `DiffRequest` | Multiple fields | Build diff operations |
| `RefreshTokenRequest` | refreshToken: String, clientId: String, tokenUrl: String? | IAM token refresh |
| `MatchTicketRequest` | Multiple fields | Matchmaking ticket creation |
| `MatchTicketFilter` | Multiple fields | Matchmaking ticket filtering |
| `SessionBrowserFilter` | 17+ fields | Session browsing (custom) |
| `CreateGameSessionRequest` | gameMode: String, maxPlayers: Int, namespace: String, region: String | Session creation |
| `AppendTeamRequest` | members: List<String> | Session team management |
| `SessionInviteRequest` | userId: String, note: String? | Session invitations |

## Required JSON Schemas by Service

### 1. Achievement Service (2 schemas)
- **AchievementQueryParams**: `{ limit: number }`
- **UserAchievementQueryParams**: `{ limit: number }`

### 2. Basic Service (4 schemas)
- **NamespaceQueryParams**: `{ activeOnly: boolean }`
- **FileUploadParams**: `{ fileType: string }`
- **UserProfileQueryParams**: `{ userIds: string }` (comma-separated)
- **FileUploadRequest**: `{ fileType: string }`

### 3. Buildinfo Service (2 schemas)
- **VersionHistoryParams**: `{ appId: string, comparedBuildId: string }`
- **BlockUrlParams**: `{ fileType: string }`

### 4. Chat Service (2 schemas)
- **ChatQueryParams**: `{ limit: number }`
- **TopicModerationRequest**: `{ userId: string, reason?: string }`

### 5. Cloud Save Service (2 schemas)
- **GameRecordData**: `{ data: any, updateIfExists: boolean }`
- **BulkRecordQuery**: `{ keys: string[] }`

### 6. Config Service (3 schemas)
- **ConfigQueryParams**: Dynamic object with optional fields
- **EmailConfigParams**: `{ includeEmailTemplates?: boolean }`
- **ConfigFilterParams**: Complex filtering object

### 7. DSM Controller Service (3 schemas)
- **ServerQueryParams**: `{ count: number, offset: number }`
- **RegionQueryParams**: `{ region?: string }`
- **DeploymentQueryParams**: `{ count: number, offset: number }`

### 8. Event Service (3 schemas)
- **EventQueryParams**: `{ namespace: string, endDate?: string, pageSize?: number }`
- **UserEventParams**: `{ namespace: string, userId: string, endDate?: string, pageSize?: number }`
- **EventDescriptionParams**: `{ namespace: string, eventId: number, endDate?: string, pageSize?: number }`

### 9. Game Telemetry Service (1 schema)
- **TelemetryQueryParams**: Dynamic object with optional fields

### 10. GDPR Service (2 schemas)
- **DataRequestParams**: `{ password: string }`
- **RequestQueryParams**: `{ limit: number }`

### 11. Group Service (2 schemas)
- **GroupQueryParams**: `{ groupName?: string, limit: number }`
- **CreateGroupRequest**: `{ groupName: string, groupRegion: string }`

### 12. IAM Service (3 schemas)
- **TokenRequest**: `{ token: string }`
- **OAuthTokenData**: `{ grant_type: string, username?: string, password?: string, client_id?: string, client_secret?: string, refresh_token?: string, scope?: string }`
- **RefreshTokenArgs**: `{ refreshToken: string, clientId: string, tokenUrl?: string }`

### 13. Leaderboard Service (1 schema)
- **LeaderboardQueryParams**: `{ limit: number }`

### 14. Legal Service (0 schemas)
- Uses empty json() objects

### 15. Lobby Service (1 schema)
- **PartyLimitRequest**: `{ max_players: number }`

### 16. Matchmaking Service (2 schemas)
- **MatchTicketData**: Complex object with multiple fields
- **MatchTicketFilterData**: Complex filtering object

### 17. Platform Service (0 schemas)
- No direct JSON schemas identified

### 18. QoS Manager Service (2 schemas)
- **QoSQueryParams**: `{ status?: string }`
- **AliasRequest**: `{ alias: string }`

### 19. Reporting Service (5 schemas)
- **ReportQueryParams**: Dynamic filtering object
- **TicketQueryParams**: Dynamic filtering object  
- **TicketResolutionRequest**: `{ resolution: string, notes?: string }`
- **ConfigurationQueryParams**: Dynamic filtering object
- **ExtensionQueryParams**: Dynamic filtering object

### 20. Season Pass Service (2 schemas)
- **SeasonQueryParams**: Dynamic filtering object
- **ForceActionParams**: `{ force?: boolean }`
- **ItemReferenceParams**: `{ itemId: string }`

### 21. Session Service (2 schemas)
- **JoinCodeRequest**: `{ code: string }`
- **SessionData**: `{ game_mode: string, max_players: number, namespace: string, region: string }`

### 22. Social Service (1 schema)
- **StatItemQueryParams**: `{ limit: number }`

### 23. UGC Service (1 schema)
- **ContentQueryParams**: `{ limit: number }`

## Summary Statistics

### Current State
- **Existing Data Classes**: 12
- **Services Analyzed**: 23
- **Unique Schema Requirements**: ~65-70

### Schema Categories
- **Query Parameters**: ~35 schemas (pagination, filtering, search)
- **Request Bodies**: ~20 schemas (create, update operations)
- **Configuration Objects**: ~10 schemas (complex settings)
- **Simple Parameters**: ~5 schemas (single field objects)

### Priority Levels

#### High Priority (Core Operations)
1. **Authentication Schemas** (IAM) - 3 schemas
2. **Session Management** (Session) - 4 schemas  
3. **User Management** (Basic, GDPR) - 6 schemas
4. **Query Parameters** (Universal) - 15 schemas

#### Medium Priority (Feature Operations)
1. **Matchmaking** - 2 schemas
2. **Cloud Save** - 2 schemas
3. **Chat/Social** - 4 schemas
4. **Reporting** - 5 schemas

#### Low Priority (Admin/Advanced)
1. **Config Management** - 3 schemas
2. **DSM Controller** - 3 schemas
3. **Build/Telemetry** - 4 schemas
4. **Season Pass** - 3 schemas

## Recommendations

### Phase 1: Core Schemas (20 schemas)
Focus on authentication, sessions, and basic user operations

### Phase 2: Feature Schemas (25 schemas) 
Add matchmaking, social features, and content management

### Phase 3: Advanced Schemas (20 schemas)
Complete with admin tools and specialized features

**Total Estimated Data Classes Needed**: ~65-70 additional classes beyond the existing 12.
