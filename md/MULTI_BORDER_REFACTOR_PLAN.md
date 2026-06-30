# Multi-Border Refactor Plan

## Problem Statement

**Current Limitation**: Territory data structure supports exactly 8 border connections (one per cardinal direction: N, S, E, W, NE, NW, SE, SW).

**Real-World Requirement**: Territories on real maps can have multiple neighbors in the same general direction.

**Example**: New York State borders:
- North: Vermont, Quebec (Canada)
- East: Connecticut, Massachusetts, Vermont
- South: Pennsylvania, New Jersey
- West: Pennsylvania, Ontario (Canada)

With current system, New York can only connect to ONE territory per direction, limiting it to 8 total connections maximum.

---

## Proposed Solution

### Option A: List-Based Borders (Recommended)
Replace single border slots with lists of borders per direction.

**Advantages**:
- Unlimited connections per direction
- Maintains directional organization
- Clear semantic meaning

**Disadvantages**:
- Breaking change to Territory data structure
- Requires migration of existing data
- More complex border management logic

### Option B: Flat Border List
Replace directional borders with a single list of all borders, storing direction as metadata.

**Advantages**:
- Simpler data structure
- More flexible

**Disadvantages**:
- Loses directional organization
- Harder to query "all northern neighbors"
- Less intuitive

**Decision**: Proceed with **Option A** (List-Based Borders)

---

## Implementation Strategy

### Phase 1: Update Territory Data Structure
**File**: `sharedModel/src/commonMain/kotlin/structs/Territory.kt`

**Current Structure**:
```kotlin
@kotlinx.serialization.Serializable
data class Territory(
    // ...
    var northBorder: Border? = null,
    var westBorder: Border? = null,
    var eastBorder: Border? = null,
    var southBorder: Border? = null,
    var northEastBorder: Border? = null,
    var northWestBorder: Border? = null,
    var southEastBorder: Border? = null,
    var southWestBorder: Border? = null,
    // ...
)
```

**New Structure**:
```kotlin
@kotlinx.serialization.Serializable
data class Territory(
    // ...
    var northBorders: MutableList<Border> = mutableListOf(),
    var westBorders: MutableList<Border> = mutableListOf(),
    var eastBorders: MutableList<Border> = mutableListOf(),
    var southBorders: MutableList<Border> = mutableListOf(),
    var northEastBorders: MutableList<Border> = mutableListOf(),
    var northWestBorders: MutableList<Border> = mutableListOf(),
    var southEastBorders: MutableList<Border> = mutableListOf(),
    var southWestBorders: MutableList<Border> = mutableListOf(),
    // ...
)
```

**Migration Considerations**:
- Old serialized Territory data will fail to deserialize
- Need migration script or accept data loss
- Consider adding @SerialName annotations for backward compatibility

**Validation**:
- Build sharedModel module
- Verify serialization works
- Test Territory creation

---

### Phase 2: Update Pin Connection Logic
**File**: `mapEditor/src/jsMain/kotlin/ui/Pin.kt`

**Current Code** (connectToPinWithBorder):
```kotlin
when (myDirection) {
    "north" -> territory.northBorder = borderToTarget
    "south" -> territory.southBorder = borderToTarget
    // ... single assignment per direction
}
```

**New Code**:
```kotlin
when (myDirection) {
    "north" -> territory.northBorders.add(borderToTarget)
    "south" -> territory.southBorders.add(borderToTarget)
    "east" -> territory.eastBorders.add(borderToTarget)
    "west" -> territory.westBorders.add(borderToTarget)
    "northEast" -> territory.northEastBorders.add(borderToTarget)
    "northWest" -> territory.northWestBorders.add(borderToTarget)
    "southEast" -> territory.southEastBorders.add(borderToTarget)
    "southWest" -> territory.southWestBorders.add(borderToTarget)
}

when (theirDirection) {
    "north" -> targetPin.territory.northBorders.add(borderToMe)
    "south" -> targetPin.territory.southBorders.add(borderToMe)
    "east" -> targetPin.territory.eastBorders.add(borderToMe)
    "west" -> targetPin.territory.westBorders.add(borderToMe)
    "northEast" -> targetPin.territory.northEastBorders.add(borderToMe)
    "northWest" -> targetPin.territory.northWestBorders.add(borderToMe)
    "southEast" -> targetPin.territory.southEastBorders.add(borderToMe)
    "southWest" -> targetPin.territory.southWestBorders.add(borderToMe)
}
```

**Duplicate Prevention**:
Add check before adding border:
```kotlin
// Check if connection already exists
val alreadyConnected = territory.northBorders.any { 
    it.adjacentTerritory == targetPin.territory 
}
if (!alreadyConnected) {
    territory.northBorders.add(borderToTarget)
}
```

**Validation**:
- Build mapEditor module
- Test creating multiple connections in same direction
- Verify no duplicate connections

---

### Phase 3: Update Border Breaking Logic
**File**: `mapEditor/src/jsMain/kotlin/ui/Pin.kt`

**Current Code** (breakAllBorders):
```kotlin
territory.northBorder = null
territory.southBorder = null
// ... set to null
```

