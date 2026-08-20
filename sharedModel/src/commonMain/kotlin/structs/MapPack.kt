package structs

import kotlinx.serialization.Serializable
import kotlin.math.atan2
import kotlin.math.PI
import org.ttt.autogenesis.logging.LogCategory
import org.ttt.autogenesis.logging.Logger

@Serializable
data class MapPackData(
    val imageName: String,
    val mapData: MapData
)

@Serializable
enum class WriterSelectionStrategy {
    RANDOM,
    GEOPOLITICS_ONLY,
    RANDOM_UP_TO_FIVE,
    WEIGHTED,
    ORIGINAL
}

@Serializable
data class InjectableCriterion(
    val id: Int,
    val description: String,
    val category: String = "",
    val chancePercent: Int = 100
)

@Serializable
data class InjectableRule(
    val id: String,
    val description: String,
    val weight: Int = 1,
    val category: String = ""
)

@Serializable
data class RuleCategory(
    val name: String,
    val chancePercent: Int = 15,
    val rules: List<InjectableRule> = emptyList()
)

@Serializable
data class WritingAgentConfig(
    val ruleCategories: List<RuleCategory> = emptyList(),
    val alwaysApplyRules: List<String> = emptyList(),
    val authorPersonality: String = "",
    val selectionCriteria: List<InjectableCriterion> = emptyList(),
    val storyWeights: StoryWeights = StoryWeights(25, 25, 25, 25),
    val selectionStrategy: WriterSelectionStrategy = WriterSelectionStrategy.RANDOM,
    val authorEnabled: Boolean = true,
    val alwaysApplyRulesEnabled: Boolean = true,
    val guardrailsEnabled: Boolean = true,
    val writingInstructions: String = "",
    /**
     * Optional override for the narrative procedure block the writing agent injects into its
     * systemPrompt. When empty, the writing agent falls back to the verbatim hardcoded block
     * (see `defaultProcedureText` in `agent.builders.writingAgent`). When non-empty, the value
     * is injected verbatim in place of the hardcoded block and the user owns the entire literal
     * blob (including the `###PROCEDURE:` and `###OVERALL:` headers).
     *
     * Default-empty decodes correctly from old map packs via kotlinx.serialization default-value
     * semantics, so existing map packs load unchanged.
     */
    val procedure: String = "")

@Serializable
data class StoryWeights(
    var geopolitics: Int = 25,
    var absurdity: Int = 25,
    var dreamlikeQualities: Int = 25,
    var unexpectedTwists: Int = 25
)

@Serializable
data class MapData(
    val pins: List<PinData>,
    val connections: List<ConnectionData>,
    val worldName: String = "",
    val storyScenario: String = "",
    val author: String = "",
    val writingInstructions: String = "",
    val storyWeights: StoryWeights = StoryWeights(),
    val selectionStrategy: WriterSelectionStrategy = WriterSelectionStrategy.RANDOM,
    val authorEnabled: Boolean = true,
    val alwaysApplyRulesEnabled: Boolean = true,
    val guardrailsEnabled: Boolean = true,
    val writingAgentConfig: WritingAgentConfig = WritingAgentConfig()
)

@Serializable
data class PinData(
    val pinId: String,
    val territory: Territory
)

@Serializable
data class ConnectionData(
    val fromPinId: String,
    val toPinId: String
)

data class UnpackedMapPack(
    val imageName: String,
    val imageBytes: ByteArray,
    val mapData: MapData
)

expect object MapPackManager {
    suspend fun pack(imageName: String, imageBytes: ByteArray, mapData: MapData): ByteArray
    suspend fun unpack(packBytes: ByteArray): UnpackedMapPack
}

