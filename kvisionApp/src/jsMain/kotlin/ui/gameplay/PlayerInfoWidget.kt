package ui.gameplay

import io.kvision.core.*
import io.kvision.html.*
import io.kvision.panel.SimplePanel
import io.kvision.panel.hPanel
import io.kvision.panel.vPanel
import io.kvision.utils.perc
import io.kvision.utils.px
import structs.Player
import globals.World

/**
 * Centered popup window that displays detailed stats about a specific player.
 */
class PlayerInfoWidget : SimplePanel(className = "login-widget-window")
{

    private var currentPlayer: Player? = null

    private val nameText = h4("Player Name") {
        color = Color.name(Col.CYAN)
        fontSize = 28.px
        fontWeight = FontWeight.BOLD
        marginBottom = 10.px
        textShadow = TextShadow(0.px, 0.px, 5.px, Color.name(Col.CYAN))
        textAlign = TextAlign.CENTER
    }

    private val descriptionText = p("Description placeholder") {
        color = Color.name(Col.LIGHTGRAY)
        fontSize = 16.px
        fontStyle = FontStyle.ITALIC
        textAlign = TextAlign.CENTER
        marginBottom = 30.px
        width = 80.perc
    }

    private val statsContainer = hPanel(spacing = 20, justify = JustifyContent.CENTER) {
        width = 100.perc
        alignItems = AlignItems.CENTER
    }

    init {
        // Shared Styling & Positioning
        width = 900.px
        height = 700.px
        position = Position.FIXED
        top = 50.perc
        left = 50.perc
        marginTop = (-350).px
        marginLeft = (-450).px
        zIndex = 105 // Higher than StatsWidget
        padding = 20.px
        
        display = Display.NONE
        flexDirection = FlexDirection.COLUMN
        alignItems = AlignItems.CENTER

        // Animation
        setStyle("animation", "dialogFadeIn 0.3s ease-out")

        // Content Wrapper
        vPanel(alignItems = AlignItems.CENTER, spacing = 10) {
            width = 100.perc
            flexGrow = 1
            
            add(this@PlayerInfoWidget.nameText)
            add(this@PlayerInfoWidget.descriptionText)
            add(this@PlayerInfoWidget.statsContainer)
        }

        // Footer (Close Button)
        hPanel(justify = JustifyContent.CENTER, alignItems = AlignItems.CENTER) {
            width = 100.perc
            marginTop = 20.px
            flexShrink = 0

            button("CLOSE", icon = "fas fa-times", className = "btn btn-play").onClick {
                this@PlayerInfoWidget.hide()
            }
        }
    }

    /**
     * Shows the player info popup and updates its contents.
     *
     * @param player Player data to render.
     */
    fun show(player: Player)
    {
        currentPlayer = player
        nameText.content = player.name
        descriptionText.content = player.shortDescription.ifEmpty { 
            player.description.ifEmpty { "No description available." }
        }

        updateStats(player)

        display = Display.FLEX
        visible = true
    }

    /**
     * Hides the player info popup.
     */
    override fun hide()
    {
        display = Display.NONE
        visible = false
    }

    /**
     * Rebuilds the stats indicators for the supplied player.
     *
     * @param player Player to display stats for.
     */
    fun updateStats(player: Player)
    {
        statsContainer.removeAll()

        // 1. Victory Points
        // Calculate total points including territory
        // Note: WorldStatsWidget calculates this dynamically but Player struct has 'victoryPoints'
        // We'll use the calculated value from World data to be consistent with leaderboard
        val ownedTerritoryPoints = globals.World.worldData.mapTiles
            .filter { it.ruler == player.name }
            .sumOf { it.pointValue }
        
        // Check if player has base VP in struct that should be added? 
        // In WorldStatsWidget we only summon ownedTerritoryPoints.
        // But in buildDemoPlayers we see 'victoryPoints = 26'. 
        // Let's assume the 'victoryPoints' property is a base or accumulated score, 
        // and territory points are dynamic. 
        // FOR NOW: Let's follow the WorldStatsWidget logic of summing territory points 
        // OR rely on the Player object if it's supposed to be the source of truth.
        // The prompt says "Number of points they own".
        // Let's assume Total Points = Player.victoryPoints + Territory Points? 
        // Or just Territory Points?
        // WorldStatsWidget uses ONLY territory points for the leaderboard.
        // Let's stick to Territory Points to ensure consistency with the leaderboard rank.
        
        val totalPoints = ownedTerritoryPoints

        // 2. Rank
        // Calculate rank based on territory points
        val allPlayersPoints = globals.World.worldData.activePlayers.map { p ->
            val pts = globals.World.worldData.mapTiles
                .filter { it.ruler == p.name }
                .sumOf { it.pointValue }
            p.name to pts
        }.sortedByDescending { it.second }
        
        val rank = allPlayersPoints.indexOfFirst { it.first == player.name } + 1
        val rankSuffix = when (rank)
        {
            1 ->
            {
                "st"
            }
            2 ->
            {
                "nd"
            }
            3 ->
            {
                "rd"
            }
            else ->
            {
                "th"
            }
        }

        statsContainer.add(createStatBox("Victory Points", "$totalPoints", "fas fa-trophy", Col.GOLD))
        statsContainer.add(createStatBox("Current Rank", "$rank$rankSuffix", "fas fa-chart-line", Col.LIGHTGREEN))
    }

    /**
     * Creates a stat bubble with label/icon/value.
     *
     * @param label Label text.
     * @param value Value text.
     * @param icon Icon name.
     * @param colorVal Accent color.
     * @return Configured [SimplePanel] representing the stat.
     */
    private fun createStatBox(label: String, value: String, icon: String, colorVal: Col): SimplePanel
    {
        return vPanel(alignItems = AlignItems.CENTER, spacing = 5)
        {
            width = 150.px
            padding = 15.px
            background = Background(Color.hex(0x1a1a2e))
            border = Border(1.px, BorderStyle.SOLID, Color.hex(0x383f59))
            borderRadius = 8.px

            icon(icon)
            {
                fontSize = 24.px
                color = Color.name(colorVal)
                marginBottom = 5.px
            }

            span(label)
            {
                color = Color.name(Col.GRAY)
                fontSize = 12.px
                fontWeight = FontWeight.BOLD
                textTransform = TextTransform.UPPERCASE
            }

            span(value)
            {
                color = Color.name(Col.WHITE)
                fontSize = 24.px
                fontWeight = FontWeight.BOLD
            }
        }
    }
}
