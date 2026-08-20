package org.ttt.autogenesis.network

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

val RpcJson = Json {
    encodeDefaults = true
    ignoreUnknownKeys = true
    classDiscriminator = "type"
}

@Serializable
data class PlayerConnectionEvent(
    val playerId: String,
    val status: ConnectionStatus,
    val timestampMillis: Long
)

@Serializable
enum class ConnectionStatus {
    CONNECTED,
    DISCONNECTED
}

@Serializable
enum class AgentStreamPhase {
    LEGACY,
    START,
    DELTA,
    END,
    ERROR
}

@Serializable
data class RpcError(
    val code: Int,
    val message: String
)

@Serializable
sealed interface RpcMessage {
    @Serializable
    @SerialName("request")
    data class Request(
        val id: String,
        val method: String,
        val params: JsonElement? = null
    ) : RpcMessage

    @Serializable
    @SerialName("response")
    data class Response(
        val id: String,
        val result: JsonElement? = null,
        val error: RpcError? = null
    ) : RpcMessage

    @Serializable
    @SerialName("stream_chunk")
    data class StreamChunk(
        val id: String,
        val payload: JsonElement
    ) : RpcMessage

    @Serializable
    @SerialName("stream_cancel")
    data class StreamCancel(
        val id: String
    ) : RpcMessage

    @Serializable
    @SerialName("multipart")
    data class Multipart(
        val messageId: String,
        val chunkIndex: Int,
        val totalChunks: Int,
        val data: String // Raw string or Base64 (depending on implementation, here we'll use Base64 for binary safety)
    ) : RpcMessage

    @Serializable
    @SerialName("notification")
    data class Notification(
        val method: String,
        val params: JsonElement? = null
    ) : RpcMessage

    @Serializable
    @SerialName("connection_state")
    data class ConnectionState(
        val event: PlayerConnectionEvent
    ) : RpcMessage

    @Serializable
    data class AgentStreamData(
        val connectionId: String,
        val tabId: String,
        val content: String = "",
        val isComplete: Boolean = false,
        val commandContext: String? = null,
        val phase: AgentStreamPhase = AgentStreamPhase.LEGACY,
        val streamId: String = "",
        val sequence: Long = 0,
        val delta: String = "",
        val finalText: String? = null
    )

    @Serializable
    /**
     * Carries a chunk of streaming text for a single UI window via [UiSignalRpcHandlers.sendAgentWorkStream].
     */
    data class AgentWorkStreamData(
        val connectionId: String,
        val streamId: String,
        val content: String,
        val isComplete: Boolean
    )
}

/**
 * Signals the classified intent of a user prompt so the client can decide whether to show gameplay UI.
 *
 * @param connectionId WebSocket connection whose prompt was classified.
 * @param tabId Optional NeuralLink tab inferred from bracketed prefixes.
 * @param prompt Original string submitted by the player.
 * @param isGameplay True when the classification or slash command maps to a gameplay turn.
 * @param hint Optional hint such as the slash command or classifier label that produced this result.
 */
@Serializable
data class CommandClassificationData(
    val connectionId: String,
    val tabId: String? = null,
    val prompt: String,
    val isGameplay: Boolean,
    val hint: String? = null
)

fun RpcMessage.toJson(json: Json = RpcJson): String = json.encodeToString(RpcMessage.serializer(), this)

fun String.toRpcMessage(json: Json = RpcJson): RpcMessage =
    json.decodeFromString(RpcMessage.serializer(), this)

@Serializable
data class AgentWorkStreamSubscriptionRequest(
    val subscribe: Boolean
)