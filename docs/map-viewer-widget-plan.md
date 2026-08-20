# Map Viewer Widget Implementation Plan

## Overview
Create a read-only map viewer widget for the KVision app that displays map packs created by the map editor, with interactive territory icons showing ownership and game state.

## Core Concepts from Map Editor

### Data Structures (Already Implemented)
- **MapPack**: ZIP file containing `map.json` + image file
- **MapData**: Contains `List<PinData>` and `List<ConnectionData>`
- **Territory**: Contains name, type, description, resources, obstacle types, position (xPos, yPos)
- **Border**: Directional connections between territories (8 directions)

### Key Patterns from Editor
- **SimplePanel** with `Position.RELATIVE` as container
- **Image** with `Position.ABSOLUTE` as background (z-index: 1)
- **Overlay elements** with `Position.ABSOLUTE` positioned via percentage (z-index: 10+)
- **Percentage-based positioning** for responsive layout across screen sizes

## Requirements

### 1. Map Pack Loading
- Load `.zip` files from resources or uploaded by user
- Unpack using `MapPackManager.unpack()`
- Display background image
- Parse territory data

### 2. Territory Icons
- Display icon at each territory position
- Icon properties:
  - **Image**: Customizable per territory (commander portrait, faction symbol, etc.)
  - **Size**: Adjustable (small/medium/large or pixel values)
  - **Shape**: Circular, square, or custom
  - **Ownership indicator**: Color border/background based on owner
  - **State indicators**: Badges for contested, captured, destroyed

### 3. Interactivity
- **Hover**: Show tooltip with territory info (name, owner, resources)
- **Click**: Trigger callback with territory data (for detail panel)
- **Real-time updates**: Update icon/ownership as game state changes

### 4. Game State Integration
- WebSocket connection to receive game state updates
- Update territory ownership dynamically
- Animate state changes (fade, pulse, etc.)

## Implementation Steps

### Step 1: Create MapViewer Widget Structure
**File**: `kvisionApp/src/jsMain/kotlin/ui/MapViewer.kt`

**Components**:
```kotlin
class MapViewer : SimplePanel() {
    private var backgroundImage: Image? = null
    private var territoryIcons = mutableMapOf<String, TerritoryIcon>()
    private var mapData: MapData? = null
    
    init {
        width = CssSize(100, UNIT.perc)
        height = CssSize(100, UNIT.perc)
        position = Position.RELATIVE
    }
}
```

**Key Features**:
- Container with relative positioning
- Background image layer (z-index: 1)
- Territory icon layer (z-index: 10)
- Tooltip/popover layer (z-index: 100)

### Step 2: Create TerritoryIcon Component
**File**: `kvisionApp/src/jsMain/kotlin/ui/TerritoryIcon.kt`

**Properties**:
```kotlin
class TerritoryIcon(
    val territory: Territory,
    var iconImage: String,
    var iconSize: IconSize = IconSize.MEDIUM,
    var iconShape: IconShape = IconShape.CIRCLE,
    var owner: String? = null
) : SimplePanel()
```

**Visual Structure**:
- Outer container: Positioned absolutely at territory.xPos/yPos
- Border/background: Shows ownership color
- Icon image: Commander portrait or faction symbol
- Badge overlay: Shows state (contested, captured, etc.)

**Styling**:
- Use CSS `border-radius` for circular shape
- Use CSS `border` for ownership color
- Use CSS `box-shadow` for hover effect
- Use CSS `transform: translate(-50%, -50%)` for center alignment

### Step 3: Implement Icon Hover Effects
**Using KVision Tooltips**:
```kotlin
icon.enableTooltip(
    title = territory.name,
    content = buildTooltipContent(territory),
    placement = Placement.TOP
)
```

**Tooltip Content**:
- Territory name
- Owner (commander/player/NPC)
- Resources available
- Point value
- Current state (captured, contested, etc.)

**Hover Visual Effects**:
- Scale up slightly (CSS transform: scale(1.1))
- Add glow effect (box-shadow)
- Show border connections (optional)

### Step 4: Implement Click Handlers
**Click Behavior**:
```kotlin
icon.onClick {
    onTerritoryClick?.invoke(territory)
}
```

**Callback Interface**:
```kotlin
var onTerritoryClick: ((Territory) -> Unit)? = null
var onTerritoryHover: ((Territory) -> Unit)? = null
```

**Use Cases**:
- Open territory detail panel
- Show available actions (attack, fortify, etc.)
- Display territory history

### Step 5: Load Map Pack
**Loading Process**:
1. Call `MapPackManager.unpack(packBytes)`
2. Create blob URL from image bytes
3. Set background image src
4. Create TerritoryIcon for each pin in mapData
5. Position icons at territory.xPos/yPos percentages

