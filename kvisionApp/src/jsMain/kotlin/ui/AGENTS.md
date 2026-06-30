# KVision UI Agents

## OVERVIEW
Main gameplay UI built with KVision 9.1.1, exposing 14+ overlay widgets and AccelByte SDK integration.

## WHERE TO LOOK

### Entry Points
- `ui/LoginWidgets.kt` — Login/register/recover flow (973 lines)
- `ui/MainMenu.kt` — Post-login menu with play button
- `ui/gameplay/GameplayUI.kt` — Main screen orchestrating all overlay widgets

### Key Screens
- `ui/gameplay/MapViewer.kt` — Game map display
- `ui/gameplay/TurnResolutionWidget.kt` — Turn resolution with streaming pages (2146 lines)
- `ui/CommanderSelectionDialog.kt` — Commander picker
- `ui/CollectionOverlay.kt` — Collection browser

### HUD Widgets (stored as nullable fields in GameplayUI)
- `ScoreDisplay.kt`, `StatsWidget.kt`, `PlayerResourcesWidget.kt`, `PlayerTerritoriesWidget.kt`
- `PlayerInfoWidget.kt`, `WorldStatsWidget.kt`, `PromptStatusWidget.kt`
- `GameHistoryWindow.kt`, `CommandBox.kt`, `AgentWorkStreamWindow.kt`

### Global State
- `globals/World.kt` — Game world data (sharedModel)
- `globals/KEnv.kt` — KVision environment singleton
- `globals/AccelByteEnv.kt` — User identity from SDK

## CONVENTIONS

### Widget Management
- Widgets stored as nullable fields (`var scoreDisplay: ScoreDisplay?`) not retrieved via `getChild()`
- Manual show/hide via `.show()`/`.hide()` — NOT reactive binding
- Add to parent with `parent.add(widget)` then `widget.show()`

### Navigation
- Stack-based navigation via `KEnv.appStack?.activeIndex = N` — NOT KVision router
- `KEnv.mainRoot` holds the `Root?` singleton for direct DOM access

### Logging
- Use `Logger.debug(LogCategory.UI, ...)` for UI events
- Category selection: `LogCategory.UI` for rendering, `LogCategory.NETWORK` for RPC calls

### Coroutine Scope
- `MainScope().launch { }` for UI-level async work

## ANTI-PATTERNS

### DO NOT
- Use `println()` for debugging — use `Logger.debug()` instead
- Access widgets via `getChildById()` — store direct references
- Rely on KVision router — use `KEnv.appStack` stack manipulation
- Call `.show()`/`.hide()` inside `update()` loops — set visibility state instead
- Create new coroutine scopes beyond `MainScope()` for UI work

### GameplayUI.init Block
- The `GameplayUI.init` block is ~330 lines — initialize child widgets sequentially
- Each widget gets added to `this` (SimplePanel) before `.show()` is called

### AccelByte SDK
- Facades accessed via `UsersFacade`, `IamFacade` from `accelbyteSdk` bindings
- Token refresh handled automatically by SDK — do not manually manage
