package ui.gameplay

import io.kvision.core.*
import io.kvision.html.*
import io.kvision.panel.SimplePanel
import io.kvision.panel.hPanel
import io.kvision.panel.vPanel
import io.kvision.utils.px
import io.kvision.utils.vh
import io.kvision.utils.vw
import io.kvision.utils.perc
import org.ttt.autogenesis.logging.LogCategory
import org.ttt.autogenesis.logging.Logger
import org.ttt.autogenesis.network.GameOverData
import globals.KEnv
import ui.MainMenu

/**
 * Cinematic fanfare widget displayed at the end of a game session.
 * 
 * @param data The game results to display.
 * @param demo If true, populates with mock data for testing.
 */
class GameEndWidget(data: GameOverData? = null, demo: Boolean = false) : SimplePanel(className = "login-widget-window")
{
    private var results: GameOverData? = data

    private val headerText = h1("") {
        fontSize = 54.px
        fontWeight = FontWeight.BOLD
        letterSpacing = 6.px
        textAlign = TextAlign.CENTER
        marginBottom = 20.px
        textShadow = TextShadow(0.px, 0.px, 20.px, Color.name(Col.CYAN))
    }

    private val winnerNameText = h2("") {
        fontSize = 28.px
        color = Color.name(Col.GOLD)
        textAlign = TextAlign.CENTER
        marginBottom = 30.px
    }

    private val statsContainer = hPanel(spacing = 12, justify = JustifyContent.SPACEAROUND) {
        width = 100.perc
        marginTop = 6.px
        marginBottom = 16.px
        flexWrap = FlexWrap.WRAP
    }

    private val placementsContainer = vPanel(spacing = 8, alignItems = AlignItems.CENTER) {
        width = 100.perc
        marginBottom = 16.px
    }

    private val contentPanel = vPanel {
        spacing = 16
        alignItems = AlignItems.CENTER
        width = 100.perc
        setStyle("animation", "dialogFadeIn 0.5s cubic-bezier(0.16, 1, 0.3, 1)")
    }

    init
    {
        // Viewport Positioning
        width = 90.vw
        maxWidth = 820.px
        minWidth = 360.px
        maxHeight = 95.vh
        position = Position.FIXED
        top = 50.perc
        left = 50.perc
        setStyle("transform", "translate(-50%, -50%)")
        zIndex = 5000
        padding = 32.px
       
        overflowY = Overflow.VISIBLE
        display = Display.NONE
        flexDirection = FlexDirection.COLUMN
        alignItems = AlignItems.CENTER
        
        contentPanel.apply {
            // Winner Avatar & Name
            add(headerText)
            vPanel(alignItems = AlignItems.CENTER) {
                marginBottom = 20.px
            div(className = "commander-selection-card-active") {
                width = 120.px
                height = 120.px
                borderRadius = 60.px
                    display = Display.FLEX
                    alignItems = AlignItems.CENTER
                    justifyContent = JustifyContent.CENTER
                    fontSize = 48.px
                    content = "👤"
                    marginBottom = 15.px
                    boxShadow = BoxShadow(0.px, 0.px, 30.px, 0.px, Color.name(Col.CYAN))
                }
                add(this@GameEndWidget.winnerNameText)
            }
            add(statsContainer)
            add(placementsContainer)
            button("RETURN TO MAIN MENU", className = "btn btn-play") {
                width = 100.perc
                maxWidth = 360.px
                height = 56.px
                fontSize = 18.px
                fontWeight = FontWeight.BOLD
                letterSpacing = 2.px
                marginTop = 12.px
                onClick {
                    returnToMainMenu()
                }
            }
        }
        add(contentPanel)

        if (demo) populateDemoData()
        updateUI()
    }

    private fun updateUI()
    {
        results?.let { res ->
            Logger.info(
                LogCategory.UI,
                "GameEndWidget: Rendering results for ${res.winnerName} (victory=${res.isVictory}, rounds=${res.rounds}, tieResolved=${res.tieResolvedByResourceScore})."
            )
            headerText.content = if (res.isVictory) "VICTORY" else "DEFEAT"
            headerText.color = if (res.isVictory) Color.name(Col.CYAN) else Color.name(Col.CRIMSON)
            headerText.textShadow = TextShadow(0.px, 0.px, 20.px, if (res.isVictory) Color.name(Col.CYAN) else Color.name(Col.CRIMSON))
            
            winnerNameText.content = res.winnerName.uppercase()
            
            statsContainer.removeAll()
            statsContainer.add(createStatCard("ROUNDS", res.rounds.toString(), "fas fa-clock"))
            statsContainer.add(createStatCard("TERRITORIES", res.territoriesClaimed.toString(), "fas fa-map-marked-alt"))
            statsContainer.add(createStatCard("VICTORY POINTS", res.victoryPoints.toString(), "fas fa-trophy"))

            placementsContainer.removeAll()
            if(res.tieResolvedByResourceScore)
            {
                val tieNote = if(res.tieResolvedRandomly)
                {
                    "Tie resolved by resource score, then random selection."
                }
                else
                {
                    "Tie resolved by resource score."
                }
                placementsContainer.add(p(tieNote) {
                    color = Color.name(Col.LIGHTGRAY)
                    fontSize = 14.px
                })
            }

            res.placements.take(5).forEach { placement ->
                val role = if(placement.isPlayer) "Player" else "NPC"
                placementsContainer.add(
                    p("${placement.rank}. ${placement.name} (${placement.territoryPoints} territory points, $role)") {
                        color = Color.name(Col.WHITE)
                        fontSize = 16.px
                    }
                )
            }
        }
    }

    private fun createStatCard(label: String, value: String, icon: String): SimplePanel
    {
        return vPanel(alignItems = AlignItems.CENTER) {
            width = 180.px
            padding = 16.px
            background = Background(Color.rgba(20, 25, 45, 180))
            border = Border(1.px, BorderStyle.SOLID, Color.rgba(94, 106, 220, 100))
            borderRadius = 12.px
            
            i(className = icon) {
                fontSize = 28.px
                color = Color.name(Col.CYAN)
                marginBottom = 10.px
            }
            
            span(value) {
                fontSize = 24.px
                fontWeight = FontWeight.BOLD
                color = Color.name(Col.WHITE)
            }
            
            span(label) {
                fontSize = 11.px
                color = Color.name(Col.LIGHTGRAY)
                opacity = 0.7
                marginTop = 5.px
            }
        }
    }

    private fun populateDemoData()
    {
        results = GameOverData(
            winnerName = "Commander Shepard",
            isVictory = true,
            rounds = 14,
            victoryPoints = 85,
            territoriesClaimed = 12,
            placements = listOf(
                org.ttt.autogenesis.network.PlacementEntry("Commander Shepard", 1, 26, true),
                org.ttt.autogenesis.network.PlacementEntry("The Illusive Man", 2, 19, true),
                org.ttt.autogenesis.network.PlacementEntry("General Vaskov", 3, 11, false)
            ),
            tieResolvedByResourceScore = false,
            tieResolvedRandomly = false
        )
    }

    private fun returnToMainMenu()
    {
        // Cleanup and transition
        KEnv.appStack?.add(MainMenu())
        KEnv.appStack?.activeIndex = 1
    }

    /**
     * Shows the fanfare widget.
     * 
     * @param data The game results to display. If null, and demo was set or results exist, it will show existing.
     */
    fun show(data: GameOverData? = null)
    {
        if (data != null) {
            results = data
        } else if (results == null) {
            populateDemoData()
        }
        
        updateUI()
        display = Display.FLEX
        visible = true
    }
}