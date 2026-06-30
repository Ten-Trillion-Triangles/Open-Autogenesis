package org.ttt.autogenesis.accelbyte

import kotlin.js.Promise
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlinx.coroutines.await
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import org.ttt.autogenesis.accelbyte.util.propagateJsErrors


class PromiseInteropTest {

    @Test
    fun testAwaitCatchesJsError() = runTest {
        val promise = Promise<String> { _, reject ->
            reject(RuntimeException("JS Error"))
        }

        try {
            promise.await()
            fail("Should have thrown")
        } catch (e: Throwable) {
            assertTrue(e.message?.contains("JS Error") == true, "Message was: ${e.message}")
        }
    }

    @Test
    fun testPropagateJsErrorsWithString() = runTest {
        val promise = Promise<String> { _, reject ->
            val r = reject.asDynamic()
            r("String Error")
        }

        try {
            promise.propagateJsErrors().await()
            fail("Should have thrown")
        } catch (e: Throwable) {
             assertTrue(e.message?.contains("String Error") == true, "Message was: ${e.message}")
        }
    }

    @Test
    fun testPropagateJsErrorsWithObject() = runTest {
        val jsError = js("({ message: 'Object Error', code: 123 })")
        val promise = Promise<String> { _, reject ->
            reject(jsError)
        }

        try {
            promise.propagateJsErrors().await()
            fail("Should have thrown")
        } catch (e: Throwable) {
             // jsErrorAsThrowable converts object to string
             val msg = e.message ?: ""
             assertTrue(msg.contains("Object Error") || msg.contains("object"), "Message was: $msg")
        }
    }
    
    // Helper to run suspend tests
    private fun runTest(block: suspend () -> Unit): Promise<Unit> = Promise { resolve, reject ->
        kotlinx.coroutines.GlobalScope.launch(kotlinx.coroutines.Dispatchers.Unconfined) {
            try {
                block()
                resolve(Unit)
            } catch (e: Throwable) {
                reject(e)
            }
        }
    }
}
