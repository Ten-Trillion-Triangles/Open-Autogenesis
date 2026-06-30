package ui

import enums.CommanderType
import io.kvision.core.*
import io.kvision.form.text.TextInput
import io.kvision.form.text.textInput
import io.kvision.html.*
import io.kvision.panel.SimplePanel
import io.kvision.panel.hPanel
import io.kvision.panel.vPanel
import io.kvision.utils.px
import io.kvision.utils.perc
import structs.Commander
import structs.matchmaking.GameType

/**
 * Two-step wizard for picking a commander and starting a match.
 *
 * **Step 1 — "Select Commander":** The user searches and picks one of their
 * locally cached commanders. The list takes the full dialog height so the
 * user can read the commander card text (Empire, Type, Trait, description)
 * without fighting a tiny scrollbar. The **Next** button is disabled until
 * a commander is selected.
 *
 * **Step 2 — "Choose Game Settings":** The user picks the game type (Single
 * Player vs. Multiplayer) and, in Single Player mode, the AI opponent count.
 * In Multiplayer mode the AI opponent section is hidden because matchmaking
 * picks the player count. The **Back** button returns to Step 1, preserving
 * the selected commander. The **Play** button invokes [onConfirmSelection]
 * with the chosen commander, game type, and AI opponent count.
 *
 * Splitting into two steps (rather than cramming everything into one
 * 900px-tall dialog) is the fix for the "1, 2, 3, and 4 player options get
 * pushed off the bottom of the widget" bug introduced by the "Prep ams and
 * multiplayer" commit. With one decision per step, neither screen needs to
 * compete for vertical space — the commander list gets a real
 * [commander-selection-list] container with `min-height: 320px` instead of
 * being squeezed to a 140px strip.
 */
