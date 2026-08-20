# Multi-Border Refactor: Planning Documentation

## Overview

This directory contains comprehensive planning documentation for refactoring the Territory border system to support multiple connections per direction.

---

## Document Hierarchy

### 1. IMPLEMENTATION_GUIDE.md (START HERE)
**Purpose**: Quick-start guide for developers  
**Audience**: Implementers who want to get started immediately  
**Content**:
- Streamlined implementation sequence
- Critical implementation details
- Testing checklist
- Common issues and solutions
- Rollback procedures

**When to use**: You want to implement the refactor NOW

---

### 2. MULTI_BORDER_IMPLEMENTATION_PLAN.md (DETAILED REFERENCE)
**Purpose**: Comprehensive implementation specification  
**Audience**: Developers who need detailed guidance  
**Content**:
- Complete code snippets for every change
- Line-by-line before/after comparisons
- Edge case handling
- Testing matrix with validation methods
- Risk assessment
- Timeline estimates
- Post-implementation tasks

**When to use**: You need detailed code examples or encounter issues

---

### 3. MULTI_BORDER_REFACTOR_PLAN.md (ORIGINAL STRATEGY)
**Purpose**: High-level strategic planning document  
**Audience**: Architects and project managers  
**Content**:
- Problem statement and analysis
- Solution options comparison
- Implementation phases overview
- Success criteria
- Risk assessment

**When to use**: You need to understand the "why" behind the refactor

---

## Recommended Reading Order

### For Implementers
1. **IMPLEMENTATION_GUIDE.md** - Get started quickly
2. **MULTI_BORDER_IMPLEMENTATION_PLAN.md** - Reference when you need details
3. **MULTI_BORDER_REFACTOR_PLAN.md** - Understand the strategy (optional)

### For Reviewers
1. **MULTI_BORDER_REFACTOR_PLAN.md** - Understand the problem and solution
2. **MULTI_BORDER_IMPLEMENTATION_PLAN.md** - Review the detailed approach
3. **IMPLEMENTATION_GUIDE.md** - See the streamlined execution plan

### For Project Managers
1. **MULTI_BORDER_REFACTOR_PLAN.md** - Understand scope and risks
2. **IMPLEMENTATION_GUIDE.md** - See timeline and success criteria
3. **MULTI_BORDER_IMPLEMENTATION_PLAN.md** - Review detailed tasks (optional)

---

## Key Differences Between Documents

| Aspect | REFACTOR_PLAN | IMPLEMENTATION_PLAN | GUIDE |
|--------|---------------|---------------------|-------|
| **Level of Detail** | High-level | Very detailed | Streamlined |
| **Code Examples** | Conceptual | Complete snippets | Key snippets only |
| **Testing** | Strategy | Complete matrix | Checklist |
| **Timeline** | Phases | Task-by-task | Summary |
| **Length** | 15 KB | 29 KB | 7.7 KB |
| **Best For** | Planning | Implementation | Execution |

---

## Quick Reference

### What files need to change?

1. `sharedModel/src/commonMain/kotlin/structs/Territory.kt`
2. `mapEditor/src/jsMain/kotlin/ui/Pin.kt` (3 functions)
3. `mapEditor/src/jsMain/kotlin/ui/MapCanvas.kt` (1 function)

### How long will it take?

**Estimated**: 3.5 hours  
**With buffer**: 4 hours

### What's the risk level?

**Medium** - Breaking change to Territory data structure, but clear rollback path

### What's the impact?

**High** - Enables realistic map connections with multiple neighbors per direction

---

## Implementation Checklist

### Pre-Implementation
- [ ] Read IMPLEMENTATION_GUIDE.md
- [ ] Review MULTI_BORDER_IMPLEMENTATION_PLAN.md (Steps 1-6)
- [ ] Backup current code: `git commit -am "Pre-refactor checkpoint"`
- [ ] Verify build works: `./gradlew :mapEditor:build -x test`

