package network

import agent.builders.MapSafetyPayload
import com.TTT.Pipe.MultimodalContent
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.ttt.autogenesis.network.MapUploadGateResponse
import org.ttt.autogenesis.network.MapUploadRequest
import org.ttt.autogenesis.network.RpcCallContext
import org.ttt.autogenesis.network.RpcMessage
import org.ttt.autogenesis.serverextend.RestPlayerConnectionManager
import org.ttt.autogenesis.serverextend.RestPlayerSession
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Regression coverage for the catalogue userId-resolution contract.
 *
 * Bug (2026-08-13):
 *   After a successful map upload, the Collection overlay's Maps tab stays
 *   empty — even after a manual reload of the overlay — and the player has
 *   no way to find their uploaded map from the UI. Server-side logs show
 *   the catalogue write succeeded, but the list returns no entries.
 *
 * Root cause:
 *   The map-upload gate's `uploadMapGate` handler derives the storage
 *   `userId` from `context.connectionId` — the SSE/REST connection id
 *   (e.g. `rest-client-979835631`). The web client's `listPlayerMaps`
 *   RPC sends `AccelByteEnv.userId` — the canonical AccelByte UUID
 *   (e.g. the test-fixture uuid `00000000-0000-0000-0000-000000000001`). The catalogue is keyed
 *   by userId; save and list therefore live in two different partitions
 *   and the list always returns empty. The bug is documented in the
 *   RestPlayerSession KDoc (the gate's `playerId` is a session-local
 *   identifier; the catalogue is scoped by AccelByte userId) but the
 *   gate has not yet been wired to honour it.
 *
 * Fix contract (operator directive):
 *   The gate MUST resolve `accelbyteId` from the live `RestPlayerSession`
 *   (registered by the SSE handler when the player connected with an
 *   `accelbyteId` query parameter) and use that resolved id as the
 *   storage `userId` when calling `MapUploadGateStorage.savePack`.
 *   When the session has no `accelbyteId` (legacy curl probes, test
 *   rigs, the `MapUploadSafetyBilling.resolveAccelByteId` fallback
 *   chain), the gate falls back to the connectionId so existing
 *   test seams still work.
 *
 * The bug surfaces only when:
 *   1. Save succeeds.
 *   2. The session has a non-blank `accelbyteId` distinct from the
 *      SSE connectionId.
 *
 * Tests pinning the contract:
 *   - [catalogWrittenUnderAccelbyteId_resolvesAccelbyteIdForSavePack]
 *     — RED before fix: gate passes `playerId` (connection id) to
 *     `savePack`; the real `MapStorageProxy.savePlayerMap` then calls
 *     `PlayerMapRepository.addEntry(userId = connectionId, ...)`.
 *     When the session has accelbyteId set, the catalogue ends up
 *     keyed by connectionId; the client's list path (which uses
 *     AccelByteEnv.userId = accelbyteId) sees an empty list.
 *
 *   - [catalogWrittenUnderConnectionId_fallsBackWhenNoAccelbyteId]
 *     — RED: NOT broken. The fallback path is already correct. This
 *     test pins the boundary so a future refactor can't accidentally
 *     drop the legacy-curl-probe fallback.
 *
 *   - [catalogWrittenUnderAccelbyteId_dedupesByNameWithinTheSameUser]
 *     — RED: the dedupe-by-name step inside MapStorageProxy looks up
 *     collision entries under the same `userId`. If save and lookup
 *     use different userIds (current bug), the dedupe is silently
 *     skipped and two same-name entries can exist. After the fix,
 *     second write under the same name replaces the first.
 */
class MapUploadGateCatalogueUserIdTest
{
    @Before
    fun resetBefore()
    {
        MapUploadGate.resetForTest()
        MapUploadGateStorage.resetForTest()
        MapUploadSuccessHandlers.resetForTest()
        MapUploadErrorHandlers.resetForTest()
        maps.PlayerMapRepository.clearForTests()
    }

    @After
    fun resetAfter()
    {
        MapUploadGate.resetForTest()
        MapUploadGateStorage.resetForTest()
        MapUploadSuccessHandlers.resetForTest()
        MapUploadErrorHandlers.resetForTest()
        maps.PlayerMapRepository.clearForTests()
    }

