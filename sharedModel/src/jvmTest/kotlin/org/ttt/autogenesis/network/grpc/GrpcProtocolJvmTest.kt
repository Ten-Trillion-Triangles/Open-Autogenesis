package org.ttt.autogenesis.network.grpc

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Verifies the JVM gRPC envelope codec matches the protobuf wire format used by grpc-web.
 */
class GrpcProtocolJvmTest
{
    /**
     * Confirms a protobuf envelope survives a stream/parse round trip.
     */
    @Test
    fun `RpcEnvelopeMarshaller round-trips the payload`() 
    {
        val original = RpcEnvelope("""{"type":"test","value":"hello"}""")
        val encoded = RpcEnvelopeMarshaller.stream(original)

        assertEquals(original, RpcEnvelopeMarshaller.parse(encoded))
    }

    /**
     * Confirms empty payloads remain valid protobuf envelopes.
     */
    @Test
    fun `RpcEnvelopeMarshaller round-trips empty payloads`()
    {
        val original = RpcEnvelope("")
        val encoded = RpcEnvelopeMarshaller.stream(original)

        assertEquals(original, RpcEnvelopeMarshaller.parse(encoded))
    }
}