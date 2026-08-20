package ui.gameplay

import globals.World
import io.kvision.core.*
import io.kvision.form.text.TextArea
import io.kvision.form.text.textArea
import io.kvision.html.*
import io.kvision.panel.SimplePanel
import io.kvision.panel.hPanel
import io.kvision.panel.vPanel
import io.kvision.utils.perc
import io.kvision.utils.px
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import org.ttt.autogenesis.kvisionapp.WebSocketRpcBridge
import org.ttt.autogenesis.logging.LogCategory
import org.ttt.autogenesis.logging.Logger
import structs.rpcRequests.SetDelegateInstructionsRequest

/**
 * Modal popup that lets the local player author free-form "delegate" instructions for the
 * AI that takes over their character when they are unreachable.
 *
 * Mirrors the visual treatment of [SettingsWidget] (fixed-center 600x640 modal, zIndex 200,
 * `login-widget-window` class, fade-in animation, cyan glow on the title) and reuses the
 * character-counter pattern from [ui.CommanderCreationDialog] (color shifts at 70% / 90% / 100%).
 *
 * Lifecycle:
 * - Hidden by default ([Display.NONE]).
 * - [show] reads the current value from [World.localPlayer] (which the server keeps in sync
 *   via `UiSignalRpcHandlers.broadcastWorldUpdate`).
 * - The SAVE button fires `player.setDelegateInstructions` over the shared
 *   [WebSocketRpcBridge] and closes the modal on success. The server then broadcasts the
 *   new world state, which re-seeds [World.localPlayer] in `GameplayUI.updateWorldState`.
 *
 * Always editable per the design decision (the player can update their guidance even when
 * the AI is currently taking their turn).
 */
class DelegateWidget : SimplePanel(className = "login-widget-window")
{
    private val maxLength: Int = MAX_LENGTH

    private lateinit var instructionsInput: TextArea
    private lateinit var counterLabel: Span
    private var commandPopup: CommandPopup? = null

