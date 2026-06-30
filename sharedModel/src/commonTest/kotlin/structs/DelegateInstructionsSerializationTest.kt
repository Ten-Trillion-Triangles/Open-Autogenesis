package structs

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Round-trip serialization tests for the new `Player.delegateInstructions` field added
 * for the AI-takeover "delegate" feature.
 *
 * Backward-compat invariant: a Player JSON without a `delegateInstructions` field
 * (e.g. produced by an older client or older server) must deserialize cleanly into a
 * Player whose `delegateInstructions` defaults to `null`. This keeps existing world
 * snapshots readable after the upgrade.
 */
class DelegateInstructionsSerializationTest
{
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    @Test
    fun playerWithNullDelegateInstructionsRoundTrips()
    {
        val p = Player(name = "Tess", delegateInstructions = null)
        val encoded = json.encodeToString(Player.serializer(), p)

        assertTrue(
            encoded.contains("\"delegateInstructions\":null") || !encoded.contains("delegateInstructions"),
            "delegateInstructions should serialize as null when null, got: $encoded"
        )

        val decoded = json.decodeFromString(Player.serializer(), encoded)
        assertEquals("Tess", decoded.name)
        assertNull(decoded.delegateInstructions)
    }

    @Test
    fun playerWithDelegateInstructionsRoundTrips()
    {
        val guidance = "Prioritize the north front. Avoid attacking Qos."
        val p = Player(name = "Kara", delegateInstructions = guidance)
        val encoded = json.encodeToString(Player.serializer(), p)

        val decoded = json.decodeFromString(Player.serializer(), encoded)
        assertEquals("Kara", decoded.name)
        assertEquals(guidance, decoded.delegateInstructions)
    }

    @Test
    fun playerJsonMissingFieldDeserializesAsNull()
    {
        // Simulate an older world snapshot that does not have the field.
        // Drop the field if it is present to model a legacy payload.
        val full = Player(name = "Mira", delegateInstructions = "old")
        val fullEncoded = json.encodeToString(Player.serializer(), full)
        val legacy = fullEncoded.replace(Regex(",?\\s*\"delegateInstructions\":\"old\""), "")
        assertTrue(!legacy.contains("delegateInstructions"),
            "legacy payload should not contain delegateInstructions: $legacy")

        val decoded = json.decodeFromString(Player.serializer(), legacy)
        assertEquals("Mira", decoded.name)
        assertNull(decoded.delegateInstructions)
    }

    @Test
    fun setDelegateInstructionsRequestSerializerRoundTrip()
    {
        val req = structs.rpcRequests.SetDelegateInstructionsRequest(
            playerName = "Tess",
            instructions = "Hold the line."
        )
        val encoded = json.encodeToString(structs.rpcRequests.SetDelegateInstructionsRequest.serializer(), req)
        val decoded = json.decodeFromString(structs.rpcRequests.SetDelegateInstructionsRequest.serializer(), encoded)
        assertEquals("Tess", decoded.playerName)
        assertEquals("Hold the line.", decoded.instructions)
    }

    @Test
    fun setDelegateInstructionsRequestWithNullInstructionsRoundTrips()
    {
        val req = structs.rpcRequests.SetDelegateInstructionsRequest(
            playerName = "Tess",
            instructions = null
        )
        val encoded = json.encodeToString(structs.rpcRequests.SetDelegateInstructionsRequest.serializer(), req)
        val decoded = json.decodeFromString(structs.rpcRequests.SetDelegateInstructionsRequest.serializer(), encoded)
        assertEquals("Tess", decoded.playerName)
        assertNull(decoded.instructions)
    }

    @Test
    fun maxLengthConstantMatchesServerHandler()
    {
        // Guards against drift between the shared constant and any future server/UI cap.
        assertEquals(1500, structs.rpcRequests.DELEGATE_INSTRUCTIONS_MAX_LENGTH)
        assertNotNull(structs.rpcRequests.DELEGATE_INSTRUCTIONS_MAX_LENGTH)
    }
}
