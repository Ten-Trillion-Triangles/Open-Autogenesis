package ui

import globals.KEnv
import io.kvision.core.*
import io.kvision.html.Div
import io.kvision.html.div
import io.kvision.panel.SimplePanel
import models.IconShape
import models.IconSize
import models.TerritoryState
import structs.Territory
import ui.gameplay.TerritoryDescriptionWindow
import org.ttt.autogenesis.logging.LogCategory
import org.ttt.autogenesis.logging.Logger

/**
 * Interactive territory icon widget for map display.
 *
 * @param territory The territory data this icon represents.
 * @param iconImage The emoji or image to display.
 * @param iconSize The size of the icon.
 * @param iconShape The shape of the icon container.
 * @param owner The current owner of the territory.
 * @param ownerColor The owner's color in hex format.
 */
class TerritoryIcon(
    val territory: Territory,
    var iconImage: String = "",
    var iconSize: IconSize = IconSize.MEDIUM,
    var iconShape: IconShape = IconShape.CIRCLE,
    initialOwner: String? = null,
    initialOwnerColor: String = "#CCCCCC"
) : SimplePanel()
{
    /**
     * The current owner of the territory.
     */
    var owner: String? = initialOwner
        private set

    /**
     * The current owner's color in hex format.
     */
    var ownerColor: String = initialOwnerColor
        private set

    private var mainIconDiv: Div? = null
    private var stateBadge: Div? = null
    var onIconClick: ((Territory) -> Unit)? = null

    /**
     * Hover-state callback fired by the icon's `mouseenter` /
     * `mouseleave` events.
     *
     * The second parameter is `isEnter` (true on `mouseenter`,
     * false on `mouseleave`). Consumers — most notably
     * [MapViewer] — use this to drive the border-line overlay
     * without the icon having to know about SVG.
     *
     * The visual hover feedback (the `transform: scale(1.1)`
     * pop and the `box-shadow` glow) stays exactly as-is and
     * is independent of this callback — a missing or throwing
     * consumer must not suppress the local feedback.
     */
    var onIconHover: ((Territory, Boolean) -> Unit)? = null

    init
    {
        Logger.debug(LogCategory.UI, "TerritoryIcon: Creating icon for '${territory.name}' at (${territory.xPos}%, ${territory.yPos}%)")
        Logger.debug(LogCategory.UI, "TerritoryIcon: Icon='$iconImage', Size=${iconSize.pixels}px, Shape=$iconShape, Owner=$owner, Color=$ownerColor")

        width = CssSize(iconSize.pixels, UNIT.px)
        height = CssSize(iconSize.pixels, UNIT.px)
        position = Position.ABSOLUTE
        left = CssSize(territory.xPos, UNIT.perc)
        top = CssSize(territory.yPos, UNIT.perc)
        zIndex = 10
        setStyle("transform", "translate(-50%, -50%)")
        cursor = Cursor.POINTER

        // Debug: ensure visibility
        setStyle("pointer-events", "auto")

        applyShape()
        applyOwnerStyle()
        setupTooltip()
        updateIdleAnimation()

        renderMainIcon()

        // Test hooks for Playwright e2e: a stable test-id plus the
        // territory name so the test can target a specific pin
        // without depending on the multi-byte emoji as a selector.
        setAttribute("data-testid", "territory-icon")
        setAttribute("data-territory-name", territory.name)

        onEvent {
            mouseenter = {
                Logger.debug(LogCategory.UI, "TerritoryIcon: Hover on '${territory.name}'")
                setStyle("transform", "translate(-50%, -50%) scale(1.1)")
                setStyle("box-shadow", "0 0 10px rgba(255, 255, 255, 0.8)")
                try
                {
                    onIconHover?.invoke(territory, true)
                }
                catch(e: Throwable)
                {
                    Logger.warn(LogCategory.UI, "TerritoryIcon: onIconHover(enter) callback threw: ${e.message}")
                }
            }
            mouseleave = {
                setStyle("transform", "translate(-50%, -50%) scale(1)")
                setStyle("box-shadow", "none")
                try
                {
                    onIconHover?.invoke(territory, false)
                }
                catch(e: Throwable)
                {
                    Logger.warn(LogCategory.UI, "TerritoryIcon: onIconHover(leave) callback threw: ${e.message}")
                }
            }
        }

        onClick {
            Logger.debug(LogCategory.UI, "TerritoryIcon: Clicked on '${territory.name}'")
            onIconClick?.invoke(territory)
        }

        Logger.debug(LogCategory.UI, "TerritoryIcon: Icon created successfully for '${territory.name}'")
    }
    
    /**
     * Sets up the tooltip with territory information.
     */
    private fun setupTooltip()
    {
        val tooltipContent = buildTooltipContent()
        title = "${territory.name}\n$tooltipContent"
    }
    
    /**
     * Builds the tooltip content string.
     *
     * @return The formatted tooltip text.
     */
    private fun buildTooltipContent(): String
    {
        return buildString {
        if(owner != null)
        {
            append("Owner: $owner\n")
        }
        append("Type: ${territory.type}\n")
        append("Points: ${territory.pointValue}")
        if(territory.ruler.isNotEmpty())
        {
            append("\nOwned by: ${territory.ruler}")
        }
        }
    }
    
    /**
     * Applies the icon shape styling.
     */
    private fun applyShape()
    {
        when(iconShape)
        {
            IconShape.CIRCLE ->
            {
                borderRadius = CssSize(50, UNIT.perc)
            }
            IconShape.SQUARE ->
            {
                borderRadius = CssSize(0, UNIT.px)
            }
            IconShape.ROUNDED_SQUARE ->
            {
                borderRadius = CssSize(8, UNIT.px)
            }
        }
    }
    
    /**
     * Applies the owner color styling to the icon.
     */
    private fun applyOwnerStyle()
    {
        val colorInt = ownerColor.removePrefix("#").toInt(16)
        border = Border(width = CssSize(3, UNIT.px), style = BorderStyle.SOLID, color = Color.hex(colorInt))
        // Derive each color channel for the translucent fill.
        val r = (colorInt shr 16) and 0xFF
        val g = (colorInt shr 8) and 0xFF
        val b = colorInt and 0xFF
        setStyle("background-color", "rgba($r, $g, $b, 0.3)")
        setStyle("--owner-color", ownerColor)
    }
    
    /**
     * Updates the territory ownership and color.
     *
     * @param newOwner The new owner's name.
     * @param newOwnerColor The new owner's color in hex format.
     * @param newEmoji Optional new emoji to display (e.g. player icon).
     * @param activePlayerName Optional name of the currently active player (for turn context).
     * @param localPlayerName Optional name of the local player (for personal context).
     * @param forceAnimate If true, triggers the animation even if the owner has not changed.
     */
    fun updateOwnership(
        newOwner: String, 
        newOwnerColor: String, 
        newEmoji: String? = null,
        activePlayerName: String? = null,
        localPlayerName: String? = null,
        forceAnimate: Boolean = false
    )
    {
        // Update emoji if provided
        if (newEmoji != null && newEmoji != iconImage) {
            updateIcon(newEmoji)
        }

        // Optimization: Only update if the owner or color has changed, unless forced
        if (owner == newOwner && newOwnerColor == ownerColor && !forceAnimate) {
            return
        }

        val oldOwner = owner
        Logger.debug(LogCategory.UI, "TerritoryIcon: Ownership updated for '${territory.name}' - Old: $oldOwner, New: $newOwner (Forced=$forceAnimate)")
        owner = newOwner
        ownerColor = newOwnerColor
        applyOwnerStyle()
        updateIdleAnimation()
        
        // Determine Animation Type based on context
        // Compare with oldOwner we just updated from
        val ownerChanged = (oldOwner != null && oldOwner != newOwner)
        
        var animationClass: String? = null

        if (ownerChanged) {
            // Priority 1: Local Player Perspective
            val isLocalGain = (localPlayerName != null && newOwner == localPlayerName)
            val isLocalLoss = (localPlayerName != null && oldOwner == localPlayerName)
            
            // Priority 2: Active Player Perspective (Spectator view of the active actor)
            val isActiveGain = (activePlayerName != null && newOwner == activePlayerName)
            val isActiveLoss = (activePlayerName != null && oldOwner == activePlayerName)

            if(isLocalGain)
            {
                animationClass = "ownership-gain"
            }
            else if(isLocalLoss)
            {
                animationClass = "ownership-loss"
            }
            else if(isActiveGain)
            {
                 // Spectators see the Active Player's success as green
                 animationClass = "ownership-gain"
            }
            else if(isActiveLoss)
            {
                 // Spectators see the Active Player's failure as red
                 animationClass = "ownership-loss"
            }
            else
            {
                animationClass = "ownership-change"
            }
        }
        else if (forceAnimate)
        {
            // If forced to animate but owner hasn't changed, use neutral animation
            animationClass = "ownership-change"
        }

        // Trigger pulse animation if we determined a class
        if (animationClass != null) {
            triggerPulseAnimation(animationClass)
        }
        
        setupTooltip() // Refresh tooltip with new owner
        
        if (animationClass != null) {
            Logger.debug(LogCategory.UI, "TerritoryIcon: Ownership updated for '${territory.name}'. Animation: $animationClass (Changed=$ownerChanged, Forced=$forceAnimate)")
        }
    }

    /**
     * Updates the idle animation state based on current ownership.
     */
    private fun updateIdleAnimation()
    {
        val localName = globals.World.localPlayer.name
        val isOwnedByLocal = (owner != null && owner?.trim() != "" && owner?.trim().equals(localName.trim(), ignoreCase = true))
        
        if (isOwnedByLocal) {
            addCssClass("territory-idle-owned")
        } else {
            removeCssClass("territory-idle-owned")
        }
    }

    /**
     * Triggers a pulse animation on the icon.
     *
     * @param animationClass The CSS class to apply for the animation.
     */
    fun triggerPulseAnimation(animationClass: String)
    {
        removeCssClass("territory-idle-owned") // Clear idle before pulse
        addCssClass(animationClass)
        kotlinx.browser.window.setTimeout({
            removeCssClass(animationClass)
            updateIdleAnimation()
        }, 4000) // Matches CSS animation duration
    }

    /**
     * Updates the main icon emoji.
     *
     * @param newEmoji The new emoji string.
     */
    fun updateIcon(newEmoji: String)
    {
        if (iconImage == newEmoji && mainIconDiv != null) return
        
        Logger.debug(LogCategory.UI, "TerritoryIcon: Updating icon for '${territory.name}' to '$newEmoji'")
        iconImage = newEmoji
        renderMainIcon()
    }

    /**
     * Renders or re-renders the main icon div.
     */
    private fun renderMainIcon()
    {
        mainIconDiv?.let { remove(it) }
        
        val displayIcon = if(iconImage.isEmpty())
        {
            when(territory.type)
            {
                enums.TerritoryType.Land -> "🏰"
                enums.TerritoryType.Coastline -> "⚓"
                enums.TerritoryType.Island -> "🏝️"
                enums.TerritoryType.Underwater -> "🪸"
                enums.TerritoryType.Desert -> "🌵"
                enums.TerritoryType.Void -> "👾"
            }
        }
        else
        {
            iconImage
        }

        mainIconDiv = div(displayIcon)
        {
            width = CssSize(100, UNIT.perc)
            height = CssSize(100, UNIT.perc)
            display = Display.FLEX
            alignItems = AlignItems.CENTER
            justifyContent = JustifyContent.CENTER
            fontSize = CssSize((iconSize.pixels * 0.6).toInt(), UNIT.px)
            // Ensure main icon is behind badges
            zIndex = 1
        }
        
        // Ensure state badge (if any) is re-added after icon to preserve z-index
        stateBadge?.let {
            remove(it)
            add(it)
        }
    }
    
    /**
     * Updates the territory state and displays appropriate badge.
     *
     * @param state The new territory state.
     */
    fun updateState(state: TerritoryState)
    {
        Logger.debug(LogCategory.UI, "TerritoryIcon: Updating state for '${territory.name}' to $state")
        stateBadge?.let { remove(it) }
        
        stateBadge = when(state)
        {
            TerritoryState.CONTESTED ->
            {
                triggerPulseAnimation("ownership-change")
                createBadge("⚔️", "#FF0000")
            }
            TerritoryState.CAPTURED ->
            {
                triggerPulseAnimation("ownership-gain")
                createBadge("✅", "#00FF00")
            }
            TerritoryState.FORTIFIED ->
            {
                triggerPulseAnimation("ownership-gain")
                createBadge("🛡️", "#0000FF")
            }
            TerritoryState.DESTROYED ->
            {
                triggerPulseAnimation("ownership-loss")
                createBadge("💥", "#888888")
            }
            TerritoryState.NORMAL ->
            {
                null
            }
        }
        
        stateBadge?.let {
            add(it)
            Logger.debug(LogCategory.UI, "TerritoryIcon: Badge added for state $state")
        }
        Logger.debug(LogCategory.UI, "TerritoryIcon: State updated for '${territory.name}'")
    }
    
    /**
     * Creates a state badge with emoji and color.
     *
     * @param emoji The emoji to display in the badge.
     * @param color The badge background color in hex format.
     * @return The created badge Div.
     */
    private fun createBadge(emoji: String, color: String): Div
    {
        return Div(emoji) {
            position = Position.ABSOLUTE
            top = CssSize(-5, UNIT.px)
            right = CssSize(-5, UNIT.px)
            fontSize = CssSize(16, UNIT.px)
            background = Background(color = Color.hex(color.removePrefix("#").toInt(16)))
            borderRadius = CssSize(50, UNIT.perc)
            padding = CssSize(2, UNIT.px)
            width = CssSize(20, UNIT.px)
            height = CssSize(20, UNIT.px)
            display = Display.FLEX
            alignItems = AlignItems.CENTER
            justifyContent = JustifyContent.CENTER
            zIndex = 1
        }
    }
    
    /**
     * Updates the icon size.
     *
     * @param newSize The new icon size.
     */
    fun updateSize(newSize: IconSize)
    {
        iconSize = newSize
        width = CssSize(newSize.pixels, UNIT.px)
        height = CssSize(newSize.pixels, UNIT.px)
        refresh()
    }
}