    init
    {
        // Shared styling & positioning.
        // Anchor to the available viewport region: 100px score bar at the top,
        // 200px command box at the bottom. The modal fills the middle band
        // and centers its content there. `top/bottom` insets (instead of
        // `top: 50%; transform`) keep the modal fully inside the safe zone
        // regardless of viewport size.
        //
        // Horizontal centering uses `left: calc(50% - 300px)` (half the 600px
        // width) instead of `left: 50%` + `transform: translateX(-50%)`. The
        // CSS `dialogFadeIn` animation (see night-mode.css) touches `transform`
        // on the `from`/`to` keyframes, which would clobber an inline
        // `translateX(-50%)` and leave the modal pinned to the right edge
        // during the animation before snapping to center on completion.
        width = 600.px
        height = io.kvision.core.CssSize(100, io.kvision.core.UNIT.perc)
        position = Position.FIXED
        top = 120.px
        bottom = 220.px
        // Use setStyle for the calc() expression — KVision's typed `left`
        // property expects a CssSize, which has no API for raw calc().
        setStyle("left", "calc(50% - 300px)")
        setStyle("max-width", "calc(100vw - 40px)")
        setStyle("max-height", "calc(100vh - 340px)")
        zIndex = 200
        padding = 30.px

        // box-sizing: border-box so padding is included in width/height
        setStyle("box-sizing", "border-box")
        // Prevent any child from spilling outside the rounded modal frame.
        overflow = Overflow.HIDDEN

        display = Display.NONE
        flexDirection = FlexDirection.COLUMN
        alignItems = AlignItems.CENTER
        // Center content vertically within the available band.
        justifyContent = JustifyContent.CENTER

        // Animation
        setStyle("animation", "dialogFadeIn 0.3s ease-out")

        // Header
        h4("DELEGATE INSTRUCTIONS") {
            color = Color.name(Col.CYAN)
            fontSize = CssSize(28, UNIT.px)
            fontWeight = FontWeight.BOLD
            marginBottom = 8.px
            textShadow = TextShadow(0.px, 0.px, 5.px, Color.name(Col.CYAN))
            textAlign = TextAlign.CENTER
            width = 100.perc
        }

        p("Guidance for the AI that takes over your character when you step away.") {
            color = Color.name(Col.LIGHTGRAY)
            fontSize = CssSize(14, UNIT.px)
            fontStyle = FontStyle.ITALIC
            textAlign = TextAlign.CENTER
            marginBottom = 16.px
            width = 100.perc
        }

        // Content
        vPanel(alignItems = AlignItems.STRETCH, spacing = 8) {
            width = 100.perc
            flexGrow = 1
            // If the modal is clamped by max-height, the content region is
            // the scrolling area. `min-height: 0` lets the flex child shrink
            // below its content's natural size.
            overflow = Overflow.AUTO
            setStyle("min-height", "0")
            // Positioning context for the CommandPopup (which is `position: absolute`).
            // The popup is added as a sibling of inputWrapper below and anchors its
            // top to this vPanel's top, visually overlaying the textarea.
            position = Position.RELATIVE

            // Local handle so the onInput closure can read/write the textarea value
            // without going through the outer `this` (which Kotlin disallows when the
            // closure captures a mutable member).
            var localInput: TextArea? = null

            localInput = textArea {
                width = 100.perc
                // Reduced from 320px → 200px to leave room for the popup below the
                // textarea inside the modal's visible area (the modal's overflow:
                // hidden would clip a popup that extends above the input).
                height = 200.px
                fontSize = CssSize(15, UNIT.px)
                padding = 12.px
                placeholder = "E.g., 'Prioritize defending the north front. Avoid attacking Qos. " +
                    "If pressured, trade with Kara for resources rather than fighting.'"
                cols = 70
                rows = 8

                onInput {
                    val ta = localInput ?: return@onInput
                    val v = ta.value ?: ""
                    if(v.length > maxLength)
                    {
                        ta.value = v.take(maxLength)
                    }
                    updateCounter()
                    updateAutocomplete(ta.value ?: "")
                }

                onEvent {
                    keydown = { e ->
                        // Only intercept keys when the popup is visible. Native Enter
                        // inside the multi-line textarea still inserts a newline; SAVE
                        // is the dedicated button, not Enter.
                        if(commandPopup?.visible == true)
                        {
                            if(commandPopup?.onNavigate(e.key) == true)
                            {
                                e.preventDefault()
                            }
                        }
                    }
                }
            }

            instructionsInput = requireNotNull(localInput) { "textArea block did not initialize" }

            counterLabel = span("0 / $maxLength characters") {
                fontSize = CssSize(13, UNIT.px)
                color = Color("#a0a8cc")
                textAlign = TextAlign.CENTER
                width = 100.perc
                marginTop = 4.px
            }

            // Textarea first, then counter below it, then popup as an absolute sibling
            // that anchors to the top of THIS vPanel (covering the textarea area).
            // The popup overlays the textarea when shown — this is the standard
            // dropdown-over-input pattern.
            add(instructionsInput)
            add(counterLabel)

            commandPopup = CommandPopup(
                onItemSelected = { selectedItem -> handleAutocompleteSelection(selectedItem) },
                anchorAtTop = true
            )
            add(commandPopup!!)
        }

        // Footer (action buttons)
        hPanel(justify = JustifyContent.CENTER, alignItems = AlignItems.CENTER, spacing = 12) {
            width = 100.perc
            marginTop = 20.px
            flexShrink = 0

            button("CLEAR", icon = "fas fa-eraser", className = "delegate-modal-button delegate-modal-button-secondary") {
                onClick {
                    instructionsInput.value = ""
                    updateCounter()
                }
            }

            button("CANCEL", icon = "fas fa-times", className = "delegate-modal-button delegate-modal-button-secondary") {
                onClick {
                    this@DelegateWidget.hide()
                }
            }

            button("SAVE", icon = "fas fa-save", className = "delegate-modal-button delegate-modal-button-primary") {
                onClick {
                    save()
                }
            }
        }

        // Seed counter (in case show() is called before any onInput)
        updateCounter()
    }