    private fun populatedMapData(): structs.MapData = structs.MapData(
        pins = listOf(
            structs.PinData(pinId = "p-A", territory = structs.Territory(name = "A"))
        ),
        connections = listOf(
            structs.ConnectionData(fromPinId = "p-A", toPinId = "p-B")
        )
    )

    /**
     * Build a real zipped pack via [MapUploadGateTestFixtures] so the
     * gate's optional `unpack` step is exercised end-to-end. We also
     * pin the per-test fake safety runner to skip the LLM call.
     */
    private suspend fun runGateUpload(
        playerId: String,
        mapName: String,
        accelbyteId: String,
        captureSaver: ((RpcCallContext, structs.rpcRequests.SavePlayerMapRequest) -> Boolean)? = null
    ): MapUploadGateResponse
    {
        MapUploadGate.fakeUnpacker = { _ ->
            MapSafetyPayload(
                imageBytes = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47),
                mapData = populatedMapData()
            )
        }
        // The fakeDownsampler seam must be set so the gate's pre-flight
        // doesn't try to decode the PNG-magic-only fixture (which would
        // throw — that's the contract enforced by
        // MapUploadGateDownsamplePreFlightTest). Returning the input
        // unchanged lets the rest of the gate path exercise the
        // catalogue userId-resolution contract under test.
        MapUploadGate.fakeDownsampler = { bytes -> bytes }
        MapUploadGate.fakeSafetyRunner = { _, _ ->
            MultimodalContent(text = "{\"isAllowed\": true, \"reason\": \"\"}")
        }
        if (captureSaver != null)
        {
            MapUploadGateStorage.fakeSaver = { ctx, request -> captureSaver(ctx, request) }
        }
        else
        {
            MapUploadGateStorage.fakeSaver = { _, _ -> true }
        }

        val manager = RestPlayerConnectionManager()
        MapUploadSuccessHandlers.registerConnectionManager(manager)
        // RestPlayerConnectionManager.register is suspend; call it
        // directly from this suspend helper (the tests run inside
        // runBlocking via the @Test wrappers below).
        manager.register(playerId, accelbyteId = accelbyteId)

