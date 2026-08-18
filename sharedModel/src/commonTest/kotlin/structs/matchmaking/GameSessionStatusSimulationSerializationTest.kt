package structs.matchmaking

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals

class GameSessionStatusSimulationSerializationTest
{
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }

    @Test
    fun `round-trip preserves simulation game type`()
    {
        val status = GameSessionStatus(gameType = GameType.SIMULATION)
        val encoded = json.encodeToString(GameSessionStatus.serializer(), status)
        val decoded = json.decodeFromString(GameSessionStatus.serializer(), encoded)

        assertEquals(GameType.SIMULATION, decoded.gameType)
    }

    @Test
    fun `round-trip preserves simulationHumanPlayerNames and isSimulationMode`()
    {
        val status = GameSessionStatus(
            gameType = GameType.SIMULATION,
            simulationHumanPlayerNames = listOf("Alpha", "Beta"),
            isSimulationMode = true
        )
        val encoded = json.encodeToString(GameSessionStatus.serializer(), status)
        val decoded = json.decodeFromString(GameSessionStatus.serializer(), encoded)

        assertEquals(GameType.SIMULATION, decoded.gameType)
        assertEquals(listOf("Alpha", "Beta"), decoded.simulationHumanPlayerNames)
        assertEquals(true, decoded.isSimulationMode)
    }

    @Test
    fun `defaults to empty list and false for non-simulation sessions`()
    {
        val status = GameSessionStatus()
        val encoded = json.encodeToString(GameSessionStatus.serializer(), status)
        val decoded = json.decodeFromString(GameSessionStatus.serializer(), encoded)

        assertEquals(emptyList(), decoded.simulationHumanPlayerNames)
        assertEquals(false, decoded.isSimulationMode)
    }
}
