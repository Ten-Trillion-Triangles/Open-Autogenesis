package structs.matchmaking

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Serialization round-trip test for [GameSessionStatus] covering
 * the Phase 2 addition of [GameSessionStatus.startedAtMillis].
 *
 * Default value (`0L`) must survive the round-trip so legacy
 * records that pre-date Phase 2 deserialize without forced
 * migration.
 */
class GameSessionStatusSerializationTest
{
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }

    @Test
    fun `round-trip with startedAtMillis set`()
    {
        val original = GameSessionStatus(
            sessionId = "abc-123",
            serverUrl = "127.0.0.1:9080",
            gameType = GameType.SINGLEPLAYER,
            aiOpponentCount = 2,
            aiOnly = false,
            resumeFromVfs = false,
            resumeUserId = "",
            startedAtMillis = 1_700_000_000_000L
        )
        val encoded = json.encodeToString(original)
        val decoded = json.decodeFromString<GameSessionStatus>(encoded)
        assertEquals(original, decoded, "round-trip must preserve every field")
        assertEquals(1_700_000_000_000L, decoded.startedAtMillis)
    }

    @Test
    fun `round-trip with default startedAtMillis (legacy compat)`()
    {
        // Constructed without specifying startedAtMillis — must
        // default to 0L, encode as 0, and decode back to 0L.
        val original = GameSessionStatus(sessionId = "legacy")
        val encoded = json.encodeToString(original)
        assertTrue(encoded.contains("\"startedAtMillis\":0"), "default value must be encoded: $encoded")
        val decoded = json.decodeFromString<GameSessionStatus>(encoded)
        assertEquals(0L, decoded.startedAtMillis, "legacy record round-trip must preserve default 0L")
    }

    @Test
    fun `JSON wire format includes startedAtMillis when non-default`()
    {
        // Pin the wire shape so the operator query surface does
        // not silently lose the field on a future serializer
        // refactor.
        val status = GameSessionStatus(sessionId = "wire-1", startedAtMillis = 42L)
        val encoded = json.encodeToString(status)
        assertTrue(encoded.contains("\"startedAtMillis\":42"), "startedAtMillis must appear in the JSON: $encoded")
    }
}
