package ui.gameplay

import enums.ResourceType
import globals.World
import structs.Player
import structs.Resource
import io.kvision.core.*
import io.kvision.html.button
import io.kvision.html.h4
import io.kvision.html.icon
import io.kvision.html.p
import io.kvision.html.span
import io.kvision.panel.SimplePanel
import io.kvision.panel.hPanel
import io.kvision.panel.vPanel
import io.kvision.utils.perc
import io.kvision.utils.px

/**
 * Player Resources popup window. Displays the local player's resources as well as demo content when requested.
 *
 * @param demo True to populate the overlay with canned content for layout validation.
 */
class PlayerResourcesWidget(demo: Boolean = false) : SimplePanel(className = "login-widget-window")
{
    private var currentPlayer: Player? = null

    private val headerText = h4("My Resources")
    {
        color = Color.name(Col.CYAN)
        fontSize = 28.px
        fontWeight = FontWeight.BOLD
        marginBottom = 20.px
        textShadow = io.kvision.core.TextShadow(0.px, 0.px, 5.px, Color.name(Col.CYAN))
    }

    private val resourcesContainer = vPanel {
        width = 90.perc
        spacing = 15
        alignItems = AlignItems.CENTER
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
        zIndex = 102
        padding = 20.px

        display = Display.NONE
        flexDirection = FlexDirection.COLUMN
        alignItems = AlignItems.CENTER
        setStyle("animation", "dialogFadeIn 0.3s ease-out")

        vPanel(alignItems = AlignItems.CENTER, spacing = 10)
        {
            width = 100.perc
            height = 100.perc
            overflow = Overflow.AUTO
            add(this@PlayerResourcesWidget.headerText)
            add(this@PlayerResourcesWidget.resourcesContainer)
        }

        hPanel(justify = JustifyContent.CENTER, alignItems = AlignItems.CENTER)
        {
            width = 100.perc
            marginTop = 20.px
            flexShrink = 0

            button("CLOSE", icon = "fas fa-times", className = "btn btn-play")
            {
                onClick {
                    this@PlayerResourcesWidget.hide()
                }
            }
        }

        if(demo)
        {
            populateDemoResources()
        }
    }

    /**
     * Shows the current local player's resources.
     */
    override fun show()
    {
        currentPlayer = World.localPlayer
        headerText.content = "My Resources"
        update()
        display = Display.FLEX
        visible = true
        refresh()
    }

    /**
     * Shows supplied player's resources.
     *
     * @param player Player whose resources should be rendered.
     */
    fun showPlayerResources(player: Player)
    {
        currentPlayer = player
        headerText.content = "${player.name}'s Resources"
        update()
        display = Display.FLEX
        visible = true
        refresh()
    }

    /**
     * Hides the widget and clears the visibility state.
     */
    override fun hide()
    {
        display = Display.NONE
        visible = false
        refresh()
    }

    /**
     * Populates demo data into the local player's resources for layout testing.
     */
    private fun populateDemoResources()
    {
        val localPlayer = getLocalPlayerFromGlobal()
        localPlayer.resources.clear()
        localPlayer.resources.addAll(buildDemoResources())
    }

    /**
     * Returns the local player as stored in the global world state.
     *
     * @return Current local player reference.
     */
    private fun getLocalPlayerFromGlobal(): Player
    {
        return World.localPlayer
    }

    /**
     * Provides a canned list of resources for demo mode.
     *
     * @return List of sample [Resource] entries.
     */
    private fun buildDemoResources(): List<Resource>
    {
        return listOf(
            Resource(
                name = "Volunteer Infantry Squad",
                type = ResourceType.Military,
                depletable = true,
                destructible = true,
                description = "Frontline volunteers trained for high attrition push.",
                abilities = "Spend to intercept an incoming attack and shield a territory."
            ),
            Resource(
                name = "Diplomatic Envoy Team",
                type = ResourceType.Diplomatic,
                depletable = false,
                destructible = false,
                description = "Carrying layered treaties and legitimate cover.",
                abilities = "Boosts diplomatic favor for the next two actions."
            ),
            Resource(
                name = "Economic Reserve Cache",
                type = ResourceType.Economic,
                depletable = true,
                destructible = false,
                description = "Hidden gold stockpiles routed through neutral banks.",
                abilities = "Convert into trade credits without penalty once per round."
            ),
            Resource(
                name = "Field Research Lab",
                type = ResourceType.Scientific,
                depletable = false,
                destructible = true,
                description = "Mobile research node humming with experimental energy.",
                abilities = "Guarantees a successful discovery but collapses afterward."
            ),
            Resource(
                name = "Autonomous Drone Swarm",
                type = ResourceType.Technological,
                depletable = true,
                destructible = true,
                description = "Drone swarms optimized for rapid reconnaissance.",
                abilities = "Clears a sector; drones expend their battery and self-destruct."
            ),
            Resource(
                name = "Ancestral Spirit Oath",
                type = ResourceType.Supernatural,
                depletable = false,
                destructible = true,
                description = "Bound spirit keeps watch but can be severed forever.",
                abilities = "Sacrifice to revive one destroyed subordinate."
            ),
            Resource(
                name = "Arcane Conduit",
                type = ResourceType.Magical,
                depletable = true,
                destructible = false,
                description = "Channels raw mana into precise spells.",
                abilities = "Rechargeable after three turns; consumption fuels a single spell."
            ),
            Resource(
                name = "Mercenary Syndicate",
                type = ResourceType.Subordinate,
                depletable = false,
                destructible = false,
                description = "Independent contractors loyal to the highest bidder.",
                abilities = "Deploys to assist any attack once per encounter."
            ),
            Resource(
                name = "Technomantic Beacon",
                type = ResourceType.Technological,
                depletable = false,
                destructible = false,
                description = "Bridge between rune and circuitry for alerts.",
                abilities = "Grants buffs to subordinate units without expending charges."
            ),
            Resource(
                name = "Mythril Supply Line",
                type = ResourceType.Economic,
                depletable = true,
                destructible = true,
                description = "Vital trade route vulnerable to sabotage.",
                abilities = "Provides a burst of resources while exposed; may collapse to enemy raids."
            )
        )
    }

