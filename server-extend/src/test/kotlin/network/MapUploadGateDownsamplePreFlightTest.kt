package network

import agent.builders.MapSafetyPayload
import com.TTT.Pipe.MultimodalContent
import globals.BedrockConfig
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.ttt.autogenesis.network.MapUploadRequest
import org.ttt.autogenesis.network.RpcCallContext
import org.ttt.autogenesis.network.RpcMessage
import org.ttt.autogenesis.serverextend.RestPlayerConnectionManager
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertFalse
import kotlin.test.fail

/**
 * Regression coverage for the gate's image-size pre-flight.
 *
 * Bug history: the original `MAX_SAFE_BINARY_BYTES = 3 * 1024 * 1024` (3 MB)
 * was empirically wrong. A live Bedrock trace at 1.58 MB PNG overflowed
 * Nova Lite's 990 K-token context window with the error
 *   "Context window size is too small to fit the binary data.
 *    Context window size: 990000 Binary size: 1579421"
 *
 * Empirical token/byte ratio for PNG input: 990_000 / 1_579_421 ≈ 0.627.
 * A safe image-only cap that leaves >=30% of the 990 K window for prompt /
 * footer / reasoning / JSON output is 957_142 bytes — round down to 900 KB.
 *
 * These tests pin the corrected threshold against the actual model and
 * verify the pre-flight actually fires for the empirically-overflowing size.
 */
class MapUploadGateDownsamplePreFlightTest
{
    @Before
    fun resetBefore()
    {
        MapUploadGate.resetForTest()
        MapUploadGateStorage.resetForTest()
        MapUploadSuccessHandlers.resetForTest()
        MapUploadErrorHandlers.resetForTest()
        MapUploadGate.fakeUnpacker = null
        MapUploadGate.fakeSafetyRunner = null
        MapUploadGate.fakeDownsampler = null
    }

    @After
    fun resetAfter()
    {
        MapUploadGate.resetForTest()
        MapUploadGateStorage.resetForTest()
        MapUploadSuccessHandlers.resetForTest()
        MapUploadErrorHandlers.resetForTest()
    }

    private fun emptyMapData(): structs.MapData = structs.MapData(
        pins = listOf(
            structs.PinData(pinId = "p-A", territory = structs.Territory(name = "A"))
        ),
        connections = listOf(
            structs.ConnectionData(fromPinId = "p-A", toPinId = "p-B")
        )
    )

    /**
     * Contract (RED before fix): the gate's MAX_SAFE_BINARY_BYTES cap must
     * sit at or below Nova Lite's actual context window headroom. At 3 MB
     * it does NOT — a 1.58 MB PNG passes the pre-flight and crashes against
     * the 990 K-token model window. The corrected cap is 900 KB.
     *
     * The threshold is exposed via a private companion so tests can pin
     * the actual constant rather than hand-computing the projection.
     */
    @Test
    fun maxSafeBinaryBytesCapIsCalibratedForNovaLiteContextWindow(): Unit = runBlocking {
        val arcticaLikeImageBytes = 1_579_421
        val novaContextWindowTokens = 990_000
        val tokensPerByte = 0.627

        // Empirical fact: 1.58 MB PNG overflows the 990 K-token window.
        val tokensForOverflowImage = (arcticaLikeImageBytes * tokensPerByte).toInt()
        assertTrue(
            tokensForOverflowImage > novaContextWindowTokens,
            "fixture sanity: 1.58 MB PNG must overflow 990 K tokens " +
                    "(got $tokensForOverflowImage tokens, threshold $novaContextWindowTokens)"
        )

        // The gate's cap must be small enough that a 1.58 MB image triggers
        // the downsample pre-flight — not gets handed raw to the model.
        val thresholdBytes = MapUploadGate.maxSafeBinaryBytesForTest()
        assertTrue(
            thresholdBytes < arcticaLikeImageBytes,
            "MAX_SAFE_BINARY_BYTES ($thresholdBytes bytes) MUST be below the " +
                    "empirical overflow image size (${arcticaLikeImageBytes} bytes). " +
                    "The current cap passes the raw 1.5 MB image to the safety pipe, " +
                    "where it crashes against the 990 K-token context window."
        )
        // At the threshold, the image alone should stay under ~70% of the
        // model window so the rest of the prompt/footer/reasoning fits.
        val tokensAtThreshold = (thresholdBytes * tokensPerByte).toInt()
        assertTrue(
            tokensAtThreshold <= (novaContextWindowTokens * 0.7).toInt(),
            "image at MAX_SAFE_BINARY_BYTES must occupy <=70% of the Nova " +
                    "Lite window. Got $tokensAtThreshold tokens at threshold " +
                    "$thresholdBytes bytes; limit ${(novaContextWindowTokens * 0.7).toInt()}."
        )
    }