class CommanderSelectionDialog(
    commanders: List<Commander>,
    private val onConfirmSelection: ((Commander, GameType, Int) -> Unit)? = null,
    private val onCancelSelection: (() -> Unit)? = null
) : SimplePanel(className = "commander-selection-overlay")
{
    private lateinit var titleLabel: io.kvision.html.H3
    private lateinit var step1Content: SimplePanel
    private lateinit var step2Content: SimplePanel
    private lateinit var backButton: Button
    private lateinit var cancelButton: Button
    private lateinit var nextButton: Button
    private lateinit var playButton: Button
    private var currentStep: Int = 1

    private var activeCard: CommanderCard? = null
    private var activeOpponentCard: OpponentCard? = null
    private var activeGameTypeCard: GameTypeCard? = null
    private var selectedCommanderValue: Commander? = null
    private var selectedAiOpponentCount: Int = 1
    private var selectedGameType: GameType = GameType.SINGLEPLAYER
    private lateinit var searchField: TextInput
    private lateinit var noCommanderMatches: SimplePanel
    private lateinit var opponentSection: SimplePanel
    private lateinit var multiplayerFooter: io.kvision.html.P
    private lateinit var playersFooter: io.kvision.html.P
    private val commanderCardEntries = mutableListOf<CommanderCardEntry>()
    val selectedCommander: Commander?
        get() = selectedCommanderValue

    init
    {
        position = Position.FIXED
        top = 0.px
        left = 0.px
        width = 100.perc
        height = 100.perc
        zIndex = 9100
        background = Background(Color("rgba(0, 0, 0, 0.85)"))
        display = Display.FLEX
        justifyContent = JustifyContent.CENTER
        alignItems = AlignItems.CENTER

        vPanel(className = "commander-selection-window") {
            width = 1265.px
            // Height is intentionally not fixed in Kotlin. The CSS class
            // `.commander-selection-window` uses
            // `max-height: calc(100vh - 48px)` so the dialog adapts to the
            // viewport. Each wizard step shows one decision worth of content
            // so the dialog never has to compete for vertical space.
            padding = 24.px
            spacing = 16
            background = Background(Color("#0c101f"))
            border = Border(2.px, BorderStyle.SOLID, Color("#3f4b75"))
            borderRadius = 14.px

            titleLabel = h3("Select Commander") {
                textAlign = TextAlign.CENTER
                fontSize = CssSize(30, UNIT.px)
                color = Color("#f4f6fb")
                margin = CssSize(0, UNIT.px)
            }

            if(commanders.isEmpty())
            {
                p("No commanders available. Create one from the Collection screen first.") {
                    fontSize = CssSize(18, UNIT.px)
                    textAlign = TextAlign.CENTER
                    color = Color("#a0a8cc")
                    margin = CssSize(24, UNIT.px)
                }
            }
            else
            {
                commanderCardEntries.clear()

                // === Step 1: Commander selection ===
                // The section wrapper is a flex child of the dialog window.
                // It claims the remaining vertical space after the title and
                // footer. Its `overflow: hidden` plus the inner list's own
                // `overflow-y: auto` make the list the only scrollable region
                // on this step. All sizing (`flex`, `min-height`,
                // `overflow`) comes from the CSS class
                // `.commander-selection-step-1` — setting any of them inline
                // would override the CSS and break the flex layout.
                step1Content = vPanel(spacing = 12, className = "commander-selection-step-1")
                {
                    width = 100.perc

                    hPanel(
                        className = "collection-search",
                        spacing = 12,
                        alignItems = AlignItems.CENTER
                    ) {
                        width = 100.perc
                        icon("fas fa-search")
                        searchField = textInput {
                            width = 100.perc
                            placeholder = "Search commanders..."
                            onInput {
                                applyCommanderFilter()
                            }
                            setAttribute("spellcheck", "false")
                        }
                    }

                    vPanel(className = "commander-selection-list") {
                        // The list is the only scrollable region on Step 1.
                        // It fills the available vertical space (whatever
                        // the section wrapper above gives it) and scrolls
                        // internally when there are more commanders than
                        // fit. The previous fixed 400px height was removed
                        // because the Step 1 layout now has the entire
                        // dialog to itself.
                        flexGrow = 1
                        flexShrink = 1
                        minHeight = 0.px
                        overflowY = Overflow.AUTO
                        spacing = 12
                        commanders.forEach { commander ->
                            val card = CommanderCard(commander) { commanderCard ->
                                handleCommanderSelection(commanderCard)
                            }
                            commanderCardEntries.add(CommanderCardEntry(commander.name.lowercase(), card.cardPanel))
                            add(card.cardPanel)
                        }
                        noCommanderMatches = p("No commanders match that search.") {
                            fontSize = CssSize(16, UNIT.px)
                            textAlign = TextAlign.CENTER
                            color = Color("#bec6e3")
                            display = Display.NONE
                            marginTop = 12.px
                        }
                    }

                    applyCommanderFilter()
                }
                add(step1Content)

                // === Step 2: Game settings ===
                // Built upfront and hidden until the user clicks Next. This
                // way the GameType / OpponentCard / footer state is set up
                // once at dialog open time, not lazily on step transition.
                step2Content = buildStep2Content()
                step2Content.addCssClass("commander-selection-step-2")
                step2Content.display = Display.NONE
                add(step2Content)
            }

            // === Footer buttons ===
            // Always visible: Cancel. Conditionally visible: Back (step 2),
            // Next (step 1), Play (step 2). The Play button replaces the
            // previous OK button and is enabled only after a commander is
            // selected (the selected commander carries over from Step 1).
            hPanel(spacing = 16, alignItems = AlignItems.CENTER, justify = JustifyContent.CENTER) {
                width = CssSize(100, UNIT.perc)
                marginTop = 4.px

                cancelButton = button("Cancel") {
                    width = CssSize(180, UNIT.px)
                    height = CssSize(60, UNIT.px)
                    addCssClass("btn-secondary-action")
                    onClick {
                        clearSelection()
                        onCancelSelection?.invoke()
                        this@CommanderSelectionDialog.hide()
                    }
                }

                backButton = button("Back") {
                    width = CssSize(180, UNIT.px)
                    height = CssSize(60, UNIT.px)
                    addCssClass("btn-secondary-action")
                    display = Display.NONE
                    onClick {
                        goToStep(1)
                    }
                }

                nextButton = button("Next") {
                    width = CssSize(180, UNIT.px)
                    height = CssSize(60, UNIT.px)
                    addCssClass("btn-secondary-action")
                    disabled = true
                    onClick {
                        goToStep(2)
                    }
                }

                playButton = button("Play") {
                    width = CssSize(220, UNIT.px)
                    height = CssSize(60, UNIT.px)
                    addCssClass("btn-secondary-action")
                    display = Display.NONE
                    disabled = true
                    onClick {
                        confirmAndPlay()
                    }
                }
            }
        }
    }

    /**
     * Builds the Step 2 content panel: Game Type selector (Single Player /
     * Multiplayer) and the Match Configuration section (AI opponent count,
     * hidden in Multiplayer). The footer summary line ("Players: 2" or
     * "PvP — match pool: default") is also rendered here so it lives next
     * to the controls it summarizes.
     */
    private fun buildStep2Content(): SimplePanel
    {
        return vPanel(spacing = 16)
        {
            width = 100.perc
            flexGrow = 1
            minHeight = 0.px

            h4("Game Type") {
                textAlign = TextAlign.CENTER
                fontSize = CssSize(24, UNIT.px)
                color = Color("#f4f6fb")
                marginBottom = 4.px
            }

            hPanel(spacing = 20, justify = JustifyContent.CENTER) {
                width = 100.perc

                val singlePlayerCard = GameTypeCard(
                    gameType = GameType.SINGLEPLAYER,
                    title = "Single Player",
                    description = "Play vs. AI locally",
                    iconName = "fas fa-robot"
                ) { handleGameTypeSelection(it) }
                val multiplayerCard = GameTypeCard(
                    gameType = GameType.MULTIPLAYER,
                    title = "Multiplayer",
                    description = "Live PvP via AccelByte",
                    iconName = "fas fa-globe"
                ) { handleGameTypeSelection(it) }

                add(singlePlayerCard.cardPanel)
                add(multiplayerCard.cardPanel)

                // Default selection
                singlePlayerCard.setActive(true)
                activeGameTypeCard = singlePlayerCard
                selectedGameType = GameType.SINGLEPLAYER
            }

            // Match Configuration (AI opponents) — hidden in Multiplayer.
            opponentSection = vPanel(spacing = 12) {
                width = 100.perc
                flexGrow = 1
                minHeight = 0.px

                h4("Match Configuration") {
                    textAlign = TextAlign.CENTER
                    fontSize = CssSize(24, UNIT.px)
                    color = Color("#f4f6fb")
                    marginBottom = 4.px
                }

                hPanel(spacing = 20, justify = JustifyContent.CENTER) {
                    width = 100.perc

                    val card1 = OpponentCard(1, "1 vs 1: Duel") { handleOpponentSelection(it) }
                    val card2 = OpponentCard(2, "1 vs 2: Triangle") { handleOpponentSelection(it) }
                    val card3 = OpponentCard(3, "1 vs 3: Standard") { handleOpponentSelection(it) }

                    add(card1.cardPanel)
                    add(card2.cardPanel)
                    add(card3.cardPanel)

                    card1.setActive(true)
                    activeOpponentCard = card1
                    selectedAiOpponentCount = 1
                }
            }

            // Footer summary line that changes based on game type.
            hPanel(spacing = 16, alignItems = AlignItems.CENTER, justify = JustifyContent.CENTER) {
                width = 100.perc

                playersFooter = p("Players: 2") {
                    fontSize = CssSize(18, UNIT.px)
                    color = Color("#92a4d4")
                    textAlign = TextAlign.CENTER
                    margin = CssSize(0, UNIT.px)
                }

                multiplayerFooter = p("PvP — match pool: default") {
                    fontSize = CssSize(18, UNIT.px)
                    color = Color("#92a4d4")
                    textAlign = TextAlign.CENTER
                    margin = CssSize(0, UNIT.px)
                    display = Display.NONE
                }
                add(playersFooter)
                add(multiplayerFooter)
            }
        }
    }

    private fun goToStep(step: Int)
    {
        currentStep = step
        applyStepVisibility()
    }

    private fun applyStepVisibility()
    {
        val isStep1 = currentStep == 1

        // Title changes to indicate which decision the user is on.
        titleLabel.content = if(isStep1) "Select Commander" else "Choose Game Settings"

        // Only one step's content is in the layout at a time.
        step1Content.display = if(isStep1) Display.FLEX else Display.NONE
        step1Content.visible = isStep1
        step2Content.display = if(isStep1) Display.NONE else Display.FLEX
        step2Content.visible = !isStep1

        // Back is hidden in Step 1 (no previous step). Next is hidden in
        // Step 2 (Play replaces it). Play is hidden in Step 1.
        backButton.display = if(isStep1) Display.NONE else Display.FLEX
        backButton.visible = !isStep1
        nextButton.display = if(isStep1) Display.FLEX else Display.NONE
        nextButton.visible = isStep1
        playButton.display = if(isStep1) Display.NONE else Display.FLEX
        playButton.visible = !isStep1
        // The commander selected in Step 1 carries over, so Play is
        // enabled as soon as we enter Step 2.
        playButton.disabled = selectedCommanderValue == null
    }

    private fun confirmAndPlay()
    {
        val commander = selectedCommanderValue ?: return
        onConfirmSelection?.invoke(commander, selectedGameType, selectedAiOpponentCount)
        this@CommanderSelectionDialog.hide()
    }

    private fun handleCommanderSelection(card: CommanderCard)
    {
        if(activeCard === card)
        {
            clearSelection()
            return
        }

        activeCard?.setActive(false)
        card.setActive(true)
        activeCard = card
        selectedCommanderValue = card.commander
        nextButton.disabled = false
    }

    private fun handleOpponentSelection(card: OpponentCard)
    {
        if(activeOpponentCard === card) return

        activeOpponentCard?.setActive(false)
        card.setActive(true)
        activeOpponentCard = card
        selectedAiOpponentCount = card.aiCount
        updatePlayersFooter()
    }

    private fun handleGameTypeSelection(card: GameTypeCard)
    {
        if(activeGameTypeCard === card) return

        activeGameTypeCard?.setActive(false)
        card.setActive(true)
        activeGameTypeCard = card
        selectedGameType = card.gameType
        applyGameTypeVisibility()
    }

    private fun applyGameTypeVisibility()
    {
        val isMultiplayer = selectedGameType == GameType.MULTIPLAYER
        opponentSection.display = if(isMultiplayer) Display.NONE else Display.FLEX
        opponentSection.visible = !isMultiplayer
        multiplayerFooter.display = if(isMultiplayer) Display.FLEX else Display.NONE
        multiplayerFooter.visible = isMultiplayer
        playersFooter.display = if(isMultiplayer) Display.NONE else Display.FLEX
        playersFooter.visible = !isMultiplayer
    }

    private fun updatePlayersFooter()
    {
        if(::playersFooter.isInitialized)
        {
            playersFooter.content = "Players: ${selectedAiOpponentCount + 1}"
        }
    }

    private fun clearSelection()
    {
        selectedCommanderValue = null
        activeCard?.setActive(false)
        activeCard = null
        nextButton.disabled = true
        if(::playButton.isInitialized) playButton.disabled = true
    }

    override fun hide()
    {
        super.hide()
        clearSelection()
        this.parent?.remove(this)
    }

    private fun applyCommanderFilter()
    {
        if(!this::searchField.isInitialized || !this::noCommanderMatches.isInitialized)
        {
            return
        }

        val filterValue = searchField.value?.trim()?.lowercase().orEmpty()
        var matchCount = 0
        commanderCardEntries.forEach { entry ->
            val matches = filterValue.isBlank() || entry.name.contains(filterValue)
            entry.panel.display = if(matches) Display.FLEX else Display.NONE
            entry.panel.visible = matches
            if(matches)
            {
                matchCount++
            }
        }
        val shouldShowEmptyState = matchCount == 0
        noCommanderMatches.visible = shouldShowEmptyState
        noCommanderMatches.display = if(shouldShowEmptyState) Display.FLEX else Display.NONE
    }

    private data class CommanderCardEntry(
        val name: String,
        val panel: SimplePanel
    )

    private class OpponentCard(
        val aiCount: Int,
        val description: String,
        private val onSelect: (OpponentCard) -> Unit
    )
    {
        val cardPanel: SimplePanel

        init
        {
            cardPanel = SimplePanel()
            cardPanel.addCssClass("commander-selection-card")
            cardPanel.width = 300.px
            cardPanel.padding = CssSize(15, UNIT.px)
            cardPanel.borderRadius = 10.px
            cardPanel.border = Border(1.px, BorderStyle.SOLID, Color("rgba(255, 255, 255, 0.08)"))
            cardPanel.cursor = Cursor.POINTER

            cardPanel.onClick {
                onSelect(this@OpponentCard)
            }

            cardPanel.vPanel(spacing = 4, alignItems = AlignItems.CENTER) {
                hPanel(spacing = 10, alignItems = AlignItems.CENTER) {
                    icon("fas fa-user-friends") {
                        fontSize = 24.px
                        color = Color("#5e6adc")
                    }

                    span("${aiCount + 1} Players") {
                        fontSize = CssSize(20, UNIT.px)
                        fontWeight = FontWeight.BOLD
                        color = Color("#ffffff")
                    }
                }

                p(description) {
                    fontSize = CssSize(14, UNIT.px)
                    color = Color("#cbd3ff")
                    textAlign = TextAlign.CENTER
                    margin = 0.px
                }
            }
        }

        fun setActive(active: Boolean)
        {
            if(active)
            {
                cardPanel.addCssClass("commander-selection-card-active")

                // One-time click animations
                cardPanel.addCssClass("commander-selection-card-click-flash")
                cardPanel.addCssClass("commander-selection-card-click-sheen")

                kotlinx.browser.window.setTimeout({
                    cardPanel.removeCssClass("commander-selection-card-click-flash")
                    cardPanel.removeCssClass("commander-selection-card-click-sheen")
                }, 800)
            }
            else
            {
                cardPanel.removeCssClass("commander-selection-card-active")
            }
        }
    }

    private class GameTypeCard(
        val gameType: GameType,
        val title: String,
        val description: String,
        val iconName: String,
        private val onSelect: (GameTypeCard) -> Unit
    )
    {
        val cardPanel: SimplePanel

        init
        {
            cardPanel = SimplePanel()
            cardPanel.addCssClass("commander-selection-card")
            cardPanel.width = 360.px
            cardPanel.padding = CssSize(20, UNIT.px)
            cardPanel.borderRadius = 10.px
            cardPanel.border = Border(1.px, BorderStyle.SOLID, Color("rgba(255, 255, 255, 0.08)"))
            cardPanel.cursor = Cursor.POINTER

            cardPanel.onClick {
                onSelect(this@GameTypeCard)
            }

            cardPanel.vPanel(spacing = 6, alignItems = AlignItems.CENTER) {
                hPanel(spacing = 10, alignItems = AlignItems.CENTER) {
                    icon(iconName) {
                        fontSize = 26.px
                        color = Color("#5e6adc")
                    }

                    span(title) {
                        fontSize = CssSize(22, UNIT.px)
                        fontWeight = FontWeight.BOLD
                        color = Color("#ffffff")
                    }
                }

                p(description) {
                    fontSize = CssSize(14, UNIT.px)
                    color = Color("#cbd3ff")
                    textAlign = TextAlign.CENTER
                    margin = 0.px
                }
            }
        }

        fun setActive(active: Boolean)
        {
            if(active)
            {
                cardPanel.addCssClass("commander-selection-card-active")

                // One-time click animations
                cardPanel.addCssClass("commander-selection-card-click-flash")
                cardPanel.addCssClass("commander-selection-card-click-sheen")

                kotlinx.browser.window.setTimeout({
                    cardPanel.removeCssClass("commander-selection-card-click-flash")
                    cardPanel.removeCssClass("commander-selection-card-click-sheen")
                }, 800)
            }
            else
            {
                cardPanel.removeCssClass("commander-selection-card-active")
            }
        }
    }

    private class CommanderCard(
        val commander: Commander,
        private val onSelect: (CommanderCard) -> Unit
    )
    {
        val cardPanel: SimplePanel

        init
        {
            cardPanel = SimplePanel()
            cardPanel.addCssClass("commander-selection-card")
            cardPanel.padding = CssSize(18, UNIT.px)
            cardPanel.borderRadius = 10.px
            cardPanel.border = Border(1.px, BorderStyle.SOLID, Color("rgba(255, 255, 255, 0.08)"))
            cardPanel.cursor = Cursor.POINTER

            cardPanel.onClick {
                onSelect(this@CommanderCard)
            }

            cardPanel.vPanel(spacing = 6) {
                hPanel(spacing = 10, alignItems = AlignItems.CENTER) {
                    icon(iconForType(commander.type)) {
                        fontSize = 28.px
                        color = Color("#5e6adc")
                    }

                    span(commander.name.ifBlank { "Unnamed Commander" }) {
                        fontSize = CssSize(22, UNIT.px)
                        fontWeight = FontWeight.BOLD
                        color = Color("#ffffff")
                    }
                }

                span("Empire: ${commander.empire.ifBlank { "Not specified" }}") {
                    fontSize = CssSize(14, UNIT.px)
                    color = Color("#92a4d4")
                    addCssClass("commander-selection-card-meta")
                }

                span("Type: ${commander.type.name} • Trait: ${commander.trait.name}") {
                    fontSize = CssSize(14, UNIT.px)
                    color = Color("#92a4d4")
                    addCssClass("commander-selection-card-meta")
                }

                p(commander.description.ifBlank { "No description recorded for this commander yet." }) {
                    fontSize = CssSize(14, UNIT.px)
                    color = Color("#cbd3ff")
                    marginTop = 6.px
                    addCssClass("commander-selection-card-description")
                }
            }
        }

        fun setActive(active: Boolean)
        {
            if(active)
            {
                cardPanel.addCssClass("commander-selection-card-active")

                // Trigger one-time click animations
                cardPanel.addCssClass("commander-selection-card-click-flash")
                cardPanel.addCssClass("commander-selection-card-click-sheen")

                // Remove animation classes after completion
                kotlinx.browser.window.setTimeout({
                    cardPanel.removeCssClass("commander-selection-card-click-flash")
                    cardPanel.removeCssClass("commander-selection-card-click-sheen")
                }, 800)
            }
            else
            {
                cardPanel.removeCssClass("commander-selection-card-active")
                cardPanel.removeCssClass("commander-selection-card-click-flash")
                cardPanel.removeCssClass("commander-selection-card-click-sheen")
            }
        }

        private fun iconForType(type: CommanderType) = when(type)
        {
            CommanderType.Flying -> "fas fa-feather-alt"
            CommanderType.Aquatic -> "fas fa-water"
            CommanderType.Land -> "fas fa-mountain"
        }
    }
}
