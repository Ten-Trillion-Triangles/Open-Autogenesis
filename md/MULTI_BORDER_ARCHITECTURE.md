# Multi-Border Refactor: Architecture Diagram

## System Overview

```
┌─────────────────────────────────────────────────────────────┐
│                     Map Editor System                        │
│                                                              │
│  ┌──────────────┐         ┌──────────────┐                 │
│  │  MapCanvas   │         │     Pin      │                 │
│  │              │         │              │                 │
│  │ - connectedPins ◄──────┤ - territory  │                 │
│  │ - svgContainer │       │ - pinId      │                 │
│  │              │         │              │                 │
│  │ drawBorderLine()│      │ connectToPinWithBorder()       │
│  │ removeBorderLine()│    │ breakAllBorders()              │
│  │ removePin()   │         │ findTargetPinAndConnect()     │
│  └──────────────┘         └──────────────┘                 │
│         │                        │                          │
│         │                        │                          │
│         └────────────┬───────────┘                          │
│                      │                                      │
│                      ▼                                      │
│              ┌──────────────┐                              │
│              │  Territory   │                              │
│              │  (Data Model)│                              │
│              └──────────────┘                              │
└─────────────────────────────────────────────────────────────┘
```

---

## Territory Data Structure Evolution

### Before Refactor (Single-Slot Borders)

```
┌─────────────────────────────────────────────────────────┐
│                    Territory                             │
├─────────────────────────────────────────────────────────┤
│ name: String                                            │
│ xPos: Double                                            │
│ yPos: Double                                            │
│                                                         │
│ northBorder: Border? ────────► [Single Border or null] │
│ southBorder: Border? ────────► [Single Border or null] │
│ eastBorder: Border? ─────────► [Single Border or null] │
│ westBorder: Border? ─────────► [Single Border or null] │
│ northEastBorder: Border? ────► [Single Border or null] │
│ northWestBorder: Border? ────► [Single Border or null] │
│ southEastBorder: Border? ────► [Single Border or null] │
│ southWestBorder: Border? ────► [Single Border or null] │
│                                                         │
│ LIMITATION: Maximum 8 connections (one per direction)  │
└─────────────────────────────────────────────────────────┘
```

### After Refactor (List-Based Borders)

```
┌─────────────────────────────────────────────────────────┐
│                    Territory                             │
├─────────────────────────────────────────────────────────┤
│ name: String                                            │
│ xPos: Double                                            │
│ yPos: Double                                            │
│                                                         │
│ northBorders: MutableList<Border> ──► [B1, B2, B3, ...] │
│ southBorders: MutableList<Border> ──► [B1, B2, ...]     │
│ eastBorders: MutableList<Border> ───► [B1, B2, B3, ...] │
│ westBorders: MutableList<Border> ───► [B1, ...]         │
│ northEastBorders: MutableList<Border> ► [B1, B2, ...]   │
│ northWestBorders: MutableList<Border> ► [B1, ...]       │
│ southEastBorders: MutableList<Border> ► [B1, B2, ...]   │
│ southWestBorders: MutableList<Border> ► [B1, ...]       │
│                                                         │
│ CAPABILITY: Unlimited connections per direction         │
└─────────────────────────────────────────────────────────┘
```

---

## Connection Flow

### Creating a Connection

```
User Action: Left-click drag from Pin A to Pin B
                    │
                    ▼
┌───────────────────────────────────────────────────────────┐
│ 1. Pin A: mousedown (button 0)                           │
│    - Set isDrawingBorder = true                          │
│    - Register global mouseup listener                    │
└───────────────────────────────────────────────────────────┘
                    │
                    ▼
┌───────────────────────────────────────────────────────────┐
│ 2. User drags mouse to Pin B                             │
│    - No action during drag                               │
└───────────────────────────────────────────────────────────┘
                    │
                    ▼
┌───────────────────────────────────────────────────────────┐
│ 3. User releases mouse (mouseup)                         │
│    - Global mouseup handler triggered                    │
│    - Call findTargetPinAndConnect(mouseEvent)            │
└───────────────────────────────────────────────────────────┘
                    │
                    ▼
┌───────────────────────────────────────────────────────────┐
│ 4. findTargetPinAndConnect()                             │
│    - Calculate mouse position as percentage              │
│    - Search connectedPins for pin within 5% tolerance    │
│    - If found: call connectToPinWithBorder(targetPin)    │
└───────────────────────────────────────────────────────────┘
                    │
                    ▼
┌───────────────────────────────────────────────────────────┐
│ 5. connectToPinWithBorder(targetPin)                     │
│    - Calculate angle: atan2(dy, dx) * 180 / PI           │
│    - Determine direction from angle (8 directions)       │
│    - Create Border objects (bidirectional)               │
│    - Check for duplicates                                │
│    - Add to appropriate border lists                     │
│    - Call MapCanvas.drawBorderLine()                     │
└───────────────────────────────────────────────────────────┘
                    │
                    ▼
┌───────────────────────────────────────────────────────────┐
│ 6. MapCanvas.drawBorderLine()                            │
│    - Generate unique line key: "${fromPinId}-${toPinId}" │
│    - Remove any existing line with same key              │
│    - Create SVG line element                             │
│    - Add to SVG container                                │
│    - Line appears on screen                              │
└───────────────────────────────────────────────────────────┘
```

