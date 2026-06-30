package ui.gameplay

import enums.NpcType
import io.kvision.core.*
import io.kvision.html.*
import io.kvision.panel.HPanel
import io.kvision.panel.vPanel
import io.kvision.utils.perc
import io.kvision.utils.px
import structs.Npc

class NpcItemWidget(
    private val npc: Npc
) : HPanel() {

    init {
        width = 100.perc
        minHeight = 130.px
        background = Background(Color.hex(0x2a2a2a))
        border = Border(1.px, BorderStyle.SOLID, Color.name(Col.GRAY))
        borderRadius = 5.px
        marginBottom = 10.px
        padding = 15.px
        alignItems = AlignItems.CENTER
        spacing = 20

        // Hover effect
        addCssClass("player-item-widget") 

        // 1. Icon / Type Indicator
        vPanel(alignItems = AlignItems.CENTER, spacing = 5) {
            width = 80.px
            minWidth = 80.px
            
            val visualStyle = getNpcVisualStyle(npc)

            icon(visualStyle.iconClass) {
                fontSize = 32.px
                color = visualStyle.color
            }

            span(visualStyle.typeLabel) {
                fontSize = 11.px
                color = visualStyle.color
                fontWeight = FontWeight.BOLD
                textAlign = TextAlign.CENTER
            }
        }

        // 2. Info (Name, Description, History)
        vPanel(spacing = 8) {
            flexGrow = 1
            
            h4(npc.name) {
                color = Color.name(Col.WHITE)
                marginBottom = 5.px
                fontSize = 20.px
            }
            
            p(npc.description) {
                color = Color.name(Col.LIGHTGRAY)
                fontSize = 18.px
                marginBottom = 8.px
                fontStyle = FontStyle.ITALIC
            }
            
            if (npc.history.isNotEmpty()) {
                p(npc.history) {
                    color = Color.name(Col.GRAY)
                    fontSize = 16.px
                    marginBottom = 0.px
                }
            }
        }
    }

}
