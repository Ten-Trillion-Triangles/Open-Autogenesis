# Answer Agent Dual-Streaming Architecture

## Implementation Date
Monday, February 9, 2026

## Overview

The answer agent now uses TPipe's multiple streaming callback feature to simultaneously stream content to two different UI windows:

1. **NeuralLinkWindow** - User-facing window for clean Q&A display
2. **AgentWorkStreamWindow** - Debug window showing agent reasoning and internal processing

## Problem Solved

Previously, the answer agent had two conflicting streaming systems that would override each other:
- `streamPipelineOutputToAgentWorkBuffer()` configured streaming to AgentWorkStreamDispatcher
- `buildAnswerAgent()` attempted to configure direct streaming to NeuralLinkWindow
- Only one callback could be registered, causing the NeuralLinkWindow to receive 0 chars

With TPipe's new multiple callback support, both systems can now coexist without conflict.

## Architecture

### Streaming Flow

```
Answer Agent Pipeline
        ↓
    BedrockPipe
        ↓
   [Streaming Callbacks]
        ↓
    ┌───────┴───────┐
    ↓               ↓
Callback 1      Callback 2
(non-suspend)   (suspend)
    ↓               ↓
AgentWork-      broadcastAgent-
StreamDispatcher    Stream()
    ↓               ↓
ui.agentWork-   ui.agentStream
Stream RPC      RPC
    ↓               ↓
AgentWork-      NeuralLink-
StreamWindow    Window
(debug)         (user-facing)
```

### Code Implementation

**File:** `/server/src/main/kotlin/agent/builders/systemActions/answerAgent.kt`

```kotlin
// Define callbacks with explicit types to avoid ambiguity
val agentWorkStreamCallback: (String) -> Unit = { chunk ->
    AgentWorkStreamDispatcher.appendChunk(connectionId, chunk)
}

val neuralLinkCallback: suspend (String) -> Unit = { chunk ->
    Logger.debug(LogCategory.GENERAL, "Stream chunk for $connectionId: $chunk")
    org.ttt.autogenesis.server.UiSignalRpcHandlers.broadcastAgentStream(
        connectionId = connectionId,
        tabId = targetTabId,
        content = chunk,
        isComplete = false
    )
}

// Register both callbacks using TPipe's new API
enableStreaming()
    .streamingCallbacks {
        add(agentWorkStreamCallback)  // Non-suspend callback for debug window
        add(neuralLinkCallback)       // Suspend callback for NeuralLinkWindow
        sequential()                  // Execute in order (default)
    }
```

**Completion Signals:**

```kotlin
setPipelineCompletionCallback { _, _ ->
    // Signal NeuralLinkWindow completion
    org.ttt.autogenesis.server.UiSignalRpcHandlers.broadcastAgentStream(
        connectionId = connectionId,
        tabId = targetTabId,
        content = "",
        isComplete = true
    )
    
    // Signal AgentWorkStreamWindow completion
    AgentWorkStreamDispatcher.notifyPipelineComplete(connectionId)
}
```

## Key Technical Details

### Callback Types

**Non-Suspend Callback (AgentWorkStreamDispatcher):**
- Type: `(String) -> Unit`
- Used because `AgentWorkStreamDispatcher.appendChunk()` is not a suspend function
- Executes synchronously

**Suspend Callback (NeuralLinkWindow):**
- Type: `suspend (String) -> Unit`
- Required because `UiSignalRpcHandlers.broadcastAgentStream()` is a suspend function
- Allows async RPC calls

### Type Inference

TPipe's `add()` method has two overloads:
```kotlin
fun add(callback: suspend (String) -> Unit): StreamingCallbackBuilder
fun add(callback: (String) -> Unit): StreamingCallbackBuilder
```

To avoid ambiguity, we use explicit callback variables with type annotations rather than inline lambdas.

### Execution Order

Callbacks execute **sequentially** (in registration order):
1. AgentWorkStreamDispatcher receives chunk first (for debugging)
2. NeuralLinkWindow receives chunk second (for user display)