**New Code**:
```kotlin
territory.northBorders.clear()
territory.southBorders.clear()
territory.eastBorders.clear()
territory.westBorders.clear()
territory.northEastBorders.clear()
territory.northWestBorders.clear()
territory.southEastBorders.clear()
territory.southWestBorders.clear()
```

**Remove References from Other Pins**:
```kotlin
canvas.connectedPins.forEach { otherPin ->
    if (otherPin != this) {
        otherPin.territory.northBorders.removeAll { it.adjacentTerritory == territory }
        otherPin.territory.southBorders.removeAll { it.adjacentTerritory == territory }
        otherPin.territory.eastBorders.removeAll { it.adjacentTerritory == territory }
        otherPin.territory.westBorders.removeAll { it.adjacentTerritory == territory }
        otherPin.territory.northEastBorders.removeAll { it.adjacentTerritory == territory }
        otherPin.territory.northWestBorders.removeAll { it.adjacentTerritory == territory }
        otherPin.territory.southEastBorders.removeAll { it.adjacentTerritory == territory }
        otherPin.territory.southWestBorders.removeAll { it.adjacentTerritory == territory }
    }
}
```

**Validation**:
- Test middle-click to break borders
- Verify all connections removed
- Verify other pins' lists updated

---

### Phase 4: Update MapCanvas.removePin()
**File**: `mapEditor/src/jsMain/kotlin/ui/MapCanvas.kt`

**Current Code**:
```kotlin
if (otherPin.territory.northBorder?.adjacentTerritory == pin.territory) {
    otherPin.territory.northBorder = null
}
```

**New Code**:
```kotlin
otherPin.territory.northBorders.removeAll { it.adjacentTerritory == pin.territory }
otherPin.territory.southBorders.removeAll { it.adjacentTerritory == pin.territory }
otherPin.territory.eastBorders.removeAll { it.adjacentTerritory == pin.territory }
otherPin.territory.westBorders.removeAll { it.adjacentTerritory == pin.territory }
otherPin.territory.northEastBorders.removeAll { it.adjacentTerritory == pin.territory }
otherPin.territory.northWestBorders.removeAll { it.adjacentTerritory == pin.territory }
otherPin.territory.southEastBorders.removeAll { it.adjacentTerritory == pin.territory }
otherPin.territory.southWestBorders.removeAll { it.adjacentTerritory == pin.territory }
```

**Validation**:
- Test pin destruction
- Verify all references removed from other pins

---

### Phase 5: Update Pin.updateDataInternal()
**File**: `mapEditor/src/jsMain/kotlin/ui/Pin.kt`

**Current Code**:
```kotlin
territory.northBorder = updatedTerritory.northBorder
territory.westBorder = updatedTerritory.westBorder
// ... single assignment
```

**New Code**:
```kotlin
territory.northBorders = updatedTerritory.northBorders.toMutableList()
territory.southBorders = updatedTerritory.southBorders.toMutableList()
territory.eastBorders = updatedTerritory.eastBorders.toMutableList()
territory.westBorders = updatedTerritory.westBorders.toMutableList()
territory.northEastBorders = updatedTerritory.northEastBorders.toMutableList()
territory.northWestBorders = updatedTerritory.northWestBorders.toMutableList()
territory.southEastBorders = updatedTerritory.southEastBorders.toMutableList()
territory.southWestBorders = updatedTerritory.southWestBorders.toMutableList()
```

**Note**: Use `toMutableList()` to create a copy, not share the same list reference.

**Validation**:
- Test receiving territory data
- Verify lists are properly copied

---

### Phase 6: Update PropertySidebar.updateDataInternal()
**File**: `mapEditor/src/jsMain/kotlin/ui/PropertySidebar.kt`

**Current Code**:
```kotlin
// No border field updates currently
```

**New Code**:
```kotlin
tileData.northBorders = updatedTerritory.northBorders.toMutableList()
tileData.southBorders = updatedTerritory.southBorders.toMutableList()
tileData.eastBorders = updatedTerritory.eastBorders.toMutableList()
tileData.westBorders = updatedTerritory.westBorders.toMutableList()
tileData.northEastBorders = updatedTerritory.northEastBorders.toMutableList()
tileData.northWestBorders = updatedTerritory.northWestBorders.toMutableList()
tileData.southEastBorders = updatedTerritory.southEastBorders.toMutableList()
tileData.southWestBorders = updatedTerritory.southWestBorders.toMutableList()
```

**Validation**:
- Test PropertySidebar receiving data
- Verify border lists updated

---

## Implementation Order

1. ✅ **Step 1**: Update Territory.kt (sharedModel)
2. ✅ **Step 2**: Update Pin.connectToPinWithBorder() to use .add()
3. ✅ **Step 3**: Update Pin.breakAllBorders() to use .clear() and .removeAll()
4. ✅ **Step 4**: Update MapCanvas.removePin() to use .removeAll()
5. ✅ **Step 5**: Update Pin.updateDataInternal() to copy lists
6. ✅ **Step 6**: Update PropertySidebar.updateDataInternal() to copy lists
7. ✅ **Step 7**: Build and test all modules
8. ✅ **Step 8**: Test multiple connections per direction

