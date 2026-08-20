# AccelByte SDK → Kotlin TODO

## Phase 1: Discovery (complete)
- [x] Catalog the AccelByte TypeScript monorepo (`accelbyte/accelbyte-typescript-sdk/packages/*`) and note entry points for each workspace.
- [x] Define the Kotlin package layout (`org.ttt.autogenesis.accelbyte` plus subpackages for modules/facades and the manual `ExternalTypes` bridge).

## Phase 2: Generation Setup
- [x] Prepare the TypeScript output Dukat needs (local SDK build artifacts exist under `packages/sdk/dist`, `tsconfig.dukat.json` targets `dist/index.d.ts`).
- [x] Define a Dukat invocation so every exported interface/method is converted (see notes in `accelbyteSdk/dukat-generated/README.md`).
- [x] Capture the generated Kotlin files in a dedicated staging area (`accelbyteSdk/dukat-generated/*.kt`) ready for post-processing.
- [ ] Add Gradle (or custom script) tasks to rerun this conversion when the SDK changes, keeping the binding up to date.
- [ ] Post-process Dukat output (resolve Zod schema conflicts, provide stubs for `tsstdlib` helpers, clean Axios definitions) so it compiles inside `src/jsMain`.

## Phase 3: Integration
- [ ] Wire the generated Kotlin bindings into `accelbyteSdk/src/jsMain` (and update `accelbyteSdk/build.gradle.kts` when necessary) so that helper facades or initialization routines can reach the new types.
- [ ] Validate that the Kotlin build recognizes the new sources and that imports (e.g., `org.ttt.autogenesis.accelbyte.sdk.LoginApi`) resolve cleanly.

## Phase 4: Validation & Documentation
- [ ] Create or update documentation (AGENTS.md or README) describing how to regenerate the bindings and use them in Kotlin.
- [ ] Run `./gradlew :accelbyteSdk:refreshAccelbyteBindings` (or `downloadAccelbyteSdk` + `buildAccelbyteSdk` + `regenerateAccelbyteBindings`) before rerunning `:accelbyteSdk:check` to ensure the new bindings compile.
- [ ] Exercise the `kvisionApp` UI (e.g., `./gradlew :kvisionApp:jsBrowserDevelopmentRun` or `:kvisionApp:build`) so the new facade surfaces in the browser.