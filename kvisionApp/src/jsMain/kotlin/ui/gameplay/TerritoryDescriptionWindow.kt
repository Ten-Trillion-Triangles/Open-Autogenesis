package ui.gameplay

import enums.ObstacleType
import enums.TerritorySize
import enums.TerritoryType
import io.kvision.core.*
import io.kvision.html.*
import io.kvision.panel.HPanel
import io.kvision.panel.SimplePanel
import io.kvision.panel.hPanel
import io.kvision.panel.vPanel
import io.kvision.utils.perc
import io.kvision.utils.px
import structs.Territory
import org.ttt.autogenesis.logging.LogCategory
import org.ttt.autogenesis.logging.Logger

/**
 * Pop up window to display the stats and description of a given territory.
 */
class TerritoryDescriptionWindow : SimplePanel(className = "login-widget-window")
{
    private val territoryName = P("Territory Name") {
        fontSize = 32.px
        fontWeight = FontWeight.BOLD
        color = Color.name(Col.WHITE)
        marginBottom = 0.px
        textShadow = TextShadow(0.px, 0.px, 10.px, Color.name(Col.CYAN))
    }
    
    private val territoryRuler = P("Ruled by: Unclaimed") {
        fontSize = 18.px
        color = Color.name(Col.LIGHTGRAY)
        fontStyle = FontStyle.ITALIC
        marginBottom = 0.px
    }
    
    private val territoryType = InfoPanel("Type", "fas fa-map-marker-alt", "Land")
    private val territorySize = InfoPanel("Size", "fas fa-expand-arrows-alt", "Medium")
    private val territoryValue = InfoPanel("Value", "fas fa-star", "3 pts")
    
    private val obstaclesContainer = vPanel(spacing = 10) {
        width = 100.perc
        padding = 15.px
        background = Background(color = Color("rgba(0, 0, 0, 0.3)"))
        border = Border(1.px, BorderStyle.SOLID, Color("rgba(94, 106, 220, 0.3)"))
        borderRadius = 8.px
        visible = false
    }

    private val territoryDescription = P("No description available.") {
        fontSize = 16.px
        color = Color.name(Col.WHITE)
        lineHeight = 24.px
        textAlign = TextAlign.JUSTIFY
        padding = 10.px
        background = Background(color = Color("rgba(13, 17, 32, 0.5)"))
        borderRadius = 8.px
        border = Border(1.px, BorderStyle.SOLID, Color("rgba(94, 106, 220, 0.3)"))
    }
    
    // Callback to close the window
    var onClose: (() -> Unit)? = null

    init {
        width = 600.px
        height = 700.px
        position = Position.FIXED
        top = 50.perc
        left = 50.perc
        marginTop = (-350).px
        marginLeft = (-300).px
        zIndex = 100
        padding = 20.px
        
        // Flexbox layout for the window itself
        display = Display.NONE
        flexDirection = FlexDirection.COLUMN
        
        // Restore animation from commander-creation-dialog
        setStyle("animation", "dialogFadeIn 0.3s ease-out")
        
        // Header
        vPanel(className = "kv-window-header", spacing = 5, alignItems = AlignItems.CENTER) {
            width = 100.perc
            paddingBottom = 15.px
            borderBottom = Border(1.px, BorderStyle.SOLID, Color.name(Col.GRAY))
            marginBottom = 15.px
            
            add(this@TerritoryDescriptionWindow.territoryName)
            add(this@TerritoryDescriptionWindow.territoryRuler)
        }
        
        // Body (Content area that grows and scrolls)
        vPanel(spacing = 20) {
            width = 100.perc
            flexGrow = 1
            overflow = Overflow.AUTO
            paddingRight = 10.px // Space for scrollbar
            
            // Info Grid
            hPanel(spacing = 20, justify = JustifyContent.SPACEAROUND) {
                width = 100.perc
                
                add(this@TerritoryDescriptionWindow.territoryType)
                add(this@TerritoryDescriptionWindow.territorySize)
                add(this@TerritoryDescriptionWindow.territoryValue)
            }
            
            // Obstacles Section
            add(this@TerritoryDescriptionWindow.obstaclesContainer)
            
            // Description
            vPanel(spacing = 5) {
                width = 100.perc
                
                p("Description") {
                    fontSize = 18.px
                    fontWeight = FontWeight.BOLD
                    color = Color.name(Col.LIGHTBLUE)
                    marginBottom = 5.px
                }
                
                add(this@TerritoryDescriptionWindow.territoryDescription)
            }
        }

        // Footer (Close Button - stays at the bottom)
        hPanel(justify = JustifyContent.CENTER, alignItems = AlignItems.CENTER) {
            width = 100.perc
            marginTop = 20.px
            flexShrink = 0 // Prevent shrinking
            paddingBottom = 10.px

            button("CLOSE", icon = "fas fa-times", className = "btn btn-play").onClick {
                onClose?.invoke()
                this@TerritoryDescriptionWindow.hide()
            }
        }
    }
    
