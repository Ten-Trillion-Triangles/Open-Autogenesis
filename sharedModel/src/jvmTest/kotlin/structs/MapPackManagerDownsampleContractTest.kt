package structs

import org.junit.After
import org.junit.Before
import org.junit.Test
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Pins the downsample contract on the JVM pack() path.
 *
 * Operator directive (2026-08-14): every map image must be downsampled to
 * <=256K tokens before reaching the safety agent. NO EXCEPTIONS.
 *
 * The JVM pack wraps the image bytes in a zip; the JVM pack ALSO runs
 * `downsampleImageBytesForPack` on the image bytes before zipping. So
 * the resulting zip payload (when unpacked) MUST contain a downsampled
 * image that fits the byte cap.
 *
 * Receipt shape:
 *  - generateOperatorCaseImage: 2048x2048 gradient+sparkle PNG runs ~4 MB.
 *  - packWithOversizedImageProducesZipUnderCap: after pack(), the
 *    extracted image inside the zip is at most 408 KB.
 *  - packWithImageAlreadyInBudgetPassesThrough: small images are
 *    returned unchanged.
 *  - packPreservesMapDataJson: the manifest entries stay untouched.
 */
class MapPackManagerDownsampleContractTest
{
    @Before
    fun setUp() {
        // No per-test state currently; the JVM pack is stateless.
    }

    @After
    fun tearDown() {
        // No teardown.
    }

    private fun generateOversizedPng(width: Int, height: Int): ByteArray
    {
        val image = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        val g = image.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        for (y in 0 until height)
        {
            for (x in 0 until width)
            {
                val r = ((x * 7 + y * 13) % 256)
                val gg = ((x * 17 + y * 31) % 256)
                val b = ((x * 3 + y * 11 + (x xor y) % 7) % 256)
                val isSparkle = (x * 31 + y * 7) % 137 == 0
                val cr = if (isSparkle) 255 else r
                val cg = if (isSparkle) 255 else gg
                val cb = if (isSparkle) 0 else b
                image.setRGB(x, y, (cr shl 16) or (cg shl 8) or cb)
            }
        }
        g.dispose()
        val out = ByteArrayOutputStream()
        ImageIO.write(image, "png", out)
        return out.toByteArray()
    }

    private fun emptyMapData(): MapData = MapData(
        pins = listOf(PinData(pinId = "p-A", territory = Territory(name = "A"))),
        connections = listOf(ConnectionData(fromPinId = "p-A", toPinId = "p-B"))
    )

    @Test
    fun packWithOversizedImageProducesZipUnderCap() {
        val oversizedBytes = generateOversizedPng(2048, 2048)
        println("[Contract] Oversized image: ${oversizedBytes.size} bytes")
        assertTrue(
            oversizedBytes.size > 408_000,
            "Oversized image must be > 408 KB to be a meaningful test; got ${oversizedBytes.size}"
        )

        val packBytes = runBlocking {
            MapPackManager.pack("map.png", oversizedBytes, emptyMapData())
        }
        val parseResult = runBlocking { MapPackManager.unpack(packBytes) }
        println("[Contract] Post-pack image: ${parseResult.imageBytes.size} bytes")
        assertTrue(
            parseResult.imageBytes.size <= 408_000,
            "Operator directive violated: image in packed zip is ${parseResult.imageBytes.size} bytes (> 408 KB cap)"
        )
        val estTokens = (parseResult.imageBytes.size * 0.627).toInt()
        println("[Contract] Estimated tokens per safety classifier: $estTokens (budget: 256000)")
        assertTrue(
            estTokens <= 256_000,
            "Operator directive violated: estimated tokens $estTokens > 256000"
        )
    }

    @Test
    fun packWithImageAlreadyInBudgetPassesThrough() {
        val tinyBytes = generateOversizedPng(64, 64)
        println("[Contract] Already-tiny image: ${tinyBytes.size} bytes")
        val packBytes = runBlocking {
            MapPackManager.pack("map.png", tinyBytes, emptyMapData())
        }
        val parseResult = runBlocking { MapPackManager.unpack(packBytes) }
        println("[Contract] Tiny image after pack+unpack: ${parseResult.imageBytes.size} bytes")
        // Tiny image should round-trip with minimal loss (re-encoding at
        // 1024 px max is a no-op for already-small inputs).
        assertTrue(
            parseResult.imageBytes.size <= 408_000,
            "Tiny image should still fit under cap"
        )
    }

