package ui

import globals.KEnv
import interfaces.WidgetInterface
import io.kvision.core.*
import io.kvision.html.Div
import io.kvision.panel.SimplePanel
import kotlinx.browser.document
import kotlinx.browser.window
import kotlinx.serialization.json.Json
import org.w3c.dom.HTMLElement
import org.w3c.dom.events.Event
import org.w3c.dom.events.MouseEvent
import structs.Territory

/**
 * Interactive pin widget representing a territory on the map canvas.
 * Supports dragging, border connections, and property editing.
 *
 * @param xPercent Horizontal position as percentage of canvas width.
 * @param yPercent Vertical position as percentage of canvas height.
 */
class Pin(private var xPercent: Double, private var yPercent: Double) : SimplePanel(), WidgetInterface
{
    private var isDragging = false
    private var isDrawingBorder = false
    private var widgetLabel = ""
    private var mouseDownX = 0.0
    private var mouseDownY = 0.0
    val territory = Territory(xPos = xPercent, yPos = yPercent)
    var pinId = "pin_${kotlin.random.Random.nextInt(1000000)}"

    init {
        width = CssSize(40, UNIT.px)
        height = CssSize(40, UNIT.px)
        position = Position.ABSOLUTE
        left = CssSize(xPercent, UNIT.perc)
        top = CssSize(yPercent, UNIT.perc)
        zIndex = 10
        setStyle("transform", "translate(-50%, -50%)")
        
        // Visual pin element
        add(Div("📍") {
            fontSize = CssSize(36, UNIT.px)
            cursor = Cursor.POINTER
        })

        // Hover events
        onEvent {
            mouseenter = {
                background = Background(color = Color.hex(0xFFFF00))
            }
            mouseleave = {
                background = null
            }
        }

        // Mouse button handlers
        onEvent {
            mousedown = { e ->
                val mouseEvent = e as MouseEvent
                mouseDownX = mouseEvent.clientX.toDouble()
                mouseDownY = mouseEvent.clientY.toDouble()
                console.log("Pin mousedown: button=${mouseEvent.button}")
                when(mouseEvent.button.toInt())
                {
                    0 ->
                    {
                        // Left click - will determine if click or drag on mouseup
                        e.preventDefault()
                        e.stopPropagation()
                        isDrawingBorder = true
                        startBorderDrawing()
                    }
                    1 ->
                    {
                        // Middle click - break all borders and start drag
                        console.log("Middle click - breaking borders")
                        e.preventDefault()
                        breakAllBorders()
                        isDragging = true
                        startDrag()
                    }
                }
            }
        }

        // Right click (context menu) should delete the pin
        onEvent {
            contextmenu = { e ->
                e.preventDefault()
                e.stopPropagation()
                console.log("Right click - removing pin")
                (parent as? MapCanvas)?.removePin(this@Pin)
                destroyWidget()
            }
        }
    }

    /**
     * Initiates drag mode for repositioning the pin on the canvas.
     */
    private fun startDrag()
    {
        var mouseMoveHandler: ((Event) -> Unit)? = null
        var mouseUpHandler: ((Event) -> Unit)? = null
        
        mouseMoveHandler = { e ->
            if(isDragging)
            {
                val mouseEvent = e as MouseEvent
                parent?.getElement()?.let { parentElement ->
                    val rect = (parentElement as HTMLElement).getBoundingClientRect()
                    xPercent = (((mouseEvent.clientX - rect.left) / rect.width) * 100).coerceIn(0.0, 100.0)
                    yPercent = (((mouseEvent.clientY - rect.top) / rect.height) * 100).coerceIn(0.0, 100.0)
                    left = CssSize(xPercent, UNIT.perc)
                    top = CssSize(yPercent, UNIT.perc)

                    territory.xPos = xPercent
                    territory.yPos = yPercent
                }
            }
        }

        mouseUpHandler = { e ->
            val mouseEvent = e as MouseEvent
            if(mouseEvent.button.toInt() == 1)
            {
                isDragging = false
                mouseMoveHandler?.let { document.removeEventListener("mousemove", it) }
                mouseUpHandler?.let { document.removeEventListener("mouseup", it) }
            }
        }

        document.addEventListener("mousemove", mouseMoveHandler)
        document.addEventListener("mouseup", mouseUpHandler)
    }

    /**
     * Returns the widget label.
     *
     * @return The label string.
     */
    override fun getLabel(): String = widgetLabel

    /**
     * Sets the widget label.
     *
     * @param label The new label value.
     */
    override fun setLabel(label: String)
    {
        widgetLabel = label
    }

