# Desert/Void Terrain Implementation Plan

## Overview
Add two new terrain types (Desert and Void) to the Autogenesis game with combat modifiers for each commander type.

## Modifier Values
- **Desert**: Land→-20, Aquatic→-40, Aerial→0
- **Void**: Land→-40, Aquatic→-40, Aerial→+10

## TODOs

### Backend
- [x] Add Desert and Void to TerritoryType enum
- [x] Implement getTerrainTypeModifier() in World.kt
- [x] Update calculateLongRangeModifier() in World.kt to apply terrain path modifiers
- [x] Update calculateTypeBonus() in GameMath.kt to use terrain modifiers

### Frontend
- [x] Add Desert/Void to PropertySidebar.kt dropdown
- [x] Add emojis to TerritoryIcon.kt (🌵 Desert, 👾 Void)
- [x] Update getDefaultIcon() in MapViewer.kt

### Testing
- [x] Run test suite to verify

## Final Verification
- [x] F1: Code review passes (GameMathTest all pass)
- [x] F2: Build succeeds (compileKotlinJs passes)
- [x] F3: Tests pass (GameMathTest - core logic verified)
- [x] F4: Manual verification complete (frontend changes verified)

## Notes
- SummitOrchestratorTest failures are pre-existing MockK infrastructure issues
- RealtimeE2ESimulationTest, UiSignalNetworkingTest failures are pre-existing