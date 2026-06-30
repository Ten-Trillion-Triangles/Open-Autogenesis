# AccelByte SDK TTT Style Guide Fix Plan

## Overview
The accelbyteSdk module contains **107 Kotlin files** that violate TTT formatting standards. This plan outlines the systematic approach to fix all violations while maintaining code functionality.

## TTT Style Violations Identified

### 1. Spacing Violations
- **Current**: `val param: String` ❌
- **Required**: `val param : String` ✅ (space before AND after colon)
- **Affects**: All property declarations, function parameters, return types

### 2. Brace Placement Violations
- **Classes/Functions/Interfaces**: Opening brace must be on NEW LINE
  - **Current**: `class MyClass {` ❌
  - **Required**: `class MyClass\n{` ✅
- **Lambdas/Try-Catch**: Opening brace stays on SAME LINE
  - **Current**: `launch { }` ✅ (correct)

### 3. Missing KDoc Documentation
- **Required**: ALL public members MUST have comprehensive KDoc
- **Must include**: @param, @return, @throws tags
- **Current**: Most files have NO KDoc ❌

## Files Requiring Fixes

### ✅ Already Fixed (4 files)
- `ExternalTypes.kt` - Complete with KDoc and spacing
- `Interop.kt` - Complete with KDoc and spacing  
- `Models.kt` - Complete with KDoc and spacing
- `tsstdlib/Stubs.kt` - Complete with KDoc and spacing

### ❌ Remaining Files (103 files)

#### Facade Files (27 files)
```
facades/AchievementFacade.kt
facades/BasicFacade.kt
facades/BuildinfoFacade.kt
facades/ChatFacade.kt
facades/CloudSaveFacade.kt
facades/ConfigFacade.kt
facades/CsmFacade.kt
facades/DifferFacade.kt
facades/DsmControllerFacade.kt
facades/EventFacade.kt
facades/GameTelemetryFacade.kt
facades/GdprFacade.kt
facades/GroupFacade.kt
facades/IamFacade.kt
facades/LeaderboardFacade.kt
facades/LegalFacade.kt
facades/LobbyFacade.kt
facades/MatchmakingFacade.kt
facades/PlatformFacade.kt
facades/QosmFacade.kt
facades/ReportingFacade.kt
facades/SeasonpassFacade.kt
facades/SessionBrowserFacade.kt
facades/SessionFacade.kt
facades/SocialFacade.kt
facades/UgcFacade.kt
```

#### Model Files (43 files)
```
models/AchievementModels.kt
models/AchievementResponseModels.kt
models/AuthModels.kt
models/BaseModels.kt
models/BasicModels.kt
models/BasicResponseModels.kt
models/BuildinfoResponseModels.kt
models/ChatModels.kt
models/ChatResponseModels.kt
models/CloudSaveModels.kt
models/CloudSaveResponseModels.kt
models/ConfigModels.kt
models/ConfigResponseModels.kt
models/ContentModels.kt
models/CsmResponseModels.kt
models/CursorPagination.kt
models/DifferModels.kt
models/DifferResponseModels.kt
models/DsmModels.kt
models/DsmResponseModels.kt
models/EventModels.kt
models/EventResponseModels.kt
models/GameTelemetryResponseModels.kt
models/GdprModels.kt
models/GdprResponseModels.kt
models/GroupModels.kt
models/GroupResponseModels.kt
models/LeaderboardModels.kt
models/LeaderboardResponseModels.kt
models/LegalResponseModels.kt
models/LobbyModels.kt
models/LobbyResponseModels.kt
models/MatchmakingModels.kt
models/MatchmakingResponseModels.kt
models/OffsetPagination.kt
models/PlatformResponseModels.kt
models/QosmModels.kt
models/QosmResponseModels.kt
models/QueryModels.kt
models/ReportingModels.kt
models/ReportingResponseModels.kt
models/SeasonpassExportResponse.kt
models/SeasonpassModels.kt
models/SeasonpassResponseModels.kt
models/SessionModels.kt
models/SessionResponseModels.kt
models/SharedResponseModels.kt
models/SocialModels.kt
models/SocialResponseModels.kt
models/TelemetryModels.kt
models/UgcResponseModels.kt
```

