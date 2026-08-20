package ui.gameplay

import io.kvision.core.Position
import io.kvision.html.Div
import io.kvision.html.div
import io.kvision.html.span
import io.kvision.panel.SimplePanel
import io.kvision.panel.VPanel
import io.kvision.utils.px
import io.kvision.utils.perc
import io.kvision.core.onEvent
import org.ttt.autogenesis.logging.LogCategory
import org.ttt.autogenesis.logging.Logger

data class AutocompleteItem(
    val label: String,
    val description: String,
    val replacementText: String
)

class CommandPopup(
    /**
     * When true the popup anchors its TOP to the parent's TOP (renders as a
     * downward overlay inside the parent container). When false (the default,
     * preserved for CommandBox backward compatibility) the popup anchors its
     * BOTTOM to the parent's TOP (renders above the parent container).
     *
     * DelegateWidget uses anchorAtTop = true because the modal's
     * `overflow: hidden` would clip an above-parent popup against the modal's
     * top edge.
     *
     * Declared BEFORE [onItemSelected] so that Kotlin's trailing-lambda syntax
     * (`CommandPopup { selectedItem -> ... }`) continues to bind to
     * [onItemSelected] — the trailing lambda always targets the LAST parameter.
     */
    private val anchorAtTop: Boolean = false,
    private val onItemSelected: (AutocompleteItem) -> Unit
) : SimplePanel()
{

    private var filteredItems = listOf<AutocompleteItem>()
    private var selectedIndex = 0
    private val commandsContainer = VPanel()

    init
    {
        visible = false
        position = Position.ABSOLUTE
        if(anchorAtTop)
        {
            // Render as a downward overlay inside the parent — used by DelegateWidget
            // where the modal's `overflow: hidden` would clip an above-parent popup.
            top = 0.px
        }
        else
        {
            // Default CommandBox behavior — anchored above the parent (bottom edge
            // sits at the parent's top edge).
            bottom = 100.perc
        }
        left = 0.px
        width = 100.perc
        maxHeight = 300.px
        zIndex = 1000 // Ensure it sits on top

        addCssClass("command-popup-container")

        add(commandsContainer)
    }

    fun show(filterText: String, items: List<AutocompleteItem>)
    {
        Logger.debug(LogCategory.UI, "CommandPopup.show() called with: '$filterText', ${items.size} items")
        
        val newFilteredItems = if(filterText.length <= 1)
        {
            // Just the trigger character (/, $, @) shows all
            items
        }
        else
        {
            // For slash commands we might want startsWith, but contains is better for fuzzy matching
            items.filter { item ->
                item.label.contains(filterText, ignoreCase = true)
            }
        }
        
        if(newFilteredItems.isEmpty())
        {
            Logger.debug(LogCategory.UI, "CommandPopup: No items found, hiding.")
            hide()
            return
        }

        // Only update state and re-render if items actually changed or if we were hidden
        filteredItems = newFilteredItems
        selectedIndex = 0 
        renderCommands()
        
        if(!visible)
        {
            Logger.debug(LogCategory.UI, "CommandPopup: Showing popup")
            visible = true
        }
    }

    override fun hide()
    {
        if (visible)
        {
            Logger.debug(LogCategory.UI, "CommandPopup: Hiding popup")
            super.hide()
        }
    }


    private fun renderCommands()
    {
        commandsContainer.removeAll()
        
        filteredItems.forEachIndexed { index, item ->
            val cmdName = item.label
            val cmdDesc = item.description

            val uiItem = Div(className = "command-popup-item")
            {
                if(index == selectedIndex)
                {
                    addCssClass("command-popup-item-selected")
                }
                
                div {
                    addCssClass("command-popup-item-row")
                    span(cmdName) {
                        addCssClass("command-popup-item-name")
                    }
                    if(cmdDesc.isNotEmpty())
                    {
                        span(cmdDesc)
                        {
                            addCssClass("command-popup-item-desc")
                        }
                    }
                }

                onEvent {
                    mousedown = { e ->
                        e.preventDefault()
                        e.stopPropagation()
                        hide()
                        onItemSelected(item)
                    }
                }
            }
            commandsContainer.add(uiItem)
        }
    }

    /**
     * Handles navigation events. Returns true if the event was consumed.
     */
    fun onNavigate(key: String): Boolean
    {
        if(!visible) return false

        when(key)
        {
            "ArrowUp" ->
            {
                selectedIndex = (selectedIndex - 1 + filteredItems.size) % filteredItems.size
                renderCommands()
                return true
            }
            "ArrowDown" ->
            {
                selectedIndex = (selectedIndex + 1) % filteredItems.size
                renderCommands()
                return true
            }
            "Enter", "Tab" ->
            {
                if(filteredItems.isNotEmpty())
                {
                    val selected = filteredItems[selectedIndex]
                    onItemSelected(selected)
                    hide()
                    return true
                }
            }
            "Escape" ->
            {
                hide()
                return true
            }
        }
        return false
    }
}