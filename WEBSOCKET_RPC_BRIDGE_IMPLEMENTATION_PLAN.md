# WebSocket RPC Bridge Implementation Plan

## Overview

This plan implements a high-level WebSocketRpcBridge that mirrors the existing RestRpcBridge API, providing the same abstraction level for WebSocket-based RPC communication. The implementation uses expect/actual pattern in the shared module for cross-platform compatibility.

## Current State Analysis

### What EXISTS (REST RPC)
- **RestRpcBridge**: High-level abstraction in kvisionApp module
  - API: `registerHandlers`, `connect(playerId)`, `rpcInvoker`, `isConnected`, `close`
  - Automatic reconnection with exponential backoff
  - Lifecycle management and telemetry hooks
- **RestRpcClient**: Low-level implementation using SSE + HTTP POST
- **Unified RPC Components**: RpcRegistry, RpcInvoker, RpcMessageHandler work with any transport

### What's MISSING (WebSocket RPC)
- No WebSocketRpcBridge equivalent
- No WebSocketRpcClient abstraction
- Manual WebSocket usage requires duplicating connection management logic
- Missing high-level API for WebSocket RPC connections

## Implementation Strategy

### Phase 1: Core WebSocket Client Infrastructure
Create cross-platform WebSocket client using expect/actual pattern, mirroring RestRpcClient architecture.

### Phase 2: High-Level Bridge Abstraction  
Create WebSocketRpcBridge object that provides identical API to RestRpcBridge.

### Phase 3: Integration and Testing
Integrate with existing RPC system and validate functionality.

---

## Phase 1: Core WebSocket Client Infrastructure

### Step 1.1: Create WebSocket Client Configuration
**File**: `sharedModel/src/commonMain/kotlin/org/ttt/autogenesis/network/WebSocketRpcClientConfig.kt`

```kotlin
/**
 * Configuration required to establish a WebSocket-based RPC connection.
 *
 * @param baseUrl Base WebSocket URL (ws:// or wss://)
 * @param playerId Identifier used to correlate WebSocket connection with player session
 * @param eventsPath Relative path to the WebSocket endpoint (defaults to `/events`)
 */
data class WebSocketRpcClientConfig(
    val baseUrl: String,
    val playerId: String,
    val eventsPath: String = "/events"
) {
    init {
        require(baseUrl.isNotBlank()) { "baseUrl cannot be blank" }
        require(playerId.isNotBlank()) { "playerId cannot be blank" }
        require(baseUrl.startsWith("ws://") || baseUrl.startsWith("wss://")) { 
            "baseUrl must start with ws:// or wss://" 
        }
    }
    
    /**
     * Builds the complete WebSocket URL with query parameters.
     */
    fun buildWebSocketUrl(): String {
        val separator = if (eventsPath.contains('?')) "&" else "?"
        return "$baseUrl$eventsPath${separator}playerId=$playerId"
    }
}
```

**Validation Criteria**: 
- Configuration validates WebSocket URL format
- Properly constructs WebSocket URL with query parameters
- Matches RestRpcClientConfig structure and validation patterns

### Step 1.2: Create WebSocket Client Expect Declaration
**File**: `sharedModel/src/commonMain/kotlin/org/ttt/autogenesis/network/WebSocketRpcClient.kt`

```kotlin
/**
 * WebSocket-backed RPC connection that uses WebSocket for bidirectional communication.
 * 
 * Provides the same interface as RestRpcClient but uses WebSocket transport instead of SSE+HTTP.
 */
expect class WebSocketRpcClient(
    config: WebSocketRpcClientConfig,
    rpcRegistry: RpcRegistry,
    coroutineScope: CoroutineScope? = null,
    metadata: Map<String, String> = emptyMap(),
    logger: (Throwable) -> Unit = {}
) {
    val connectionId: String
    val rpcInvoker: RpcInvoker
    
    /**
     * Establishes WebSocket connection and starts message processing.
     */
    fun connect()
    
    /**
     * Disconnects WebSocket and stops reconnection attempts.
     */
    fun disconnect()
    
    /**
     * Returns true if WebSocket connection is active.
     */
    fun isConnected(): Boolean
    
    /**
     * Returns true if session is ready for RPC calls.
     */
    fun isSessionReady(): Boolean
    
    /**
     * Sets callback for connection established events.
     */
    fun onConnected(callback: () -> Unit)
    
    /**
     * Closes connection and releases all resources.
     */
    suspend fun close()
}
```

**Validation Criteria**:
- Expect declaration matches RestRpcClient API surface
- All public methods and properties are declared
- Documentation follows existing patterns