---

## Testing Strategy

### Test Case 1: Multiple Connections Same Direction
1. Create pin A (center)
2. Create pin B (northeast of A, 30° angle)
3. Create pin C (northeast of A, 60° angle)
4. Connect A→B (should be NE)
5. Connect A→C (should also be NE)
6. **Expected**: Both connections exist, both lines visible
7. **Verify**: A.territory.northEastBorders.size == 2

### Test Case 2: Border Breaking
1. Create pins A, B, C, D around pin E
2. Connect E to all 4 pins
3. Middle-click E
4. **Expected**: All 4 connections broken, all 4 lines disappear
5. **Verify**: All border lists empty

### Test Case 3: Pin Destruction
1. Create pins A, B, C all connected to D
2. Right-click D to destroy
3. **Expected**: All lines disappear, A/B/C have D removed from their lists
4. **Verify**: No references to D remain

### Test Case 4: Duplicate Prevention
1. Create pins A and B
2. Connect A→B
3. Connect A→B again
4. **Expected**: Only one connection exists, one line visible
5. **Verify**: Border list size == 1

---

## Rollback Strategy

If refactor fails:
1. Revert Territory.kt changes
2. Revert to single border slots
3. Git reset to last working commit
4. All changes are in isolated functions, easy to revert

---

## Success Criteria

1. ✅ Territory supports multiple borders per direction
2. ✅ Pins can connect to multiple neighbors in same direction
3. ✅ All lines persist and are visible
4. ✅ Border breaking removes all connections
5. ✅ Pin destruction cleans up all references
6. ✅ No duplicate connections
7. ✅ Build successful across all modules
8. ✅ No runtime errors

---

## Risk Assessment

### High Risk
- **Breaking change to Territory data structure**
  - Mitigation: Update all modules simultaneously
  - Mitigation: Accept data loss for existing saved maps

### Medium Risk
- **List management complexity**
  - Mitigation: Use removeAll with predicate for clean removal
  - Mitigation: Extensive testing of add/remove operations

### Low Risk
- **Visual line rendering**
  - Already working with current system
  - No changes needed to line drawing code

---

## Dependencies

### Module Build Order
1. `sharedModel` - Contains Territory.kt (must build first)
2. `mapEditor` - Depends on sharedModel
3. `kvisionApp` - May depend on sharedModel (check if affected)

### Affected Files
- ✅ `sharedModel/src/commonMain/kotlin/structs/Territory.kt`
- ✅ `mapEditor/src/jsMain/kotlin/ui/Pin.kt`
- ✅ `mapEditor/src/jsMain/kotlin/ui/MapCanvas.kt`
- ✅ `mapEditor/src/jsMain/kotlin/ui/PropertySidebar.kt`
- ⚠️ Any other code that reads Territory borders (search required)

---

## Pre-Implementation Checklist

- [ ] Search codebase for all Territory border references
- [ ] Identify all files that need updates
- [ ] Backup current working state (git commit)
- [ ] Verify no other modules depend on single-border structure
- [ ] Plan data migration strategy (if needed)

---

## Post-Implementation Checklist

- [ ] Build sharedModel successfully
- [ ] Build mapEditor successfully
- [ ] Test creating multiple connections per direction
- [ ] Test border breaking
- [ ] Test pin destruction
- [ ] Test duplicate prevention
- [ ] Verify no memory leaks (check browser dev tools)
- [ ] Verify all lines render correctly
- [ ] Test with 10+ pins and 20+ connections

---

## Code Search Required

Before implementing, search for all usages of:
```bash
grep -r "northBorder" --include="*.kt"
grep -r "southBorder" --include="*.kt"
grep -r "eastBorder" --include="*.kt"
grep -r "westBorder" --include="*.kt"
grep -r "northEastBorder" --include="*.kt"
grep -r "northWestBorder" --include="*.kt"
grep -r "southEastBorder" --include="*.kt"
grep -r "southWestBorder" --include="*.kt"
```

Update all found references to use list operations.

---

## Alternative: Hybrid Approach

If full refactor is too risky, consider:

**Keep single border slots for primary connections**
**Add overflow list for additional connections**

```kotlin
data class Territory(
    // Primary borders (backward compatible)
    var northBorder: Border? = null,
    var southBorder: Border? = null,
    // ...
    
    // Additional borders (new)
    var additionalBorders: MutableList<Border> = mutableListOf()
)
```

**Advantages**:
- Backward compatible
- Simpler migration

**Disadvantages**:
- More complex logic (check primary first, then list)
- Less clean architecture

---

## Recommendation

Proceed with **full list-based refactor** (Option A) because:
1. Cleaner architecture
2. No ambiguity about which border is "primary"
3. Simpler logic (always work with lists)
4. Project appears to be in development (no production data to migrate)
5. Better long-term maintainability

**Estimated Effort**: 2-3 hours
**Risk Level**: Medium
**Impact**: High (enables realistic map connections)
