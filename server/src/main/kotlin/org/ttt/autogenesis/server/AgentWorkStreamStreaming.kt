package org.ttt.autogenesis.server

import bedrockPipe.BedrockPipe
import com.TTT.Pipe.MultimodalContent
import com.TTT.Pipe.Pipe
import com.TTT.Pipeline.Pipeline
import org.ttt.autogenesis.logging.LogCategory
import org.ttt.autogenesis.logging.Logger

/**
 * Returns all connected client player IDs for broadcasting public agent work.
 * Note: This returns ALL connected clients. The dispatcher will filter by subscription
 * status when actually sending chunks.
 */
suspend fun getAllConnectedClientIds(): Collection<String>
{
    val connectionManager = UiSignalRpcHandlers.connectionManager
    if(connectionManager == null)
    {
        Logger.warn(LogCategory.NETWORK, "AgentWorkStreamStreaming: connectionManager is null, no broadcast recipients")
        return emptyList()
    }
    
    val allIds = connectionManager.allSessions().map { it.playerId }.filter { it.isNotBlank() }
    Logger.debug(LogCategory.NETWORK, "AgentWorkStreamStreaming: Resolved ${allIds.size} broadcast recipients")
    return allIds
}

/**
 * Hooks every Bedrock [Pipe] inside a pipeline to stream through the agent work buffer so bits of
 * reasoning can be forwarded to the client window. If [connectionId] is null or blank the streaming
 * is skipped because there is no listener.
 */
fun streamPipelineOutputToAgentWorkBuffer(connectionId: String?, pipeline: Pipeline)
{
    val safeConnectionId = connectionId ?: return
    if(safeConnectionId.isBlank())
    {
        return
    }
    streamPipelineOutputToAgentWorkBuffer(listOf(safeConnectionId), pipeline)
}

/**
 * Hooks every Bedrock [Pipe] inside [pipeline] and fans chunks out to all [connectionIds].
 */
fun streamPipelineOutputToAgentWorkBuffer(connectionIds: Collection<String>, pipeline: Pipeline)
{
    val recipientIds = connectionIds.filter { it.isNotBlank() }.distinct()
    if(recipientIds.isEmpty())
    {
        Logger.debug(LogCategory.NETWORK, "AgentWorkStreamStreaming: No recipients for pipeline '${pipeline.pipelineName}'")
        return
    }
    Logger.debug(
        LogCategory.NETWORK,
        "AgentWorkStreamStreaming: Scheduling pipeline '${pipeline.pipelineName}' for ${recipientIds.size} recipients"
    )
    Logger.debug(
        LogCategory.NETWORK,
        "AgentWorkStreamStreaming: Wiring pipeline '${pipeline.pipelineName}' for ${recipientIds.size} recipients"
    )

    // Ensure all recipients are subscribed to the workstream window
    recipientIds.forEach { AgentWorkStreamDispatcher.subscribe(it) }

    val configured = mutableSetOf<Pipe>()
    configureStreamingForPipes(recipientIds, pipeline.getPipes(), configured)

    val existingCallback = pipeline.pipelineCompletionCallBack
    pipeline.pipelineCompletionCallBack = { pipe, content ->
        Logger.debug(
            LogCategory.NETWORK,
            "AgentWorkStreamStreaming: Pipeline '${pipeline.pipelineName}' completed; notifying ${recipientIds.size} recipients"
        )
        AgentWorkStreamDispatcher.notifyPipelineCompleteMany(recipientIds)
        existingCallback?.invoke(pipe, content)
    }
}

private fun configureStreamingForPipes(
    connectionIds: Collection<String>,
    pipes: Iterable<Pipe>,
    configured: MutableSet<Pipe>
)
{
    for(pipe in pipes)
    {
        configureStreamingForPipe(connectionIds, pipe, configured)
    }
}

private fun configureStreamingForPipe(
    connectionIds: Collection<String>,
    pipe: Pipe?,
    configured: MutableSet<Pipe>
)
{
    if(pipe == null || !configured.add(pipe))
    {
        return
    }

    configureBedrockStreaming(connectionIds, pipe)
    configureStreamingForPipe(connectionIds, pipe.reasoningPipe, configured)
}

private fun configureBedrockStreaming(connectionIds: Collection<String>, pipe: Pipe)
{
    if(pipe !is BedrockPipe)
    {
        return
    }

    val callback: (String) -> Unit = { chunk ->
        AgentWorkStreamDispatcher.appendChunkToMany(connectionIds, chunk)
    }
    
    pipe.enableStreaming()
        .streamingCallbacks {
            add(callback)
        }
}