    /**
     * Opens the modal and seeds the textarea with the current value from [World.localPlayer].
     */
    override fun show()
    {
        val priorLength = World.localPlayer.delegateInstructions?.length ?: 0
        Logger.debug(LogCategory.UI, "DelegateWidget.show: opening for player='${World.localPlayer.name}' priorLength=${priorLength}")
        instructionsInput.value = World.localPlayer.delegateInstructions.orEmpty()
        updateCounter()
        display = Display.FLEX
        visible = true
    }

    /**
     * Hides the modal without persisting any changes.
     */
    override fun hide()
    {
        Logger.debug(LogCategory.UI, "DelegateWidget.hide: closing for player='${World.localPlayer.name}'")
        display = Display.NONE
        visible = false
    }

    /**
     * Sends the current textarea contents to the server via `player.setDelegateInstructions`.
     * On success the server broadcasts a new world state, which `GameplayUI.updateWorldState`
     * re-stamps into [World.localPlayer]. The modal closes once the RPC is dispatched.
     */
    private fun save()
    {
        val playerName = World.localPlayer.name
        if(playerName.isBlank())
        {
            Logger.warn(LogCategory.UI, "DelegateWidget.save: local player name is blank; refusing to save")
            return
        }

        val raw = instructionsInput.value.orEmpty().trim()
        val toSend: String? = raw.ifBlank { null }

        MainScope().launch {
            val invoker = WebSocketRpcBridge.rpcInvoker
            if(invoker == null)
            {
                Logger.warn(LogCategory.UI, "DelegateWidget.save: WebSocketRpcBridge.rpcInvoker is null; cannot save delegate instructions")
                return@launch
            }
            try
            {
                invoker.invoke(
                    "player.setDelegateInstructions",
                    SetDelegateInstructionsRequest(playerName = playerName, instructions = toSend)
                )
                Logger.info(LogCategory.UI, "DelegateWidget.save: submitted delegate instructions for '$playerName' (length=${toSend?.length ?: 0})")
                this@DelegateWidget.hide()
            }
            catch(e: Exception)
            {
                Logger.error(LogCategory.UI, "DelegateWidget.save: failed to invoke player.setDelegateInstructions: ${e.message}")
            }
        }
    }

    /**
     * Updates the character counter below the textarea. Color shifts at 70% (yellow), 90%
     * (orange), and 100% (red) of [maxLength] so the player can see how close they are to
     * the cap. The hard cap is enforced by the `onInput` truncation above.
     */
    private fun updateCounter()
    {
        if(!::counterLabel.isInitialized) return
        val count = instructionsInput.value.orEmpty().length
        counterLabel.content = "$count / $maxLength characters"
        counterLabel.color = when
        {
            count >= maxLength -> Color("#ff4444")
            count > maxLength * 0.9 -> Color("#ff9944")
            count > maxLength * 0.7 -> Color("#ffcc44")
            else -> Color("#a0a8cc")
        }
        counterLabel.refresh()
    }

    /**
     * Inspects the current textarea contents, computes the trailing `@...` or `$...`
     * trigger word, and shows the [CommandPopup] with the matching item set. Mirrors
     * the CommandBox onInput pattern (`kvisionApp/src/jsMain/kotlin/ui/gameplay/CommandBox.kt:172-237`)
     * but only branches on `@` and `$`. `/` slash commands are intentionally excluded
     * because the delegate modal is launched via `/delegate` and the user does not want
     * slash-command autocomplete inside this modal.
     */
    private fun updateAutocomplete(text: String)
    {
        val lastTriggerIndex = text.indexOfLast { it == '@' || it == '$' }
        val lastSpaceIndex = text.indexOfLast { it.isWhitespace() }

        val lastWord = if(lastTriggerIndex > lastSpaceIndex)
        {
            text.substring(lastTriggerIndex)
        }
        else
        {
            ""
        }

        Logger.debug(LogCategory.UI, "DelegateWidget.updateAutocomplete: textLength=${text.length} lastWord='$lastWord'")

        when
        {
            lastWord.startsWith("$") -> showDollarAutocomplete(lastWord)
            lastWord.startsWith("@") -> showAtAutocomplete(lastWord)
            else -> commandPopup?.hide()
        }
    }