---

## Direction Calculation

### Angle to Direction Mapping (8 Directions)

```
                    North (-90°)
                        │
                        │
         NorthWest      │      NorthEast
         (-135° to      │      (-45° to
          -112.5°)      │       -22.5°)
                   ╲    │    ╱
                    ╲   │   ╱
                     ╲  │  ╱
                      ╲ │ ╱
West (-180°/180°) ─────┼───── East (0°)
                      ╱ │ ╲
                     ╱  │  ╲
                    ╱   │   ╲
                   ╱    │    ╲
         SouthWest      │      SouthEast
         (112.5° to     │      (22.5° to
          157.5°)       │       67.5°)
                        │
                        │
                    South (90°)

Angle Ranges:
- East:       -22.5° to 22.5°
- SouthEast:   22.5° to 67.5°
- South:       67.5° to 112.5°
- SouthWest:  112.5° to 157.5°
- West:       157.5° to -157.5° (wraps around)
- NorthWest: -157.5° to -112.5°
- North:     -112.5° to -67.5°
- NorthEast:  -67.5° to -22.5°
```

---

## Border List Management

### Adding a Border (with Duplicate Prevention)

```
┌─────────────────────────────────────────────────────────┐
│ Input: sourcePin, targetPin, direction                  │
└─────────────────────────────────────────────────────────┘
                    │
                    ▼
┌─────────────────────────────────────────────────────────┐
│ Get border list for direction                           │
│ Example: direction = "north"                            │
│          list = territory.northBorders                  │
└─────────────────────────────────────────────────────────┘
                    │
                    ▼
┌─────────────────────────────────────────────────────────┐
│ Check for duplicate                                     │
│ isDuplicate = list.any {                                │
│     it.adjacentTerritory == targetPin.territory         │
│ }                                                       │
└─────────────────────────────────────────────────────────┘
                    │
                    ├─── Yes (duplicate) ──► Skip, log message
                    │
                    └─── No (unique) ──────► Continue
                                              │
                                              ▼
                    ┌─────────────────────────────────────┐
                    │ Create Border object                │
                    │ border = Border(                    │
                    │     adjacentTerritory = targetPin.territory │
                    │ )                                   │
                    └─────────────────────────────────────┘
                                              │
                                              ▼
                    ┌─────────────────────────────────────┐
                    │ Add to list                         │
                    │ list.add(border)                    │
                    └─────────────────────────────────────┘
                                              │
                                              ▼
                    ┌─────────────────────────────────────┐
                    │ Draw visual line                    │
                    │ MapCanvas.drawBorderLine()          │
                    └─────────────────────────────────────┘
```

---

## Border Removal

### Breaking All Borders (Middle-Click)

```
┌─────────────────────────────────────────────────────────┐
│ User Action: Middle-click on Pin A                      │
└─────────────────────────────────────────────────────────┘
                    │
                    ▼
┌─────────────────────────────────────────────────────────┐
│ 1. Clear all border lists for Pin A                    │
│    territory.northBorders.clear()                       │
│    territory.southBorders.clear()                       │
│    ... (all 8 directions)                               │
└─────────────────────────────────────────────────────────┘
                    │
                    ▼
┌─────────────────────────────────────────────────────────┐
│ 2. Remove references from other pins                    │
│    For each otherPin in connectedPins:                  │
│      otherPin.territory.northBorders.removeAll {        │
│          it.adjacentTerritory == territory              │
│      }                                                  │
│      ... (all 8 directions)                             │
└─────────────────────────────────────────────────────────┘
                    │
                    ▼
┌─────────────────────────────────────────────────────────┐
│ 3. Remove all visual lines                              │
│    MapCanvas.removeAllLinesForPin(this)                 │
│    - Removes lines FROM this pin                        │
│    - Removes lines TO this pin                          │
└─────────────────────────────────────────────────────────┘
```

---

## Data Flow Example

### Scenario: New York State with Multiple Northern Neighbors

```
Initial State:
┌──────────────────────────────────────────────────────────┐
│ New York Territory                                       │
│ northBorders: []  (empty)                                │
└──────────────────────────────────────────────────────────┘

After connecting to Vermont:
┌──────────────────────────────────────────────────────────┐
│ New York Territory                                       │
│ northBorders: [Border(adjacentTerritory=Vermont)]        │
└──────────────────────────────────────────────────────────┘

After connecting to Quebec:
┌──────────────────────────────────────────────────────────┐
│ New York Territory                                       │
│ northBorders: [                                          │
│     Border(adjacentTerritory=Vermont),                   │
│     Border(adjacentTerritory=Quebec)                     │
│ ]                                                        │
└──────────────────────────────────────────────────────────┘

Visual Representation:
        Quebec          Vermont
           │               │
           │               │
           └───────┬───────┘
                   │
                   │ (both connections in "north" direction)
                   │
              ┌────▼────┐
              │New York │
              └─────────┘
```

