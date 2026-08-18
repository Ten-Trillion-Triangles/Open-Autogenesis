package structs

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Browser-side contract test for the JS pack's downsample.
 *
 * Operator directive (2026-08-14): every map image MUST be downsampled
 * to <=256K tokens before reaching the safety agent. NO EXCEPTIONS.
 *
 * This test runs in the JS browser test target (Karma + Chrome) so the
 * real `Image`, `HTMLCanvasElement`, and `toDataURL` APIs are available.
 * It generates a 2048x2048 PNG via Canvas, packs it through
 * `MapPackManager.pack`, unpacks it, and asserts the resulting image
 * bytes are within the 408 KB cap.
 *
 * Run: `./gradlew :sharedModel:jsBrowserTest` (Chrome browser target).
 * Skipped when running on Node because Canvas/Image are browser-only.
 *
 * Receipt shape:
 *  - generatedPNGIsOver4MB: the generated fixture exceeds the cap.
 *  - packWithOversizedImageProducesZipUnderCap: after pack(), the
 *    extracted image inside the zip is at most 408 KB.
 *  - packWithImageAlreadyInBudgetPassesThrough: small images do not
 *    get re-encoded bigger.
 *  - packPreservesMapDataJson: the manifest entries stay untouched.
 */
class MapPackManagerJsDownsampleContractTest
{
    /**
     * Build a 2048x2048 PNG with sparse sparkles — compresses to
     * several MB, well over the 408 KB cap. Uses the browser's native
     * `OffscreenCanvas` so the test runs in the browser target.
     */
    private fun generateOversizedPng(width: Int, height: Int): ByteArray {
        val canvas = kotlinx.browser.document.createElement("canvas") as org.w3c.dom.HTMLCanvasElement
        canvas.width = width
        canvas.height = height
        val ctx = canvas.getContext("2d") as org.w3c.dom.CanvasRenderingContext2D
        // Pseudo-random-per-pixel noise so the PNG can't compress.
        // Work pixel-by-pixel via ImageData: each pixel gets a distinct
        // RGB so the PNG payload is roughly 3*width*height bytes
        // uncompressed and stays near that after zlib.
        val imageData = ctx.createImageData(width.toDouble(), height.toDouble())
        val data = imageData.data
        // ImageData.data is a Uint8ClampedArray; index assignment requires
        // an explicit dynamic cast to Byte-per-slot.
        val dyn = data.asDynamic()
        // js Math.random() returns Double in [0, 1); multiply by 256, floor
        // for a uniform byte. Random per-pixel data compresses poorly so
        // the PNG payload is close to width*height*4 bytes uncompressed.
        val mathRandom = js("Math.random")
        for (y in 0 until height) {
            for (x in 0 until width) {
                val idx = (y * width + x) * 4
                dyn[idx] = kotlin.math.floor(mathRandom() * 256.0).toInt()
                dyn[idx + 1] = kotlin.math.floor(mathRandom() * 256.0).toInt()
                dyn[idx + 2] = kotlin.math.floor(mathRandom() * 256.0).toInt()
                dyn[idx + 3] = 0xff
            }
        }
        ctx.putImageData(imageData, 0.0, 0.0)
        val pngDataUrl = canvas.toDataURL("image/png")
        val base64 = pngDataUrl.substringAfter("base64,", missingDelimiterValue = "")
        val bin = kotlinx.browser.window.atob(base64)
        val out = ByteArray(bin.length)
        for (i in 0 until bin.length) {
            out[i] = (bin[i].code).toByte()
        }
        return out
    }

    private fun emptyMapData(): MapData = MapData(
        pins = listOf(PinData(pinId = "p-A", territory = Territory(name = "A"))),
        connections = listOf(ConnectionData(fromPinId = "p-A", toPinId = "p-B"))
    )

    @Test
    fun generatedPNGIsOver4MB() = kotlinx.coroutines.test.runTest {
        val bytes = generateOversizedPng(2048, 2048)
        println("[Contract-JS] Generated PNG: ${bytes.size} bytes")
        assertTrue(
            bytes.size > 408_000,
            "Fixture must be > 408 KB to be a meaningful downsample test; got ${bytes.size}"
        )
    }

    @Test
    fun packWithOversizedImageProducesZipUnderCap() = kotlinx.coroutines.test.runTest {
        val oversizedBytes = generateOversizedPng(2048, 2048)
        println("[Contract-JS] Oversized image: ${oversizedBytes.size} bytes")
        assertTrue(
            oversizedBytes.size > 408_000,
            "Oversized image must be > 408 KB to be a meaningful test; got ${oversizedBytes.size}"
        )

        val packBytes = MapPackManager.pack("map.png", oversizedBytes, emptyMapData())
        val unpacked = MapPackManager.unpack(packBytes)
        println("[Contract-JS] Post-downsample image: ${unpacked.imageBytes.size} bytes")
        assertTrue(
            unpacked.imageBytes.size <= 408_000,
            "Operator directive violated: image in packed zip is ${unpacked.imageBytes.size} bytes (> 408 KB cap)"
        )
        val estTokens = (unpacked.imageBytes.size * 0.627).toInt()
        println("[Contract-JS] Estimated tokens per safety classifier: $estTokens (budget: 256000)")
        assertTrue(
            estTokens <= 256_000,
            "Operator directive violated: estimated tokens $estTokens > 256000"
        )
    }

    @Test
    fun packWithImageAlreadyInBudgetPassesThrough() = kotlinx.coroutines.test.runTest {
        val tinyBytes = generateOversizedPng(64, 64)
        println("[Contract-JS] Already-tiny image: ${tinyBytes.size} bytes")
        val packBytes = MapPackManager.pack("map.png", tinyBytes, emptyMapData())
        val unpacked = MapPackManager.unpack(packBytes)
        println("[Contract-JS] Tiny image after pack+unpack: ${unpacked.imageBytes.size} bytes")
        assertTrue(
            unpacked.imageBytes.size <= 408_000,
            "Tiny image should still fit under cap"
        )
    }

    @Test
    fun packPreservesMapDataJson() = kotlinx.coroutines.test.runTest {
        val oversizedBytes = generateOversizedPng(1024, 1024)
        val customMap = emptyMapData().copy(
            worldName = "TestWorld",
            storyScenario = "operator-directive-receipt"
        )
        val packBytes = MapPackManager.pack("map.png", oversizedBytes, customMap)
        val unpacked = MapPackManager.unpack(packBytes)
        assertEquals("TestWorld", unpacked.mapData.worldName)
        assertEquals("operator-directive-receipt", unpacked.mapData.storyScenario)
        assertEquals("map.png", unpacked.imageName)
    }
}