    /**
     * Builds the `$`-prefixed macro set from [globals.World.localPlayer] and shows the
     * popup. Mirrors the `$` branch in CommandBox.kt:192-202.
     */
    private fun showDollarAutocomplete(filterText: String)
    {
        val player = globals.World.localPlayer
        val macros = listOf(
            AutocompleteItem(
                "\$allResources",
                "All your owned resources",
                player.resources.joinToString(", ") { it.name }.ifEmpty { "None" }
            ),
            AutocompleteItem(
                "\$allTerritories",
                "All your captured territories",
                player.capturedTerritory.joinToString(", ") { it.name }.ifEmpty { "None" }
            ),
            AutocompleteItem(
                "\$myCommander",
                "Your commander name",
                player.name
            )
        )
        commandPopup?.show(filterText, macros)
    }

    /**
     * Builds the `@`-prefixed reference set from the world (territories, NPCs, active
     * players) plus a hardcoded list of system characters. Mirrors the `@` branch in
     * CommandBox.kt:204-229.
     */
    private fun showAtAutocomplete(filterText: String)
    {
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

        // Hardcoded system characters — mirrors CommandBox.kt:217-223 verbatim so the
        // delegate experience matches the command-box experience.
        val systemCharacters = listOf(
            "Zuzusarogorata Suguruzands", "Haematemesis Coprophobia", "N'zelquin G'zeeloth",
            "Bigwang McDouchebag", "Invis von Disappearo", "Big Googar", "Narjodo Bazingazooka",
            "Shitty Bob", "Officer Dave", "Robert the Destroyer", "Quag LoBogon",
            "Zeta Step Reasoner", "Narjan Goren", "Gl'kr'kr'kr'k Shshshsh-shsh-‘’’’////",
            "Nordold Trable"
        )
        systemCharacters.forEach {
            references.add(AutocompleteItem("@$it", "[Character]", "@$it"))
        }

        commandPopup?.show(filterText, references.distinctBy { it.label })
    }

    /**
     * Replaces the trailing `@...` or `$...` word in the textarea with the selected
     * item's [AutocompleteItem.replacementText] (followed by a single space) and
     * refocuses the textarea. Enforces the [maxLength] cap by truncating the result.
     *
     * Mirrors the onItemSelected lambda in CommandBox.kt:133-150 with the addition of
     * [maxLength] truncation (DelegateWidget has a 1500-char hard cap that CommandBox
     * does not enforce at the popup level).
     */
    private fun handleAutocompleteSelection(selectedItem: AutocompleteItem)
    {
        val currentText = instructionsInput.value ?: ""
        val lastTriggerIndex = currentText.indexOfLast { it == '@' || it == '$' }
        val lastSpaceIndex = currentText.indexOfLast { it.isWhitespace() }

        val lastWord = if(lastTriggerIndex > lastSpaceIndex)
        {
            currentText.substring(lastTriggerIndex)
        }
        else
        {
            if(lastSpaceIndex == currentText.length - 1) "" else currentText.substring(lastSpaceIndex + 1)
        }

        if(lastWord.isNotEmpty())
        {
            val newText = currentText.dropLast(lastWord.length) + selectedItem.replacementText + " "
            val truncated = if(newText.length > maxLength) newText.take(maxLength) else newText
            instructionsInput.value = truncated
            updateCounter()
        }
        commandPopup?.hide()
        instructionsInput.focus()
    }

    companion object
    {
        /**
         * Soft cap (in characters) for the delegate instructions text. Mirrors the
         * server-side cap in [structs.rpcRequests.DELEGATE_INSTRUCTIONS_MAX_LENGTH].
         */
        const val MAX_LENGTH: Int = 1500
    }
}