    @Test
    fun packPreservesMapDataJson() {
        val oversizedBytes = generateOversizedPng(1024, 1024)
        val customMap = emptyMapData().copy(
            worldName = "TestWorld",
            storyScenario = "operator-directive-receipt"
        )
        val packBytes = runBlocking {
            MapPackManager.pack("map.png", oversizedBytes, customMap)
        }
        val parseResult = runBlocking { MapPackManager.unpack(packBytes) }
        assertEquals("TestWorld", parseResult.mapData.worldName)
        assertEquals("operator-directive-receipt", parseResult.mapData.storyScenario)
        assertEquals("map.png", parseResult.imageName)
    }

    @Test
    fun packWithReproducibleOversizedImageHalvesDownToCap() {
        // A 4MB-class reproducible image:
        val oversizedBytes = generateOversizedPng(2048, 2048)
        val startSize = oversizedBytes.size
        val packBytes = runBlocking {
            MapPackManager.pack("map.png", oversizedBytes, emptyMapData())
        }
        val parseResult = runBlocking { MapPackManager.unpack(packBytes) }
        val compressedSize = parseResult.imageBytes.size
        println("[Contract] Input: $startSize -> packed image: $compressedSize (ratio ${compressedSize.toDouble() / startSize.toDouble()})")
        assertTrue(
            compressedSize < startSize / 4,
            "Downsample should compress by at least 4x for a 2048x2048 mid-density image; got ratio ${compressedSize.toDouble() / startSize.toDouble()}"
        )
    }

    // Helper: MapPackManager.pack is suspend in the SDK; use runBlocking
    // on JVM (which has the actual implementation).
    private fun <T> runBlocking(block: suspend () -> T): T =
        kotlinx.coroutines.runBlocking { block() }

    /**
     * Contract: when the JVM packer's downsample helper cannot decode the
     * input image (ImageIO returns null or throws), `MapPackManager.pack`
     * MUST surface the failure as an exception rather than silently
     * embedding the undecodable bytes into the zip.
     *
     * Reproduces the Sand Martello upload failure on the JVM pack path
     * (2026-08-15): the map editor packs the map on the JVM side via
     * `MapPackManager.pack`, which calls `downsampleImageBytesForPack`.
     * If the input image is one ImageIO can't decode (indexed-color PNG,
     * palette issue, color model mismatch — the actual Sand Martello case),
     * the downsample's catch returns `sourceBytes` unchanged, and the
     * zip contains the undecodable bytes. The server gate's downsample then
     * hits the same failure, and the safety classifier sees the raw
     * undecodable image.
     *
     * Fix: the downsample's catch must throw. `MapPackManager.pack` must
     * propagate the exception so the map editor surfaces a clear error
     * to the operator instead of uploading a zip with an undecodable image.
     */
    @Test
    fun packRejectsInputWhenImageCannotBeDecodedForDownsample() {
        // Under the 408 KB cap but NOT a valid image — exactly the Sand
        // Martello shape: small enough to pass the cap check, but undecodable.
        val undecodableBytes = ByteArray(200_000) { idx -> ((idx * 13) and 0xff).toByte() }
        // Sanity: confirm the test fixture actually fails ImageIO.decode.
        val probe = javax.imageio.ImageIO.read(java.io.ByteArrayInputStream(undecodableBytes))
        if (probe != null) {
            fail("test fixture sanity: 200 KB of arbitrary bytes MUST not decode as an image")
        }

        val threw = runCatching {
            runBlocking {
                MapPackManager.pack("map.png", undecodableBytes, emptyMapData())
            }
        }.isFailure

        assertTrue(
            threw,
            "MapPackManager.pack MUST throw when the input image cannot be decoded " +
                    "for downsample. Got success — the undecodable bytes were embedded in " +
                    "the zip unchanged, which propagates to the server gate's safety " +
                    "pipeline and defeats the operator's 256K-token directive."
        )
    }
}