suspend fun loadWorldFromMapPack(packBytes: ByteArray): World {
    Logger.info(LogCategory.GENERAL, "MapPack: loadWorldFromMapPack invoked (packBytes=${packBytes.size})")
    val unpacked = MapPackManager.unpack(packBytes)
    Logger.debug(LogCategory.GENERAL, "MapPack: unpacked image='${unpacked.imageName}' (${unpacked.imageBytes.size} bytes)")
    val mapData = unpacked.mapData
    Logger.debug(LogCategory.GENERAL, "MapPack: metadata world='${mapData.worldName}' scenario='${mapData.storyScenario}' pins=${mapData.pins.size} connections=${mapData.connections.size}")

    //Bind pins for the game client. Then bind llm friendly border names for the game server.
    val territories = mapData.pins.map { it.territory }.toMutableList()
    
    reconstructBorders(mapData)
    territories.forEach { it.setTerritoryNamesForLlm() }
    initializeAllTerritoryThreats(territories)

    val adjacencySamples = territories
        .take(8)
        .map { tile -> "${tile.name}(borderLists=${listOf(tile.northBorders.size, tile.southBorders.size, tile.eastBorders.size, tile.westBorders.size, tile.northEastBorders.size, tile.northWestBorders.size, tile.southEastBorders.size, tile.southWestBorders.size).sum()}, names=${tile.adjacentTerritoryNames.size})" }
        .joinToString(" || ")
    Logger.debug(LogCategory.GENERAL, "MapPack: post-load adjacency samples (${adjacencySamples})")
    
    return World(
        name = mapData.worldName,
        storyScenario = mapData.storyScenario,
        mapTiles = territories
    )
}

fun reconstructBorders(mapData: MapData) {
    val pinMap = mapData.pins.associateBy { it.pinId }
    Logger.debug(LogCategory.GENERAL, "MapPack: reconstructBorders starting (${mapData.connections.size} connections)")
    
    mapData.connections.forEach { conn ->
        val fromPin = pinMap[conn.fromPinId]
        val toPin = pinMap[conn.toPinId]
        
        if(fromPin != null && toPin != null)
        {
            val dx = toPin.territory.xPos - fromPin.territory.xPos
            val dy = toPin.territory.yPos - fromPin.territory.yPos
            val angle = atan2(dy, dx) * 180 / PI
            
            val (myDirection, theirDirection) = when
            {
                angle >= -22.5 && angle < 22.5 -> "east" to "west"
                angle >= 22.5 && angle < 67.5 -> "southEast" to "northWest"
                angle >= 67.5 && angle < 112.5 -> "south" to "north"
                angle >= 112.5 && angle < 157.5 -> "southWest" to "northEast"
                angle >= 157.5 || angle < -157.5 -> "west" to "east"
                angle >= -157.5 && angle < -112.5 -> "northWest" to "southEast"
                angle >= -112.5 && angle < -67.5 -> "north" to "south"
                else -> "northEast" to "southWest"
            }
            
            val borderToTarget = Border(adjacentTerritory = toPin.territory)
            val borderToMe = Border(adjacentTerritory = fromPin.territory)

            when(myDirection)
            {
                "north" -> fromPin.territory.northBorders.add(borderToTarget)
                "south" -> fromPin.territory.southBorders.add(borderToTarget)
                "east" -> fromPin.territory.eastBorders.add(borderToTarget)
                "west" -> fromPin.territory.westBorders.add(borderToTarget)
                "northEast" -> fromPin.territory.northEastBorders.add(borderToTarget)
                "northWest" -> fromPin.territory.northWestBorders.add(borderToTarget)
                "southEast" -> fromPin.territory.southEastBorders.add(borderToTarget)
                "southWest" -> fromPin.territory.southWestBorders.add(borderToTarget)
            }
            
            when(theirDirection)
            {
                "north" -> toPin.territory.northBorders.add(borderToMe)
                "south" -> toPin.territory.southBorders.add(borderToMe)
                "east" -> toPin.territory.eastBorders.add(borderToMe)
                "west" -> toPin.territory.westBorders.add(borderToMe)
                "northEast" -> toPin.territory.northEastBorders.add(borderToMe)
                "northWest" -> toPin.territory.northWestBorders.add(borderToMe)
                "southEast" -> toPin.territory.southEastBorders.add(borderToMe)
                "southWest" -> toPin.territory.southWestBorders.add(borderToMe)
            }
            Logger.debug(LogCategory.GENERAL, "MapPack: connection '${conn.fromPinId}' -> '${conn.toPinId}' assigned directions $myDirection/$theirDirection")
        }
    }
    Logger.debug(LogCategory.GENERAL, "MapPack: reconstructBorders completed")
}