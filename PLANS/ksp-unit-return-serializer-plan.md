# Fix KSP unit-return serializer bug

**Context & constraints (per AGENTS.md / project style):**
- The shared `rpc-ksp` processor generates RPC handlers for both JVM and JS targets. Changes must respect the style guide (braces on new lines, KDoc, 4-space indentation) and avoid touching root Gradle scripts unless unavoidable.
- Generated code feeds into `kvisionApp` (KVision 9.1.1) where JS builds fail if the binding references `kotlinx.serialization.builtins.UnitSerializer`, so we must keep the generated artifacts JS-friendly.
- Planning to keep shared logic in `rpc-ksp` and limit runtime behavior to the existing RPC registration flow described in `AGENTS.md`.

## Goal
Ensure RPC handler generation avoids referencing `UnitSerializer` by detecting `Unit` return types and registering them with the untyped handler path. This allows Unit-returning methods such as `ActionHistoryClientHandlers.handleTurnComplete` to compile on JS and match the expectations gathered in the identifier step.

## Success criteria
1. `ActionHistoryClientHandlers` no longer pulls in `UnitSerializer` from generated bindings, and `kvisionApp` compiles cleanly.
2. All other generated RPC handlers (server/client) preserve their behavior for non-Unit returns.
3. Verification commands (`./gradlew :server:build` and `./gradlew :kvisionApp:compileKotlinJs`) succeed, ensuring both JVM and JS consumers are satisfied.

## Tasks
1. **Audit the existing generation path for Unit returns**
   - Review `rpc-ksp/src/main/kotlin/org/ttt/autogenesis/ksp/RpcProcessor.kt`, focusing on the `generateFunctionBinding` method and how it handles `isUnitReturn` vs. typed returns.
   - Note that the current implementation always falls through to `registerTyped` (with `UnitSerializer`) even for Unit, so the plan will adjust this logic.
2. **Implement a Unit-aware registration branch**
   - Modify `generateFunctionBinding` to branch earlier: when `isUnitReturn` is true (and not a Flow), emit the simpler `rpcRegistry.register(...)` block rather than the typed variant. The handler should still decode payloads when present and call the target function, but no serializer should be referenced for the return value.
   - Ensure we leave the existing typed branch untouched for non-Unit returns (both synchronous and streaming) to keep other handlers working.
3. **Rebuild JS bindings and confirm generated code**
   - Run `./gradlew :kvisionApp:kspKotlinJs` so KSP emits new bindings. Inspect `build/generated/ksp/js/jsMain/kotlin/ui/gameplay/GeneratedActionHistoryClientHandlersRpcBindings.kt` to verify the `UnitSerializer` import/usage is gone and the handler uses the simple `register` call.
4. **Re-run the JS compilation**
   - `./gradlew :kvisionApp:compileKotlinJs` ensures the new bindings compile cleanly, thus proving the fix and covering all affected generated handlers.
5. **Double-check server build**
   - Although the change centers on JS, rerun `./gradlew :server:build` to ensure no JVM regressions or new warnings appear from updated processor logic.

## Risks & mitigation
- *Risk*: Accidentally regressing Flow or typed handler generation when changing the common code path. Mitigation: Keep `hasPayload`/`isFlowReturn` logic as is; only reroute Unit returns to the existing untyped branch.
- *Risk*: Forgetting to regenerate bindings, leading to stale JS artifacts. Mitigation: After code changes, explicitly run the relevant `kspKotlinJs` task before verifying.

## Verification
- `./gradlew :kvisionApp:compileKotlinJs` (checks both KSP and Kotlin/JS compile). Expect success with no `UnitSerializer` references.
- `./gradlew :server:build` (reuses previous green run; ensures JVM path unaffected).

## Rollout notes
- After verification, commit the updated `rpc-ksp` source file (and any checked-in generated bindings if necessary, though in this project they usually aren’t committed) and note in the PR summary that Unit-returning RPCs were fixed to avoid JS-only serializer issues.
- Mention this fix in release notes if there is an existing section for RPC infrastructure, since it affects generated clients.

## Questions/Next steps
- Should we extend the generator to log a warning whenever a Unit handler is annotated but was previously compiled with the typed branch? (Not blocking for the fix but might help future debugging.)