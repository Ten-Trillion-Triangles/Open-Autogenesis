package ui

import io.kvision.core.*
import io.kvision.html.CustomTag
import io.kvision.html.Image
import io.kvision.html.customTag
import io.kvision.html.image
import io.kvision.panel.SimplePanel
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.await
import models.IconSize
import models.TerritoryState
import org.khronos.webgl.ArrayBuffer
import org.khronos.webgl.Uint8Array
import org.w3c.dom.url.URL
import org.w3c.files.Blob
import org.w3c.files.BlobPropertyBag
import org.w3c.files.File
import org.w3c.files.FileReader
import structs.MapData
import org.ttt.autogenesis.logging.LogCategory
import org.ttt.autogenesis.logging.Logger
import structs.MapPackManager
import structs.Territory
import structs.UnpackedMapPack
import kotlin.coroutines.resume

/**
 * Interactive map viewer widget that displays territories with clickable icons.
 */
class MapViewer : SimplePanel()
{
    
    private var backgroundImage: Image? = null
    private val territoryIcons = mutableMapOf<String, TerritoryIcon>()
    private var mapData: MapData? = null
    var isMapLoaded = false
    
    var onTerritoryClick: ((Territory) -> Unit)? = null
    var onTerritoryHover: ((Territory) -> Unit)? = null

    private var updateOwnershipCallCount = 0
    private var totalUpdateOwnershipDuration = 0.0

    /**
     * SVG overlay that hosts the per-hover border lines. Sits above
     * the background image (zIndex 1) and below the territory icons
     * (zIndex 10). `pointer-events: none` so the icons remain the
     * hover target; the lines are purely visual.
     *
     * Initialized once in [init] and reused for the lifetime of the
     * widget. Children are added/removed via [svgLineGroup] (the
     * inner `<g>` element); the outer `<svg>` is never replaced, so
     * the snabbdom diff for hover changes is scoped to the inner
     * group and never re-renders the background image or the icon
     * tree.
     */
    private var svgLayer: CustomTag? = null

    /**
     * The inner `<g>` group whose children are the `<line>` elements
     * for the currently-hovered territory. Cleared on every
     * hover-out and rebuilt on every hover-in. Keeping the lines
     * inside a dedicated group means `removeAll()` only diffs that
     * group, not the SVG element itself.
     */
    private var svgLineGroup: CustomTag? = null

    /**
     * Pure-logic helper that turns the current [mapData] into
     * per-hover line geometry. Rebuilt every time a new map pack is
     * loaded; the [loadMapPack] / [clearMap] pair owns the lifecycle.
     */
    private var borderRenderer: HoverBorderLineRenderer? = null

    init
    {
        addCssClass("map-viewer-container")
        width = CssSize(100, UNIT.perc)
        height = CssSize(100, UNIT.vh)
        position = Position.RELATIVE
        overflow = Overflow.HIDDEN
        
        // Background image layer
        backgroundImage = image(src = "", alt = "Map") {
            position = Position.ABSOLUTE
            left = CssSize(0, UNIT.px)
            top = CssSize(0, UNIT.px)
            width = CssSize(100, UNIT.perc)
            height = CssSize(100, UNIT.perc)
            zIndex = 1
            setStyle("object-fit", "fill")
            setStyle("pointer-events", "none")
        }

        // SVG border-line overlay. Stays mounted for the lifetime
        // of the widget; only its inner <g> child list mutates per
        // hover. Sized 100% / 100% so the percent-attribute lines
        // track the background image as the container resizes.
        svgLayer = customTag("svg") {
            setAttribute("width", "100%")
            setAttribute("height", "100%")
            setAttribute("data-testid", "map-svg-borders")
            setAttribute("style", "position: absolute; top: 0; left: 0; pointer-events: none; z-index: 5;")
            svgLineGroup = customTag("g")
        }
    }
    
