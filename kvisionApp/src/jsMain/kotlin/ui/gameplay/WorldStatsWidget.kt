package ui.gameplay

import enums.NpcType
import enums.ResourceType
import enums.TerritoryType
import globals.World
import io.kvision.core.*
import io.kvision.html.button
import io.kvision.html.h4
import io.kvision.html.p
import io.kvision.html.span
import io.kvision.panel.SimplePanel
import io.kvision.panel.hPanel
import io.kvision.panel.vPanel
import io.kvision.utils.perc
import io.kvision.utils.px
import structs.Npc
import structs.Player
import structs.Resource
import structs.Territory

/**
 * World Stats Popup Window. Displays global game info and leaderboard.
 */
class WorldStatsWidget(demo: Boolean = false) : SimplePanel(className = "login-widget-window")
{

    var demo = demo

    private val roundText = p(if (demo) "Round 3" else "") {
        fontSize = 24.px
        fontWeight = FontWeight.BOLD
        color = Color.name(Col.WHITE)
        marginBottom = 10.px
        textAlign = TextAlign.CENTER
        width = 100.perc
    }

    private val pointsText = p(if (demo) "Total World Points: 82" else "") {
        fontSize = 16.px
        color = Color.name(Col.LIGHTGRAY)
        marginBottom = 20.px
        textAlign = TextAlign.CENTER
        width = 100.perc
    }

    private val leaderboardContainer = vPanel {
        spacing = 5
        width = 100.perc
        alignItems = AlignItems.CENTER
    }

    init
    {
        // Popup Styling & Positioning
        width = 540.px
        height = 650.px
        minWidth = 520.px
        minHeight = 620.px
        maxWidth = CssSize(92, UNIT.vw)
        maxHeight = CssSize(90, UNIT.vh)
        position = Position.FIXED
        top = 50.perc
        left = 50.perc
        marginTop = (-300).px // Half of height
        marginLeft = (-250).px // Half of width
        zIndex = 100
        padding = 20.px
        
        display = Display.NONE // Hidden by default
        flexDirection = FlexDirection.COLUMN
        
        // Animation
        setStyle("animation", "dialogFadeIn 0.3s ease-out")

        // Content Container
        vPanel(alignItems = AlignItems.CENTER, spacing = 10) {
            width = 100.perc
            flexGrow = 1
            overflow = Overflow.HIDDEN
            
            // Header
            h4("World Stats") {
                color = Color.name(Col.CYAN)
                fontSize = 28.px
                fontWeight = FontWeight.BOLD
                marginBottom = 20.px
                textShadow = io.kvision.core.TextShadow(0.px, 0.px, 5.px, Color.name(Col.CYAN))
            }

            val scrollPanel = vPanel(spacing = 12) {
                width = 100.perc
                flexGrow = 1
                overflow = Overflow.AUTO
            }

            scrollPanel.add(this@WorldStatsWidget.roundText)
            scrollPanel.add(this@WorldStatsWidget.pointsText)

            scrollPanel.add(h4("Leaderboard") {
                color = Color.name(Col.GOLD)
                fontSize = 20.px
                fontWeight = FontWeight.BOLD
                marginBottom = 10.px
                marginTop = 10.px
                textAlign = TextAlign.CENTER
                width = 100.perc
            })

            scrollPanel.add(this@WorldStatsWidget.leaderboardContainer)

            add(scrollPanel)
        }

        // Footer (Close Button)
        hPanel(justify = JustifyContent.CENTER, alignItems = AlignItems.CENTER) {
            width = 100.perc
            marginTop = 20.px
            flexShrink = 0

            button("CLOSE", icon = "fas fa-times", className = "btn btn-play").onClick {
                this@WorldStatsWidget.hide()
            }
        }
    }

    /**
     * Displays and refreshes the world stats window.
     */
    override fun show()
    {
        update()
        display = Display.FLEX
        visible = true
        if (this@WorldStatsWidget.demo)
        {
            populateDemoWorldState()
            update()
        }
        refresh()
    }

    /**
     * Hides the world stats window.
     */
    override fun hide()
    {
        display = Display.NONE
        visible = false
        refresh()
    }