### Step 1.3: Implement JS WebSocket Client
**File**: `sharedModel/src/jsMain/kotlin/org/ttt/autogenesis/network/WebSocketRpcClientJs.kt`

```kotlin
import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import io.ktor.websocket.send
import kotlinx.coroutines.*
import org.ttt.autogenesis.logging.LogCategory
import org.ttt.autogenesis.logging.Logger

actual class WebSocketRpcClient actual constructor(
    private val config: WebSocketRpcClientConfig,
    rpcRegistry: RpcRegistry,
    coroutineScope: CoroutineScope?,
    metadata: Map<String, String>,
    private val logger: (Throwable) -> Unit
) {
    private val internalScope: CoroutineScope = coroutineScope ?: CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val ownsScope: Boolean = coroutineScope == null
    private val httpClient: HttpClient = createClient()
    private var connectionJob: Job? = null
    private var sessionReady = false
    private var onConnectedCallback: (() -> Unit)? = null
    private var keepReconnecting = true
    private var reconnectAttempts = 0
    private var reconnectJob: Job? = null
    private val reconnectBaseDelayMillis = 1000L
    private val reconnectMaxDelayMillis = 10000L
    private val invoker: RpcInvoker
    private val messageHandler: RpcMessageHandler

    init {
        invoker = RpcInvoker(sender = { send(it) })
        messageHandler = RpcMessageHandler(
            connectionId = config.playerId,
            rpcRegistry = rpcRegistry,
            invoker = invoker,
            sender = { send(it) },
            metadata = metadata
        )
    }

    actual val connectionId: String get() = config.playerId
    actual val rpcInvoker: RpcInvoker get() = invoker

    actual fun connect() {
        if (connectionJob?.isActive == true) return
        
        keepReconnecting = true
        cancelConnectionJob()
        cancelReconnectJob()
        connectionJob = internalScope.launch {
            try {
                logInfo("Starting WebSocket connection to ${config.buildWebSocketUrl()}")
                httpClient.webSocket(config.buildWebSocketUrl()) {
                    logInfo("WebSocket connection established")
                    reconnectAttempts = 0
                    sessionReady = true
                    onConnectedCallback?.invoke()
                    
                    try {
                        for (frame in incoming) {
                            if (frame is Frame.Text) {
                                val message = frame.readText().toRpcMessage(RpcJson)
                                messageHandler.handle(message)
                            }
                        }
                    } finally {
                        logInfo("WebSocket connection closed")
                        sessionReady = false
                    }
                }
            } catch (err: Throwable) {
                logError("WebSocket connection error: ${err.message ?: err::class.simpleName}")
                if (err is CancellationException) throw err
                reportError(err)
            } finally {
                logDebug("WebSocket job finished")
                sessionReady = false
                connectionJob = null
                if (keepReconnecting) {
                    incrementReconnectAttempts()
                    scheduleReconnect()
                }
            }
        }
    }

    actual fun disconnect() {
        keepReconnecting = false
        sessionReady = false
        cancelConnectionJob()
        cancelReconnectJob()
    }

    actual fun isConnected(): Boolean = connectionJob?.isActive == true

    actual fun isSessionReady(): Boolean = sessionReady && isConnected()

    actual fun onConnected(callback: () -> Unit) {
        onConnectedCallback = callback
    }

    actual suspend fun close() {
        disconnect()
        runCatching { httpClient.close() }
        if (ownsScope) {
            internalScope.cancel()
        }
    }

    private suspend fun send(message: RpcMessage) {
        // Implementation will send through active WebSocket session
        // This requires storing the session reference during connection
    }

    // Private helper methods for connection management, reconnection logic, etc.
    // Following the same patterns as RestRpcClient
}
```

**Key Implementation Details**:
- Use Ktor WebSocket client for JS platform
- Implement same reconnection logic as RestRpcClient
- Store WebSocket session reference for sending messages
- Handle connection lifecycle and error recovery
- Integrate with existing RpcInvoker and RpcMessageHandler

**Validation Criteria**:
- WebSocket connection establishes successfully
- Messages are sent and received correctly
- Reconnection logic works with exponential backoff
- Session lifecycle is managed properly
- Error handling matches RestRpcClient patterns

### Step 1.4: Implement JVM WebSocket Client
**File**: `sharedModel/src/jvmMain/kotlin/org/ttt/autogenesis/network/WebSocketRpcClientJvm.kt`

```kotlin
// Similar implementation to JS but using JVM-specific WebSocket client
// Follow same patterns as SseChannelJvm.kt for JVM-specific implementations
```

**Validation Criteria**:
- JVM WebSocket client works identically to JS version
- Cross-platform compatibility maintained
- Same API behavior on both platforms

---

## Phase 2: High-Level Bridge Abstraction