    fun show(territory: Territory) {
        Logger.debug(LogCategory.UI, "TerritoryDescriptionWindow.show() called for territory: ${territory.name}")
        territoryName.content = territory.name
        territoryRuler.content = if (territory.ruler.isNotEmpty()) "Ruled by: ${territory.ruler}" else "Ruled by: Unclaimed"
        
        display = Display.FLEX
        
        // Update Info Panels
        territoryType.setValue(territory.type.name)
        territorySize.setValue(territory.size.name)
        territoryValue.setValue("${territory.pointValue} pts")
        
        // Update Obstacles
        obstaclesContainer.visible = false
        obstaclesContainer.removeAll()
        // Re-add title
         obstaclesContainer.add(P("Border Obstacles") {
            fontSize = 18.px
            fontWeight = FontWeight.BOLD
            color = Color.name(Col.LIGHTBLUE)
            marginBottom = 10.px
            textAlign = TextAlign.CENTER
        })
        
        var hasObstacles = false
        val obstacleMap = mapOf(
            "North" to territory.northObstacleType,
            "South" to territory.southObstacleType,
            "East" to territory.eastObstacleType,
            "West" to territory.westObstacleType,
            "North-East" to territory.northEastObstacleType,
            "North-West" to territory.northWestObstacleType,
            "South-East" to territory.southEastObstacleType,
            "South-West" to territory.southWestObstacleType
        )

        obstacleMap.forEach { (direction, obstacle) ->
            if (obstacle != null) {
                hasObstacles = true
                obstaclesContainer.add(HPanel(spacing = 10, alignItems = AlignItems.CENTER) {
                    icon("fas fa-exclamation-triangle") {
                        color = Color.name(Col.ORANGE)
                    }
                    div {
                        content = "$direction: ${obstacle.name}"
                        color = Color.name(Col.WHITE)
                    }
                })
            }
        }
        
        if (hasObstacles) {
            obstaclesContainer.visible = true
        }

        territoryDescription.content = if (territory.description.isNotEmpty()) territory.description else "No description available."
        
        this.visible = true
    }
    
    override fun hide() {
        display = Display.NONE
        visible = false
    }
}

/**
 * A small panel to display a labeled value with an icon.
 */
private class InfoPanel(label: String, iconClass: String, initialValue: String) : SimplePanel() {
    private val valueText = P(initialValue) {
        fontSize = 16.px
        color = Color.name(Col.WHITE)
        fontWeight = FontWeight.BOLD
        marginBottom = 0.px
    }
    
    init {
        addCssClass("info-panel")
        padding = 10.px
        background = Background(color = Color("rgba(35, 40, 61, 0.8)"))
        border = Border(1.px, BorderStyle.SOLID, Color("rgba(94, 106, 220, 0.3)"))
        borderRadius = 8.px
        width = 30.perc
        textAlign = TextAlign.CENTER
        
        vPanel(alignItems = AlignItems.CENTER, spacing = 5) {
            icon(iconClass) {
                fontSize = 24.px
                color = Color.name(Col.GOLD)
            }
            
            p(label) {
                fontSize = 12.px
                color = Color.name(Col.GRAY)
                marginBottom = 0.px
                fontWeight = FontWeight.BOLD
                textTransform = TextTransform.UPPERCASE
            }
            
            add(this@InfoPanel.valueText)
        }
    }
    
    fun setValue(newValue: String) {
        valueText.content = newValue
    }
}