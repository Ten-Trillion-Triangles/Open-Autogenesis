package org.ttt.autogenesis.network

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Verifies the bridge's "client closed mid-request" behavior:
 *
 * - [RpcInvoker.cancelAll] completes every pending request with the supplied
 *   cause, returns the dropped (id, method) pairs in the order they were
 *   observed, and lets the invoker be reused for new requests after the
 *   cancellation.
 * - [RestRpcClientClosedException] is the canonical cause used by the REST
 *   client teardown path so callers can distinguish a transport teardown
 *   from a generic network failure.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RestRpcClientClosedBehaviorTest
{
    /**
     * `cancelAll` must complete every pending request with the supplied cause.
     */
    @Test
    fun cancelAllCompletesEveryPendingRequest() = runTest {
        val invoker = RpcInvoker(sender = { /* no remote - simulate hang */ })
        val handleA = invoker.request("test.methodA", null)
        val handleB = invoker.request("test.methodB", null)
        val handleC = invoker.request("test.methodC", null)

        val dropped = invoker.cancelAll(
            RestRpcClientClosedException("simulated teardown")
        )

        assertEquals(3, dropped.size)
        assertEquals(setOf("test.methodA", "test.methodB", "test.methodC"), dropped.map { it.second }.toSet())
        for(handle in listOf(handleA, handleB, handleC))
        {
            val err = assertFailsWith<RestRpcClientClosedException> { handle.await() }
            assertTrue(err.message?.contains("simulated teardown") == true,
                "expected simulated teardown message but was '${err.message}'")
        }
    }

    /**
     * After `cancelAll`, new requests can still be issued (the invoker is
     * reusable; only the pending map was cleared).
     */
    @Test
    fun invokerIsReusableAfterCancelAll() = runTest {
        val invoker = RpcInvoker(sender = { /* no remote */ })
        val first = invoker.request("test.first", null)
        invoker.cancelAll()
        assertFailsWith<RestRpcClientClosedException> { first.await() }

        val second = invoker.request("test.second", null)
        assertTrue(second.isActive)
        // Resolve the second request normally to prove the invoker is reusable.
        val response = RpcMessage.Response(id = second.id, result = null)
        invoker.handleResponse(response)
        val awaited = second.await()
        assertEquals(response.id, awaited.id)
    }

    /**
     * The default cause must be a [RestRpcClientClosedException] so that the
     * REST client teardown path is self-describing in stack traces.
     */
    @Test
    fun cancelAllDefaultsToClientClosedException() = runTest {
        val invoker = RpcInvoker(sender = { })
        val handle = invoker.request("test.default", null)
        invoker.cancelAll()
        assertFailsWith<RestRpcClientClosedException> { handle.await() }
    }

    /**
     * Cancelling an empty invoker is a no-op (no crash, no dropped pairs).
     */
    @Test
    fun cancelAllOnEmptyInvokerReturnsEmpty() = runTest {
        val invoker = RpcInvoker(sender = { })
        val dropped = invoker.cancelAll()
        assertTrue(dropped.isEmpty())
    }

    /**
     * Custom causes are honored so callers can override the default with a
     * more specific failure (for example, a timeout vs a teardown).
     */
    @Test
    fun cancelAllRespectsCustomCause() = runTest {
        val invoker = RpcInvoker(sender = { })
        val handle = invoker.request("test.custom", null)
        val sentinel = CancellationException("custom reason")
        invoker.cancelAll(sentinel)
        val err = assertFailsWith<CancellationException> { handle.await() }
        assertEquals("custom reason", err.message)
    }
}