    /**
     * Contract (RED before fix): when an unpacked image exceeds the gate
     * cap, the gate MUST invoke the downsample helper. Currently the gate
     * wires the downsample only for images > 3 MB, so a 1.58 MB image
     * passes through unwritten — exactly the failure mode in the live
     * Bedrock trace.
     */
    @Test
    fun downsamplePreFlightFiresForEmpiricalOverflowImageSize(): Unit = runBlocking {
        val originalBytes = ByteArray(1_579_421) { idx -> ((idx * 7) and 0xff).toByte() }

        MapUploadGate.fakeUnpacker = { _ ->
            MapSafetyPayload(imageBytes = originalBytes, mapData = emptyMapData())
        }
        MapUploadGate.fakeSafetyRunner = { _, _ ->
            MultimodalContent(text = "{\"isAllowed\": true, \"reason\": \"test\"}")
        }

        var downsampleCalled = false
        MapUploadGate.fakeDownsampler = { _ ->
            downsampleCalled = true
            // Simulate the real downsample: a 1024x1024 PNG is ~300 KB.
            ByteArray(300 * 1024) { idx -> ((idx * 11) and 0xff).toByte() }
        }
        MapUploadGateStorage.fakeSaver = { _, _ -> true }

        val manager = RestPlayerConnectionManager()
        MapUploadSuccessHandlers.registerConnectionManager(manager)
        MapUploadErrorHandlers.registerConnectionManager(manager)
        manager.register("downsampler-test")

        val request = MapUploadRequest(
            mapPackBytes = byteArrayOf(1, 2, 3, 4),
            mapName = "Downsample Test"
        )

        MapUploadGate.uploadMapGate(
            RpcCallContext(
                connectionId = "downsampler-test",
                metadata = emptyMap(),
                sender = { _: RpcMessage -> }
            ),
            request
        )

        assertTrue(
            downsampleCalled,
            "the gate MUST downsample images above the cap. " +
                    "Got downsampleCalled=false — the gate passed a 1.58 MB " +
                    "image raw to the safety pipe even though it exceeds the " +
                    "Nova Lite 990 K-token context window."
        )
    }

    /**
     * Contract (RED before fix): the gate MUST route EVERY image through
     * the downsample helper, not just images above the cap. The
     * operator's directive: "always downsample any images we send to the
     * map safety agent to 256K tokens in size."
     *
     * The downsample helper is a no-op fast path for images already
     * smaller than the target dimension (it returns the original bytes
     * unchanged when `longestEdge <= DOWNSAMPLE_MAX_DIMENSION`), so the
     * production cost of always invoking it is byte-padding the input
     * and skipping the re-encode. Without this, the gate cannot
     * guarantee every safety-pipe invocation sees a payload calibrated
     * for the 256K-token floor.
     */
    @Test
    fun downsamplePreFlightFiresForEveryImageRegardlessOfSize(): Unit = runBlocking {
        val smallImageBytes = ByteArray(50_000) { idx -> ((idx * 7) and 0xff).toByte() }

        MapUploadGate.fakeUnpacker = { _ ->
            MapSafetyPayload(imageBytes = smallImageBytes, mapData = emptyMapData())
        }
        MapUploadGate.fakeSafetyRunner = { _, _ ->
            MultimodalContent(text = "{\"isAllowed\": true, \"reason\": \"\"}")
        }

        var downsampleCalled = false
        MapUploadGate.fakeDownsampler = { _ ->
            downsampleCalled = true
            smallImageBytes
        }
        MapUploadGateStorage.fakeSaver = { _, _ -> true }

        val manager = RestPlayerConnectionManager()
        MapUploadSuccessHandlers.registerConnectionManager(manager)
        MapUploadErrorHandlers.registerConnectionManager(manager)
        manager.register("always-downsample-test")

        val request = MapUploadRequest(
            mapPackBytes = byteArrayOf(1, 2, 3, 4),
            mapName = "Small Map"
        )

        MapUploadGate.uploadMapGate(
            RpcCallContext(
                connectionId = "always-downsample-test",
                metadata = emptyMap(),
                sender = { _: RpcMessage -> }
            ),
            request
        )

        assertTrue(
            downsampleCalled,
            "the gate MUST route every image through the downsample helper, " +
                    "regardless of size. Got downsampleCalled=false for a 50 KB image — " +
                    "the operator's directive was to always downsample to the 256K-token floor."
        )
    }

