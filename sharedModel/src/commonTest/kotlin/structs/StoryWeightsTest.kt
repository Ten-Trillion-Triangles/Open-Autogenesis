package structs

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.serialization.json.Json

/**
 * Tests for StoryWeights serialization behavior.
 */
class StoryWeightsTest
{
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }

    @Test
    fun `should serialize with default values correctly`()
    {
        val weights = StoryWeights()

        val jsonString = json.encodeToString(StoryWeights.serializer(), weights)
        val decoded = json.decodeFromString(StoryWeights.serializer(), jsonString)

        // Verify all fields are present and have correct default values
        assertEquals(25, decoded.geopolitics)
        assertEquals(25, decoded.absurdity)
        assertEquals(25, decoded.dreamlikeQualities)
        assertEquals(25, decoded.unexpectedTwists)
    }

    @Test
    fun `should deserialize default values correctly`()
    {
        val jsonString = """{"geopolitics":25,"absurdity":25,"dreamlikeQualities":25,"unexpectedTwists":25}"""

        val weights = json.decodeFromString(StoryWeights.serializer(), jsonString)

        assertEquals(25, weights.geopolitics)
        assertEquals(25, weights.absurdity)
        assertEquals(25, weights.dreamlikeQualities)
        assertEquals(25, weights.unexpectedTwists)
    }

    @Test
    fun `should serialize and deserialize custom values correctly`()
    {
        val weights = StoryWeights(
            geopolitics = 40,
            absurdity = 20,
            dreamlikeQualities = 15,
            unexpectedTwists = 25
        )

        val jsonString = json.encodeToString(StoryWeights.serializer(), weights)
        val decoded = json.decodeFromString(StoryWeights.serializer(), jsonString)

        assertEquals(40, decoded.geopolitics)
        assertEquals(20, decoded.absurdity)
        assertEquals(15, decoded.dreamlikeQualities)
        assertEquals(25, decoded.unexpectedTwists)
    }

    @Test
    fun `should preserve values through roundtrip serialization`()
    {
        val original = StoryWeights(geopolitics = 30, absurdity = 35, dreamlikeQualities = 20, unexpectedTwists = 15)

        val encoded = json.encodeToString(StoryWeights.serializer(), original)
        val decoded = json.decodeFromString(StoryWeights.serializer(), encoded)

        assertEquals(original.geopolitics, decoded.geopolitics)
        assertEquals(original.absurdity, decoded.absurdity)
        assertEquals(original.dreamlikeQualities, decoded.dreamlikeQualities)
        assertEquals(original.unexpectedTwists, decoded.unexpectedTwists)
    }
}