    /**
     * Updates the pin's territory data from JSON.
     *
     * @param data JSON string containing territory properties.
     */
    override fun updateDataInternal(data: String)
    {
        val updatedTerritory = JSON.parse<Territory>(data)
        territory.name = updatedTerritory.name
        territory.type = updatedTerritory.type
        territory.description = updatedTerritory.description
        territory.ruler = updatedTerritory.ruler
        territory.resource = updatedTerritory.resource
        territory.size = updatedTerritory.size
        territory.pointValue = updatedTerritory.pointValue
        territory.northBorders = updatedTerritory.northBorders.toMutableList()
        territory.southBorders = updatedTerritory.southBorders.toMutableList()
        territory.eastBorders = updatedTerritory.eastBorders.toMutableList()
        territory.westBorders = updatedTerritory.westBorders.toMutableList()
        territory.northEastBorders = updatedTerritory.northEastBorders.toMutableList()
        territory.northWestBorders = updatedTerritory.northWestBorders.toMutableList()
        territory.southEastBorders = updatedTerritory.southEastBorders.toMutableList()
        territory.southWestBorders = updatedTerritory.southWestBorders.toMutableList()
        territory.isCaptured = updatedTerritory.isCaptured
        territory.isDestroyed = updatedTerritory.isDestroyed
    }

    /**
     * Removes the pin from the canvas and cleans up all connections.
     */
    override fun destroyWidget()
    {
        (parent as? MapCanvas)?.removeAllLinesForPin(this)
        (parent as? MapCanvas)?.connectedPins?.remove(this)
        parent?.remove(this)
        dispose()
    }

    /**
     * Initiates border drawing mode to connect this pin to another.
     */
    private fun startBorderDrawing()
    {
        var mouseUpHandler: ((Event) -> Unit)? = null
        
        mouseUpHandler = { e ->
            val mouseEvent = e as MouseEvent
            if(mouseEvent.button.toInt() == 0 && isDrawingBorder)
            {
                isDrawingBorder = false
                
                // Calculate distance moved
                val dx = kotlin.math.abs(mouseEvent.clientX - mouseDownX)
                val dy = kotlin.math.abs(mouseEvent.clientY - mouseDownY)
                val distance = kotlin.math.sqrt(dx * dx + dy * dy)
                
                if(distance < 5)
                {
                    // Click (not drag) - select pin
                    console.log("Pin clicked - selecting")
                    selectPin()
                }
                else
                {
                    // Drag - create border connection
                    console.log("Border drawing completed")
                    findTargetPinAndConnect(mouseEvent)
                }
                
                mouseUpHandler?.let { document.removeEventListener("mouseup", it) }
            }
        }
        
        document.addEventListener("mouseup", mouseUpHandler)
    }

    /**
     * Selects this pin and displays its properties in the sidebar.
     */
    fun selectPin()
    {
        val sidebar = globals.KEnv.getWidget("Sidebar") as? PropertySidebar
        if(sidebar != null)
        {
            sidebar.pinRef = this
            sidebar.updateDataInternal(kotlinx.serialization.json.Json.encodeToString(territory))
            console.log("Pin selected - data sent to sidebar")
        }
        else
        {
            console.log("ERROR: Could not find PropertySidebar")
        }
    }

    /**
     * Finds the nearest pin to the mouse position and creates a border connection.
     *
     * @param mouseEvent The mouse event containing the target position.
     */
    private fun findTargetPinAndConnect(mouseEvent: MouseEvent)
    {
        console.log("findTargetPinAndConnect called")
        val canvas = parent as? MapCanvas
        if(canvas == null)
        {
            console.log("ERROR: parent is not MapCanvas")
            return
        }
        
        val canvasElement = canvas.getElement() as? HTMLElement
        if(canvasElement == null)
        {
            console.log("ERROR: cannot get canvas element")
            return
        }
        
        val rect = canvasElement.getBoundingClientRect()
        val targetX = ((mouseEvent.clientX - rect.left) / rect.width) * 100
        val targetY = ((mouseEvent.clientY - rect.top) / rect.height) * 100
        console.log("Target position: $targetX%, $targetY%")
        console.log("Available pins: ${canvas.connectedPins.size}")
        
        val targetPin = canvas.connectedPins
            .filter { it != this }
            .map { pin ->
                val dx = kotlin.math.abs(pin.territory.xPos - targetX)
                val dy = kotlin.math.abs(pin.territory.yPos - targetY)
                val distance = kotlin.math.sqrt(dx * dx + dy * dy)
                console.log("Checking pin at ${pin.territory.xPos}%, ${pin.territory.yPos}% - distance: $distance")
                pin to distance
            }
            .filter { it.second < 5 }
            .minByOrNull { it.second }
            ?.first
        
        if(targetPin == null)
        {
            console.log("No target pin found within tolerance")
        }
        else if(targetPin == this)
        {
            console.log("Target pin is same as source pin")
        }
        else
        {
            console.log("Found target pin! Connecting...")
            connectToPinWithBorder(targetPin)
        }
    }

