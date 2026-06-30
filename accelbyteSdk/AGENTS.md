# accelbyteSdk

Kotlin/JS bindings for AccelByte TypeScript SDK via Dukat translation.

## OVERVIEW

Binding module that exposes 31 typed facades across 26 @JsModule packages, generated from the AccelByte TypeScript SDK using Dukat CLI.

## STRUCTURE

```
ExternalTypes.kt         → Core @JsModule interfaces (@accelbyte/sdk)
modules/ (26 files)     → @JsModule declarations per SDK package
facades/ (31 files)     → Kotlin wrappers with request/response data classes
models/                  → Typed request/response data classes
util/PromiseExtensions.kt → mapJson(), propagateJsErrors() helpers
```

3-LAYER ARCHITECTURE:
1. **ExternalTypes** - raw @JsModule bindings from Dukat (@accelbyte/sdk core)
2. **Modules** - per-package @JsModule declarations (sdk-iam, sdk-cloudsave, etc.)
3. **Facades** - Kotlin-friendly wrappers that convert data classes to Json before calling JS APIs

## WHERE TO LOOK

| File | Purpose |
|------|---------|
| `ExternalTypes.kt` | Core SDK constructor, token management, interceptor interfaces |
| `Interop.kt` | `AccelByteSdkFactory` and `AccelByteSdkInstance` for SDK creation |
| `modules/IamModule.kt` | `@JsModule("@accelbyte/sdk-iam")` + OAuth20Api factory |
| `facades/IamFacade.kt` | Login, logout, token refresh, user profile |
| `facades/CloudSaveFacade.kt` | Game record read/write operations |
| `util/PromiseExtensions.kt` | mapJson(), propagateJsErrors() for JS Promise handling |
| `BINDING_CLEANUP.md` | Manual fixes required after Dukat regeneration |
| `MODULE_COVERAGE.md` | Coverage matrix of all 32 SDK packages |

## BINDING PIPELINE

```
downloadAccelbyteSdk     → git clone accelbyte/accelbyte-typescript-sdk
buildAccelbyteSdk        → yarn workspaces foreach -Apt run build
regenerateAccelbyteBindings → Dukat CLI (dukat -t tsconfig.dukat.json)
```

Run `./gradlew :accelbyteSdk:refreshAccelbyteBindings` for full pipeline.

## CONVENTIONS

- All @JsModule files use `@file:JsModule("@accelbyte/sdk-xxx")` + `@file:JsNonModule`
- External interfaces mirror TypeScript signatures exactly (Dukat output)
- Facades accept Kotlin data classes, convert to `Json` via `toJson()`, call JS API, return typed results
- Models live in `models/` subdirectory per facade group
- PromiseExtensions provides `mapJson<T>()`, `propagateJsErrors()` for async handling
- Helper stubs in `tsstdlib/Stubs.kt` (Record, Partial, Omit, RegExpExecArray) avoid external deps

## ANTI-PATTERNS

- **DO NOT edit Dukat-generated files directly** - changes will be lost on next regeneration
- **Manual cleanup required** after running Dukat - see BINDING_CLEANUP.md:
  - Remove incorrect `external interface` merging
  - Fix nullable parameter defaults (Dukat emits `= definedExternally`)
  - Add missing helper stubs when TypeScript stdlib types are used
- **DO NOT use dynamic** for new bindings - define proper external interfaces
- **tsstdlib/Stubs.kt** must be updated when new TypeScript helper types appear in generated output