### Step 2.1: Create WebSocket RPC Bridge
**File**: `kvisionApp/src/jsMain/kotlin/org/ttt/autogenesis/kvisionapp/WebSocketRpcBridge.kt`

```kotlin
/**
 * Manages a WebSocket-based RPC connection that targets the server module.
 *
 * Provides identical API to RestRpcBridge but uses WebSocket transport for
 * bidirectional real-time communication.
 */
object WebSocketRpcBridge {
    private const val playerIdPrefix = "kvision-ws-client"
    private val rpcRegistry = RpcRegistry(RpcDirection.CLIENT)
    private var client: WebSocketRpcClient? = null
    private var currentPlayerId: String? = null

    /**
     * Returns the active invoker after a successful connection.
     */
    val rpcInvoker: RpcInvoker?
        get() = client?.rpcInvoker

    /**
     * Determines whether an active connection exists.
     */
    val isConnected: Boolean
        get() = client?.isConnected() == true

    /**
     * Determines whether the session is ready for RPC calls.
     */
    val isSessionReady: Boolean
        get() = client?.isSessionReady() == true

    /**
     * Registers client-side RPC handlers so the server can invoke them.
     *
     * @param block Configuration block executed against the shared registry.
     */
    fun registerHandlers(block: RpcRegistry.() -> Unit) {
        block(rpcRegistry)
    }

    /**
     * Connects to the configured WebSocket endpoint using the provided player ID.
     *
     * @param playerId Optional player ID; a stable one is generated by default.
     * @param baseUrl WebSocket base URL (defaults to ws://127.0.0.1:9080)
     */
    suspend fun connect(
        playerId: String = generatePlayerId(),
        baseUrl: String = "ws://127.0.0.1:9080"
    ) {
        console.info("WebSocketRpcBridge: connect() invoked with playerId=$playerId target=$baseUrl")
        if (currentPlayerId == playerId && isConnected) {
            console.info("WebSocketRpcBridge: already connected as $playerId")
            return
        }
        
        // Tear down any previous connection
        client?.let {
            it.disconnect()
            runCatching { it.close() }
        }
        
        val wsClient = WebSocketRpcClient(
            WebSocketRpcClientConfig(
                baseUrl = baseUrl,
                playerId = playerId
            ),
            rpcRegistry = rpcRegistry
        )
        wsClient.connect()
        console.info("WebSocketRpcBridge: connected to $baseUrl as $playerId")
        client = wsClient
        currentPlayerId = playerId
    }

    /**
     * Disconnects and releases any held client resources.
     */
    suspend fun close() {
        client?.let {
            it.disconnect()
            try {
                it.close()
            } catch (err: CancellationException) {
                throw err
            } finally {
                client = null
                currentPlayerId = null
            }
        }
    }

    private fun generatePlayerId(): String =
        "$playerIdPrefix-${Random.nextInt().absoluteValue}"
}
```

**Validation Criteria**:
- API matches RestRpcBridge exactly
- Connection management works correctly
- Player ID generation follows same patterns
- Resource cleanup is proper
- Console logging matches existing patterns

### Step 2.2: Create Shared WebSocket Bridge (Optional)
**File**: `sharedModel/src/commonMain/kotlin/org/ttt/autogenesis/network/WebSocketRpcBridge.kt`

If cross-platform bridge is needed, create expect/actual version in shared module following same pattern.

**Validation Criteria**:
- Cross-platform compatibility maintained
- Same API available on all platforms
- Platform-specific optimizations where needed

---

## Phase 3: Integration and Testing

### Step 3.1: Update Build Configuration
**File**: `sharedModel/build.gradle.kts`

Ensure WebSocket dependencies are included:
```kotlin
commonMain.dependencies {
    implementation("io.ktor:ktor-client-websockets:$ktorVersion")
}
```

**Validation Criteria**:
- Dependencies resolve correctly
- No version conflicts
- Build succeeds on all platforms

### Step 3.2: Create Integration Tests
**File**: `sharedModel/src/commonTest/kotlin/WebSocketRpcBridgeTest.kt`

```kotlin
class WebSocketRpcBridgeTest {
    @Test
    fun `WebSocket RPC bridge provides same API as REST bridge`() {
        // Test API compatibility
        // Verify method signatures match
        // Test connection lifecycle
    }
    
    @Test
    fun `WebSocket client handles reconnection correctly`() {
        // Test reconnection logic
        // Verify exponential backoff
        // Test connection recovery
    }
    
    @Test
    fun `RPC calls work through WebSocket transport`() {
        // Test bidirectional RPC calls
        // Verify streaming works
        // Test error handling
    }
}
```

**Validation Criteria**:
- All tests pass
- API compatibility verified
- Connection management tested
- RPC functionality validated

