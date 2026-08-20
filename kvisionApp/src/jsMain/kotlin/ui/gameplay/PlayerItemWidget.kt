package ui.gameplay

import io.kvision.core.*
import io.kvision.html.Button
import io.kvision.html.button
import io.kvision.html.icon
import io.kvision.html.span
import io.kvision.panel.HPanel
import io.kvision.panel.hPanel
import io.kvision.utils.perc
import io.kvision.utils.px
import structs.Player

/**
 * Widget representing a player item with action buttons for viewing resources, territories, and info.
 *
 * @param player The player data this widget represents.
 * @param onViewResources Callback for viewing player resources.
 * @param onViewTerritories Callback for viewing player territories.
 * @param onViewInfo Callback for viewing player info.
 */
class PlayerItemWidget(
    val player: Player,
    val onViewResources: ((Player) -> Unit)? = null,
    val onViewTerritories: ((Player) -> Unit)? = null,
    val onViewInfo: ((Player) -> Unit)? = null
) : HPanel(
    spacing = 10,
    alignItems = AlignItems.CENTER,
    justify = JustifyContent.SPACEBETWEEN
)
{
    init
    {
        width = 100.perc
        padding = 10.px
        marginBottom = 5.px
        background = Background(Color.hex(0x1a1a2e))
        border = Border(1.px, BorderStyle.SOLID, Color.hex(0x383f59))
        borderRadius = 5.px

        // Player name
        span(player.name) {
            color = Color.name(Col.WHITE)
            fontSize = 16.px
            fontWeight = FontWeight.BOLD
        }

        // Button group
        hPanel(spacing = 5)
        {
            add(createActionButton("fas fa-box-open", "RESOURCES") {
                onViewResources?.invoke(player)
            })
            add(createActionButton("fas fa-map-marked-alt", "TERRITORIES") {
                onViewTerritories?.invoke(player)
            })
            add(createActionButton("fas fa-info-circle", "INFO") {
                onViewInfo?.invoke(player)
            })
        }
    }

    /**
     * Builds a mini action button with icon and label.
     *
     * @param iconClass FontAwesome icon class.
     * @param label Text label for the action.
     * @param onClickAction Callback invoked when button is pressed.
     * @return Configured [Button] instance.
     */
    private fun createActionButton(iconClass: String, label: String, onClickAction: () -> Unit): Button
    {
        return button("", className = "btn btn-sm")
        {
            icon(iconClass)
            {
                fontSize = 18.px
            }
            span(label)
            {
                fontSize = 12.px
                fontWeight = FontWeight.BOLD
            }
            title = label
            onClick {
                onClickAction()
            }
        }
    }
}