**Code Pattern**:
```kotlin
suspend fun loadMapPack(packBytes: ByteArray) {
    val unpacked = MapPackManager.unpack(packBytes)
    
    // Set background
    val imageBlob = createBlobFromBytes(unpacked.imageBytes)
    backgroundImage?.src = URL.createObjectURL(imageBlob)
    
    // Create icons
    unpacked.mapData.pins.forEach { pinData ->
        val icon = TerritoryIcon(
            territory = pinData.territory,
            iconImage = getDefaultIcon(pinData.territory.type)
        )
        addTerritoryIcon(icon)
    }
}
```

### Step 6: Icon Customization System
**Icon Configuration**:
```kotlin
data class IconConfig(
    val territoryId: String,
    val iconImage: String,
    val size: IconSize,
    val shape: IconShape,
    val ownerColor: String
)

enum class IconSize(val pixels: Int) {
    SMALL(32),
    MEDIUM(48),
    LARGE(64),
    XLARGE(96)
}

enum class IconShape {
    CIRCLE,
    SQUARE,
    ROUNDED_SQUARE,
    HEXAGON
}
```

**Dynamic Updates**:
```kotlin
fun updateTerritoryIcon(territoryId: String, config: IconConfig) {
    territoryIcons[territoryId]?.apply {
        iconImage = config.iconImage
        iconSize = config.size
        iconShape = config.shape
        updateOwnerColor(config.ownerColor)
        refresh()
    }
}
```

### Step 7: Ownership Visualization
**Owner Indicators**:
- **Border color**: Unique color per player/commander
- **Background tint**: Semi-transparent owner color
- **Badge**: Small icon showing faction/allegiance
- **Animation**: Pulse effect when ownership changes

**Implementation**:
```kotlin
fun updateOwnership(territoryId: String, newOwner: String, ownerColor: String) {
    territoryIcons[territoryId]?.apply {
        owner = newOwner
        border = Border(width = 3.px, style = BorderStyle.SOLID, color = Color.hex(ownerColor))
        background = Background(color = Color.hex(ownerColor).withAlpha(0.3))
        
        // Animate change
        addCssClass("ownership-change-animation")
        window.setTimeout({ removeCssClass("ownership-change-animation") }, 1000)
    }
}
```

### Step 8: Game State Integration
**WebSocket Updates**:
```kotlin
class MapViewer {
    private var gameStateSocket: WebSocket? = null
    
    fun connectToGameState(gameId: String) {
        gameStateSocket = WebSocket("ws://server/game/$gameId/state")
        
        gameStateSocket?.onMessage { event ->
            val update = Json.decodeFromString<GameStateUpdate>(event.data)
            handleGameStateUpdate(update)
        }
    }
    
    private fun handleGameStateUpdate(update: GameStateUpdate) {
        when (update.type) {
            UpdateType.OWNERSHIP_CHANGE -> {
                updateOwnership(update.territoryId, update.newOwner, update.ownerColor)
            }
            UpdateType.TERRITORY_CAPTURED -> {
                showCaptureAnimation(update.territoryId)
            }
            UpdateType.BATTLE_STARTED -> {
                showContestedState(update.territoryId)
            }
        }
    }
}
```

### Step 9: State Indicators
**Visual States**:
- **Normal**: Default icon appearance
- **Captured**: Checkmark badge overlay
- **Contested**: Crossed swords badge, pulsing border
- **Destroyed**: Grayscale filter, explosion icon
- **Fortified**: Shield badge overlay

**Badge System**:
```kotlin
class TerritoryIcon {
    private var stateBadge: Div? = null
    
    fun updateState(state: TerritoryState) {
        stateBadge?.let { remove(it) }
        
        stateBadge = when (state) {
            TerritoryState.CONTESTED -> createBadge("⚔️", Color.RED)
            TerritoryState.CAPTURED -> createBadge("✓", Color.GREEN)
            TerritoryState.FORTIFIED -> createBadge("🛡️", Color.BLUE)
            TerritoryState.DESTROYED -> createBadge("💥", Color.GRAY)
            else -> null
        }
        
        stateBadge?.let { add(it) }
    }
    
    private fun createBadge(emoji: String, color: Color): Div {
        return Div(emoji) {
            position = Position.ABSOLUTE
            top = CssSize(-5, UNIT.px)
            right = CssSize(-5, UNIT.px)
            fontSize = CssSize(16, UNIT.px)
            background = Background(color = color)
            borderRadius = CssSize(50, UNIT.perc)
            padding = CssSize(2, UNIT.px)
            zIndex = 1
        }
    }
}
```

