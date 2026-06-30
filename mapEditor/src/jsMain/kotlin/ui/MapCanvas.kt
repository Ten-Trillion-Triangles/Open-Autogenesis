package ui

import interfaces.WidgetInterface
import io.kvision.core.CssSize
import io.kvision.core.Position
import io.kvision.core.UNIT
import io.kvision.core.onClick
import io.kvision.html.Canvas
import io.kvision.html.Image
import io.kvision.html.canvas
import io.kvision.html.image
import io.kvision.panel.SimplePanel
import org.w3c.dom.HTMLElement
import org.w3c.dom.events.MouseEvent
import structs.Territory
import structs.ConnectionData
import structs.PinData
import structs.MapData
import structs.WritingAgentConfig
import structs.defaultWritingAgentConfig
import structs.StoryWeights
import structs.WriterSelectionStrategy
import structs.RuleCategory
import structs.InjectableRule
import structs.InjectableCriterion
import kotlin.js.JSON
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@JsModule("/kotlin/modules/img/Map-Us.png")
@JsNonModule
external val autogenesisTitlePng: dynamic

//Required to pass forward so we can switch our images with the main button.
data class CanvasImageData(
    var src: String,
    var alt: String = ""
)

/**
 * Interactive map canvas for creating and editing territory maps.
 * Allows placing pins, drawing connections, and managing territory data.
 *
 * @param imagePath The background image for the map canvas.
 */
class MapCanvas(var imagePath: dynamic = autogenesisTitlePng) : SimplePanel(), WidgetInterface
{
    var canvas: Canvas? = null
    var imageRef: Image? = null
    var svgContainer: io.kvision.html.CustomTag? = null

    var widgetName = ""
    var connectedPins = mutableListOf<Pin>()
    var worldName: String = ""
    var storyScenario: String = ""
    var author: String = "Nordold Trable"
    var writingInstructions: String = ""
    var writingAgentConfig: WritingAgentConfig = defaultWritingAgentConfig()

    init {
        width = CssSize(100, UNIT.perc)
        height = CssSize(100, UNIT.perc)
        position = Position.RELATIVE

        // Canvas layer - positioned above the background
        canvas = canvas {
            width = CssSize(100, UNIT.perc)
            height = CssSize(100, UNIT.perc)
            position = Position.RELATIVE
            zIndex = 2

            onClick { e ->
                val mouseEvent = e as MouseEvent
                val element = getElement() as? HTMLElement
                element?.let {
                    val xPercent = (mouseEvent.offsetX / it.offsetWidth) * 100
                    val yPercent = (mouseEvent.offsetY / it.offsetHeight) * 100
                    console.log("Clicked at: X=${xPercent}%, Y=${yPercent}%")
                    
                    // Create and add pin at clicked position
                    val pin = Pin(xPercent, yPercent)
                    pin.territory.xPos = xPercent
                    pin.territory.yPos = yPercent
                    connectedPins.add(pin)
                    this@MapCanvas.add(pin)
                    pin.selectPin()
                }
            }

            // Background image - positioned absolutely at the bottom layer
            imageRef = image(
                src = imagePath,
                alt = "Load a background to get started") {
                position = Position.ABSOLUTE
                left = CssSize(0, UNIT.px)
                top = CssSize(0, UNIT.px)
                width = CssSize(100, UNIT.perc)
                height = CssSize(100, UNIT.perc)
                zIndex = 1
            }
        }
        
        // SVG layer for drawing border lines
        svgContainer = svg {
            position = Position.ABSOLUTE
            left = CssSize(0, UNIT.px)
            top = CssSize(0, UNIT.px)
            width = CssSize(100, UNIT.perc)
            height = CssSize(100, UNIT.perc)
            zIndex = 5
            
            svgDefs {
                arrowMarker("arrowhead", "#00ff00")
            }
        }
    }


    /**
     * Updates the canvas background image from JSON data.
     *
     * @param data JSON string containing image source and alt text.
     */
    override fun updateDataInternal(data: String)
    {
        val canvasImage = JSON.parse<CanvasImageData>(data)
        imagePath = canvasImage.src
        imageRef?.src = canvasImage.src
        imageRef?.alt = canvasImage.alt
        refresh()
    }

    /**
     * Sets the widget label name.
     *
     * @param label The new label for this widget.
     */
    override fun setLabel(label: String)
    {
        widgetName = label
    }

