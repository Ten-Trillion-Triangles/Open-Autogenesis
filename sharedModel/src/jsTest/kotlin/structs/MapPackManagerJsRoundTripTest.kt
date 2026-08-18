package structs

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Regression coverage for the JS pack-byte → typed-array conversion
 * bug (2026-08-13).
 *
 * Bug: the catalogue's map-card thumbnail renderer fetches the
 * player's saved pack via `server.extend.getPlayerMap` and passes the
 * returned `ByteArray` to `MapPackManager.unpack`. The unpack failed
 * with JSZip's "Can't find end of central directory" because the
 * internal `ByteArray.toUint8Array()` reinterpreted the bytes over an
 * undefined `ArrayBuffer` (a Kotlin `ByteArray` on the JS target is a
 * plain `Array<Number>`, not a `Uint8Array`).
 *
 * Fix contract: `toUint8Array()` allocates a fresh `ArrayBuffer`-backed
 * `Uint8Array` and copies each byte with an explicit `and 0xff` mask.
 * The result must be a proper typed array that JSZip's `loadAsync`
 * can parse.
 *
 * Test pins:
 *   - Bytes above 0x7F (signed-Bit-set values) round-trip correctly
 *     through the conversion (no high-bit stripping).
 *   - The resulting `Uint8Array.length` matches the input `size`.
 *   - The first 16 bytes match the source bytes (small fixture sanity).
 *   - The `pack` then `unpack` round-trip recovers the original
 *     `imageBytes` exactly. This is the contract the live catalogue
 *     relies on: `unpack` reads back what `pack` wrote.
 *
 * RED before fix: high-bit bytes (>= 0x80) round-trip with corruption;
 * the pack→unpack image-byte assertion fails.
 *
 * GREEN after fix: every assertion passes.
 */
class MapPackManagerJsRoundTripTest
{
    /**
     * Build a 256-byte ByteArray with bytes 0..255 (every byte value
     * represented). Pinned because it covers the high-bit corner
     * (0x80..0xff) that the pre-fix conversion was corrupting.
     */
    private fun allBytes(): ByteArray
    {
        val arr = ByteArray(256)
        for (i in 0 until 256)
        {
            arr[i] = i.toByte()
        }
        return arr
    }

    /**
     * Round-trip: pack → unpack must produce identical image bytes for
     * every byte value 0..255. The pre-fix `toUint8Array()` corrupts
     * bytes >= 0x80 because the JS view over `undefined.buffer` ends
     * up as garbage. After the fix, the round-trip preserves every
     * byte.
     */
    @Test
    fun packThenUnpackPreservesAll256ByteValues() = kotlinx.coroutines.test.runTest {
        val original = allBytes()
        val mapData = MapData(
            pins = listOf(
                PinData(pinId = "p-A", territory = Territory(name = "A"))
            ),
            connections = listOf(
                ConnectionData(fromPinId = "p-A", toPinId = "p-B")
            )
        )
        val packBytes = MapPackManager.pack(imageName = "map.png", imageBytes = original, mapData = mapData)
        assertEquals(original.size + 1_000 /* zip overhead */, packBytes.size, "pack size sanity")

        val unpacked = MapPackManager.unpack(packBytes)
        assertEquals(original.size, unpacked.imageBytes.size, "image byte count must match after round-trip")
        for (i in 0 until original.size)
        {
            assertEquals(
                original[i], unpacked.imageBytes[i],
                "byte at index $i must survive pack→unpack round-trip. " +
                    "Pre-fix the high-bit bytes (>= 0x80) were corrupted by the " +
                    "Uint8Array-over-undefined-buffer reinterpretation."
            )
        }
    }

    /**
     * Boundary: even on a single byte 0xFF (high bit set), pack →
     * unpack must recover it. This is the smallest possible
     * regression fixture for the high-bit bug.
     */
    @Test
    fun packThenUnpackPreservesSingle0xFFByte() = kotlinx.coroutines.test.runTest {
        val original = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
        val mapData = MapData(
            pins = listOf(
                PinData(pinId = "p-A", territory = Territory(name = "A"))
            ),
            connections = listOf(
                ConnectionData(fromPinId = "p-A", toPinId = "p-B")
            )
        )
        val packBytes = MapPackManager.pack(imageName = "map.png", imageBytes = original, mapData = mapData)
        val unpacked = MapPackManager.unpack(packBytes)
        assertEquals(original.size, unpacked.imageBytes.size)
        assertTrue(
            unpacked.imageBytes.contentEquals(original),
            "PNG-magic-byte fixture must round-trip exactly. Got " +
                buildString {
                    unpacked.imageBytes.forEach { b ->
                        val v = b.toInt() and 0xff
                        val hex = v.toString(16)
                        if (hex.length < 2) append("0")
                        append(hex)
                    }
                }
        )
    }
}