---

## Memory Management

### List Copying (Critical for Correctness)

```
WRONG - Shared Reference:
┌─────────────────┐         ┌─────────────────┐
│  Territory A    │         │  Territory B    │
│                 │         │                 │
│ northBorders ───┼────────►│ [B1, B2, B3]    │◄─── Same list!
└─────────────────┘         └─────────────────┘
                                     ▲
                                     │
                            ┌────────┴────────┐
                            │  Territory C    │
                            │                 │
                            │ northBorders ───┘
                            └─────────────────┘

Problem: Modifying A's borders also modifies B's and C's borders!


RIGHT - Separate Copies:
┌─────────────────┐         ┌─────────────────┐
│  Territory A    │         │  [B1, B2, B3]   │
│                 │         └─────────────────┘
│ northBorders ───┼────────►│ Copy 1          │
└─────────────────┘         └─────────────────┘

┌─────────────────┐         ┌─────────────────┐
│  Territory B    │         │  [B1, B2, B3]   │
│                 │         └─────────────────┘
│ northBorders ───┼────────►│ Copy 2          │
└─────────────────┘         └─────────────────┘

┌─────────────────┐         ┌─────────────────┐
│  Territory C    │         │  [B1, B2, B3]   │
│                 │         └─────────────────┘
│ northBorders ───┼────────►│ Copy 3          │
└─────────────────┘         └─────────────────┘

Solution: Always use .toMutableList() when copying!
```

---

## SVG Line Rendering

### Line Key Generation

```
Pin A (pinId = "pin_123456")
  │
  │ Connection
  │
  ▼
Pin B (pinId = "pin_789012")

Line Key: "pin_123456-pin_789012"

SVG Element:
<line 
    x1="30%" 
    y1="40%" 
    x2="70%" 
    y2="60%" 
    stroke="#00ff00" 
    stroke-width="2" 
    marker-end="url(#arrowhead)"
    data-connection="pin_123456-pin_789012"
/>

Note: Unique pinId ensures unique line keys even if territory names are empty
```

---

## Performance Characteristics

### Time Complexity

| Operation | Before | After | Notes |
|-----------|--------|-------|-------|
| Add border | O(1) | O(n) | n = borders in direction, typically small |
| Remove border | O(1) | O(n) | n = borders in direction |
| Check duplicate | N/A | O(n) | n = borders in direction |
| Break all borders | O(1) | O(m*n) | m = pins, n = borders per pin |

### Space Complexity

| Aspect | Before | After | Notes |
|--------|--------|-------|-------|
| Per territory | 8 references | 8 lists | Each list can grow |
| Max connections | 8 | Unlimited | Limited only by memory |
| Typical usage | 8 borders | 10-20 borders | Real-world maps |

---

## Error Handling

### Potential Issues and Safeguards

```
Issue 1: Null SVG Container
┌─────────────────────────────────────────────────────────┐
│ if (svgContainer == null) {                             │
│     console.log("ERROR: svgContainer is null!")         │
│     return                                              │
│ }                                                       │
└─────────────────────────────────────────────────────────┘

Issue 2: Self-Connection
┌─────────────────────────────────────────────────────────┐
│ if (targetPin == this) {                                │
│     console.log("Target pin is same as source pin")    │
│     return                                              │
│ }                                                       │
└─────────────────────────────────────────────────────────┘

Issue 3: Duplicate Connection
┌─────────────────────────────────────────────────────────┐
│ if (list.any { it.adjacentTerritory == target }) {     │
│     console.log("Connection already exists")           │
│     return                                              │
│ }                                                       │
└─────────────────────────────────────────────────────────┘

Issue 4: Invalid Direction
┌─────────────────────────────────────────────────────────┐
│ val list = when (direction) {                           │
│     "north" -> territory.northBorders                   │
│     ...                                                 │
│     else -> null  // Invalid direction                  │
│ }                                                       │
│ if (list == null) {                                     │
│     console.log("ERROR: Invalid direction")            │
│     return                                              │
│ }                                                       │
└─────────────────────────────────────────────────────────┘
```

---

## Summary

### Key Architectural Changes

1. **Data Structure**: Single nullable borders → Mutable lists
2. **Connection Logic**: Assignment → List addition with duplicate check
3. **Removal Logic**: Null assignment → List clearing and removeAll
4. **Memory Management**: Direct reference → Defensive copying with .toMutableList()

### Benefits

- ✅ Unlimited connections per direction
- ✅ Maintains directional organization
- ✅ Clear semantic meaning
- ✅ Minimal changes to existing logic
- ✅ Preserves angle-based direction calculation

### Trade-offs

- ⚠️ Slightly more complex list management
- ⚠️ Need to check for duplicates
- ⚠️ Breaking change to data structure
- ⚠️ Potential visual overlap of multiple lines

### Next Steps

1. Implement changes following IMPLEMENTATION_GUIDE.md
2. Test thoroughly with complex maps
3. Monitor performance with large maps
4. Consider UI enhancements for overlapping lines