        val request = MapUploadRequest(
            mapPackBytes = byteArrayOf(1, 2, 3, 4),
            mapName = mapName
        )
        return MapUploadGate.uploadMapGate(
            RpcCallContext(
                connectionId = playerId,
                metadata = emptyMap(),
                sender = { _: RpcMessage -> }
            ),
            request
        )
    }

    /**
     * RED before fix:
     *   When the session has a non-blank accelbyteId distinct from the
     *   SSE playerId, the gate must resolve accelbyteId from the live
     *   RestPlayerSession and pass it to savePack as the canonical
     *   storage userId. Today the gate passes playerId (the
     *   connectionId) — same partition as the client's listUserId
     *   only when accelbyteId is blank.
     *
     * GREEN after fix:
     *   The captured savePack call's request.userId equals
     *   the test-fixture accelbyteId
     *   (`00000000-0000-0000-0000-000000000001`), not
     *   `"rest-client-<some-integer>"` (the connectionId). The
     *   follow-on list call hits the right partition.
     */
    @Test
    fun catalogWrittenUnderAccelbyteId_resolvesAccelbyteIdForSavePack(): Unit = runBlocking {
        val capturedUserIds = mutableListOf<String>()
        val playerId = "rest-client-979835631"
        val accelbyteId = "00000000-0000-0000-0000-000000000001"

        val response = runGateUpload(
            playerId = playerId,
            mapName = "Probe Map",
            accelbyteId = accelbyteId,
            captureSaver = { _, request ->
                capturedUserIds.add(request.userId)
                true
            }
        )

        assertEquals(true, response.accepted,
            "safety-pass + save-success must return accepted=true")
        assertTrue(capturedUserIds.isNotEmpty(),
            "savePack must have been invoked at least once")
        val savedUnder = capturedUserIds.first()
        assertEquals(
            accelbyteId, savedUnder,
            "savePack must receive the resolved accelbyteId, NOT the SSE " +
                    "connectionId. Got userId='$savedUnder' for session " +
                    "playerId='$playerId' accelbyteId='$accelbyteId'. " +
                    "If this assertion fails the catalogue is keyed by the " +
                    "wrong identifier — the client's listPlayerMaps (which " +
                    "sends AccelByteEnv.userId = '$accelbyteId') returns " +
                    "empty for userId='$savedUnder', and the user can't " +
                    "find their uploaded map from the Collection overlay."
        )
    }

    /**
     * RED before fix:
     *   Not broken — pinned as a boundary test. Sessions without an
     *   accelbyteId (legacy curl probes, test rigs, the dev-mode
     *   skipLogin flow when the playerId is NOT mapped to an
     *   AccelByte UUID) must still persist under the connectionId.
     *   Otherwise the legacy path breaks.
     *
     * GREEN today and after the fix.
     */
    @Test
    fun catalogWrittenUnderConnectionId_fallsBackWhenNoAccelbyteId(): Unit = runBlocking {
        val capturedUserIds = mutableListOf<String>()
        val playerId = "rest-client-12345"

        // accelbyteId = "" simulates the legacy-session case.
        val response = runGateUpload(
            playerId = playerId,
            mapName = "Legacy Probe",
            accelbyteId = "",
            captureSaver = { _, request ->
                capturedUserIds.add(request.userId)
                true
            }
        )

        assertEquals(true, response.accepted)
        assertTrue(capturedUserIds.isNotEmpty())
        assertEquals(
            playerId, capturedUserIds.first(),
            "savePack must receive the connectionId (playerId) as the " +
                    "fallback storage userId when no accelbyteId is " +
                    "registered against the live session. This pins the " +
                    "boundary so the legacy-curl-probe and skipLogin " +
                    "paths keep working after the fix."
        )
    }

    /**
     * RED before fix:
     *   The dedupe-by-name step inside MapStorageProxy.savePlayerMap
     *   looks up the user's existing catalogue to find a name collision
     *   (`collision = PlayerMapRepository.findByName(request.userId, ...)`).
     *   Today the gate passes playerId (connectionId) to savePack, so
     *   the dedupe runs against the session-local partition. The
     *   follow-on list call hits the accelbyteId partition, where no
     *   collision exists. Net effect: re-uploading a map with the
     *   same name lands as TWO catalogue entries under the user's
     *   accelbyteId — the second does not replace the first.
     *
     * GREEN after fix: the gate resolves accelbyteId for savePack, so
     *   both writes AND dedupe lookups happen under the same key.
     *   The second write replaces the first (collision mapId is
     *   different from the just-minted UUID each time the gate runs).
     */
    @Test
    fun catalogWrittenUnderAccelbyteId_dedupesByNameWithinTheSameUser(): Unit = runBlocking {
        val playerId = "rest-client-catalogue-dedupe"
        val accelbyteId = "00000000-0000-0000-0000-000000000001"

        // First upload — capture what savePack received.
        val firstSaved = mutableListOf<String>()
        val r1 = runGateUpload(
            playerId = playerId,
            mapName = "Same Name",
            accelbyteId = accelbyteId,
            captureSaver = { _, request ->
                firstSaved.add(request.userId)
                true
            }
        )
        assertEquals(true, r1.accepted)
        assertEquals(accelbyteId, firstSaved.first(),
            "first save must persist under accelbyteId (RED if " +
                    "the gate still uses connectionId)")

        // Second upload with the SAME map name — also capture.
        val secondSaved = mutableListOf<String>()
        val r2 = runGateUpload(
            playerId = playerId,
            mapName = "Same Name",
            accelbyteId = accelbyteId,
            captureSaver = { _, request ->
                secondSaved.add(request.userId)
                true
            }
        )
        assertEquals(true, r2.accepted)
        assertEquals(accelbyteId, secondSaved.first(),
            "second save must persist under accelbyteId (RED if " +
                    "the gate still uses connectionId)")
        // The two mapIds are different (gate mints a UUID per upload)
        // — this is by design. The dedupe-and-replace flow ONLY fires
        // when savePack goes through the real MapStorageProxy, not
        // through the fakeSaver. End-to-end coverage of the dedupe
        // itself lives in MapStorageProxyTest (the in-memory
        // PlayerMapRepository is asserted there).
    }
}