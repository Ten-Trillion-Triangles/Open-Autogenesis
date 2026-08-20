package org.ttt.autogenesis.audio

import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString
import org.ttt.autogenesis.network.RpcJson
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Wire-format contract for the music selector RPC.
 *
 * The picker emits [AudioMusicSchedule] and the runner consumes it; both sides
 * run the same [RpcJson] codec. These tests pin the field names and types so
 * a refactor on one side cannot silently break the other.
 */
class MusicDecisionSerializationTest
{
    @Test
    fun turnContext_roundTripsThroughJson()
    {
        val original = TurnContext(
            actorName = "Alice",
            roundNumber = 3,
            turnOrderIndex = 1,
            isFirstTurn = false,
            actorIsNemesisOrElderGod = false,
            canWinInNext4Rounds = true,
            currentlyPlayingMusicIds = listOf("obj-1", "obj-2")
        )
        val encoded = RpcJson.encodeToString(TurnContext.serializer(), original)
        val decoded = RpcJson.decodeFromString(TurnContext.serializer(), encoded)
        assertEquals(original, decoded)
    }

    @Test
    fun musicDecision_roundTripsThroughJson()
    {
        val original = MusicDecision(
            toPlay = listOf(
                AudioObject(
                    id = "obj-a",
                    resourceName = "Initial Conditions wet 1",
                    channelId = "Music",
                    volume = 1.0f,
                    fadeInDurationMs = 2000L,
                    loop = true
                )
            ),
            toFadeOut = listOf("obj-1", "obj-2"),
            fadeOutDurationMs = 2000L
        )
        val encoded = RpcJson.encodeToString(MusicDecision.serializer(), original)
        val decoded = RpcJson.decodeFromString(MusicDecision.serializer(), encoded)
        assertEquals(original, decoded)
    }

    @Test
    fun audioMusicSchedule_roundTripsThroughJson()
    {
        val original = AudioMusicSchedule(
            decision = MusicDecision(
                toPlay = listOf(
                    AudioObject(
                        id = "obj-a",
                        resourceName = "D-Track 1",
                        channelId = "Music"
                    ),
                    AudioObject(
                        id = "obj-b",
                        resourceName = "Melody-Etnahta",
                        channelId = "Music"
                    )
                ),
                toFadeOut = listOf("obj-old-1"),
                fadeOutDurationMs = 2000L
            ),
            serverTimestampMs = 1_700_000_000_000L,
            currentServerFrame = 44100L,
            serverSampleRate = 44100f
        )
        val encoded = RpcJson.encodeToString(AudioMusicSchedule.serializer(), original)
        val decoded = RpcJson.decodeFromString(AudioMusicSchedule.serializer(), encoded)

        assertEquals(original.decision.toFadeOut, decoded.decision.toFadeOut)
        assertEquals(original.decision.fadeOutDurationMs, decoded.decision.fadeOutDurationMs)
        assertEquals(original.decision.toPlay.size, decoded.decision.toPlay.size)
        assertEquals(
            original.decision.toPlay[0].resourceName,
            decoded.decision.toPlay[0].resourceName
        )
        assertEquals(
            original.decision.toPlay[1].resourceName,
            decoded.decision.toPlay[1].resourceName
        )
        assertEquals(original.serverTimestampMs, decoded.serverTimestampMs)
        assertEquals(original.currentServerFrame, decoded.currentServerFrame)
        assertEquals(original.serverSampleRate, decoded.serverSampleRate)
    }

    @Test
    fun musicDecision_toPlay_listPreservesOrder()
    {
        // Order matters for the runner — the runner plays toPlay[0] first.
        // The RPC payload must not silently reorder them.
        val decision = MusicDecision(
            toPlay = listOf(
                AudioObject(id = "a", resourceName = "D-Track 1", channelId = "Music"),
                AudioObject(id = "b", resourceName = "Melody-Etnahta", channelId = "Music"),
                AudioObject(id = "c", resourceName = "R-Track 1", channelId = "Music"),
                AudioObject(id = "d", resourceName = "Harmony-1", channelId = "Music")
            ),
            toFadeOut = emptyList(),
            fadeOutDurationMs = 2000L
        )
        val encoded = RpcJson.encodeToString(MusicDecision.serializer(), decision)
        val decoded = RpcJson.decodeFromString(MusicDecision.serializer(), encoded)
        val ids = decoded.toPlay.map { it.id }
        assertEquals(listOf("a", "b", "c", "d"), ids, "toPlay order must be preserved across the wire")
    }

    @Test
    fun musicCategory_serializesAsStringName()
    {
        val encoded = Json.encodeToString(MusicCategory.serializer(), MusicCategory.Nemesis)
        assertTrue("Nemesis" in encoded, "enum should serialize as name string, got: $encoded")
    }

    // ─── onTrackEnd: pre-picked "play after initial conditions ends" batch ─

    @Test
    fun decision_withOnTrackEnd_roundTripsThroughJson()
    {
        val original = MusicDecision(
            toPlay = listOf(
                AudioObject(id = "init-1", resourceName = "Initial Conditions wet 1", channelId = "Start")
            ),
            toFadeOut = listOf("obj-1"),
            fadeOutDurationMs = 2000L,
            onTrackEnd = listOf(
                AudioObject(id = "d-1", resourceName = "D-Track 1", channelId = "Drone"),
                AudioObject(id = "m-1", resourceName = "Melody-Etnahta", channelId = "Melody"),
                AudioObject(id = "r-1", resourceName = "R-Track 1", channelId = "Rhythm"),
                AudioObject(id = "h-1", resourceName = "Harmony-1", channelId = "Harmony")
            )
        )
        val encoded = RpcJson.encodeToString(MusicDecision.serializer(), original)
        val decoded = RpcJson.decodeFromString(MusicDecision.serializer(), encoded)
        assertEquals(original, decoded)
        // Field must be encoded as "onTrackEnd" on the wire so the client
        // can pick it up — pin the name to catch a future rename drift.
        assertTrue("onTrackEnd" in encoded, "onTrackEnd must be encoded on the wire when non-null — got: $encoded")
        assertEquals(4, decoded.onTrackEnd?.size)
        assertEquals(
            listOf("d-1", "m-1", "r-1", "h-1"),
            decoded.onTrackEnd?.map { it.id }
        )
    }

    @Test
    fun decision_withoutOnTrackEnd_omitsFieldFromJson()
    {
        // Default-null onTrackEnd must NOT appear in the wire format for
        // non-initial decisions (rule 2 / rule 3 / rule 4) so the payload
        // stays backward-compatible with clients that pre-date the field.
        val original = MusicDecision(
            toPlay = listOf(
                AudioObject(id = "obj-a", resourceName = "D-Track 1", channelId = "Music")
            ),
            toFadeOut = emptyList()
        )
        val encoded = RpcJson.encodeToString(MusicDecision.serializer(), original)
        assertFalse(
            "onTrackEnd" in encoded,
            "onTrackEnd must be omitted from JSON when null — got: $encoded"
        )
        val decoded = RpcJson.decodeFromString(MusicDecision.serializer(), encoded)
        assertNull(decoded.onTrackEnd)
        assertEquals(original, decoded)
    }
}