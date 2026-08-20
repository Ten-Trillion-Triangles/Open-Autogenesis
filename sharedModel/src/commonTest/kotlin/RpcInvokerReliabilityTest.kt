package org.ttt.autogenesis.network

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.serializer
import org.ttt.autogenesis.network.RpcJson
import org.ttt.autogenesis.network.RpcMessage
import kotlin.test.Ignore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class RpcInvokerReliabilityTest {
    @Test
    fun requestTimesOutAfterLimit() = runTest {
        val invoker = RpcInvoker(
            sender = { },
            telemetry = RpcTelemetrySink.NoOp
        )
        val handle = invoker.request("test.timeout", null, timeoutMillis = 20)
        assertFailsWith<RpcTimeoutException> {
            handle.await()
        }
    }

    @Test
    fun cancellationCompletesWithException() = runTest {
        val invoker = RpcInvoker(
            sender = { },
            telemetry = RpcTelemetrySink.NoOp
        )
        val handle = invoker.request("test.cancel", null)
        assertTrue(handle.isActive)
        val cancelled = handle.cancel(CancellationException("manual"))
        assertTrue(cancelled)
        assertFailsWith<CancellationException> {
            handle.await()
        }
    }

    @Test
    @Ignore
    fun streamChunksArriveOnHandle() = runTest {
        val invoker = RpcInvoker(sender = { })
        val handle = invoker.request("test.stream", null, streamBufferCapacity = 2)
        invoker.handleStreamChunk(
            RpcMessage.StreamChunk(
                id = handle.id,
                payload = RpcJson.encodeToJsonElement(serializer<String>(), "slice")
            )
        )
        assertTrue(handle.stream != null)
        val chunk = handle.stream!!.first()
        val value = RpcJson.decodeFromJsonElement(serializer<String>(), chunk)
        assertEquals("slice", value)
    }

    @Test
    fun handleStreamCancelCompletesRequest() = runTest {
        val invoker = RpcInvoker(sender = { })
        val handle = invoker.request("stream.cancel", null, streamBufferCapacity = 1)
        assertTrue(handle.stream != null)
        assertTrue(invoker.handleStreamCancel(RpcMessage.StreamCancel(handle.id)))
        assertFailsWith<CancellationException> {
            handle.await()
        }
    }
}