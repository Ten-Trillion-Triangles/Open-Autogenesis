package ui.gameplay

import globals.World
import enums.TerritoryType
import io.kvision.core.*
import io.kvision.html.*
import io.kvision.panel.SimplePanel
import io.kvision.panel.hPanel
import io.kvision.panel.vPanel
import io.kvision.utils.perc
import io.kvision.utils.px
import structs.Player
import structs.Territory

/**
 * Popup that lists the territories owned by a player and allows clicking into the description window.
 */
class PlayerTerritoriesWidget : SimplePanel(className = "login-widget-window")
{
    private var currentPlayer: Player? = null
    var onTerritoryClick: ((Territory) -> Unit)? = null

    private val headerText = h4("Territories")
    {
        color = Color.name(Col.CYAN)
        fontSize = 28.px
        fontWeight = FontWeight.BOLD
        marginBottom = 20.px
        textShadow = TextShadow(0.px, 0.px, 5.px, Color.name(Col.CYAN))
    }

    private val territoriesContainer = vPanel {
        width = 90.perc
        height = 100.perc
        spacing = 10
        alignItems = AlignItems.CENTER
        overflow = Overflow.AUTO
    }

    init
    {
        width = 600.px
        height = 700.px
        position = Position.FIXED
        top = 50.perc
        left = 50.perc
        marginTop = (-350).px
        marginLeft = (-300).px
        zIndex = 105
        padding = 20.px

        display = Display.NONE
        flexDirection = FlexDirection.COLUMN
        alignItems = AlignItems.CENTER
        setStyle("animation", "dialogFadeIn 0.3s ease-out")

        vPanel(spacing = 15, alignItems = AlignItems.CENTER)
        {
            width = 100.perc
            height = 100.perc
            overflow = Overflow.HIDDEN
            add(this@PlayerTerritoriesWidget.headerText)
            add(this@PlayerTerritoriesWidget.territoriesContainer)
        }

        hPanel(justify = JustifyContent.CENTER, alignItems = AlignItems.CENTER)
        {
            width = 100.perc
            marginTop = 20.px
            flexShrink = 0

            button("CLOSE", icon = "fas fa-times", className = "btn btn-play")
            {
                onClick {
                    this@PlayerTerritoriesWidget.hide()
                }
            }
        }
    }

    /**
     * Opens the popup for the provided player.
     *
     * @param player The player whose territories will be shown.
     */
    fun show(player: Player)
    {
        currentPlayer = player
        headerText.content = "${player.name}'s Territories"
        update()
        display = Display.FLEX
        visible = true
    }

    /**
     * Hides the popup and resets the display state.
     */
    override fun hide()
    {
        display = Display.NONE
        visible = false
    }

    /**
     * Refreshes the territory list for the current player.
     */
    fun update()
    {
        territoriesContainer.removeAll()
        val player = currentPlayer ?: return
        val territories = World.worldData.mapTiles.filter { it.ruler == player.name }

        if(territories.isEmpty())
        {
            territoriesContainer.add(p("No territories owned.")
            {
                color = Color.name(Col.LIGHTGRAY)
                fontStyle = FontStyle.ITALIC
            })
            return
        }

        territories.forEach { territory ->
            territoriesContainer.add(createTerritoryItem(territory))
        }
    }

    /**
     * Creates a single territory list item with click handling.
     *
     * @param territory The territory to render.
     * @return Configured panel for the territory.
     */
    private fun createTerritoryItem(territory: Territory): SimplePanel
    {
        return hPanel(className = "resource-item", spacing = 15, alignItems = AlignItems.CENTER)
        {
            width = 100.perc
            padding = 10.px
            background = Background(Color.hex(0x1a1a2e))
            border = Border(1.px, BorderStyle.SOLID, Color.hex(0x383f59))
            borderRadius = 5.px
            cursor = Cursor.POINTER

            onClick {
                onTerritoryClick?.invoke(territory)
            }

            val iconStr = if(territory.type == TerritoryType.Land)
            {
                "fas fa-map-marker-alt"
            }
            else
            {
                "fas fa-water"
            }

            icon(iconStr)
            {
                fontSize = 24.px
                color = Color.name(Col.GOLD)
                minWidth = 30.px
                textAlign = TextAlign.CENTER
            }

            vPanel(spacing = 2)
            {
                width = 100.perc

                span(territory.name)
                {
                    color = Color.name(Col.WHITE)
                    fontSize = 16.px
                    fontWeight = FontWeight.BOLD
                }

                hPanel(spacing = 10)
                {
                    span("${territory.pointValue} pts")
                    {
                        color = Color.name(Col.CYAN)
                        fontSize = 12.px
                    }
                    span("•")
                    {
                        color = Color.name(Col.GRAY)
                    }
                    span(territory.type.name)
                    {
                        color = Color.name(Col.LIGHTGRAY)
                        fontSize = 12.px
                    }
                }
            }

            icon("fas fa-chevron-right")
            {
                fontSize = 14.px
                color = Color.name(Col.GRAY)
            }
        }
    }
}
