# Reverse Story Impact Agents Plan

## Constraints & Context
- AGENTS.md prohibits touching root Gradle scripts or pipeline infrastructure unrelated to story agents; we must only extend `server/src/main/kotlin/agent/builders/reverseAgent.kt` (and supporting shared modules/tests if needed) with analogous functionality.
- The existing `reverseAgent` pipeline uses `BedrockMultimodalPipe` + reasoning/validation pipes and relies on the `reverse` system prompt (see file lines 17‑71). **Keep every prompt and helper sequence identical** for both new agents except for the outcome-consequence guidance described below.
- The new agents must accept structured JSON input (data class with `story: String` and `adjustmentPercent: Int`) so the final pipe can tune the story output relative to the original narrative. This adds no extra prompts.
- Do not modify any system prompts other than injecting the new adjustment behavior when generating the final story (the base instructions should still apply).

## Goals & Success Criteria
1. Add a reusable `StoryImpactAdjustment` data class (with `story: String` + `adjustmentPercent: Int`) so both pipelines receive structured input describing the original story plus how much to harden/soften it.
2. Create two mirrored pipelines that only differ in how they interpret `adjustmentPercent`:
   - The **Hardened Story Agent** (e.g., `buildHardenedStoryAgent()`) should treat `adjustmentPercent` as a positive multiplier that inflates rewards/penalties and makes every win/loss more extreme compared to the base story.
   - The **Softened Story Agent** (e.g., `buildSoftenedStoryAgent()`) should treat `adjustmentPercent` as a reducer that tones down stakes, softens consequences, and otherwise removes intensity.
   - Both pipelines reuse the reverse agent’s structure/prompts, pre-validation, and validator pipe; only the final system prompt should mention “harden by X%” vs. “soften by X%” while mirroring the remaining instructions verbatim.
3. Maintain identical pipeline structure, system prompts, validators, and reasoning builders from `reverseAgent` except for the new outcome emphasis.
4. Add or update tests/docs as needed to document the new agents (the plan should note where to log or verify behavior).

## Current Status
- There is a single pipeline builder (`buildReverseAgent`) that:
  - Runs a `reversalPipe` with a reversal-specific system prompt and reasoning pipe.
  - Captures the original story via `setPreValidationMiniBankFunction`.
  - Chains a validator pipe to confirm the reversal happened.
- The pipeline does **not** currently accept external data classes for story/percentage, nor does it provide a "harden/soften" story transformation.

## Tasks
1. **Create the shared input schema**
   - File: `server/src/main/kotlin/agent/builders/StoryImpactAdjustment.kt`
   - Include `@Serializable data class StoryImpactAdjustment(val story: String, val adjustmentPercent: Int)`
   - Document how `adjustmentPercent` maps to intensity: e.g., positive values intensify, negative values soften (or create two separate class derivatives if clearer).
   - Ensure `adjustmentPercent` is clamped (0‑100) during parsing/validation if that is desired.

2. **Copy the reverse agent structure twice**
   - Files: `server/src/main/kotlin/agent/builders/hardenedStoryAgent.kt` and `server/src/main/kotlin/agent/builders/softenedStoryAgent.kt`.
   - Each builder returns a `Pipeline` like `buildReverseAgent`.
   - The initial pipe should replicate the setup from `reverseAgent` (service tier, tokens, reasoning pipe, and prompts) **including the existing pre-validation logic** that caches the original story via `ContextWindow`.
   - Add `.setJsonOutput(StoryImpactAdjustment())` so the pipe expects structured JSON input describing the story + intensity.
   - Example snippet:
     ```kotlin
     setJsonOutput(StoryImpactAdjustment())
     setSystemPrompt("""
         [same as reverse agent...]
         Use 'adjustmentPercent' to amplify/soften the outcome by that percentage.
     """.trimIndent())
     ```
     (But do **not** alter the base instructions beyond referencing the adjustment behavior at the very end.)

3. **Adjust final generation logic**
   - Append a final `BedrockMultimodalPipe` or existing reversal pipe step that:
     * Reads the `StoryImpactAdjustment` JSON (the original story + percent).
     * Constructs a prompt referencing the original story for comparison.
     * Applies the multiplier by instructing the model to "harden/soften by X%" in the system prompt while keeping all other wording identical to `reverseAgent`.
   - The final output should be the story adjusted in tone/outcome relative to the original input. Mention explicit metrics (e.g., "Increase rewards by {adjustmentPercent}%") so it's clear to the LLM.
   - Keep validator pipe identical (reuse `TrueFalse` output), but set `setJsonOutput(TrueFalse())` for the validator and ensure it references the new `StoryImpactAdjustment` context.

4. **Document the new agents**
   - Update README or `PLANS` doc referencing new agents’ purpose, expected input format, and how to invoke them.
   - Mention that the new agents rely on `StoryImpactAdjustment` to modulate impact (maybe near `server/AGENTS` docs).

5. **Testing/verification**
   - Create a simple unit/regression test (if there is existing pipeline test harness) verifying:
     * Input `adjustmentPercent` results in prompted instruction string containing that value.
     * The pre-validation still stores the original story.
   - If no harness exists, document how to manually invoke the pipeline (maybe via `Bedrock` stub) to confirm the new outcomes.

## Dependencies & Risks
- Input validation must clamp `adjustmentPercent` to prevent runaway instructions (use `kotlin.math.coerceIn(0, 200)` or similar).
- The LLM prompt must remain identical outside of the new adjustment clause to respect the "Do not modify system prompts" instruction.
- Introducing JSON input may require ensuring `BedrockMultimodalPipe` receives it (validate via existing `extractJson` helper).

## Testing & Verification
- Gradle target: `./gradlew :server:test` if unit tests are added.
- Manual verification: run the new pipeline with sample `StoryImpactAdjustment` JSON and examine the generated prompt for the `adjustmentPercent` mention.

## Rollout & Monitoring
- After merging, watch the behavior of downstream story processors (ActionHistory etc.) to ensure new agents produce consistent output shapes (string stories).
- Document in `PLANS`/`CHANGELOG` which agent to use for each narrative effect.

## Communication
- Mention plan completion in `PLANS/reverse-story-impact-plan.md` notes, referencing this file for implementers.