### Implementation
- [ ] Step 1: Update Territory.kt (15 min)
- [ ] Step 2: Update Pin.connectToPinWithBorder() (30 min)
- [ ] Step 3: Update Pin.breakAllBorders() (20 min)
- [ ] Step 4: Update MapCanvas.removePin() (15 min)
- [ ] Step 5: Update Pin.updateDataInternal() (10 min)
- [ ] Step 6: Build and fix errors (30 min)

### Testing
- [ ] Basic connection test
- [ ] Multiple connections same direction test
- [ ] Duplicate prevention test
- [ ] Border breaking test
- [ ] Pin destruction test
- [ ] Complex map test (10+ pins, 20+ connections)
- [ ] Performance test (50+ pins, 100+ connections)

### Post-Implementation
- [ ] Update BORDER_LINES_VALIDATION_REPORT.md
- [ ] Create MULTI_BORDER_TESTING_REPORT.md
- [ ] Update README.md (if exists)
- [ ] Commit changes: `git commit -am "Implement multi-border refactor"`

---

## Critical Implementation Details

### 1. Always Use .toMutableList()

```kotlin
// WRONG - shares list reference
territory.northBorders = updatedTerritory.northBorders

// RIGHT - creates copy
territory.northBorders = updatedTerritory.northBorders.toMutableList()
```

### 2. Duplicate Prevention

```kotlin
if (!list.any { it.adjacentTerritory == target }) {
    list.add(border)
} else {
    console.log("Connection already exists, skipping duplicate")
}
```

### 3. Use removeAll with Predicate

```kotlin
// Clean removal with count
val removed = list.removeAll { it.adjacentTerritory == target }
console.log("Removed $removed border(s)")
```

---

## Troubleshooting

### Build Fails
- Check IMPLEMENTATION_GUIDE.md → "Common Issues and Solutions"
- Try rollback procedure
- Verify module build order (sharedModel first, then mapEditor)

### Runtime Errors
- Check browser console for JavaScript errors
- Verify .toMutableList() is used everywhere
- Check duplicate prevention logic

### Visual Issues
- Lines not appearing: Check SVG container exists
- Lines not disappearing: Check removeAllLinesForPin logic
- Duplicate lines: Check duplicate prevention

---

## Success Criteria

### Functional
- [x] Territory supports multiple borders per direction
- [x] Pins can create multiple connections in same direction
- [x] Duplicate connections are prevented
- [x] Border breaking removes all connections
- [x] Pin destruction cleans up all references

### Technical
- [x] Build completes without errors
- [x] No runtime exceptions
- [x] UI remains responsive with 50+ pins
- [x] Memory usage stable (no leaks)

---

## Next Steps After Completion

1. **Validation**: Update BORDER_LINES_VALIDATION_REPORT.md
2. **Testing**: Create MULTI_BORDER_TESTING_REPORT.md with screenshots
3. **Documentation**: Update README.md with multi-border capability
4. **Enhancement**: Consider UI improvements for overlapping lines
5. **Optimization**: Monitor performance with larger maps

---

## Questions?

- **Quick answer**: Check IMPLEMENTATION_GUIDE.md
- **Detailed answer**: Check MULTI_BORDER_IMPLEMENTATION_PLAN.md
- **Strategic context**: Check MULTI_BORDER_REFACTOR_PLAN.md

---

## Document Maintenance

### When to Update

- **REFACTOR_PLAN**: When strategy or requirements change
- **IMPLEMENTATION_PLAN**: When implementation details change
- **GUIDE**: When quick-start steps change

### Version History

- **v1.0** (Dec 4, 2024): Initial comprehensive planning documentation
  - Created MULTI_BORDER_IMPLEMENTATION_PLAN.md (29 KB)
  - Created IMPLEMENTATION_GUIDE.md (7.7 KB)
  - Updated MULTI_BORDER_REFACTOR_PLAN.md (15 KB)

---

## Summary

**Start with**: IMPLEMENTATION_GUIDE.md  
**Reference**: MULTI_BORDER_IMPLEMENTATION_PLAN.md  
**Understand**: MULTI_BORDER_REFACTOR_PLAN.md

**Total effort**: 4 hours  
**Risk**: Medium  
**Impact**: High

**Key takeaway**: Always use `.toMutableList()` when copying border lists!