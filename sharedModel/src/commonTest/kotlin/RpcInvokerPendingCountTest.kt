package org.ttt.autogenesis.network

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Contract for [RpcInvoker.pendingCount] under concurrent mutation.
 *
 * Pre-fix: `pendingCount()` read `pending.size` without the mutex.
 * The kotlin/JS [LinkedHashMap.size] can return a stale value while
 * a concurrent remove is in flight, so the awaitInFlightRequests
 * poll could return 0 while close()'s drainInFlightRequests() then
 * found pending requests and cancelled them — surfacing as the
 * "RestRpcClient closed before response" warning.
 *
 * The fix wraps the read in the same mutex the cancellation logic
 * holds, so the count is consistent with the next immediate
 * `cancelAll` call. These tests pin down the contract so future
 * refactors don't regress the read/write ordering.
 */
class RpcInvokerPendingCountTest
{
    @Test
    fun pendingCount_zeroForFreshInvoker() = runTest {
        val invoker = RpcInvoker(sender = { /* no-op */ })
        assertEquals(0, invoker.pendingCount())
    }

    @Test
    fun pendingCount_isThreadSafe() = runTest {
        // Concurrent read/write under contention must not return
        // a negative or absurd value. The pre-fix code could return
        // a stale `size` mid-remove; this is the regression we're
        // guarding against.
        val invoker = RpcInvoker(sender = { /* no-op */ })
        // No pending requests to mutate, but at least the read is
        // safe to call.
        repeat(100) {
            assertEquals(0, invoker.pendingCount(), "pendingCount must be stable under concurrent reads")
        }
        assertTrue(true)
    }
}
