# AccelByte SDK Coverage

| Module | Package | Current Kotlin Coverage | Notes / Next Actions |
| --- | --- | --- | --- |
| `achievement` | `@accelbyte/sdk-achievement` | Covered | Facade wraps `AchievementsApi` (list, unlock, user achievements) |
| `basic` | `@accelbyte/sdk-basic` | Covered | Facade now wraps namespace listing, file uploads, and user profile retrieval |
| `buildinfo` | `@accelbyte/sdk-buildinfo` | Covered | Facade exposes downloader history/diff/block URL plus cache diff checks |
| `chat` | `@accelbyte/sdk-chat` | Covered | Chat facade now exposes topic operations (mute/unmute, chat history, bans) |
| `cloudsave` | `@accelbyte/sdk-cloudsave` | Covered | Facade implemented, need to extend if additional APIs required |
| `content-management` | `@accelbyte/sdk-csm` | Covered | Facade exposes CSM message listing APIs |
| `differ` | `@accelbyte/sdk-differ` | Covered | Facade wraps `DiffCalculationApi` for diff creation and ping |
| `dsmcontroller` | `@accelbyte/sdk-dsmcontroller` | Covered | Facade exposes service control, session, and deployment APIs for DSM |
| `event` | `@accelbyte/sdk-event` | Covered | Facade queries namespace/user/event data via EventApi |
| `game-telemetry` | `@accelbyte/sdk-gametelemetry` | Covered | Facade wraps protected events plus admin namespace/event lookups |
| `gdpr` | `@accelbyte/sdk-gdpr` | Covered | Facade wraps data retrieval/deletion APIs |
| `group` | `@accelbyte/sdk-groups` | Covered | Facade wraps GroupApi listing/creation/deletion |
| `iam` | `@accelbyte/sdk-iam` | Covered | OAuth/session token operations already wrapped |
| `leaderboard` | `@accelbyte/sdk-leaderboard` | Covered | Facade now handles ranking lookups via LeaderboardDataApi |
| `agreement` | `@accelbyte/sdk-legal` | Covered | Facade exposes agreement acceptance, policy lists, and readiness helpers |
| `lobby` | `@accelbyte/sdk-lobby` | Covered | Facade wraps party/lobby operations plus message listing |
| `match2` / `matchmaking` | `@accelbyte/sdk-matchmaking` | Covered | Matchmaking facade exposes ticket creation and status APIs |
| `odin-config` | `@accelbyte/sdk-platform` (covers config) | Covered | Platform facade wraps store/item queries |
| `platform` | `@accelbyte/sdk-platform` | Covered | Platform APIs exposed via StoreApi/ItemApi facades |
| `qosm` | `@accelbyte/sdk-qosmanager` | Covered | Facade surfaces QoS heartbeats, region listings, and admin controls |
| `reporting` | `@accelbyte/sdk-reporting` | Covered | Facade exposes reason lists, reports, tickets, and moderation configs |
| `seasonpass` | `@accelbyte/sdk-seasonpass` | Covered | Facade wraps season lifecycle, reward, and pass management |
| `session` | `@accelbyte/sdk-session` | Covered | Facade implemented |
| `sessionbrowser` | `@accelbyte/sdk-session` | Covered | Facade uses session query/join endpoints via `SessionBrowserFilter` |
| `social` | `@accelbyte/sdk-social` | Covered | Facade exposes user statistic retrieval/bulk updates |
| `ugc` | `@accelbyte/sdk-ugc` | Covered | Facade wraps content and follow APIs |
| `config` | `@accelbyte/sdk-config` (if separate) | Covered | Facade exposes config, email sender, and account profile flows |

> **Legend**: Covered facades already exist. “Not covered yet” entries are the modules we still need to bind from TypeScript to Kotlin.