    /**
     * Loads a map pack and displays territories with icons.
     *
     * @param packBytes The map pack data as ByteArray.
     */
    suspend fun loadMapPack(packBytes: ByteArray)
    {
        val totalStart = kotlinx.browser.window.performance.now()
        Logger.debug(LogCategory.UI, "MapViewer: Loading map pack...")
        
        // Reset existing map state
        clearMap()
        
        val unpackStart = kotlinx.browser.window.performance.now()
        val unpacked = MapPackManager.unpack(packBytes)
        val unpackDuration = kotlinx.browser.window.performance.now() - unpackStart
        Logger.debug(LogCategory.UI, "MapViewer: Unpacked ${unpacked.mapData.pins.size} territories in ${unpackDuration.asDynamic().toFixed(2)}ms")
        
        // Set background image
        val imageStart = kotlinx.browser.window.performance.now()
        val imageBlob = createBlobFromBytes(unpacked.imageBytes)
        val blobUrl = URL.createObjectURL(imageBlob)
        backgroundImage?.src = blobUrl
        val imageDuration = kotlinx.browser.window.performance.now() - imageStart
        Logger.debug(LogCategory.UI, "MapViewer: Background image loaded in ${imageDuration.asDynamic().toFixed(2)}ms")
        
        // Store map data
        mapData = unpacked.mapData
        Logger.debug(LogCategory.UI, "MapViewer: Loaded Map with ${mapData?.pins?.size} territories")
        
        // Create territory icons
        val iconLoopStart = kotlinx.browser.window.performance.now()
        unpacked.mapData.pins.forEach { pinData ->
            addTerritoryIcon(pinData.territory)
        }
        val iconLoopDuration = kotlinx.browser.window.performance.now() - iconLoopStart

        // Build the per-hover border-line index for this map. The
        // helper is pure, so building it is cheap; the resulting
        // [borderRenderer] is what every hover lookup asks.
        borderRenderer = HoverBorderLineRenderer(unpacked.mapData)
        
        val totalDuration = kotlinx.browser.window.performance.now() - totalStart
        Logger.info(LogCategory.UI, "[PERF] [CLIENT] MapViewer.loadMapPack completed in ${totalDuration.asDynamic().toFixed(2)}ms (Unpack: ${unpackDuration.asDynamic().toFixed(2)}ms, Image: ${imageDuration.asDynamic().toFixed(2)}ms, Icons: ${iconLoopDuration.asDynamic().toFixed(2)}ms)")
        isMapLoaded = true
    }
    
    /**
     * Loads a map pack asynchronously in a coroutine scope.
     *
     * @param packBytes The map pack data as ByteArray.
     */
    fun loadMapPackAsync(packBytes: ByteArray)
    {
        MainScope().launch {
            try
            {
                loadMapPack(packBytes)
            }
            catch(e: Exception)
            {
                Logger.error(LogCategory.UI, "MapViewer: Failed to load map pack: ${e.message}")
            }
        }
    }
    
    /**
     * Creates and adds a territory icon to the map.
     *
     * @param territory The territory data for the icon.
     */
    private fun addTerritoryIcon(territory: Territory)
    {
        Logger.debug(LogCategory.UI, "MapViewer: Adding icon for territory '${territory.name}' (type: ${territory.type})")
        
        val defaultIcon = getDefaultIcon(territory.type)
        Logger.debug(LogCategory.UI, "MapViewer: Default icon for ${territory.type} = '$defaultIcon'")
        
        val icon = TerritoryIcon(
            territory = territory,
            iconImage = defaultIcon,
            iconSize = IconSize.MEDIUM
        )
        
        icon.onIconClick = { t ->
            Logger.debug(LogCategory.UI, "MapViewer: Territory clicked callback - '${t.name}'")
            onTerritoryClick?.invoke(t)
        }

        icon.onIconHover = { t, isEnter ->
            if(isEnter)
            {
                Logger.debug(LogCategory.UI, "MapViewer: Territory hover-enter - '${t.name}', drawing border lines")
                showBorderLinesForTerritory(t.name)
            }
            else
            {
                Logger.debug(LogCategory.UI, "MapViewer: Territory hover-leave - '${t.name}', clearing border lines")
                clearBorderLines()
            }
        }

        territoryIcons[territory.name] = icon
        add(icon)
        Logger.debug(LogCategory.UI, "MapViewer: Icon added to map for '${territory.name}'")
    }
    
    /**
     * Returns the default icon emoji for a territory type.
     *
     * @param territoryType The type of territory.
     * @return The icon emoji string.
     */
    private fun getDefaultIcon(territoryType: enums.TerritoryType): String
    {
        val icon = when(territoryType)
        {
            enums.TerritoryType.Land -> "🏰"
            enums.TerritoryType.Coastline -> "⚓"
            enums.TerritoryType.Island -> "🏝️"
            enums.TerritoryType.Underwater -> "🪸"
            enums.TerritoryType.Desert -> "🌵"
            enums.TerritoryType.Void -> "👾"
        }
        Logger.debug(LogCategory.UI, "MapViewer: getDefaultIcon(${territoryType}) = '$icon'")
        return icon
    }
    
