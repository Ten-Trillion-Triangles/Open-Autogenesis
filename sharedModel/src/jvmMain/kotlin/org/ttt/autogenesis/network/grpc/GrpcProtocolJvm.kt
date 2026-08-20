package org.ttt.autogenesis.network.grpc

import io.grpc.Context
import io.grpc.MethodDescriptor
import io.grpc.Metadata
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.nio.charset.StandardCharsets

/**
 * gRPC service and method names for the RPC bridge.
 */
const val GRPC_BRIDGE_SERVICE_NAME = "autogenesis.rpc.RpcBridge"
const val GRPC_BRIDGE_METHOD_NAME = "Stream"
const val GRPC_BRIDGE_INVOKE_METHOD_NAME = "Invoke"

/**
 * Envelope wrapper for RPC message payloads in gRPC communication.
 *
 * @param payload JSON string containing the serialized RPC message
 */
data class RpcEnvelope(val payload: String)

/**
 * gRPC marshaller for [RpcEnvelope] objects.
 *
 * The browser grpc-web client uses the generated protobuf schema for
 * [RpcEnvelope], so the JVM marshaller needs to emit and consume the same
 * protobuf wire format instead of treating the envelope as a raw UTF-8
 * string.
 */
object RpcEnvelopeMarshaller : MethodDescriptor.Marshaller<RpcEnvelope>
{
    /**
     * Serializes an RPC envelope to an input stream for gRPC transmission.
     *
     * @param value The RPC envelope to serialize
     * @return Input stream containing the serialized envelope
     */
    override fun stream(value: RpcEnvelope): InputStream
    {
        val payloadBytes = value.payload.toByteArray(StandardCharsets.UTF_8)
        val output = ByteArrayOutputStream(payloadBytes.size + 4)
        output.write(RPC_ENVELOPE_PAYLOAD_TAG)
        output.write(encodeVarInt(payloadBytes.size))
        output.write(payloadBytes)
        return ByteArrayInputStream(output.toByteArray())
    }

    /**
     * Deserializes an RPC envelope from an input stream.
     *
     * @param stream Input stream containing the serialized envelope
     * @return Deserialized RPC envelope
     */
    override fun parse(stream: InputStream): RpcEnvelope
    {
        val bytes = stream.readBytes()
        if(bytes.isEmpty())
        {
            return RpcEnvelope("")
        }

        var index = 0
        val tag = readVarInt(bytes, index)
        index += tag.second
        require(tag.first == RPC_ENVELOPE_PAYLOAD_TAG)
        {
            "Unexpected RpcEnvelope tag: ${tag.first}"
        }
        val length = readVarInt(bytes, index)
        index += length.second
        val endIndex = index + length.first
        require(endIndex <= bytes.size)
        {
            "RpcEnvelope payload length ${length.first} exceeds available bytes ${bytes.size - index}"
        }
        return RpcEnvelope(String(bytes.copyOfRange(index, endIndex), StandardCharsets.UTF_8))
    }
}

private const val RPC_ENVELOPE_PAYLOAD_TAG: Int = 0x0A

private fun encodeVarInt(value: Int): ByteArray
{
    require(value >= 0)
    {
        "Varint value must be non-negative"
    }

    var current = value
    val output = ByteArrayOutputStream()
    while(current and -0x80 != 0)
    {
        output.write((current and 0x7F) or 0x80)
        current = current ushr 7
    }
    output.write(current)
    return output.toByteArray()
}

private fun readVarInt(bytes: ByteArray, startIndex: Int): Pair<Int, Int>
{
    var shift = 0
    var result = 0
    var index = startIndex

    while(index < bytes.size)
    {
        val current = bytes[index].toInt() and 0xFF
        result = result or ((current and 0x7F) shl shift)
        index += 1

        if(current and 0x80 == 0)
        {
            return result to (index - startIndex)
        }

        shift += 7
        require(shift < Int.SIZE_BITS)
        {
            "Varint is too long"
        }
    }

    error("Unexpected end of RpcEnvelope while decoding varint")
}

/**
 * gRPC method descriptor for bidirectional streaming RPC communication.
 */
val RPC_METHOD_DESCRIPTOR: MethodDescriptor<RpcEnvelope, RpcEnvelope> =
    MethodDescriptor.newBuilder<RpcEnvelope, RpcEnvelope>()
        .setType(MethodDescriptor.MethodType.BIDI_STREAMING)
        .setFullMethodName(MethodDescriptor.generateFullMethodName(GRPC_BRIDGE_SERVICE_NAME, GRPC_BRIDGE_METHOD_NAME))
        .setRequestMarshaller(RpcEnvelopeMarshaller)
        .setResponseMarshaller(RpcEnvelopeMarshaller)
        .build()

/**
 * gRPC method descriptor for unary browser-friendly RPC communication.
 */
val RPC_INVOKE_METHOD_DESCRIPTOR: MethodDescriptor<RpcEnvelope, RpcEnvelope> =
    MethodDescriptor.newBuilder<RpcEnvelope, RpcEnvelope>()
        .setType(MethodDescriptor.MethodType.UNARY)
        .setFullMethodName(MethodDescriptor.generateFullMethodName(GRPC_BRIDGE_SERVICE_NAME, GRPC_BRIDGE_INVOKE_METHOD_NAME))
        .setRequestMarshaller(RpcEnvelopeMarshaller)
        .setResponseMarshaller(RpcEnvelopeMarshaller)
        .build()

/**
 * Metadata key for player ID in gRPC headers.
 */
val PLAYER_ID_METADATA_KEY: Metadata.Key<String> =
    Metadata.Key.of("player-id", Metadata.ASCII_STRING_MARSHALLER)

/**
 * Context key for player ID in gRPC call context.
 */
val PLAYER_ID_CTX_KEY: Context.Key<String> =
    Context.key("player-id")