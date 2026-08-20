# WebSocket Boolean RPC Unit Test Implementation Plan

## Overview
Create a unit test that validates a complete WebSocket RPC round-trip communication returning a boolean value using the existing RPC infrastructure. The test leverages all existing WebSocket and RPC systems without creating redundant code.

## Test Location and Structure

### **Step 1: Identify Correct Test Location**
- **Target Directory**: `/server/src/test/kotlin/org/ttt/autogenesis/server/`
- **Test File**: `BooleanRpcTest.kt`
- **Rationale**: Following existing project structure where server tests are located

### **Step 2: Add Boolean RPC Handler to Existing GameRpcHandlers**
- **Location**: `/server/src/main/kotlin/org/ttt/autogenesis/server/GameRpcHandlers.kt`
- **Purpose**: Add a simple RPC method that returns a boolean value to existing handler object
- **Implementation**: Add method to existing `GameRpcHandlers` object
  ```kotlin
  @RpcMethod(name = "test.boolean", direction = RpcDirection.SERVER)
  suspend fun testBoolean(ctx: RpcCallContext, input: Boolean): Boolean = input
  ```

### **Step 3: Generate KSP Bindings**
- **Trigger**: Run `./gradlew :server:build` to regenerate existing registration code
- **Expected Output**: Updated `GeneratedGameRpcBindings.kt` includes new boolean method
- **Validation**: Verify registration function includes the boolean RPC

## Test Implementation Steps

### **Step 4: Use Existing Test Infrastructure**
- **Pattern**: Follow existing `WebSocketRpcTest.kt` approach
- **Server**: Use existing `testApplication { serverModule() }`
- **WebSocket**: Connect to existing `/events` endpoint
- **RPC System**: Use existing `RpcMessage` types and `RpcJson` utilities

### **Step 5: Implement Minimal Test Logic**
- **Test Method**: `testBooleanRpcRoundTrip()`
- **Pattern**: Copy structure from existing `WebSocketRpcTest`
- **Input Values**: Test both `true` and `false` boolean values
- **Verification**: Validate boolean response matches input

### **Step 6: Test Execution Flow**
1. **Setup**: Use `testApplication { serverModule() }`
2. **Connect**: WebSocket to `/events?playerId=test-player`
3. **Handle**: Initial connection handshake (existing pattern)
4. **Send**: RPC request with boolean parameter
5. **Receive**: Validate boolean response
6. **Cleanup**: Automatic via `testApplication`

## Implementation Details

### **Modified GameRpcHandlers.kt**
```kotlin
object GameRpcHandlers {
    // ... existing methods ...
    
    @RpcMethod(name = "test.boolean", direction = RpcDirection.SERVER)
    suspend fun testBoolean(ctx: RpcCallContext, input: Boolean): Boolean = input
}
```

### **New BooleanRpcTest.kt**
```kotlin
class BooleanRpcTest {
    @Test
    fun `boolean rpc round trip through existing websocket`() = testApplication {
        application { serverModule() }
        
        val websocketClient = createClient { install(WebSockets) }
        websocketClient.webSocket("/events?playerId=test-player") {
            // Handle initial handshake (copy from WebSocketRpcTest)
            // Send boolean RPC request
            // Validate boolean response
        }
    }
}
```

## Validation Criteria

### **Success Verification**
- [ ] Boolean method added to existing `GameRpcHandlers`
- [ ] KSP regenerates existing registration code
- [ ] Test uses existing WebSocket endpoint `/events`
- [ ] Test follows existing `WebSocketRpcTest` pattern
- [ ] Boolean values correctly round-trip
- [ ] No duplicate infrastructure created

### **Integration Verification**
- [ ] Uses existing `testApplication` and `serverModule()`
- [ ] Uses existing `RpcMessage` and `RpcJson` utilities
- [ ] Leverages existing WebSocket client setup
- [ ] Follows existing connection handshake pattern

## Build and Execution Commands

### **Regenerate KSP Code**
```bash
./gradlew :server:build
```

### **Run Test**
```bash
./gradlew :server:test --tests "BooleanRpcTest"
```

## Expected Outcomes

### **Modified Files**
- `server/src/main/kotlin/org/ttt/autogenesis/server/GameRpcHandlers.kt` (add one method)
- `server/src/test/kotlin/org/ttt/autogenesis/server/BooleanRpcTest.kt` (new file)
- `server/build/generated/ksp/main/kotlin/org/ttt/autogenesis/server/GeneratedGameRpcBindings.kt` (updated)

### **No New Infrastructure**
- ❌ No new WebSocket servers
- ❌ No new RPC registries  
- ❌ No duplicate connection management
- ✅ Uses all existing systems

This plan ensures minimal implementation that validates boolean RPC functionality while leveraging the complete existing WebSocket and RPC infrastructure.