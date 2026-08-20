package ui.gameplay

import io.kvision.core.AlignItems
import io.kvision.core.CssSize
import io.kvision.core.JustifyItems
import io.kvision.core.onInput
import io.kvision.core.onEvent
import io.kvision.form.text.TextArea
import io.kvision.form.text.textArea
import io.kvision.html.button
import io.kvision.html.icon
import io.kvision.html.span
import io.kvision.panel.HPanel
import io.kvision.panel.gridPanel
import io.kvision.utils.perc
import io.kvision.utils.px
import kotlinx.browser.window
import org.ttt.autogenesis.logging.LogCategory
import org.ttt.autogenesis.logging.Logger
import utils.BrowserEnvironment
import utils.getBrowserEnvironment

/**
 * Bottom command box, houses a text area to allow a player to define their play or command, an execute button to send
 * the command, and the player stats, gameplay stats, and settings button.
 */
class CommandBox : HPanel()
{
    var onWorldClick: (() -> Unit)? = null
    var onResourcesClick: (() -> Unit)? = null
    var onStatsClick: (() -> Unit)? = null
    var onSettingsClick: (() -> Unit)? = null
    var onSend: ((String) -> Unit)? = null

    var commandTextArea: TextArea? = null

    var sendButton: io.kvision.html.Button? = null
    private var commandPopup: CommandPopup? = null

    private var highlightTimer: Int? = null
    private var persistentTurnState: Boolean? = null
    private var currentInputText = ""
    private var charCounter: io.kvision.html.Span? = null

    private fun submitCommand()
    {
        submitCurrentCommand()
    }

    /**
     * Programmatically sets the command text and submits it.
     * Used by DebugConsole.triggerExecuteCommand() via the Python debug signal bridge.
     */
    fun setAndSubmit(text: String)
    {
        currentInputText = text
        commandTextArea?.value = text
        charCounter?.content = "${text.length} / 500"
        submitCurrentCommand()
    }

    private fun submitCurrentCommand()
    {
        if(currentInputText.isNotBlank())
        {
            /**
             * Bound in [GameplayUI] at the construction of this widget.
             */
            onSend?.invoke(currentInputText)
        }
        currentInputText = "" //Then clear once we've sent it off.
        commandTextArea?.value = ""
        commandPopup?.hide()
        charCounter?.content = "0 / 500"
    }

    private fun applyTurnStateHighlight()
    {
        val isMyTurn = persistentTurnState ?: return
        if(isMyTurn)
        {
            addCssClass("command-box-flash")
            removeCssClass("command-box-flash-red")
            commandTextArea?.placeholder = "Type the action you want the game to take, ask any question, use slash commands (e.g., /chat, /prompts), and hit Shift+Enter to send. Try asking how to play this game!"
        }
        else
        {
            addCssClass("command-box-flash-red")
            removeCssClass("command-box-flash")
            commandTextArea?.placeholder = "Waiting for turn... (You can still use /chat or /ask, and Shift+Enter will send your input)."
        }
    }