    /**
     * Creates a Blob from a ByteArray for image display.
     *
     * @param bytes The image data as ByteArray.
     * @return Blob containing the image data.
     */
    private fun createBlobFromBytes(bytes: ByteArray): Blob
    {
        val dynamicBytes = bytes.asDynamic()
        val uint8Array = Uint8Array(dynamicBytes.buffer, dynamicBytes.byteOffset, dynamicBytes.byteLength)
        return Blob(arrayOf(uint8Array), BlobPropertyBag(type = "image/png"))
    }
    
    /**
     * Updates the ownership display for a territory.
     *
     * @param territoryName The name of the territory to update.
     * @param owner The owner's name.
     * @param ownerColor The owner's color in hex format.
     * @param activePlayerName Optional name of the currently active player.
     * @param localPlayerName Optional name of the local player.
     * @param forceAnimate If true, triggers the animation even if the owner has not changed.
     */
    fun updateTerritoryOwnership(
        territoryName: String, 
        owner: String, 
        ownerColor: String,
        activePlayerName: String? = null,
        localPlayerName: String? = null,
        forceAnimate: Boolean = false
    )
    {
        val callStart = kotlinx.browser.window.performance.now()
        val icon = territoryIcons[territoryName]
        if(icon != null)
        {
            // Determine the correct emoji icon based on ownership
            var emojiIcon = getDefaultIcon(icon.territory.type)
            
            if(owner.isBlank())
            {
                emojiIcon = getDefaultIcon(icon.territory.type)
            }
            else
            {
                // Use passed localPlayerName if available, else fallback to globals
                val resolvedLocalPlayer = localPlayerName ?: globals.World.localPlayer.name
                val world = globals.World.worldData
                
                Logger.debug(LogCategory.UI, "MapViewer.updateTerritoryOwnership: '$territoryName' - Owner: '$owner', Local: '$resolvedLocalPlayer', PlayersInWorld: ${world.activePlayers.size}")

                if(owner.trim().equals(resolvedLocalPlayer.trim(), ignoreCase = true))
                {
                    emojiIcon = "👑"
                    Logger.debug(LogCategory.UI, "MapViewer.updateTerritoryOwnership: Matched LOCAL player for '$territoryName'")
                }
                else
                {
                    // Check turn order index for numbering
                    val playerIndex = world.turnOrder.indexOfFirst { it.trim().equals(owner.trim(), ignoreCase = true) }
                    if(playerIndex != -1)
                    {
                        emojiIcon = when(playerIndex) {
                            0 -> "①"
                            1 -> "②"
                            2 -> "③"
                            3 -> "④"
                            else -> "👤"
                        }
                        Logger.debug(LogCategory.UI, "MapViewer.updateTerritoryOwnership: Matched player at turn index $playerIndex for '$territoryName' -> Icon: $emojiIcon")
                    }
                    else
                    {
                        // Check if it's an NPC (not in turn order as a player)
                        val isNpc = world.npc.any { it.name.trim().equals(owner.trim(), ignoreCase = true) }
                        if(isNpc)
                        {
                            emojiIcon = "🤖"
                            Logger.debug(LogCategory.UI, "MapViewer.updateTerritoryOwnership: Matched NPC for '$territoryName'")
                        }
                        else
                        {
                            // Fallback for unknown owners
                            emojiIcon = getDefaultIcon(icon.territory.type)
                            Logger.warn(LogCategory.UI, "MapViewer.updateTerritoryOwnership: UNKNOWN owner '$owner' for '$territoryName', using default icon")
                        }
                    }
                }
            }
            
            // Keep the territory record in sync so description popups see the latest ruler.
            val oldOwner = icon.owner
            icon.territory.ruler = owner
            icon.updateOwnership(owner, ownerColor, emojiIcon, activePlayerName, localPlayerName, forceAnimate)
        }
        else
        {
            // Silently ignore - territory may not be added to map yet
            Logger.warn(LogCategory.UI, "MapViewer: Territory '$territoryName' not found in icon map (Size: ${territoryIcons.size})")
        }

        val callDuration = kotlinx.browser.window.performance.now() - callStart
        totalUpdateOwnershipDuration += callDuration
        updateOwnershipCallCount++
        
        if(updateOwnershipCallCount >= 100)
        {
            val avg = totalUpdateOwnershipDuration / updateOwnershipCallCount
            Logger.info(LogCategory.UI, "[PERF] [CLIENT] MapViewer.updateTerritoryOwnership (Avg of last 100): ${avg.asDynamic().toFixed(4)}ms")
            updateOwnershipCallCount = 0
            totalUpdateOwnershipDuration = 0.0
        }
    }
    