### Step 10: Animation System
**CSS Animations**:
```css
@keyframes ownership-change {
    0%, 100% { transform: translate(-50%, -50%) scale(1); }
    50% { transform: translate(-50%, -50%) scale(1.2); }
}

@keyframes pulse-border {
    0%, 100% { border-width: 3px; }
    50% { border-width: 5px; }
}

@keyframes fade-in {
    from { opacity: 0; }
    to { opacity: 1; }
}
```

**Kotlin Animation Triggers**:
```kotlin
fun animateOwnershipChange(territoryId: String) {
    territoryIcons[territoryId]?.addCssClass("ownership-change-animation")
}

fun animateContestedState(territoryId: String) {
    territoryIcons[territoryId]?.addCssClass("pulse-border-animation")
}
```

## File Structure

```
kvisionApp/src/jsMain/kotlin/
├── ui/
│   ├── MapViewer.kt              # Main map viewer widget
│   ├── TerritoryIcon.kt          # Individual territory icon component
│   └── MapViewerControls.kt      # Zoom, pan, filter controls (optional)
├── models/
│   ├── IconConfig.kt             # Icon configuration data classes
│   ├── GameStateUpdate.kt        # WebSocket update messages
│   └── TerritoryState.kt         # Territory state enum
└── resources/
    ├── icons/                    # Default territory icons
    │   ├── commander-default.png
    │   ├── faction-red.png
    │   └── faction-blue.png
    └── css/
        └── map-viewer.css        # Custom animations and styles
```

## Testing Strategy

### Unit Tests
1. MapViewer loads map pack correctly
2. TerritoryIcon positions at correct percentages
3. Ownership updates change visual appearance
4. Click handlers fire with correct territory data

### Integration Tests
1. Load map pack → verify all icons appear
2. WebSocket update → verify icon updates
3. Hover → verify tooltip shows correct data
4. Click → verify callback receives territory

### Visual Tests
1. Icons scale properly on different screen sizes
2. Tooltips don't overflow viewport
3. Animations play smoothly
4. Z-index layering is correct

## Performance Considerations

### Optimization Strategies
1. **Lazy loading**: Only create icons in viewport
2. **Icon caching**: Reuse icon images via CSS sprites
3. **Debounce updates**: Batch multiple state changes
4. **Virtual scrolling**: For maps with 100+ territories
5. **WebGL rendering**: For very large maps (future enhancement)

### Memory Management
- Remove event listeners when icons are destroyed
- Close WebSocket connection when viewer unmounts
- Clear blob URLs when switching maps

## Accessibility

### ARIA Labels
```kotlin
icon.setAttribute("role", "button")
icon.setAttribute("aria-label", "Territory: ${territory.name}, Owner: ${owner}")
icon.setAttribute("tabindex", "0")
```

### Keyboard Navigation
- Tab through territories
- Enter/Space to "click" territory
- Arrow keys to navigate between adjacent territories

## Future Enhancements

1. **Zoom/Pan**: Pinch-to-zoom and drag-to-pan
2. **Minimap**: Small overview map in corner
3. **Filters**: Show/hide territories by owner, state, etc.
4. **Animations**: Troop movement animations between territories
5. **3D Mode**: Isometric or 3D view of map
6. **Fog of War**: Hide unexplored territories
7. **Path Finding**: Visual path between territories
8. **Territory Grouping**: Show regions/continents

## Dependencies

### Required
- `structs.MapPackManager` (already implemented)
- `structs.MapData`, `PinData`, `Territory` (already implemented)
- KVision Core (SimplePanel, Image, Div)
- KVision Bootstrap (Tooltips, Popovers)
- kotlinx.serialization (JSON parsing)

### Optional
- KVision WebSocket module (for real-time updates)
- KVision Charts (for resource visualization)
- KVision Animations (for advanced effects)

## Implementation Timeline

1. **Phase 1** (2-3 days): Basic MapViewer + TerritoryIcon
2. **Phase 2** (1-2 days): Hover/click interactions + tooltips
3. **Phase 3** (2-3 days): Ownership visualization + state indicators
4. **Phase 4** (2-3 days): WebSocket integration + real-time updates
5. **Phase 5** (1-2 days): Animations + polish
6. **Phase 6** (1-2 days): Testing + bug fixes

**Total**: ~10-15 days for full implementation

## Success Criteria

- ✅ Load and display map packs from editor
- ✅ Show territory icons at correct positions
- ✅ Hover shows territory information
- ✅ Click triggers callback with territory data
- ✅ Icons update when ownership changes
- ✅ State indicators show game status
- ✅ Smooth animations for state changes
- ✅ Responsive on different screen sizes
- ✅ Accessible via keyboard
- ✅ Performant with 50+ territories