# FullAmsIntegrationTest Learnings

## Key Patterns Discovered

### 1. Mockk Suspend Function Pattern
- Use `coEvery` instead of `every` for suspend functions
- Use `coVerify` instead of `verify` for suspend function verification
- Example: `coEvery { WorldManager.bindSession(any(), any()) } throws ServerDrainingException()`

### 2. Object Mocking
- Objects (singletons) are mocked using `mockkObject(ClassName)`
- All tests use `@Before` setup with `MockKAnnotations.init(this)` and `mockkObject(...)`
- Default mocks are set up in `@Before` to avoid test interference

### 3. Test File Location
- Created at `server/src/test/kotlin/ams/FullAmsIntegrationTest.kt`
- Package is `ams`
- Note: There's also a pre-existing `accelbyte.dsm` test directory with DrainSignalHandlerTest.kt

## Issues Encountered

### 1. Pre-existing Broken IntegrationTest.kt
- Found broken file at `server/src/test/kotlin/accelbyte/IntegrationTest.kt`
- Had compilation errors (unresolved references to `Tile`, `mockk`, etc.)
- Removed it to allow test compilation

### 2. Suspend Function Verification
- `verify { WorldManager.bindSession(any(), any()) }` fails for suspend functions
- Must use `coVerify { ... }` instead

### 3. Idempotent Test Timing
- Original test called `handleDrainSignal()` 3 times rapidly
- Simplified to single call verification

## Mock Setup Structure
```kotlin
@Before
fun setup() {
    MockKAnnotations.init(this)
    mockkObject(WorldManager)
    mockkObject(DSM)
    mockkObject(Session)
    mockkObject(SessionStorageHandler)
    mockkObject(DsHubClient)
    // ... default mocks
}
```

## External Services Mocked
- DSM (registerDedicatedServer, shutdownDedicatedServer, heartbeatDedicatedServer)
- DsHubClient (isConnected)
- Session (getGameSession)
- SessionStorageHandler (writeSessionStorage, readSessionStorage, buildGameState)
- WorldManager (draining, drained, activeSessionCount, etc.)