This ensures debug logs are captured before user-facing display.

### Error Isolation

TPipe's callback manager provides error isolation:
- If AgentWorkStreamDispatcher fails, NeuralLinkWindow still receives content
- If NeuralLinkWindow streaming fails, AgentWorkStreamDispatcher continues working
- Errors in one callback don't affect the other

## Other Agents

**Important:** Other agents (gameplay orchestrator, judge, etc.) continue using only AgentWorkStreamDispatcher via `streamPipelineOutputToAgentWorkBuffer()`. Only the answer agent uses dual-streaming.

**File:** `/server/src/main/kotlin/org/ttt/autogenesis/server/AgentWorkStreamStreaming.kt`

```kotlin
private fun configureBedrockStreaming(connectionId: String, pipe: Pipe)
{
    if(pipe !is BedrockPipe)
    {
        return
    }

    val callback: (String) -> Unit = { chunk ->
        AgentWorkStreamDispatcher.appendChunk(connectionId, chunk)
    }
    
    pipe.enableStreaming()
        .streamingCallbacks {
            add(callback)
        }
}
```

This function is called by `streamPipelineOutputToAgentWorkBuffer()` for all agents except the answer agent.

## Client-Side Handlers

**NeuralLinkWindow Handler:**
```kotlin
@RpcMethod(name = "ui.agentStream", direction = RpcDirection.CLIENT)
suspend fun handleAgentStream(_ctx: RpcCallContext, data: AgentStreamData)
{
    ui.gameplay.NeuralLinkManager.streamMessage(data.tabId, data.content, data.commandContext)
    
    if(data.isComplete)
    {
        ui.gameplay.NeuralLinkManager.endStream(data.tabId)
    }
}
```

**AgentWorkStreamWindow Handler:**
```kotlin
@RpcMethod(name = "ui.agentWorkStream", direction = RpcDirection.CLIENT)
suspend fun handleAgentWorkStream(_ctx: RpcCallContext, data: AgentWorkStreamData)
{
    AgentWorkStreamManager.handleStream(data)
}
```

## Usage

### User Asks Question

**Without `/ask` command:**
```
User types: "How do I play this game?"
```

**With `/ask` command:**
```
User types: "/ask How do I play this game?"
```

**Result:**
1. NeuralLinkWindow opens and displays answer
2. AgentWorkStreamWindow (if open) shows reasoning process
3. Both windows receive streaming content simultaneously
4. Both windows show completion state when done

### Opening AgentWorkStreamWindow

Users can open the debug window to see agent reasoning:
1. Click the "Agent Work Stream" button in the UI
2. Window shows all agent processing (answer agent, gameplay orchestrator, etc.)
3. Useful for debugging and understanding agent decisions

## Benefits

### For Users
- Clean, focused Q&A experience in NeuralLinkWindow
- Optional debug view in AgentWorkStreamWindow
- No content duplication or confusion

### For Developers
- Both streaming systems work simultaneously
- Easy to debug streaming issues
- Can verify content reaches both destinations
- Error isolation prevents cascading failures

### For Debugging
- AgentWorkStreamWindow shows all agent reasoning
- NeuralLinkWindow shows final user-facing output
- Can compare both to verify correctness
- Streaming logs show chunk delivery

## Troubleshooting

### NeuralLinkWindow Not Receiving Content

**Check:**
1. Server logs for "Stream chunk for [connectionId]" messages
2. Browser logs for "ui.agentStream" RPC messages
3. NeuralLinkWindow logs for "streamContent received X chars"

**Common Issues:**
- `broadcastAgentStream()` not being called (check callback registration)
- Wrong `tabId` (default is "Commander")
- RPC handler not registered on client

### AgentWorkStreamWindow Not Receiving Content

**Check:**
1. Window is open (it only displays when visible)
2. Server logs for AgentWorkStreamDispatcher activity
3. Browser logs for "ui.agentWorkStream" RPC messages

**Common Issues:**
- Window not opened by user
- `appendChunk()` not being called
- RPC handler not registered on client

### Both Windows Not Receiving Content

