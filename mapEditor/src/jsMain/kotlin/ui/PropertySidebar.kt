package ui

import enums.ObstacleType
import enums.TerritoryType
import interfaces.WidgetInterface
import io.kvision.core.AlignItems
import io.kvision.core.Component
import io.kvision.core.CssSize
import io.kvision.core.JustifyContent
import io.kvision.core.Overflow
import io.kvision.core.TextAlign
import io.kvision.core.UNIT
import io.kvision.core.onChange
import io.kvision.core.onInput
import io.kvision.form.number.Spinner
import io.kvision.form.number.spinner
import io.kvision.form.select.Select
import io.kvision.form.select.select
import io.kvision.form.text.TextArea
import io.kvision.form.text.TextInput
import io.kvision.form.text.textArea
import io.kvision.form.text.textInput
import io.kvision.html.p
import io.kvision.panel.VPanel
import kotlinx.serialization.json.Json
import structs.Territory

/**
 * Sidebar dock that allows us to define properties for each tile.
 */
class PropertySidebar : VPanel(
    justify = JustifyContent.FLEXSTART,
    alignItems = AlignItems.STRETCH,
    spacing = 20
), WidgetInterface {
    var tileData = Territory() //Tile data that will be exported to the pin in real time.
    private var widgetLabel = ""
    var pinRef: WidgetInterface? = null

    //Internal child widget refs. External access not provided.
    var nameBoxRef: TextInput? = null
    var typeComboBox: Select? = null
    var territoryDescription: TextArea? = null
    var pointValue: Spinner? = null
    var northObstacle: Select? = null
    var southObstacle: Select? = null
    var eastObstacle: Select? = null
    var westObstacle: Select? = null
    var northEastObstacle: Select? = null
    var northWestObstacle: Select? = null
    var southEastObstacle: Select? = null
    var southWestObstacle: Select? = null
    var resourceSettings: Component? = null

    init {
        overflow = Overflow.AUTO
        width = CssSize(400, UNIT.px)
        minWidth = CssSize(400, UNIT.px)
        height = CssSize(100, UNIT.perc)
        padding = CssSize(20, UNIT.px)
        addCssClass("property-sidebar")
        background = io.kvision.core.Background(color = io.kvision.core.Color.hex(0x222222))
        color = io.kvision.core.Color.name(io.kvision.core.Col.WHITE)

        p("Name") {
            fontSize = CssSize(20, UNIT.px)
            marginBottom = CssSize(4, UNIT.px)
            textAlign = TextAlign.LEFT
            width = CssSize(100, UNIT.perc)
        }

        //Territory name box
        nameBoxRef = textInput {
            width = CssSize(100, UNIT.perc)
            height = CssSize(40, UNIT.px)
            maxlength = 128
            placeholder = "Name your map tile"
            value = ""
            padding = CssSize(10, UNIT.px) // Keep internal padding for text input comfort

            onInput {
                tileData.name = this.value ?: ""
                syncToPin()
            }
        }

        p("Type") {
            fontSize = CssSize(20, UNIT.px)
            marginBottom = CssSize(4, UNIT.px)
            textAlign = TextAlign.LEFT
            width = CssSize(100, UNIT.perc)
        }

        //TerritoryType combo box.
        typeComboBox = select {
            width = CssSize(100, UNIT.perc)
            height = CssSize(35, UNIT.px)

            options = listOf(
                TerritoryType.Land.toString() to "Land",
                TerritoryType.Underwater.toString() to "Underwater",
                TerritoryType.Coastline.toString() to "Coastline",
                TerritoryType.Island.toString() to "Island",
                TerritoryType.Desert.toString() to "Desert",
                TerritoryType.Void.toString() to "Void"
            )

            value = "Land"

            onChange {
                tileData.type = enumValueOf<TerritoryType>(this.value ?: "")
                syncToPin()
            }
        }

        p("Description") {
            fontSize = CssSize(20, UNIT.px)
            marginBottom = CssSize(4, UNIT.px)
            paddingTop = CssSize(140, UNIT.px)
            textAlign = TextAlign.LEFT
            width = CssSize(100, UNIT.perc)
        }

        //Optional territory description, affects behavior of the agents and outcome of events potentially.
        territoryDescription = textArea {
            cols = 30
            rows = 4
            fontSize = CssSize(14, UNIT.px)
            width = CssSize(100, UNIT.perc)
            paddingTop = CssSize(20, UNIT.px)
            placeholder = "Describe any desired details about the territory here. The LLM's will use this data."

            onInput {
                tileData.description = this.value ?: ""
                syncToPin()
            }
        }

        p("Point Value") {
            fontSize = CssSize(20, UNIT.px)
            marginBottom = CssSize(4, UNIT.px)
            textAlign = TextAlign.LEFT
            width = CssSize(100, UNIT.perc)
        }

        //Basic spinner to allow us to define the point value for taking and holding this tile.
        pointValue = spinner {
            min = 1
            max = 6
            width = CssSize(100, UNIT.perc)
            height = CssSize(30, UNIT.px)

            onChange {
                tileData.pointValue = this.value!!.toInt()
                syncToPin()
            }

            onInput {
                tileData.pointValue = this.value!!.toInt()
                syncToPin()
            }
        }

        p("Border Obstacles") {
            fontSize = CssSize(20, UNIT.px)
            marginBottom = CssSize(4, UNIT.px)
            marginTop = CssSize(20, UNIT.px)
            textAlign = TextAlign.LEFT
            width = CssSize(100, UNIT.perc)
        }

        val obstacleOptions = listOf(
            "" to "None",
            "River" to "River",
            "Mountain" to "Mountain",
            "Ocean" to "Ocean"
        )

        northObstacle = select {
            label = "North"
            width = CssSize(100, UNIT.perc)
            options = obstacleOptions
            onChange {
                tileData.northObstacleType = if (this.value.isNullOrEmpty()) null else enums.ObstacleType.valueOf(this.value!!)
                syncToPin()
            }
        }

        southObstacle = select {
            label = "South"
            width = CssSize(100, UNIT.perc)
            options = obstacleOptions
            onChange {
                tileData.southObstacleType = if (this.value.isNullOrEmpty()) null else enums.ObstacleType.valueOf(this.value!!)
                syncToPin()
            }
        }

        eastObstacle = select {
            label = "East"
            width = CssSize(100, UNIT.perc)
            options = obstacleOptions
            onChange {
                tileData.eastObstacleType = if (this.value.isNullOrEmpty()) null else enums.ObstacleType.valueOf(this.value!!)
                syncToPin()
            }
        }

        westObstacle = select {
            label = "West"
            width = CssSize(100, UNIT.perc)
            options = obstacleOptions
            onChange {
                tileData.westObstacleType = if (this.value.isNullOrEmpty()) null else enums.ObstacleType.valueOf(this.value!!)
                syncToPin()
            }
        }

        northEastObstacle = select {
            label = "North-East"
            width = CssSize(100, UNIT.perc)
            options = obstacleOptions
            onChange {
                tileData.northEastObstacleType = if (this.value.isNullOrEmpty()) null else enums.ObstacleType.valueOf(this.value!!)
                syncToPin()
            }
        }

        northWestObstacle = select {
            label = "North-West"
            width = CssSize(100, UNIT.perc)
            options = obstacleOptions
            onChange {
                tileData.northWestObstacleType = if (this.value.isNullOrEmpty()) null else enums.ObstacleType.valueOf(this.value!!)
                syncToPin()
            }
        }

        southEastObstacle = select {
            label = "South-East"
            width = CssSize(100, UNIT.perc)
            options = obstacleOptions
            onChange {
                tileData.southEastObstacleType = if (this.value.isNullOrEmpty()) null else enums.ObstacleType.valueOf(this.value!!)
                syncToPin()
            }
        }

        southWestObstacle = select {
            label = "South-West"
            width = CssSize(100, UNIT.perc)
            options = obstacleOptions
            onChange {
                tileData.southWestObstacleType = if (this.value.isNullOrEmpty()) null else enums.ObstacleType.valueOf(this.value!!)
                syncToPin()
            }
        }

        resourceSettings = ResourceSettings().apply {
            width = CssSize(100, UNIT.perc)
            visible = true
            paddingTop = CssSize(40, UNIT.px)
            onDataChange = { syncToPin() }
            refresh()
        }
        add(resourceSettings!!)

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
     * Updates the sidebar with territory data from JSON.
     *
     * @param data JSON string containing territory properties.
     */
    override fun updateDataInternal(data: String)
    {
        val updatedTerritory = kotlinx.serialization.json.Json.decodeFromString<Territory>(data)
        tileData = updatedTerritory
        
        // Update form fields to match received data
        nameBoxRef?.value = updatedTerritory.name
        typeComboBox?.value = updatedTerritory.type.toString()
        territoryDescription?.value = updatedTerritory.description
        pointValue?.value = updatedTerritory.pointValue.toDouble()
        northObstacle?.value = updatedTerritory.northObstacleType?.toString() ?: ""
        southObstacle?.value = updatedTerritory.southObstacleType?.toString() ?: ""
        eastObstacle?.value = updatedTerritory.eastObstacleType?.toString() ?: ""
        westObstacle?.value = updatedTerritory.westObstacleType?.toString() ?: ""
        northEastObstacle?.value = updatedTerritory.northEastObstacleType?.toString() ?: ""
        northWestObstacle?.value = updatedTerritory.northWestObstacleType?.toString() ?: ""
        southEastObstacle?.value = updatedTerritory.southEastObstacleType?.toString() ?: ""
        southWestObstacle?.value = updatedTerritory.southWestObstacleType?.toString() ?: ""
        
        // Update resource settings
        (resourceSettings as? ResourceSettings)?.updateDataInternal(kotlinx.serialization.json.Json.encodeToString(updatedTerritory.resource))
        refresh()
    }

    /**
     * Removes the sidebar from its parent and disposes resources.
     */
    override fun destroyWidget()
    {
        parent?.remove(this)
        dispose()
    }

    /**
     * Synchronizes the current tile data to the referenced pin's territory.
     */
    private fun syncToPin()
    {
        val pin = pinRef as? Pin
        if(pin != null)
        {
            pin.territory.name = tileData.name
            pin.territory.type = tileData.type
            pin.territory.description = tileData.description
            pin.territory.pointValue = tileData.pointValue
            pin.territory.northObstacleType = tileData.northObstacleType
            pin.territory.southObstacleType = tileData.southObstacleType
            pin.territory.eastObstacleType = tileData.eastObstacleType
            pin.territory.westObstacleType = tileData.westObstacleType
            pin.territory.northEastObstacleType = tileData.northEastObstacleType
            pin.territory.northWestObstacleType = tileData.northWestObstacleType
            pin.territory.southEastObstacleType = tileData.southEastObstacleType
            pin.territory.southWestObstacleType = tileData.southWestObstacleType
            pin.territory.resource = (resourceSettings as? ResourceSettings)?.resourceData ?: tileData.resource
        }
    }
}