    /**
     * Updates the widget content with current world data.
     */
    fun update()
    {
        val worldData = World.worldData

        // 1. Update Round
        roundText.content = "Round: ${worldData.roundNumber}"

        // 2. Update Points
        // Max Points: Sum of all tile points
        val maxPoints = worldData.mapTiles.sumOf { it.pointValue }
        
        // Used Points: Sum of points for tiles that have a ruler (Player or NPC) or are destroyed
        val usedPoints = worldData.mapTiles.filter { it.ruler.isNotEmpty() || it.isDestroyed }.sumOf { it.pointValue }

        pointsText.content = "Points Claimed: $usedPoints / $maxPoints"

        // 3. Update Leaderboard
        leaderboardContainer.removeAll()
        
        // Calculate actual points from territory ownership
        val playerPoints = worldData.activePlayers.map { player ->
            val ownedTerritoryPoints = worldData.mapTiles
                .filter { it.ruler == player.name }
                .sumOf { it.pointValue }
            player to ownedTerritoryPoints
        }.sortedByDescending { it.second }.take(4)
        
        playerPoints.forEachIndexed { index, (player, points) ->
            leaderboardContainer.add(
                hPanel(spacing = 10) {
                    width = 100.perc
                    alignItems = AlignItems.CENTER
                    justifyContent = JustifyContent.SPACEBETWEEN

                    span {
                        content = "${index + 1}. ${player.name}"
                        color = Color.name(Col.WHITE)
                        fontSize = 14.px
                        fontWeight = if (index == 0)
                        {
                            FontWeight.BOLD
                        }
                        else
                        {
                            FontWeight.NORMAL
                        }
                    }

                    span {
                        content = "$points VP"
                        color = Color.name(Col.LIGHTGREEN)
                        fontSize = 14.px
                        fontWeight = FontWeight.BOLD
                    }
                }
            )
        }
        
        if (playerPoints.isEmpty())
        {
             leaderboardContainer.add(
                 p("No active commanders") {
                     color = Color.name(Col.GRAY)
                     fontSize = 12.px
                     fontStyle = io.kvision.core.FontStyle.ITALIC
                 }
             )
        }
        
        // NPCs Section
        if (worldData.npc.isNotEmpty())
        {
            leaderboardContainer.add(
                h4("Active NPCs") {
                    color = Color.name(Col.ORANGE)
                    fontSize = 16.px
                    fontWeight = FontWeight.BOLD
                    marginTop = 15.px
                    marginBottom = 10.px
                }
            )
            
            val npcPoints = worldData.npc.map { npc ->
                val ownedTerritoryPoints = worldData.mapTiles
                    .filter { it.ruler == npc.name }
                    .sumOf { it.pointValue }
                npc to ownedTerritoryPoints
            }.sortedByDescending { it.second }.take(4)
            
            npcPoints.forEach { (npc, points) ->
                leaderboardContainer.add(
                    hPanel(spacing = 10) {
                        width = 100.perc
                        alignItems = AlignItems.CENTER
                        justifyContent = JustifyContent.SPACEBETWEEN

                        span {
                            content = "• ${npc.name}"
                            color = Color.name(Col.LIGHTGRAY)
                            fontSize = 13.px
                        }

                        span {
                            content = "$points pts"
                            color = Color.name(Col.ORANGE)
                            fontSize = 13.px
                            fontWeight = FontWeight.BOLD
                        }
                    }
                )
            }
        }
    }

    /**
     * Populates demo world state for testing purposes.
     */
    private fun populateDemoWorldState()
    {
        val worldData = World.worldData
        worldData.name = "Aurora Convergence"
        worldData.storyScenario = "Nations scramble to survive the Emberfall anomaly."
        worldData.points = 92
        worldData.roundNumber = 8
        worldData.mapTiles.apply {
            clear()
            addAll(buildDemoMapTiles())
        }
        worldData.activePlayers.apply {
            clear()
            addAll(buildDemoPlayers())
        }
        worldData.npc.apply {
            clear()
            addAll(buildDemoNpcs())
        }

        World.worldData = worldData
    }

