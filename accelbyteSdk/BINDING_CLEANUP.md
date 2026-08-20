# Binding Cleanup Notes

## Generated Modules
- `@accelbyte/sdk`, `@accelbyte/sdk-iam`, `@accelbyte/sdk-session`, and `@accelbyte/sdk-cloudsave` are now consumed via Dukat/JsModule definitions found under `accelbyteSdk/src/jsMain/kotlin/org/ttt/autogenesis/accelbyte/modules/`. These files expose the factories (`OAuth20Api`, `GameSessionApi`, `PublicGameRecordApi`, etc.) that the typed facades require.
- All missing standard helpers (`Record`, `Partial`, `Omit`, `RegExpExecArray`) remain stubbed in `tsstdlib/Stubs.kt` so the translated Kotlin files don’t pull in external dependencies.

## Facades
- `IamFacade`, `SessionFacade`, and `CloudSaveFacade` wrap the typed module factories and expose Kotlin-friendly request builders (in `facades/`). Each facade converts Kotlin data classes to `Json` before calling the JS APIs.
- If new modules are required, add a new `JsModule` definition plus a facade that uses it—no dynamic proxies are needed anymore.

## Regenerating bindings
1. Run Dukat from `accelbyte/accelbyte-typescript-sdk` (see `tsconfig.dukat.json`) to refresh the `generated` folder.
2. Verify the new definitions still comply with the helper stubs in `tsstdlib/Stubs.kt`; add any additional helpers as needed.
3. After updating facades, rerun `./gradlew :accelbyteSdk:compileKotlinJs` to ensure the new APIs compile.