    /**
     * Creates a bidirectional border connection between this pin and the target pin.
     *
     * @param targetPin The pin to connect to.
     */
    private fun connectToPinWithBorder(targetPin: Pin)
    {
        val dx = targetPin.territory.xPos - territory.xPos
        val dy = targetPin.territory.yPos - territory.yPos
        val angle = kotlin.math.atan2(dy, dx) * 180 / kotlin.math.PI
        
        // Determine direction based on angle (8 directions)
        val (myDirection, theirDirection) = when
        {
            angle >= -22.5 && angle < 22.5 -> "east" to "west"
            angle >= 22.5 && angle < 67.5 -> "southEast" to "northWest"
            angle >= 67.5 && angle < 112.5 -> "south" to "north"
            angle >= 112.5 && angle < 157.5 -> "southWest" to "northEast"
            angle >= 157.5 || angle < -157.5 -> "west" to "east"
            angle >= -157.5 && angle < -112.5 -> "northWest" to "southEast"
            angle >= -112.5 && angle < -67.5 -> "north" to "south"
            else -> "northEast" to "southWest" // -67.5 to -22.5
        }
        
        val borderToTarget = structs.Border(adjacentTerritory = targetPin.territory)
        val borderToMe = structs.Border(adjacentTerritory = territory)
        
        // Add border to source territory (with duplicate check)
        val sourceList = when(myDirection)
        {
            "north" -> territory.northBorders
            "south" -> territory.southBorders
            "east" -> territory.eastBorders
            "west" -> territory.westBorders
            "northEast" -> territory.northEastBorders
            "northWest" -> territory.northWestBorders
            "southEast" -> territory.southEastBorders
            "southWest" -> territory.southWestBorders
            else -> null
        }
        
        if(sourceList != null && !sourceList.any { it.adjacentTerritory == targetPin.territory })
        {
            sourceList.add(borderToTarget)
            console.log("Added border from ${territory.name} to ${targetPin.territory.name} ($myDirection)")
        }
        else if(sourceList != null)
        {
            console.log("Connection already exists, skipping duplicate")
            return
        }
        
        // Add border to target territory (with duplicate check)
        val targetList = when(theirDirection)
        {
            "north" -> targetPin.territory.northBorders
            "south" -> targetPin.territory.southBorders
            "east" -> targetPin.territory.eastBorders
            "west" -> targetPin.territory.westBorders
            "northEast" -> targetPin.territory.northEastBorders
            "northWest" -> targetPin.territory.northWestBorders
            "southEast" -> targetPin.territory.southEastBorders
            "southWest" -> targetPin.territory.southWestBorders
            else -> null
        }
        
        if(targetList != null && !targetList.any { it.adjacentTerritory == territory })
        {
            targetList.add(borderToMe)
            console.log("Added border from ${targetPin.territory.name} to ${territory.name} ($theirDirection)")
        }
        
        (parent as? MapCanvas)?.drawBorderLine(this, targetPin)
        console.log("Connected ${territory.name} ($myDirection) to ${targetPin.territory.name} ($theirDirection)")
    }

    /**
     * Removes all border connections from this pin and updates connected pins.
     */
    private fun breakAllBorders()
    {
        val canvas = parent as? MapCanvas ?: return
        canvas.removeAllLinesForPin(this)
        
        territory.northBorders.clear()
        territory.southBorders.clear()
        territory.eastBorders.clear()
        territory.westBorders.clear()
        territory.northEastBorders.clear()
        territory.northWestBorders.clear()
        territory.southEastBorders.clear()
        territory.southWestBorders.clear()
        
        console.log("Cleared all borders for ${territory.name}")
        
        canvas.connectedPins.forEach { otherPin ->
            if(otherPin != this)
            {
                var removed = 0
                if(otherPin.territory.northBorders.removeAll { it.adjacentTerritory == territory }) removed++
                if(otherPin.territory.southBorders.removeAll { it.adjacentTerritory == territory }) removed++
                if(otherPin.territory.eastBorders.removeAll { it.adjacentTerritory == territory }) removed++
                if(otherPin.territory.westBorders.removeAll { it.adjacentTerritory == territory }) removed++
                if(otherPin.territory.northEastBorders.removeAll { it.adjacentTerritory == territory }) removed++
                if(otherPin.territory.northWestBorders.removeAll { it.adjacentTerritory == territory }) removed++
                if(otherPin.territory.southEastBorders.removeAll { it.adjacentTerritory == territory }) removed++
                if(otherPin.territory.southWestBorders.removeAll { it.adjacentTerritory == territory }) removed++
                
                if(removed > 0)
                {
                    console.log("Removed $removed border(s) from ${otherPin.territory.name}")
                }
            }
        }
        
        console.log("Broke all borders for ${territory.name}")
    }
}