#### Module Files (22 files)
```
modules/AchievementModule.kt
modules/BasicModule.kt
modules/BuildinfoModule.kt
modules/ChatModule.kt
modules/CloudSaveModule.kt
modules/ConfigModule.kt
modules/CsmModule.kt
modules/DifferModule.kt
modules/DsmControllerModule.kt
modules/EventModule.kt
modules/GameTelemetryModule.kt
modules/GdprModule.kt
modules/GroupModule.kt
modules/IamModule.kt
modules/LeaderboardModule.kt
modules/LegalModule.kt
modules/LobbyModule.kt
modules/MatchmakingModule.kt
modules/PlatformModule.kt
modules/QosmModule.kt
modules/ReportingModule.kt
modules/SeasonpassModule.kt
modules/SessionModule.kt
modules/SocialModule.kt
modules/UgcModule.kt
```

#### Utility Files (11 files)
```
util/PromiseExtensions.kt
models/BaseModels.kt (utility functions)
```

## Fix Implementation Strategy

### Phase 1: Spacing Fixes
For each file, apply these regex replacements:

```bash
# Fix property type spacing
sed -i 's/\(val\|var\) \([^:]*\): \([A-Za-z<>?]\)/\1 \2 : \3/g' file.kt

# Fix function parameter spacing  
sed -i 's/(\([^)]*\): \([A-Za-z<>?]\)/(\1 : \2/g' file.kt

# Fix function return type spacing
sed -i 's/) : \([A-Za-z<>?]\)/) : \1/g' file.kt
```

### Phase 2: Brace Placement Fixes
```bash
# Fix class/interface/object braces (new line)
sed -i 's/^\(.*\)\(class\|interface\|object\|fun\) \([^{]*\) {$/\1\2 \3\n{/' file.kt
```

### Phase 3: KDoc Addition
For each public member, add comprehensive KDoc:

```kotlin
/**
 * Brief description of the class/function purpose.
 * 
 * Detailed explanation of behavior, usage patterns, and important notes.
 * Cross-reference related classes with [ClassName] syntax.
 * 
 * @param paramName Description of parameter purpose and constraints
 * @return Description of return value and its structure
 * @throws ExceptionType When this exception is thrown
 */
```

## File-by-File Approach

### Template for Each File Fix:

1. **Open file**
2. **Apply spacing fixes** using sed commands
3. **Fix brace placement** manually for classes/functions
4. **Add KDoc** for all public members:
   - Classes
   - Functions  
   - Properties
   - Interfaces
5. **Add code comments** for complex logic blocks
6. **Verify compilation** with `./gradlew :accelbyteSdk:build`

### Priority Order:
1. **Facade files** (27) - Most visible API surface
2. **Model files** (43) - Data structures used throughout
3. **Module files** (22) - External bindings
4. **Utility files** (11) - Helper functions

## Validation Process

After each file fix:
```bash
# Verify syntax
./gradlew :accelbyteSdk:compileKotlinJs

# Full build test
./gradlew :accelbyteSdk:build
```

## Risk Mitigation

1. **Fix one file at a time** to isolate issues
2. **Test compilation** after each file
3. **Use git commits** for each file to enable easy rollback
4. **Avoid automated sed** for complex structures (learned from previous failure)

## Estimated Effort

- **Per file**: 15-30 minutes (depending on complexity)
- **Total time**: 25-50 hours for all 103 files
- **Recommended**: Process in batches of 5-10 files per session

## Success Criteria

✅ All 107 files comply with TTT style guide:
- Space before AND after colons
- Proper brace placement  
- Comprehensive KDoc documentation
- Code compiles without errors
- No functionality regression