    init
    {
        width = 100.perc
        height = 200.px
        alignItems = AlignItems.CENTER
        justifyItems = JustifyItems.STRETCH
        addCssClass("score-display-widget")
        spacing = 10

        /**
         * Send button. Will send a command to the agentic system when pressed.
         */
        sendButton = button("Send", className = "btn btn-play") {
            width = 200.px
            height = 100.px
            fontSize = 36.px

            onClick {
                submitCommand()
            }
        }

        // Wrapper panel for text area + popup (proper positioning context)
        val inputWrapper = io.kvision.panel.SimplePanel().apply {
            flexGrow = 1
            minWidth = 0.px // Allow shrinking if needed
            position = io.kvision.core.Position.RELATIVE // Make this the positioning context
        }

        val allCommands = listOf(
            AutocompleteItem("/play", "Take an action in the game", "/play"),
            AutocompleteItem("/ask", "Ask an AI agent for help or info about the game", "/ask"),
            AutocompleteItem("/open", "Open part of the UI through prompting", "/open"),
            AutocompleteItem("/chat", "Chat with an AI character", "/chat"),
            AutocompleteItem("/prompts", "Show status of active agents", "/prompts"),
            AutocompleteItem("/agentstream", "Stream the agents' reasoning in the work window", "/agentstream"),
            AutocompleteItem("/delegate", "Leave guidance for the AI that takes over when you step away", "/delegate")
        )
        
        commandPopup = CommandPopup { selectedItem ->
            val lastTriggerIndex = currentInputText.indexOfLast { it == '@' || it == '$' || it == '/' }
            val lastSpaceIndex = currentInputText.indexOfLast { it.isWhitespace() }
            
            val lastWord = if (lastTriggerIndex > lastSpaceIndex) {
                currentInputText.substring(lastTriggerIndex)
            } else {
                if (lastSpaceIndex == currentInputText.length - 1) "" else currentInputText.substring(lastSpaceIndex + 1)
            }

            if (lastWord.isNotEmpty()) {
                currentInputText = currentInputText.dropLast(lastWord.length) + selectedItem.replacementText + " "
                commandTextArea?.value = currentInputText
                charCounter?.content = "${currentInputText.length} / 500"
            }
            commandPopup?.hide() // Explicitly hide on selection callback
            commandTextArea?.focus()
        }
        
        commandTextArea = textArea {
            rows = 8

            /**
             * Stupid hack needed because KVision does not allow size control except through rows
             * and cols. Unfortunately this means sane sizing strategies are not possible here,
             * and we need hacky hard coded values for each browser.
             */
            cols = when(getBrowserEnvironment())
            {
                BrowserEnvironment.ELECTRON -> 150
                BrowserEnvironment.FIREFOX -> 190
                BrowserEnvironment.CHROMIUM -> 150
                BrowserEnvironment.UNSUPPORTED -> 150
            }

            fontSize = 24.px
            maxlength = 500
            placeholder = "Type the action you want the game to take, ask a question, use slash commands like /chat or /agentstream, and press Shift+Enter to send."

            onInput {
                currentInputText = this.value ?: ""
                charCounter?.content = "${currentInputText.length} / 500"
                
                // Get the current word being typed at the end of the input
                val lastTriggerIndex = currentInputText.indexOfLast { it == '@' || it == '$' || it == '/' }
                val lastSpaceIndex = currentInputText.indexOfLast { it.isWhitespace() }
                
                val lastWord = if (lastTriggerIndex > lastSpaceIndex) {
                    currentInputText.substring(lastTriggerIndex)
                } else {
                    if (lastSpaceIndex == currentInputText.length - 1) "" else currentInputText.substring(lastSpaceIndex + 1)
                }
                
                Logger.debug(LogCategory.UI, "CommandBox: lastWord extracted as '$lastWord'")

                when {
                    lastWord.startsWith("/") -> {
                        commandPopup?.show(lastWord, allCommands)
                    }
                    lastWord.startsWith("$") -> {
                        val macros = listOf(
                            AutocompleteItem("\$allResources", "All your owned resources", 
                                globals.World.localPlayer.resources.joinToString(", ") { it.name }.ifEmpty { "None" }),
                            AutocompleteItem("\$allTerritories", "All your captured territories", 
                                globals.World.localPlayer.capturedTerritory.joinToString(", ") { it.name }.ifEmpty { "None" }),
                            AutocompleteItem("\$myCommander", "Your commander name", 
                                globals.World.localPlayer.name)
                        )
                        commandPopup?.show(lastWord, macros)
                    }

                    lastWord.startsWith("@") -> {
                        val world = globals.World.worldData
                        val references = mutableListOf<AutocompleteItem>()
                        world.mapTiles.forEach { 
                            references.add(AutocompleteItem("@${it.name}", "[Territory]", "@${it.name}"))
                        }
                        world.npc.forEach { 
                            references.add(AutocompleteItem("@${it.name}", "[NPC]", "@${it.name}"))
                        }
                        world.activePlayers.forEach { 
                            references.add(AutocompleteItem("@${it.name}", "[Player]", "@${it.name}"))
                        }

                        val systemCharacters = listOf(
                            "Zuzusarogorata Suguruzands", "Haematemesis Coprophobia", "N'zelquin G'zeeloth",
                            "Bigwang McDouchebag", "Invis von Disappearo", "Big Googar", "Narjodo Bazingazooka",
                            "Shitty Bob", "Officer Dave", "Robert the Destroyer", "Quag LoBogon",
                            "Zeta Step Reasoner", "Narjan Goren", "Gl’kr’kr’kr’k Shshshsh-shsh-‘’’’////",
                            "Nordold Trable"
                        )
                        systemCharacters.forEach {
                            references.add(AutocompleteItem("@$it", "[Character]", "@$it"))
                        }

                        commandPopup?.show(lastWord, references.distinctBy { it.label })
                    }
                    else -> {
                        if(commandPopup?.visible == true)
                        {
                            commandPopup?.hide()
                        }
                    }
                }
            }

            onEvent {
                keydown = { e ->
                    if(commandPopup?.visible == true)
                    {
                        if(commandPopup?.onNavigate(e.key) == true)
                        {
                            e.preventDefault()
                        }
                    }
                    else if (e.key == "Enter")
                    {
                        if (e.shiftKey)
                        {
                            e.preventDefault() // Prevent newline
                            submitCommand()
                        }
                        else
                        {
                            // Check for specific UI commands that should trigger immediately anyway
                            val currentCmd = currentInputText.trim()
                            if (currentCmd.equals("/chat", true) || currentCmd.equals("/prompts", true) || currentCmd.equals("/agentstream", true))
                            {
                                e.preventDefault() // Prevent newline
                                submitCommand()
                            }
                        }
                    }
                }
            }
        }
        
        inputWrapper.add(commandTextArea!!)
        inputWrapper.add(commandPopup!!)
        
        charCounter = span("0 / 500") {
            position = io.kvision.core.Position.ABSOLUTE
            bottom = 20.px
            right = 20.px
            opacity = 0.5
            fontSize = 14.px
            zIndex = 10
            addCssClass("character-counter")
        }
        inputWrapper.add(charCounter!!)

        add(inputWrapper)

        gridPanel(templateColumns = "1fr 1fr", templateRows = "1fr 1fr", columnGap = 10, rowGap = 10)
        {
            width = 220.px
            height = 180.px
            
            // RESOURCES button (top left)
            button("", className = "action-button") {
                width = 100.perc
                height = 100.perc
                setStyle("display", "flex")
                setStyle("flex-direction", "column")
                setStyle("align-items", "center")
                setStyle("justify-content", "center")
                setStyle("gap", "5px")

                onClick {
                    Logger.debug(LogCategory.UI, "CommandBox: RESOURCES button clicked")
                    onResourcesClick?.invoke()
                }

                icon("fas fa-cubes") {
                    fontSize = 20.px
                }
                span("RESOURCES") {
                    fontSize = 12.px
                    fontWeight = io.kvision.core.FontWeight.BOLD
                }
            }
            
            // STATS button (top right)
            button("", className = "action-button") {
                width = 100.perc
                height = 100.perc
                setStyle("display", "flex")
                setStyle("flex-direction", "column")
                setStyle("align-items", "center")
                setStyle("justify-content", "center")
                setStyle("gap", "5px")
                
                onClick {
                    Logger.debug(LogCategory.UI, "CommandBox: STATS button clicked")
                    onStatsClick?.invoke()
                }
                
                icon("fas fa-chart-bar") {
                    fontSize = 20.px
                }
                span("STATS") {
                    fontSize = 12.px
                    fontWeight = io.kvision.core.FontWeight.BOLD
                }
            }
            
            // INTEL button (bottom left)
            button("", className = "action-button") {
                width = 100.perc
                height = 100.perc
                setStyle("display", "flex")
                setStyle("flex-direction", "column")
                setStyle("align-items", "center")
                setStyle("justify-content", "center")
                setStyle("gap", "5px")

                onClick {
                    Logger.debug(LogCategory.UI, "CommandBox: WORLD button clicked")
                    onWorldClick?.invoke()
                }

                icon("fas fa-globe") {
                    fontSize = 20.px
                }
                span("WORLD") {
                    fontSize = 12.px
                    fontWeight = io.kvision.core.FontWeight.BOLD
                }
            }
            
            // SETTINGS button (bottom right)
            button("", className = "action-button") {
                width = 100.perc
                height = 100.perc
                setStyle("display", "flex")
                setStyle("flex-direction", "column")
                setStyle("align-items", "center")
                setStyle("justify-content", "center")
                setStyle("gap", "5px")

                onClick {
                    Logger.debug(LogCategory.UI, "CommandBox: SETTINGS button clicked")
                    onSettingsClick?.invoke()
                }
                
                icon("fas fa-cog") {
                    fontSize = 20.px
                }
                span("SETTINGS") {
                    fontSize = 12.px
                    fontWeight = io.kvision.core.FontWeight.BOLD
                }
            }
        }
    }