    /**
     * Contract: when the downsample helper cannot decode the image (ImageIO
     * returns null or throws), the gate MUST reject the upload rather than
     * silently passing the original oversized bytes to the safety classifier.
     *
     * Reproduces the Sand Martello upload failure (2026-08-15): the map's
     * image triggers an ImageIO.decode failure during the downsample pre-flight.
     * The current implementation's `catch (e: Exception) { return bytes }`
     * fallback returns the original oversized bytes unchanged, which then
     * land in the multimodal binary sent to Bedrock — the safety pipe's
     * context window overflows.
     *
     * The downstream cap check (`if (imageBytes.size > MAX_SAFE_BINARY_BYTES)`)
     * only fires when downsample SUCCEEDED. A decode failure skips the cap
     * check entirely because the catch returns the original bytes, so the
     * safety classifier sees the raw oversized payload.
     *
     * Fix: the downsample catch must throw so the gate's outer try/catch
     * routes the failure through `MapUploadErrorHandlers.sendMapUploadError`
     * + returns `MapUploadGateResponse(accepted=false, reason=...)`.
     */
    @Test
    fun gateRejectsUploadWhenImageCannotBeDecodedForDownsample(): Unit = runBlocking {
        // Under the 408 KB cap, but NOT a valid image. This is the exact
        // Sand Martello repro: the file is small enough to pass the
        // downstream cap check on its own, but the downsample helper cannot
        // decode it (e.g. indexed-color PNG, palette issue, color model
        // mismatch). The current implementation's catch returns the
        // original bytes unchanged, the cap check passes because the bytes
        // fit, and the safety classifier sees the raw undecodable image
        // — the Bedrock pipeline then explodes against the model's context
        // window OR the wrong image lands in the multimodal payload.
        //
        // 200 KB of arbitrary bytes — well under the 408 KB cap, and ImageIO
        // returns null because the PNG signature is missing.
        val undecodableBytes = ByteArray(200_000) { idx -> ((idx * 13) and 0xff).toByte() }
        // Sanity: confirm the test fixture actually triggers the failure
        // mode (ImageIO returns null, doesn't throw).
        val probe = javax.imageio.ImageIO.read(java.io.ByteArrayInputStream(undecodableBytes))
        assertNull(probe, "test fixture sanity: 200 KB of arbitrary bytes MUST not decode as an image")

        MapUploadGate.fakeUnpacker = { _ ->
            MapSafetyPayload(imageBytes = undecodableBytes, mapData = emptyMapData())
        }
        // The safety runner MUST NOT be invoked when downsample cannot decode
        // the image. Invoking it would mean the original oversized bytes
        // reached Bedrock — the exact failure mode the operator is reporting.
        MapUploadGate.fakeSafetyRunner = { _, _ ->
            fail(
                "safety runner MUST NOT be invoked when downsample cannot decode the image — " +
                "the original 1.5 MB payload would land in the Bedrock context window and " +
                "overflow the 990 K-token model window."
            )
        }
        // Same for storage: an upload that cannot be downsampled must never
        // be persisted to AGS.
        MapUploadGateStorage.fakeSaver = { _, _ ->
            fail("storage MUST NOT be invoked when downsample cannot decode the image")
        }

        val manager = RestPlayerConnectionManager()
        MapUploadSuccessHandlers.registerConnectionManager(manager)
        MapUploadErrorHandlers.registerConnectionManager(manager)
        manager.register("undecodable-test")

        val request = MapUploadRequest(
            mapPackBytes = byteArrayOf(1, 2, 3, 4),
            mapName = "Sand Martello Repro"
        )

        val response = MapUploadGate.uploadMapGate(
            RpcCallContext(
                connectionId = "undecodable-test",
                metadata = emptyMap(),
                sender = { _: RpcMessage -> }
            ),
            request
        )

        assertFalse(
            response.accepted,
            "the gate MUST reject the upload when the image cannot be decoded for downsample. " +
            "Got accepted=true — the undecodable 1.5 MB payload was passed through to the " +
            "safety classifier unchanged, defeating the operator's 256K-token directive."
        )
        assertNotNull(response.reason, "rejection reason MUST be populated for client diagnostics")
        assertTrue(
            response.reason!!.contains("decode", ignoreCase = true) ||
                    response.reason!!.contains("downsample", ignoreCase = true),
            "rejection reason must clearly identify the decode/downsample failure so the " +
                    "player can see what went wrong. Got: '${response.reason}'"
        )
    }
}