    /**
     * Refreshes the displayed resources list.
     */
    fun update()
    {
        resourcesContainer.removeAll()
        val resources = currentPlayer?.resources?.filter { !it.isDestroyedOrDepleted } ?: emptyList()

        if(resources.isEmpty())
        {
            resourcesContainer.add(p("No resources owned.")
            {
                color = Color.name(Col.GRAY)
                fontStyle = io.kvision.core.FontStyle.ITALIC
            })
            return
        }

        resources.forEach { resource ->
            resourcesContainer.add(vPanel(alignItems = AlignItems.CENTER, spacing = 5)
            {
                width = 100.perc
                padding = 10.px
                border = Border(1.px, BorderStyle.SOLID, Color.name(Col.DARKGRAY))
                background = Background(Color.hex(0x00000033.toInt()))

                hPanel(alignItems = AlignItems.CENTER, spacing = 10)
                {
                    icon(getIconForType(resource.type))
                    {
                        fontSize = 24.px
                        color = Color.name(Col.GOLD)
                    }

                    span(resource.name)
                    {
                        fontSize = 18.px
                        fontWeight = FontWeight.BOLD
                        color = Color.name(Col.WHITE)
                    }
                }

                if(resource.depletable || resource.destructible)
                {
                    hPanel(alignItems = AlignItems.CENTER, spacing = 10)
                    {
                        if(resource.depletable)
                        {
                            span("Depletable")
                            {
                                fontSize = 12.px
                                color = Color.name(Col.ORANGE)
                                border = Border(1.px, BorderStyle.SOLID, Color.name(Col.ORANGE))
                                padding = 2.px
                                paddingLeft = 5.px
                                paddingRight = 5.px
                                borderRadius = 5.px
                            }
                        }
                        if(resource.destructible)
                        {
                            span("Destructible")
                            {
                                fontSize = 12.px
                                color = Color.name(Col.RED)
                                border = Border(1.px, BorderStyle.SOLID, Color.name(Col.RED))
                                padding = 2.px
                                paddingLeft = 5.px
                                paddingRight = 5.px
                                borderRadius = 5.px
                            }
                        }
                    }
                }

                if(resource.description.isNotEmpty())
                {
                    p(resource.description)
                    {
                        fontSize = 14.px
                        color = Color.name(Col.LIGHTGRAY)
                        fontStyle = io.kvision.core.FontStyle.ITALIC
                        textAlign = TextAlign.CENTER
                    }
                }
            })
        }
    }

    /**
     * Helper that picks the icon class for a resource type.
     *
     * @param type Resource type to inspect.
     * @return FontAwesome class string.
     */
    private fun getIconForType(type: ResourceType): String
    {
        return when(type)
        {
            ResourceType.Military ->
            {
                "fas fa-fighter-jet"
            }
            ResourceType.Diplomatic ->
            {
                "fas fa-handshake"
            }
            ResourceType.Economic ->
            {
                "fas fa-coins"
            }
            ResourceType.Scientific ->
            {
                "fas fa-flask"
            }
            ResourceType.Technological ->
            {
                "fas fa-microchip"
            }
            ResourceType.Supernatural ->
            {
                "fas fa-ghost"
            }
            ResourceType.Magical ->
            {
                "fas fa-hat-wizard"
            }
            ResourceType.Subordinate ->
            {
                "fas fa-users"
            }
            else ->
            {
                "fas fa-box-open"
            }
        }
    }
}