    /**
     * Toggles interactive state of the command inputs.
     *
     * @param enabled True to enable typing/sending, false to disable.
     */
    fun setInteractive(enabled: Boolean)
    {
        // No longer disabling inputs to allow chat/ask at all times.
        if(!enabled)
        {
            stopHighlight()
        }
    }

    /**
     * Updates the visual state of the command box based on whose turn it is.
     * 
     * @param isMyTurn True if it's the player's turn (Blue flash), false if not (Red flash).
     */
    fun setTurnState(isMyTurn: Boolean)
    {
        persistentTurnState = isMyTurn
        applyTurnStateHighlight()
    }

    /**
     * Focuses the command text area.
     */
    fun focusInput()
    {
        commandTextArea?.focus()
    }

    /**
     * Clears the command text area.
     */
    fun clearInput()
    {
        commandTextArea?.value = ""
        currentInputText = ""
        charCounter?.content = "0 / 500"
    }

    /**
     * Highlights the command box for a short duration to draw attention.
     *
     * @param durationMs Duration of the highlight in milliseconds.
     */
    fun highlightForResponse(durationMs: Int = 4000)
    {
        stopHighlight()
        addCssClass("command-box-flash")
        highlightTimer = window.setTimeout({
            stopHighlight()
        }, durationMs)
    }

    /**
     * Clears any active highlight animation on the command box.
     */
    fun stopHighlight()
    {
        highlightTimer?.let {
            window.clearTimeout(it)
        }
        highlightTimer = null
        removeCssClass("command-box-flash")
        removeCssClass("command-box-flash-red")
        applyTurnStateHighlight()
    }

    /**
     * Updates the status of the command box based on active agent usage.
     * Specifically handles the "Classifying" state for natural language prompts.
     */
    fun updateAgentUsage(usage: structs.AgentUsage)
    {
        val btn = sendButton ?: return
        val isAiRunning = usage.runningPlayAgent || usage.runningAnswerAgent || usage.runningOpenAgent || usage.runningChatAgent || usage.runningClassifier

        if (isAiRunning)
        {
            btn.text = ""
            btn.icon = "fas fa-bolt"
            btn.addCssClass("btn-thinking")
            btn.disabled = true
        }
        else
        {
            btn.text = "Send"
            btn.icon = null
            btn.removeCssClass("btn-thinking")
            btn.disabled = false
        }
    }
}