    /**
     * Updates the state display for a territory.
     *
     * @param territoryName The name of the territory to update.
     * @param state The new territory state.
     */
    fun updateTerritoryState(territoryName: String, state: TerritoryState)
    {
        Logger.debug(LogCategory.UI, "MapViewer: Updating state for '$territoryName' - State: $state")
        val icon = territoryIcons[territoryName]
        if(icon != null)
        {
            icon.updateState(state)
            Logger.debug(LogCategory.UI, "MapViewer: State updated successfully")
        }
        else
        {
            // Silently ignore - territory may not be added to map yet
            Logger.debug(LogCategory.UI, "MapViewer: Territory '$territoryName' not found in icon map")
        }
    }
    
    /**
     * Retrieves a territory icon by name.
     *
     * @param territoryName The name of the territory.
     * @return The TerritoryIcon or null if not found.
     */
    fun getTerritoryIcon(territoryName: String): TerritoryIcon?
    {
        return territoryIcons[territoryName]
    }
    
    /**
     * Rebuilds the SVG `<line>` children of [svgLineGroup] so the
     * hovered territory's direct neighbors are visually connected
     * to it.
     *
     * The geometry comes from [HoverBorderLineRenderer]; the SVG
     * work stays here so the renderer can stay pure and unit-
     * testable. Each line is written as percent-attribute values
     * (0..100) so it tracks the background image as the container
     * resizes. Stroke is fixed at gold for v1 (no owner-aware
     * coloring yet — see plan).
     *
     * Safe to call when [borderRenderer] or [svgLineGroup] is
     * null: the function becomes a no-op so it can be called from
     * the hover callback without null-guarding at the call site.
     *
     * @param territoryName The territory under the cursor.
     */
    private fun showBorderLinesForTerritory(territoryName: String)
    {
        val renderer = borderRenderer ?: return
        val group = svgLineGroup ?: return
        val specs = renderer.linesForHoveredTerritory(territoryName)
        group.removeAll()
        for(spec in specs)
        {
            group.add(
                customTag("line") {
                    setAttribute("x1", "${spec.x1}%")
                    setAttribute("y1", "${spec.y1}%")
                    setAttribute("x2", "${spec.x2}%")
                    setAttribute("y2", "${spec.y2}%")
                    setAttribute("stroke", "#FFD700")
                    setAttribute("stroke-width", "2")
                    setAttribute("data-connection", spec.connectionKey)
                }
            )
        }
    }

    /**
     * Removes every SVG `<line>` child from [svgLineGroup] so the
     * map returns to its idle (line-free) state.
     *
     * Called from the hover-leave callback. Safe to call when
     * [svgLineGroup] is null.
     */
    private fun clearBorderLines()
    {
        svgLineGroup?.removeAll()
    }

    /**
     * Clears all territories and resets the map.
     */
    fun clearMap()
    {
        clearBorderLines()
        borderRenderer = null
        territoryIcons.values.forEach { remove(it) }
        territoryIcons.clear()
        backgroundImage?.src = ""
        mapData = null
    }
}

/**
 * Loads a map pack file and returns its contents as ByteArray.
 *
 * @param file The File object to read.
 * @return ByteArray containing the file data.
 */
suspend fun loadMapPackFile(file: File): ByteArray = suspendCancellableCoroutine { cont ->
    val reader = FileReader()
    reader.onload = {
        val arrayBuffer = reader.result as ArrayBuffer
        val uint8Array = Uint8Array(arrayBuffer)
        val bytes = ByteArray(uint8Array.length)
        for(i in bytes.indices) bytes[i] = uint8Array.asDynamic()[i] as Byte
        cont.resume(bytes)
    }
    reader.onerror = { cont.cancel() }
    reader.readAsArrayBuffer(file)
}

/**
 * Fetches a map pack from a URL and returns its contents as ByteArray.
 *
 * @param url The URL to fetch from.
 * @return ByteArray containing the fetched data.
 */
suspend fun fetchMapPackFromUrl(url: String): ByteArray
{
    val response = kotlinx.browser.window.fetch(url).await()
    val arrayBuffer = response.arrayBuffer().await()
    val uint8Array = Uint8Array(arrayBuffer)
    val bytes = ByteArray(uint8Array.length)
    for(i in bytes.indices) bytes[i] = uint8Array.asDynamic()[i] as Byte
    return bytes
}