### Step 3.3: Update Documentation
**Files**: 
- `README.md` - Add WebSocket RPC usage examples
- `AGENTS.md` - Document new WebSocket bridge patterns

```markdown
## WebSocket RPC Usage

```kotlin
// High-level WebSocket RPC abstraction - NEW
import org.ttt.autogenesis.kvisionapp.WebSocketRpcBridge

WebSocketRpcBridge.registerHandlers {
    register("methodName") { context, params -> "response" }
}

WebSocketRpcBridge.connect("player-id")
val response = WebSocketRpcBridge.rpcInvoker?.invoke("server.method", params)
```

**Validation Criteria**:
- Documentation is clear and complete
- Examples work as written
- API usage patterns are consistent

---

## Implementation Rules and Constraints

### Code Style Requirements
- Follow existing patterns from RestRpcClient and RestRpcBridge
- Use same error handling and logging patterns
- Maintain consistent naming conventions
- Follow Kotlin official code style (4-space indentation, camelCase)

### Cross-Platform Compatibility
- Use expect/actual pattern for platform-specific implementations
- Ensure identical behavior on JS and JVM platforms
- Handle platform-specific WebSocket client differences
- Maintain same API surface across platforms

### Integration Requirements
- Reuse existing RPC components (RpcRegistry, RpcInvoker, RpcMessageHandler)
- Follow same connection lifecycle patterns as RestRpcClient
- Implement identical reconnection logic with exponential backoff
- Provide same telemetry and logging hooks

### Error Handling
- Use same error types and patterns as REST implementation
- Handle WebSocket-specific errors (connection drops, protocol errors)
- Implement proper resource cleanup on errors
- Maintain connection state consistency

### Performance Considerations
- Minimize memory allocations in message processing
- Reuse WebSocket connections when possible
- Implement efficient message serialization/deserialization
- Handle backpressure in streaming scenarios

### Security Considerations
- Validate WebSocket URLs and prevent injection
- Handle authentication if required
- Implement proper connection timeouts
- Secure WebSocket connections (WSS) in production

---

## Testing Strategy

### Unit Tests
- Test WebSocketRpcClient connection management
- Test reconnection logic and backoff calculations
- Test message serialization/deserialization
- Test error handling and recovery

### Integration Tests
- Test WebSocketRpcBridge API compatibility with RestRpcBridge
- Test bidirectional RPC communication
- Test streaming RPC calls
- Test connection lifecycle management

### Cross-Platform Tests
- Verify identical behavior on JS and JVM platforms
- Test platform-specific WebSocket implementations
- Validate expect/actual implementations

### Performance Tests
- Test connection establishment time
- Test message throughput
- Test memory usage under load
- Test reconnection performance

---

## Success Criteria

### Functional Requirements
✅ WebSocketRpcBridge provides identical API to RestRpcBridge
✅ WebSocket connections establish and maintain properly
✅ Bidirectional RPC calls work correctly
✅ Streaming RPC calls function properly
✅ Automatic reconnection works with exponential backoff
✅ Resource cleanup happens correctly on disconnect
✅ Cross-platform compatibility maintained

### Non-Functional Requirements
✅ Performance matches or exceeds REST+SSE implementation
✅ Memory usage is reasonable and stable
✅ Error handling is robust and informative
✅ Code follows existing patterns and style guidelines
✅ Documentation is complete and accurate
✅ Tests provide adequate coverage

### Integration Requirements
✅ Existing RPC components work unchanged
✅ No breaking changes to current REST RPC system
✅ WebSocket and REST RPC can coexist
✅ Build system handles new dependencies correctly
✅ All platforms build and run successfully

---

## Risk Mitigation

### Technical Risks
- **WebSocket connection instability**: Implement robust reconnection with circuit breaker
- **Cross-platform differences**: Extensive testing on both JS and JVM platforms
- **Message ordering issues**: Use same message handling patterns as REST implementation
- **Resource leaks**: Implement comprehensive cleanup and testing

### Integration Risks
- **Breaking existing code**: Maintain separate bridge objects, no changes to REST system
- **Dependency conflicts**: Careful version management and testing
- **Performance regression**: Benchmark against REST implementation
- **API inconsistency**: Strict adherence to RestRpcBridge API patterns

### Operational Risks
- **Deployment complexity**: Document WebSocket endpoint requirements
- **Monitoring gaps**: Implement same telemetry as REST system
- **Security vulnerabilities**: Follow WebSocket security best practices
- **Scalability issues**: Test under realistic load conditions

This comprehensive plan ensures the WebSocketRpcBridge implementation provides the missing high-level abstraction while maintaining full compatibility with the existing RPC system architecture.
