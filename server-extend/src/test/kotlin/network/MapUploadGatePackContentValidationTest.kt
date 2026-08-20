package network

import agent.builders.MapSafetyPayload
import com.TTT.Pipe.MultimodalContent
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.ttt.autogenesis.network.MapUploadRequest
import org.ttt.autogenesis.network.RpcCallContext
import org.ttt.autogenesis.network.RpcMessage
import org.ttt.autogenesis.serverextend.RestPlayerConnectionManager
import structs.MapData
import structs.Territory
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Cost-control coverage: the gate MUST reject map packs whose contents
 * are empty / all-defaults BEFORE invoking the safety classifier. Without
 * this, the prior shape would happily route an empty pack to Nova Lite,
 * burning tokens for a meaningful pass on an empty map.
 *
 * The validation pins three rejection conditions:
 *  1. image bytes are empty (zero-byte image entry)
 *  2. mapData has zero pins AND zero connections
 *  3. mapData is otherwise all-defaults (every String field blank, both
 *     pins and connections empty)
 *
 * The agent MUST NOT run for any of these. The contract is observable
 * via the `fakeSafetyRunner` seam: if the safety runner is invoked, the
 * gate failed to gate.
 */
class MapUploadGatePackContentValidationTest
{
    @Before
    fun resetBefore()
    {
        MapUploadGate.resetForTest()
        MapUploadGateStorage.resetForTest()
        MapUploadSuccessHandlers.resetForTest()
        MapUploadErrorHandlers.resetForTest()
    }

    @After
    fun resetAfter()
    {
        MapUploadGate.resetForTest()
        MapUploadGateStorage.resetForTest()
        MapUploadSuccessHandlers.resetForTest()
        MapUploadErrorHandlers.resetForTest()
    }

    private fun aRealImage(): ByteArray = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
    private fun emptyMapData(): MapData = MapData(
        pins = emptyList(),
        connections = emptyList()
    )

    /**
     * Contract (RED): an empty image (zero bytes) MUST be rejected before
     * the safety pipeline runs. Currently the pipeline accepts a zero-byte
     * image and the image pipe's `setPreInitFunction` terminates it
     * downstream — but the LLM call still gets scheduled.
     */
    @Test
    fun emptyImageBytesFailsFastBeforeSafetyPipeline(): Unit = runBlocking {
        var safetyRunnerCalled = false
        MapUploadGate.fakeUnpacker = { _ ->
            MapSafetyPayload(imageBytes = ByteArray(0), mapData = emptyMapData())
        }
        MapUploadGate.fakeSafetyRunner = { _, _ ->
            safetyRunnerCalled = true
            MultimodalContent(text = "{\"isAllowed\": true}")
        }
        MapUploadGate.fakeDownsampler = { it } // no-op passthrough

        val manager = RestPlayerConnectionManager()
        MapUploadSuccessHandlers.registerConnectionManager(manager)
        MapUploadErrorHandlers.registerConnectionManager(manager)
        manager.register("empty-image-test")

        val response = MapUploadGate.uploadMapGate(
            RpcCallContext("empty-image-test", emptyMap()) { _: RpcMessage -> },
            MapUploadRequest(mapPackBytes = byteArrayOf(1, 2, 3, 4), mapName = "Empty image")
        )

        assertFalse(
            response.accepted,
            "empty-image upload must be rejected (got accepted=${response.accepted})"
        )
        assertFalse(
            safetyRunnerCalled,
            "safety runner MUST NOT be invoked for an empty-image pack — " +
                    "the gate must short-circuit before the Bedrock call."
        )
        assertTrue(
            (response.reason?.contains("image", ignoreCase = true) == true) ||
                    (response.reason?.contains("empty", ignoreCase = true) == true),
            "rejection reason must explain it's the empty image " +
                    "(got: '${response.reason}')"
        )
    }

    /**
     * Contract (RED): a map pack with no pins AND no connections (the
     * "all default" shape — `MapData()` constructor with the two
     * required fields empty) MUST be rejected before the safety
     * pipeline. The LLM sees nothing meaningful to inspect and the call
     * would burn tokens for no value.
     */
    @Test
    fun emptyMapDataFailsFastBeforeSafetyPipeline(): Unit = runBlocking {
        var safetyRunnerCalled = false
        MapUploadGate.fakeUnpacker = { _ ->
            MapSafetyPayload(imageBytes = aRealImage(), mapData = emptyMapData())
        }
        MapUploadGate.fakeSafetyRunner = { _, _ ->
            safetyRunnerCalled = true
            MultimodalContent(text = "{\"isAllowed\": true}")
        }
        MapUploadGate.fakeDownsampler = { it }

        val manager = RestPlayerConnectionManager()
        MapUploadSuccessHandlers.registerConnectionManager(manager)
        MapUploadErrorHandlers.registerConnectionManager(manager)
        manager.register("empty-mapdata-test")

        val response = MapUploadGate.uploadMapGate(
            RpcCallContext("empty-mapdata-test", emptyMap()) { _: RpcMessage -> },
            MapUploadRequest(mapPackBytes = byteArrayOf(1, 2, 3, 4), mapName = "Empty map")
        )

        assertFalse(
            response.accepted,
            "empty-MapData upload must be rejected (got accepted=${response.accepted})"
        )
        assertFalse(
            safetyRunnerCalled,
            "safety runner MUST NOT be invoked for an empty MapData — " +
                    "there are no pins / connections for the safety classifier to inspect."
        )
        assertTrue(
            (response.reason?.contains("pins", ignoreCase = true) == true) ||
                    (response.reason?.contains("connections", ignoreCase = true) == true) ||
                    (response.reason?.contains("empty", ignoreCase = true) == true),
            "rejection reason must name the empty-field condition " +
                    "(got: '${response.reason}')"
        )
    }

    /**
     * Contract (RED): the gate MUST recognise [MapPackUnpackException]
     * (the new typed exception) and surface its message verbatim instead
     * of the prior "Unpack failed: null" useless-NPE fallback. The pack
     * constructor wires the exception by routing the unpacker through
     * a lambda that throws [MapPackUnpackException] — the same shape
     * that the [structs.MapPackManager.unpack] throws on a malformed zip.
     */
    @Test
    fun mapPackUnpackExceptionMessageSurfacesVerbatim(): Unit = runBlocking {
        var safetyRunnerCalled = false
        MapUploadGate.fakeUnpacker = { _ ->
            throw structs.MapPackUnpackException("No map.json entry found in zip")
        }
        MapUploadGate.fakeSafetyRunner = { _, _ ->
            safetyRunnerCalled = true
            MultimodalContent(text = "{\"isAllowed\": true}")
        }

        val manager = RestPlayerConnectionManager()
        MapUploadSuccessHandlers.registerConnectionManager(manager)
        MapUploadErrorHandlers.registerConnectionManager(manager)
        manager.register("typed-exception-test")

        val response = MapUploadGate.uploadMapGate(
            RpcCallContext("typed-exception-test", emptyMap()) { _: RpcMessage -> },
            MapUploadRequest(mapPackBytes = byteArrayOf(1, 2, 3, 4), mapName = "Bad pack")
        )

        assertFalse(response.accepted, "typed-exception pack must be rejected")
        assertFalse(
            safetyRunnerCalled,
            "safety runner MUST NOT be invoked when the unpacker throws"
        )
        assertEquals(
            "Unpack failed: No map.json entry found in zip",
            response.reason,
            "the typed-exception message must surface verbatim " +
                    "(got: '${response.reason}')"
        )
    }
}