    /**
     * Builds demo map tiles for testing.
     *
     * @return List of demo territories.
     */
    private fun buildDemoMapTiles(): List<Territory>
    {
        return listOf(
            Territory(
                name = "Highpass Citadel",
                type = TerritoryType.Land,
                description = "Stone fortress overlooking the trade pass.",
                ruler = "Commander Juno",
                resource = Resource(
                    name = "Citadel Ward",
                    type = ResourceType.Military,
                    depletable = false,
                    destructible = true,
                    description = "Watch towers bristling on every wall.",
                    abilities = "Deploy to hold a border for two turns."
                ),
                pointValue = 5,
                isCaptured = true
            ),
            Territory(
                name = "Sunlit Terrace",
                type = TerritoryType.Coastline,
                description = "Lavender fields spilling toward a sapphire sea.",
                ruler = "Ambassador Kael",
                resource = Resource(
                    name = "Trade Winds",
                    type = ResourceType.Economic,
                    depletable = true,
                    destructible = false,
                    description = "Merchant guilds funnel wealth through the harbor.",
                    abilities = "Convert into trade credits with boosted yield."
                ),
                pointValue = 4,
                isCaptured = true
            ),
            Territory(
                name = "Glass Reef",
                type = TerritoryType.Underwater,
                description = "Shards of bioluminescent crystal pulse below the tide.",
                ruler = "",
                resource = Resource(
                    name = "Reef Echoes",
                    type = ResourceType.Scientific,
                    depletable = false,
                    destructible = true,
                    description = "Research pod tuned to tidal resonance.",
                    abilities = "Decode enemy plans while collapsing itself."
                ),
                pointValue = 4,
                isDestroyed = true
            ),
            Territory(
                name = "Ironwood Grove",
                type = TerritoryType.Land,
                description = "Towering trees from which iron sap drips.",
                ruler = "Marshal Sera",
                resource = Resource(
                    name = "Ironwood Aegis",
                    type = ResourceType.Technological,
                    depletable = true,
                    destructible = false,
                    description = "Living steel skins that heal slowly.",
                    abilities = "Absorb damage for two units before breaking."
                ),
                pointValue = 3,
                isCaptured = true
            ),
            Territory(
                name = "Cinder Wastes",
                type = TerritoryType.Land,
                description = "Volcanic plains still smoldering from last winter.",
                ruler = "",
                resource = Resource(
                    name = "Ember Communion",
                    type = ResourceType.Supernatural,
                    depletable = true,
                    destructible = true,
                    description = "Spirit-imbued flames that roar at the cost of lives.",
                    abilities = "Burns for a colossal strike then extinguishes entirely."
                ),
                pointValue = 6,
                isDestroyed = true
            ),
            Territory(
                name = "Aurora Ward",
                type = TerritoryType.Land,
                description = "Shielded plateau bridging continent and sky.",
                ruler = "Consul Orin",
                resource = Resource(
                    name = "Ward Lantern",
                    type = ResourceType.Magical,
                    depletable = false,
                    destructible = false,
                    description = "Renders allies unseen while the glow persists.",
                    abilities = "Grants immunity for the next enemy action."
                ),
                pointValue = 5,
                isCaptured = true
            ),
            Territory(
                name = "Mirror Islands",
                type = TerritoryType.Island,
                description = "Floating isles that reflect enemy tactics back at them.",
                ruler = "Commander Juno",
                resource = Resource(
                    name = "Refraction Field",
                    type = ResourceType.Technological,
                    depletable = true,
                    destructible = false,
                    description = "Bounces attacks with precision triangulation.",
                    abilities = "Redirects a strike back to its origin once per round."
                ),
                pointValue = 4,
                isCaptured = true
            ),
            Territory(
                name = "Fallow Delta",
                type = TerritoryType.Coastline,
                description = "Rivers braid golden sediment across the marsh.",
                ruler = "Rival NPC Harrow",
                resource = Resource(
                    name = "Delta Mend",
                    type = ResourceType.Economic,
                    depletable = false,
                    destructible = false,
                    description = "Restores trade flow after any interruption.",
                    abilities = "Rebuilds one destroyed resource automatically."
                ),
                pointValue = 2,
                isCaptured = true
            ),
            Territory(
                name = "Stormwatch Outpost",
                type = TerritoryType.Coastline,
                description = "A windswept cliff base peppered with storm lanterns.",
                ruler = "NPC Carver",
                resource = Resource(
                    name = "Storm Lantern",
                    type = ResourceType.Supernatural,
                    depletable = true,
                    destructible = true,
                    description = "Lanterns that wield charged lightning and scream warnings.",
                    abilities = "Discharge to delay enemy coupling while sacrificing the outpost."
                ),
                pointValue = 3,
                isCaptured = true
            )
        )
    }

    /**
     * Builds demo players for testing.
     *
     * @return List of demo players.
     */
    private fun buildDemoPlayers(): List<Player>
    {
        return listOf(
            Player(
                name = "Commander Juno",
                victoryPoints = 26,
                militaryPoints = 12,
                diplomacyPoints = 3,
                researchPoints = 2,
                summitPoints = 1,
                description = "Red Wolf strategist controlling the high peaks."
            ),
            Player(
                name = "Ambassador Kael",
                victoryPoints = 22,
                militaryPoints = 4,
                diplomacyPoints = 10,
                researchPoints = 5,
                summitPoints = 3,
                description = "Silver-tongued diplomat bending alliances."
            ),
            Player(
                name = "Marshal Sera",
                victoryPoints = 18,
                militaryPoints = 9,
                diplomacyPoints = 2,
                researchPoints = 4,
                summitPoints = 2,
                description = "Tactical juggernaut carving trenches."
            ),
            Player(
                name = "Consul Orin",
                victoryPoints = 14,
                militaryPoints = 2,
                diplomacyPoints = 8,
                researchPoints = 3,
                summitPoints = 4,
                description = "Barely a step behind with well-timed plays."
            )
        )
    }

    /**
     * Builds demo NPCs for testing.
     *
     * @return List of demo NPCs.
     */
    private fun buildDemoNpcs(): List<Npc>
    {
        return listOf(
            Npc(
                name = "Rival NPC Harrow",
                type = NpcType.Hostile,
                description = "Poisonous tactician haunting the marshlands.",
                pointValue = 12,
                resources = mutableListOf(
                    Resource(
                        name = "Harrow's Shade",
                        type = ResourceType.Supernatural,
                        depletable = false,
                        destructible = true,
                        description = "A phantom guard that roams the delta.",
                        abilities = "Drains nearby points when an enemy captures land."
                    )
                )
            ),
            Npc(
                name = "NPC Carver",
                type = NpcType.Active,
                description = "Carver roams the storms and shackles lightning.",
                pointValue = 8,
                resources = mutableListOf(
                    Resource(
                        name = "Carver's Beacon",
                        type = ResourceType.Technological,
                        depletable = true,
                        destructible = false,
                        description = "Focuses the outpost into a blazing beam.",
                        abilities = "Repels the next capture attempt but shatters afterwards."
                    )
                )
            )
        )
    }
}