**Check:**
1. `enableStreaming()` was called on the pipe
2. Callbacks were registered with `streamingCallbacks { add(...) }`
3. Pipeline is actually executing (check trace logs)

**Common Issues:**
- Forgot to call `enableStreaming()`
- Callbacks not registered (check for compilation errors)
- Pipeline not initialized or executed

### Type Ambiguity Errors

**Error:**
```
Overload resolution ambiguity between candidates:
fun add(callback: suspend (String) -> Unit): StreamingCallbackBuilder
fun add(callback: (String) -> Unit): StreamingCallbackBuilder
```

**Solution:**
Use explicit callback variables with type annotations:
```kotlin
val callback: (String) -> Unit = { chunk -> ... }  // Non-suspend
val callback: suspend (String) -> Unit = { chunk -> ... }  // Suspend
```

Don't use inline lambdas when calling suspend functions inside the callback.

## Performance Considerations

### Sequential Execution
- Callbacks execute in order (AgentWorkStreamDispatcher first, then NeuralLinkWindow)
- Minimal overhead (~microseconds per chunk)
- Acceptable for typical streaming workloads

### Concurrent Execution
If performance becomes an issue, switch to concurrent mode:
```kotlin
.streamingCallbacks {
    add(agentWorkStreamCallback)
    add(neuralLinkCallback)
    concurrent()  // Execute callbacks in parallel
}
```

**Trade-offs:**
- Faster execution (callbacks run in parallel)
- Less predictable ordering (debug logs may arrive after user display)
- Slightly more complex error handling

## Future Enhancements

### Potential Improvements
1. **Selective streaming** - Allow users to disable AgentWorkStreamWindow streaming
2. **Filtering** - Stream only certain content types to each window
3. **Buffering** - Batch chunks for more efficient RPC calls
4. **Metrics** - Track streaming performance and errors
5. **Replay** - Save and replay streaming sessions for debugging

### Migration Path
If other agents need dual-streaming:
1. Remove `streamPipelineOutputToAgentWorkBuffer()` call
2. Add explicit callback registration in agent builder
3. Update completion callback to signal both windows
4. Test thoroughly

## Related Files

**Server:**
- `/server/src/main/kotlin/agent/builders/systemActions/answerAgent.kt` - Answer agent implementation
- `/server/src/main/kotlin/org/ttt/autogenesis/server/AgentWorkStreamStreaming.kt` - AgentWorkStreamDispatcher setup
- `/server/src/main/kotlin/org/ttt/autogenesis/server/UiSignalRpcHandlers.kt` - RPC handlers for streaming
- `/server/src/main/kotlin/accounting/PromptManager.kt` - Answer agent invocation

**Client:**
- `/kvisionApp/src/jsMain/kotlin/ui/gameplay/NeuralLinkWindow.kt` - User-facing window
- `/kvisionApp/src/jsMain/kotlin/ui/gameplay/NeuralLinkManager.kt` - Window management
- `/kvisionApp/src/jsMain/kotlin/ui/gameplay/AgentWorkStreamWindow.kt` - Debug window
- `/kvisionApp/src/jsMain/kotlin/ui/gameplay/AgentWorkStreamManager.kt` - Debug window management
- `/kvisionApp/src/jsMain/kotlin/ui/gameplay/networking/UiSignalClientHandlers.kt` - RPC handlers

**TPipe:**
- `/TPipe/TPipe/src/main/kotlin/bedrockPipe/BedrockPipe.kt` - Streaming implementation
- `/TPipe/TPipe/src/main/kotlin/com/TTT/Pipe/StreamingCallbackBuilder.kt` - Callback builder API
- `/TPipe/TPipe/src/main/kotlin/com/TTT/Pipe/StreamingCallbackManager.kt` - Callback execution

## References

- [TPipe Multiple Streaming Callbacks Documentation](../TPipe/TPipe/docs/streaming-callbacks.md)
- [Answer Agent Player Identification](./answer-agent-player-identification.md)
- [NeuralLink Streaming Bug Investigation](../CHECKPOINT.md)