    /**
     * Returns the current widget label.
     *
     * @return The widget label string.
     */
    override fun getLabel(): String
    {
        return widgetName
    }

    /**
     * Updates the background image to a new path.
     *
     * @param newPath The new image path or URL.
     */
    fun updateImage(newPath: dynamic)
    {
        imageRef?.src = newPath
        refresh()
    }

    /**
     * Draws a directional border line between two pins on the SVG layer.
     *
     * @param fromPin The source pin for the connection.
     * @param toPin The target pin for the connection.
     */
    fun drawBorderLine(fromPin: Pin, toPin: Pin)
    {
        console.log("drawBorderLine called: from ${fromPin.pinId} to ${toPin.pinId}")
        val key = "${fromPin.pinId}-${toPin.pinId}"
        removeBorderLine(fromPin, toPin)
        
        val x1 = fromPin.territory.xPos
        val y1 = fromPin.territory.yPos
        val x2 = toPin.territory.xPos
        val y2 = toPin.territory.yPos
        console.log("Line coordinates: ($x1%, $y1%) -> ($x2%, $y2%)")
        
        if(svgContainer == null)
        {
            console.log("ERROR: svgContainer is null!")
            return
        }
        
        console.log("Calling svgLine with key: $key")
        svgContainer?.svgLine(
            x1 = x1,
            y1 = y1,
            x2 = x2,
            y2 = y2,
            stroke = "#00ff00",
            strokeWidth = "2",
            markerEnd = "arrowhead",
            dataConnection = key
        )
        
        // Check how many lines exist in SVG
        svgContainer?.getElement()?.let { element ->
            val lineCount = element.querySelectorAll("line").length
            console.log("Total lines in SVG: $lineCount")
        }
        
        console.log("Line drawn successfully")
    }

    /**
     * Removes the border line between two pins from the SVG layer.
     *
     * @param fromPin The source pin of the connection.
     * @param toPin The target pin of the connection.
     */
    fun removeBorderLine(fromPin: Pin, toPin: Pin)
    {
        val key = "${fromPin.pinId}-${toPin.pinId}"
        svgContainer?.getElement()?.let { svgElement ->
            val line = svgElement.querySelector("[data-connection='$key']")
            line?.let { svgElement.removeChild(it) }
        }
    }

    /**
     * Removes all border lines connected to a specific pin.
     *
     * @param pin The pin whose connections should be removed.
     */
    fun removeAllLinesForPin(pin: Pin)
    {
        connectedPins.forEach { otherPin ->
            if(otherPin != pin)
            {
                removeBorderLine(pin, otherPin)
                removeBorderLine(otherPin, pin)
            }
        }
    }

    /**
     * Removes a pin from the canvas and cleans up all its connections.
     *
     * @param pin The pin to remove.
     */
    fun removePin(pin: Pin)
    {
        removeAllLinesForPin(pin)
        connectedPins.remove(pin)
        
        connectedPins.forEach { otherPin ->
            var totalRemoved = 0
            if(otherPin.territory.northBorders.removeAll { it.adjacentTerritory == pin.territory }) totalRemoved++
            if(otherPin.territory.southBorders.removeAll { it.adjacentTerritory == pin.territory }) totalRemoved++
            if(otherPin.territory.eastBorders.removeAll { it.adjacentTerritory == pin.territory }) totalRemoved++
            if(otherPin.territory.westBorders.removeAll { it.adjacentTerritory == pin.territory }) totalRemoved++
            if(otherPin.territory.northEastBorders.removeAll { it.adjacentTerritory == pin.territory }) totalRemoved++
            if(otherPin.territory.northWestBorders.removeAll { it.adjacentTerritory == pin.territory }) totalRemoved++
            if(otherPin.territory.southEastBorders.removeAll { it.adjacentTerritory == pin.territory }) totalRemoved++
            if(otherPin.territory.southWestBorders.removeAll { it.adjacentTerritory == pin.territory }) totalRemoved++
            
            if(totalRemoved > 0)
            {
                console.log("Removed $totalRemoved border(s) from ${otherPin.territory.name} referencing ${pin.territory.name}")
            }
        }
    }

    /**
     * Finds a pin by its territory name.
     *
     * @param name The territory name to search for.
     * @return The matching pin, or null if not found.
     */
    fun findPinByTerritoryName(name: String): Pin?
    {
        return connectedPins.firstOrNull { it.territory.name == name }
    }

