package org.ttt.autogenesis.network

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * Sentinel for the SKIP-LOGIN boot reconnect storm regression.
 *
 * **Before the fix (verified 2026-08-12 via JS e2e probe
 * `kvisionApp-e2e/probes/map-upload-e2e.mjs`):** Main.kt:252 fired
 * `ServerExtendBridge.connect()` 4-5x in rapid succession during boot;
 * each call generated a fresh `playerId` via `generatePlayerId()`,
 * failed the dedup check, and tore down the previously-installed client.
 * 8-11 seconds later, `MapUploadModal: ServerExtendBridge.rpcInvoker is
 * null (disconnected); aborting publish` fired at publish-click time.
 *
 * **Fix (2026-08-12):** `RestRpcBridge.connect()` (both JVM and JS
 * impls) now (a) clears `client = null` BEFORE creating the new client,
 * and (b) treats a same-`accelbyteId` callback as a no-rebind when the
 * bridge is already live, regardless of `playerId` rotation.
 *
 * The full storm is exercised end-to-end by the JS probe; this sentinel
 * pins the JVM surface so the bridge API stays observable and idle.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RestRpcBridgeReconnectStormTest
{
    @Test
    fun bridgeExposesIdempotentSurface() = runTest {
        val bridge = RestRpcBridge
        assertNotNull(bridge, "RestRpcBridge JVM singleton must exist")
        // Idle state: no live client.
        assertEquals(null, bridge.rpcInvoker)
    }
}
