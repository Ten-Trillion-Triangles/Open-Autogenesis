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
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Unit tests for [MapUploadGate].
 *
 * The gate is a state machine with three terminal paths:
 *  - safety-pass + save-success → accepted=true, notify success
 *  - safety-fail               → accepted=false, notify error (already handled by pipes)
 *  - safety-pass + save-fail   → accepted=false, notify error
 *
 * Every test resets the fake seams via [MapUploadGate.resetForTest] and the
 * related singleton teardown so the global state doesn't leak between cases.
 */
class MapUploadGateTest
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

    /**
     * A non-empty `MapData` shape that passes the gate's content-validation.
     * The two required `List<...>` fields have no defaults so we have to
     * pass them explicitly; everything else has a default in the data class.
     *
     * The operator's content-validation (added with the empty-pack cost
     * control) rejects empty pins AND empty connections. The downstream
     * tests on this file exercise the safety pipeline / save paths, so
     * the helper must give the safety pipeline something to inspect.
     * The empty-pack cost-control contract is owned by
     * [MapUploadGatePackContentValidationTest].
     */
    private fun emptyMapData(): MapData = MapData(
        pins = listOf(
            structs.PinData(pinId = "p-A", territory = structs.Territory(name = "A"))
        ),
        connections = listOf(
            structs.ConnectionData(fromPinId = "p-A", toPinId = "p-B")
        )
    )

    /**
     * Builds a test context whose connectionId is the same string we'll use
     * as the playerId in the gate. The connectionId is the load-bearing
     * surface for `RestPlayerConnectionManager.findSession` lookups.
     */
    private fun fakeContext(playerId: String): RpcCallContext
    {
        return RpcCallContext(
            connectionId = playerId,
            metadata = emptyMap(),
            sender = { message: RpcMessage -> /* no-op */ }
        )
    }

    @Test
    fun `safety-pass + save-success returns accepted=true with full catalogue metadata`() = runBlocking {
        MapUploadGate.fakeUnpacker = { _ ->
            MapSafetyPayload(
                imageBytes = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47), // PNG magic
                mapData = emptyMapData()
            )
        }
        // The fakeDownsampler seam is set so the gate's pre-flight doesn't try to
        // decode the raw PNG-magic-only fixture (which would throw — that's the
        // exact contract enforced by MapUploadGateDownsamplePreFlightTest). The
        // seam returns the same bytes unchanged so the pipeline receives them
        // and the safety runner can return its success verdict.
        MapUploadGate.fakeDownsampler = { bytes -> bytes }
        MapUploadGate.fakeSafetyRunner = { _, _ -> MultimodalContent(text = "safe") }
        MapUploadGateStorage.fakeSaver = { _, _ -> true }

        val manager = RestPlayerConnectionManager()
        MapUploadSuccessHandlers.registerConnectionManager(manager)
        manager.register("player-success")

        val packBytes = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8, 9, 10) // 10 bytes
        val request = MapUploadRequest(
            mapPackBytes = packBytes,
            mapName = "My New Map"
        )
        val response = MapUploadGate.uploadMapGate(fakeContext("player-success"), request)

        assertEquals(true, response.accepted, "safety-pass + save-success must return accepted=true")
        assertNotNull(response.mapId, "mapId must be populated on success")
        assertEquals("My New Map", response.mapName,
            "mapName must echo the request name on success")
        assertNull(response.reason, "reason must be null on success")

        // The catalogue entry carries everything the player UI needs to
        // render the new map row without a follow-up listPlayerMaps call.
        val entry = assertNotNull(response.metadata,
            "metadata must be populated on success so the UI can render the new map row")
        assertEquals(response.mapId, entry.mapId,
            "metadata.mapId must match the top-level mapId")
        assertEquals("My New Map", entry.mapName,
            "metadata.mapName must echo the request name")
        assertEquals(packBytes.size, entry.sizeBytes,
            "metadata.sizeBytes must reflect the request pack size")
        assertTrue(entry.uploadedAt > 0L,
            "metadata.uploadedAt must be a positive epoch ms timestamp")
    }

    @Test
    fun `safety-fail returns accepted=false and propagates the classifier rejection`() = runBlocking {
        MapUploadGate.fakeUnpacker = { _ ->
            MapSafetyPayload(
                imageBytes = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47),
                mapData = emptyMapData()
            )
        }
        // See note on `safety-pass + save-success` above — the fakeDownsampler
        // seam is required so the gate's pre-flight doesn't try to decode the
        // PNG-magic-only fixture.
        MapUploadGate.fakeDownsampler = { bytes -> bytes }

        val failing = MultimodalContent(text = "rejected")
        failing.terminate()
        MapUploadGate.fakeSafetyRunner = { _, _ -> failing }

        val saveInvoked = booleanArrayOf(false)
        MapUploadGateStorage.fakeSaver = { _, _ ->
            saveInvoked[0] = true
            true
        }

        val request = MapUploadRequest(
            mapPackBytes = byteArrayOf(1, 2, 3, 4),
            mapName = "Map"
        )
        val response = MapUploadGate.uploadMapGate(fakeContext("player-fail"), request)

        assertEquals(false, response.accepted, "safety-fail must return accepted=false")
        assertNull(response.mapId, "mapId must be null on failure")
        assertNull(response.mapName, "mapName must be null on failure")
        assertNull(response.metadata, "metadata must be null on safety failure")
        assertNotNull(response.reason, "reason must be populated on failure")
        assertEquals(false, saveInvoked[0], "save must NOT be invoked on safety fail")
    }

    @Test
    fun `safety-pass + save-fail returns accepted=false and pushes Map Upload Error with the save reason`() = runBlocking {
        MapUploadGate.fakeUnpacker = { _ ->
            MapSafetyPayload(
                imageBytes = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47),
                mapData = emptyMapData()
            )
        }
        // See note on `safety-pass + save-success` above.
        MapUploadGate.fakeDownsampler = { bytes -> bytes }
        MapUploadGate.fakeSafetyRunner = { _, _ -> MultimodalContent(text = "safe") }
        MapUploadGateStorage.fakeSaver = { _, _ -> false }

        val manager = RestPlayerConnectionManager()
        MapUploadErrorHandlers.registerConnectionManager(manager)
        manager.register("player-save-fail")

        val request = MapUploadRequest(
            mapPackBytes = byteArrayOf(1, 2, 3, 4),
            mapName = "My Map"
        )
        val response = MapUploadGate.uploadMapGate(fakeContext("player-save-fail"), request)

        assertEquals(false, response.accepted, "safety-pass + save-fail must return accepted=false")
        assertNull(response.mapId, "mapId must be null on save failure")
        assertNull(response.mapName, "mapName must be null on save failure")
        assertNull(response.metadata, "metadata must be null on save failure")
        assertNotNull(response.reason, "reason must be populated on save failure")
        assertTrue(response.reason!!.startsWith("Save failed:"),
            "the reason must indicate the save failure, got: ${response.reason}")
    }

    @Test
    fun `unpack-fail returns accepted=false and pushes Map Upload Error with the unpack reason`() = runBlocking {
        MapUploadGate.fakeUnpacker = { _ ->
            throw RuntimeException("not a zip")
        }

        val safetyInvoked = booleanArrayOf(false)
        MapUploadGate.fakeSafetyRunner = { _, _ ->
            safetyInvoked[0] = true
            MultimodalContent(text = "should-not-run")
        }

        val saveInvoked = booleanArrayOf(false)
        MapUploadGateStorage.fakeSaver = { _, _ ->
            saveInvoked[0] = true
            true
        }

        val request = MapUploadRequest(
            mapPackBytes = byteArrayOf(0, 0, 0, 0),
            mapName = "Map"
        )
        val response = MapUploadGate.uploadMapGate(fakeContext("player-unpack-fail"), request)

        assertEquals(false, response.accepted, "unpack-fail must return accepted=false")
        assertNull(response.mapId, "mapId must be null on unpack failure")
        assertNull(response.mapName, "mapName must be null on unpack failure")
        assertNull(response.metadata, "metadata must be null on unpack failure")
        assertNotNull(response.reason, "reason must be populated on unpack failure")
        assertTrue(response.reason!!.contains("Unpack failed"),
            "the reason must indicate the unpack failure, got: ${response.reason}")
        assertEquals(false, safetyInvoked[0], "safety runner must NOT be invoked on unpack fail")
        assertEquals(false, saveInvoked[0], "save must NOT be invoked on unpack fail")
    }

    @Test
    fun `oversized image triggers downsample and the pipeline runs on the downsampled bytes`() = runBlocking {
        // The unpacked image bytes are larger than MAX_SAFE_BINARY_BYTES
        // (3 MB). The gate should downsample to 1024x1024 once and run the
        // pipeline on the downsampled bytes. The fake downsampler returns
        // a smaller fixed-size byte array so the size check after downsample
        // passes and the pipeline runs.
        val oversizedImage = ByteArray(4 * 1024 * 1024) { 0x42 } // 4 MB
        MapUploadGate.fakeUnpacker = { _ ->
            MapSafetyPayload(
                imageBytes = oversizedImage,
                mapData = emptyMapData()
            )
        }
        val downsampleCalls = mutableListOf<ByteArray>()
        MapUploadGate.fakeDownsampler = { bytes ->
            downsampleCalls.add(bytes)
            // Pretend the downsample returns a small (under-cap) result.
            byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
        }
        MapUploadGate.fakeSafetyRunner = { _, _ -> MultimodalContent(text = "safe") }
        MapUploadGateStorage.fakeSaver = { _, _ -> true }

        val manager = RestPlayerConnectionManager()
        MapUploadSuccessHandlers.registerConnectionManager(manager)
        manager.register("player-downsample")

        val request = MapUploadRequest(
            mapPackBytes = byteArrayOf(1, 2, 3, 4),
            mapName = "Downsample Map"
        )
        val response = MapUploadGate.uploadMapGate(fakeContext("player-downsample"), request)

        // The pre-flight downsample fires once. The pipeline runs on the
        // downsampled bytes (fake safety runner returns success).
        assertEquals(1, downsampleCalls.size,
            "downsample must fire exactly once for an oversized image, got: ${downsampleCalls.size}")
        assertTrue(downsampleCalls[0].contentEquals(oversizedImage),
            "downsample must be called with the original oversized bytes")
        assertEquals(true, response.accepted,
            "the pipeline must run on the downsampled bytes and accept")
        assertNotNull(response.mapId, "mapId must be populated on success")
        assertNull(response.reason, "reason must be null on success")
    }

    @Test
    fun `downsample that is still too big rejects the upload with a clear reason`() = runBlocking {
        // The image is over the cap. The downsample fires but the
        // downsampled output is STILL over the cap. The gate must reject
        // with a clear reason rather than run the pipeline (which would
        // overflow the model context window).
        val oversizedImage = ByteArray(4 * 1024 * 1024) { 0x42 } // 4 MB
        MapUploadGate.fakeUnpacker = { _ ->
            MapSafetyPayload(
                imageBytes = oversizedImage,
                mapData = emptyMapData()
            )
        }
        // Pretend the downsample failed to bring the image under the cap
        // (e.g. the source image is enormous).
        val stillOversized = ByteArray(5 * 1024 * 1024) { 0x42 }
        MapUploadGate.fakeDownsampler = { _ -> stillOversized }
        MapUploadGate.fakeSafetyRunner = { _, _ ->
            MultimodalContent(text = "should-not-run")
        }
        MapUploadGateStorage.fakeSaver = { _, _ -> true }

        val manager = RestPlayerConnectionManager()
        MapUploadErrorHandlers.registerConnectionManager(manager)
        manager.register("player-still-big")

        val request = MapUploadRequest(
            mapPackBytes = byteArrayOf(1, 2, 3, 4),
            mapName = "Big Map"
        )
        val response = MapUploadGate.uploadMapGate(fakeContext("player-still-big"), request)

        assertEquals(false, response.accepted,
            "image that is still too big after downsample must be rejected")
        assertNull(response.mapId, "mapId must be null on rejection")
        assertNull(response.mapName, "mapName must be null on rejection")
        assertNull(response.metadata, "metadata must be null on rejection")
        assertNotNull(response.reason, "reason must be populated on rejection")
        assertTrue(response.reason!!.contains("Image too large"),
            "reason must indicate the image is too large, got: ${response.reason}")
    }

    @Test
    fun `small image passes through downsample without rejection`() = runBlocking {
        // Per the operator's directive "always downsample any images we send
        // to the map safety agent to 256K tokens in size", every image —
        // including small ones — must route through the downsample helper.
        // The helper is a no-op fast path for images already under
        // DOWNSAMPLE_MAX_DIMENSION (returns original bytes unchanged),
        // so the production cost is bounded.
        val smallImage = ByteArray(100) { 0x42 } // 100 bytes
        MapUploadGate.fakeUnpacker = { _ ->
            MapSafetyPayload(
                imageBytes = smallImage,
                mapData = emptyMapData()
            )
        }
        val downsampleCalls = mutableListOf<ByteArray>()
        MapUploadGate.fakeDownsampler = { bytes ->
            downsampleCalls.add(bytes)
            bytes // stand-in for the downsampled bytes
        }
        MapUploadGate.fakeSafetyRunner = { _, _ -> MultimodalContent(text = "safe") }
        MapUploadGateStorage.fakeSaver = { _, _ -> true }

        val manager = RestPlayerConnectionManager()
        MapUploadSuccessHandlers.registerConnectionManager(manager)
        manager.register("player-small")

        val request = MapUploadRequest(
            mapPackBytes = byteArrayOf(1, 2, 3, 4),
            mapName = "Small Map"
        )
        val response = MapUploadGate.uploadMapGate(fakeContext("player-small"), request)

        assertEquals(1, downsampleCalls.size,
            "downsample MUST fire for every image regardless of size " +
                    "(operator directive: always downsample to 256K-token floor). " +
                    "Got ${downsampleCalls.size} calls for a 100-byte image.")
        assertTrue(downsampleCalls[0].contentEquals(smallImage),
            "downsample must be called with the original small bytes")
        assertEquals(true, response.accepted, "small image must be accepted")
    }
}