    /**
     * Exports the current map state as [structs.MapData].
     *
     * @return MapData containing all pins and connections.
     */
    fun getMapData(): structs.MapData
    {
        val pinDataList = connectedPins.map { pin ->
            PinData(pin.pinId, pin.territory)
        }
        
        val connectionsList = mutableListOf<ConnectionData>()
        connectedPins.forEach { pin ->
            listOf(
                pin.territory.northBorders,
                pin.territory.southBorders,
                pin.territory.eastBorders,
                pin.territory.westBorders,
                pin.territory.northEastBorders,
                pin.territory.northWestBorders,
                pin.territory.southEastBorders,
                pin.territory.southWestBorders
            ).flatten().forEach { border ->
                val targetPin = connectedPins.find { it.territory == border.adjacentTerritory }
                if(targetPin != null)
                {
                    connectionsList.add(ConnectionData(pin.pinId, targetPin.pinId))
                }
            }
        }
        
        console.log("[MC] getMapData: writingAgentConfig.ruleCategories.size=${writingAgentConfig.ruleCategories.size}")
        writingAgentConfig.ruleCategories.forEachIndexed { index, cat ->
            console.log("[MC] getMapData: ruleCategory[$index] '${cat.name}': chancePercent=${cat.chancePercent}, rules=${cat.rules.size}")
        }
        return structs.MapData(
            pinDataList,
            connectionsList.distinct(),
            worldName,
            storyScenario,
            author,
            writingInstructions,
            writingAgentConfig.storyWeights,
            writingAgentConfig.selectionStrategy,
            writingAgentConfig.authorEnabled,
            writingAgentConfig.alwaysApplyRulesEnabled,
            writingAgentConfig.guardrailsEnabled,
            writingAgentConfig
        )
    }
    
    /**
     * Returns the current background image source URL.
     *
     * @return The image source string.
     */
    fun getImageSrc(): String
    {
        return imageRef?.src ?: imagePath.toString()
    }

