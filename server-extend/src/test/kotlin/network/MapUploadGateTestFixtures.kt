package network

import structs.MapData
import structs.MapPackManager

/**
 * Test fixtures for the [MapUploadGate] end-to-end harness.
 *
 * Wraps the real [MapPackManager.pack] so the harness exercises the actual
 * zip + JSON wire format the production client packs into a `MapUploadRequest`.
 * No fakes on the format side — every byte the harness sends is byte-for-byte
 * what a real client would send, and every byte the gate's `MapPackManager.unpack`
 * parses came from the matching production `pack` call.
 *
 * Keeping the helper separate from the test file keeps the test bodies
 * readable; both helper functions are stateless and safe to call from
 * multiple tests in the same class.
 */
object MapUploadGateTestFixtures
{
    /**
     * The minimal `MapData` shape `MapPackManager.pack` will accept. Only
     * the two `List<...>` fields have no defaults; everything else falls
     * back to its declared default. The `worldName` is plumbed through
     * the request map name via the harness so the unpack round-trip is
     * observable in the gate's pipeline trace.
     */
    fun emptyMapData(worldName: String = "Test World"): MapData = MapData(
        pins = emptyList(),
        connections = emptyList(),
        worldName = worldName
    )

    /**
     * Minimal non-empty MapData: one pin + one connection. Used by
     * tests that need to satisfy the gate's content-validation check
     * (which rejects packs with zero pins AND zero connections BEFORE
     * routing the safety pipeline). Empty packs were rejected by the
     * 2026-08-12 fail-fast operator directive; downstream tests of
     * the safety/save/downsample flow must use this populated fixture.
     */
    fun populatedMapData(worldName: String = "Test World"): MapData = MapData(
        pins = listOf(
            structs.PinData(
                pinId = "p-A",
                territory = structs.Territory(name = "Alpha")
            )
        ),
        connections = listOf(
            structs.ConnectionData(fromPinId = "p-A", toPinId = "p-B")
        ),
        worldName = worldName
    )

    /**
     * Builds a real zipped map pack with [imageName] inside the zip and
     * the supplied [imageBytes] as the image entry. Delegates straight to
     * `MapPackManager.pack` — no shortcut, no in-memory shortcut. The
     * resulting bytes are what `MapUploadGate.uploadMapGate` will hand to
     * `MapPackManager.unpack` on the server side, which is the round-trip
     * the harness verifies.
     *
     * @param mapName Display name echoed into the gate response / notification.
     * @param worldName World-name field on the embedded `MapData`. Lives in
     *   the zip's `map.json` entry; the content-safety pipe inspects it.
     * @param imageName Entry name inside the zip for the image (typically
     *   the filename; default `map.png`).
     * @param imageBytes Image bytes; the harness keeps these small so the
     *   downsample pre-flight stays in the no-op fast path.
     */
    suspend fun buildPackBytes(
        mapName: String,
        worldName: String = "Test World",
        imageName: String = "map.png",
        imageBytes: ByteArray = SMALL_PNG_BYTES,
        mapData: MapData = populatedMapData(worldName)
    ): ByteArray = MapPackManager.pack(
        imageName = imageName,
        imageBytes = imageBytes,
        mapData = mapData
    )

    /**
     * Pre-builds the image bytes + MapData the gate would have obtained
     * from calling `MapPackManager.unpack` itself. Returns the public-
     * API types so the fixture does not need to expose the production
     * gate's `internal` payload wrapper; the test callers reconstruct
     * the [MapSafetyPayload] (also `internal`, visible across the same
     * module) at the call site.
     */
    suspend fun preUnpack(packBytes: ByteArray): Pair<ByteArray, MapData>
    {
        val unpacked = MapPackManager.unpack(packBytes)
        return unpacked.imageBytes to unpacked.mapData
    }

    /**
     * A real, decodable 4×4 RGBA PNG (115 bytes). ImageIO reads it
     * successfully so `MapPackManager.pack` → `downsampleImageBytesForPack`
     * passes the small-image fast path and the gate's pre-flight
     * `downsampleImageBytes` decodes the bytes successfully too.
     *
     * Previously this fixture was a 33-byte PNG-header stub with a
     * placeholder CRC — ImageIO rejected it with
     * `IIOException: Error skipping PNG metadata`, which the JVM packer's
     * old `catch (e: Throwable) { return sourceBytes }` silently swallowed.
     * The 2026-08-15 Sand Martello fix made the catch throw, which surfaced
     * the silent-passthrough bug — but it also broke this fixture's
     * downstream tests because the pack harness fed undecodable bytes
     * through. Replacing the fixture with a verified, decoded-by-ImageIO
     * PNG keeps the existing tests green and prevents the fixture itself
     * from becoming a source of "undecodable bytes" false positives.
     *
     * Loaded from `src/test/resources/small-4x4.png` (a real PNG written by
     * the fixture-builder script). Resource loading avoids the byteArrayOf
     * literal corruption that hit earlier hand-crafted fixtures.
     *
     * Verified 2026-08-15: `kotlinc -script verify.kts` with
     * `ImageIO.read(small-4x4.png)` returns `BufferedImage 4x4 type=6`.
     */
    val SMALL_PNG_BYTES: ByteArray by lazy {
        val stream = MapUploadGateTestFixtures::class.java.getResourceAsStream("/small-4x4.png")
            ?: error("test resource /small-4x4.png not found on classpath")
        stream.use { it.readBytes() }
    }
}