    /**
     * Loads a complete map from an unpacked map pack.
     * Clears existing state, loads the image, creates pins, and reconstructs borders.
     *
     * @param unpacked The unpacked map pack data containing image and map state.
     */
    fun loadFromPack(unpacked: structs.UnpackedMapPack)
    {
        console.log("loadFromPack: Starting load...")
        console.log("Image name: ${unpacked.imageName}")
        console.log("Image bytes: ${unpacked.imageBytes.size}")
        console.log("Pins: ${unpacked.mapData.pins.size}")
        console.log("Connections: ${unpacked.mapData.connections.size}")
        
        // Load scenario data
        worldName = unpacked.mapData.worldName
        storyScenario = unpacked.mapData.storyScenario
        author = unpacked.mapData.author
        writingInstructions = unpacked.mapData.writingInstructions
        writingAgentConfig = unpacked.mapData.writingAgentConfig
        console.log("[MC] loadFromPack: writingAgentConfig.ruleCategories.size=${writingAgentConfig.ruleCategories.size}")
        writingAgentConfig.ruleCategories.forEachIndexed { index, cat ->
            console.log("[MC] loadFromPack: ruleCategory[$index] '${cat.name}': chancePercent=${cat.chancePercent}, rules=${cat.rules.size}")
        }
        console.log("Loaded scenario: worldName='$worldName', storyScenario length=${storyScenario.length}")
        console.log("Loaded writing settings: author='$author', writingInstructions length=${writingInstructions.length}")
        
        // Clear existing state
        connectedPins.toList().forEach { pin ->
            removeAllLinesForPin(pin)
            remove(pin)
        }
        connectedPins.clear()
        console.log("Cleared existing pins")
        
        // Create blob URL from image bytes
        val uint8Array = org.khronos.webgl.Uint8Array(unpacked.imageBytes.size)
        for (i in unpacked.imageBytes.indices) {
            uint8Array.asDynamic()[i] = unpacked.imageBytes[i]
        }
        val blob = org.w3c.files.Blob(arrayOf(uint8Array), org.w3c.files.BlobPropertyBag(type = "image/png"))
        val blobUrl = org.w3c.dom.url.URL.createObjectURL(blob)
        console.log("Created blob URL: $blobUrl")
        
        // Update image
        imageRef?.src = blobUrl
        imageRef?.alt = unpacked.imageName
        refresh()
        console.log("Updated image")
        
        // Create pins with their territories FIRST
        val pinMap = mutableMapOf<String, Pin>()
        unpacked.mapData.pins.forEach { pinData ->
            console.log("Creating pin: ${pinData.pinId} at (${pinData.territory.xPos}, ${pinData.territory.yPos})")
            val pin = Pin(pinData.territory.xPos, pinData.territory.yPos)
            pin.pinId = pinData.pinId
            
            // Copy all territory data
            pin.territory.name = pinData.territory.name
            pin.territory.type = pinData.territory.type
            pin.territory.description = pinData.territory.description
            pin.territory.ruler = pinData.territory.ruler
            pin.territory.resource = pinData.territory.resource
            pin.territory.size = pinData.territory.size
            pin.territory.pointValue = pinData.territory.pointValue
            pin.territory.northObstacleType = pinData.territory.northObstacleType
            pin.territory.southObstacleType = pinData.territory.southObstacleType
            pin.territory.eastObstacleType = pinData.territory.eastObstacleType
            pin.territory.westObstacleType = pinData.territory.westObstacleType
            pin.territory.northEastObstacleType = pinData.territory.northEastObstacleType
            pin.territory.northWestObstacleType = pinData.territory.northWestObstacleType
            pin.territory.southEastObstacleType = pinData.territory.southEastObstacleType
            pin.territory.southWestObstacleType = pinData.territory.southWestObstacleType
            pin.territory.isCaptured = pinData.territory.isCaptured
            pin.territory.isDestroyed = pinData.territory.isDestroyed
            
            connectedPins.add(pin)
            add(pin)
            pinMap[pinData.pinId] = pin
            console.log("Added pin: ${pin.pinId}")
        }
        console.log("Created ${pinMap.size} pins")
        
        // Now reconstruct borders using the actual pin territories
        console.log("Reconstructing borders...")
        unpacked.mapData.connections.forEach { conn ->
            val fromPin = pinMap[conn.fromPinId]
            val toPin = pinMap[conn.toPinId]
            if(fromPin != null && toPin != null)
            {
                // Calculate direction
                val dx = toPin.territory.xPos - fromPin.territory.xPos
                val dy = toPin.territory.yPos - fromPin.territory.yPos
                val angle = kotlin.math.atan2(dy, dx) * 180 / kotlin.math.PI
                
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
                
                val borderToTarget = structs.Border(adjacentTerritory = toPin.territory)
                val borderToMe = structs.Border(adjacentTerritory = fromPin.territory)
                
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
            }
        }
        console.log("Borders reconstructed")
        
        // Draw all border lines
        var linesDrawn = 0
        unpacked.mapData.connections.forEach { conn ->
            val fromPin = pinMap[conn.fromPinId]
            val toPin = pinMap[conn.toPinId]
            if(fromPin != null && toPin != null)
            {
                drawBorderLine(fromPin, toPin)
                linesDrawn++
            }
        }
        console.log("Drew $linesDrawn border lines")
        console.log("loadFromPack: Complete!")
    }

    /**
     * Serializes the current map state to JSON.
     *
     * @return JSON string containing all map data.
     */
    fun saveMapData(): String
    {
        val pinDataList = connectedPins.map { pin ->
            PinData(pin.pinId, pin.territory)
        }
        
        val connectionsList = mutableListOf<ConnectionData>()
        connectedPins.forEach { pin ->
            // Collect all connections from this pin
            pin.territory.northBorders.forEach { border ->
                val targetPin = connectedPins.find { it.territory == border.adjacentTerritory }
                if(targetPin != null)
                {
                    connectionsList.add(ConnectionData(pin.pinId, targetPin.pinId))
                }
            }
            pin.territory.southBorders.forEach { border ->
                val targetPin = connectedPins.find { it.territory == border.adjacentTerritory }
                if(targetPin != null)
                {
                    connectionsList.add(ConnectionData(pin.pinId, targetPin.pinId))
                }
            }
            pin.territory.eastBorders.forEach { border ->
                val targetPin = connectedPins.find { it.territory == border.adjacentTerritory }
                if(targetPin != null)
                {
                    connectionsList.add(ConnectionData(pin.pinId, targetPin.pinId))
                }
            }
            pin.territory.westBorders.forEach { border ->
                val targetPin = connectedPins.find { it.territory == border.adjacentTerritory }
                if(targetPin != null)
                {
                    connectionsList.add(ConnectionData(pin.pinId, targetPin.pinId))
                }
            }
            pin.territory.northEastBorders.forEach { border ->
                val targetPin = connectedPins.find { it.territory == border.adjacentTerritory }
                if(targetPin != null)
                {
                    connectionsList.add(ConnectionData(pin.pinId, targetPin.pinId))
                }
            }
            pin.territory.northWestBorders.forEach { border ->
                val targetPin = connectedPins.find { it.territory == border.adjacentTerritory }
                if(targetPin != null)
                {
                    connectionsList.add(ConnectionData(pin.pinId, targetPin.pinId))
                }
            }
            pin.territory.southEastBorders.forEach { border ->
                val targetPin = connectedPins.find { it.territory == border.adjacentTerritory }
                if(targetPin != null)
                {
                    connectionsList.add(ConnectionData(pin.pinId, targetPin.pinId))
                }
            }
            pin.territory.southWestBorders.forEach { border ->
                val targetPin = connectedPins.find { it.territory == border.adjacentTerritory }
                if(targetPin != null)
                {
                    connectionsList.add(ConnectionData(pin.pinId, targetPin.pinId))
                }
            }
        }
        
        val mapData = MapData(
            pinDataList,
            connectionsList.distinct(),
            worldName,
            storyScenario,
            author,
            writingInstructions,
            writingAgentConfig.storyWeights,
            writingAgentConfig.selectionStrategy,
            writingAgentConfig.authorEnabled,
            writingAgentConfig.alwaysApplyRulesEnabled,
            writingAgentConfig.guardrailsEnabled,
            writingAgentConfig
        )
        return Json.encodeToString(MapData.serializer(), mapData)
    }

    /**
     * Loads map data from a JSON string.
     *
     * @param jsonData JSON string containing serialized map data.
     */
    fun loadMapData(jsonData: String)
    {
        // Clear existing pins
        connectedPins.toList().forEach { pin ->
            removeAllLinesForPin(pin)
            remove(pin)
        }
        connectedPins.clear()
        
        val mapData = Json.decodeFromString(MapData.serializer(), jsonData)
        
        // Create pins with their territories
        val pinMap = mutableMapOf<String, Pin>()
        mapData.pins.forEach { pinData ->
            val pin = Pin(pinData.territory.xPos, pinData.territory.yPos)
            pin.pinId = pinData.pinId
            pin.territory.name = pinData.territory.name
            pin.territory.type = pinData.territory.type
            pin.territory.description = pinData.territory.description
            pin.territory.pointValue = pinData.territory.pointValue
            pin.territory.northObstacleType = pinData.territory.northObstacleType
            pin.territory.southObstacleType = pinData.territory.southObstacleType
            pin.territory.eastObstacleType = pinData.territory.eastObstacleType
            pin.territory.westObstacleType = pinData.territory.westObstacleType
            pin.territory.northEastObstacleType = pinData.territory.northEastObstacleType
            pin.territory.northWestObstacleType = pinData.territory.northWestObstacleType
            pin.territory.southEastObstacleType = pinData.territory.southEastObstacleType
            pin.territory.southWestObstacleType = pinData.territory.southWestObstacleType
            pin.territory.resource = pinData.territory.resource
            
            connectedPins.add(pin)
            add(pin)
            pinMap[pinData.pinId] = pin
        }

        writingAgentConfig = mapData.writingAgentConfig

        // Recreate connections
        mapData.connections.forEach { conn ->
            val fromPin = pinMap[conn.fromPinId]
            val toPin = pinMap[conn.toPinId]
            if(fromPin != null && toPin != null)
            {
                // Calculate direction and create border
                val dx = toPin.territory.xPos - fromPin.territory.xPos
                val dy = toPin.territory.yPos - fromPin.territory.yPos
                val angle = kotlin.math.atan2(dy, dx) * 180 / kotlin.math.PI
                
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
                
                val borderToTarget = structs.Border(adjacentTerritory = toPin.territory)
                val borderToMe = structs.Border(adjacentTerritory = fromPin.territory)
                
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
                
                drawBorderLine(fromPin, toPin)
            }
        }
    }
}
