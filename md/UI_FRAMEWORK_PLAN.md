# KVision UI Framework Plan

## Mission
- Build the next UI framework inside `kvisionApp` using pure KVision idioms, referencing existing main-app patterns, and avoiding raw HTML/CSS/JS. Each development step will start with a targeted web search for the right KVision syntax/design guidance before touching the code.
- Keep this plan updated whenever requirements adjust; note the current step status in this file.

## Planning steps
| Step | Status | Description |
| --- | --- | --- |
| 1. Inventory current `kvisionApp` views and shared logic | completed | Traced how the main app defines layouts/components and then confirmed that a small MapEditor test can be built with the same idiomatic DSL before moving on. |
| 2. Draft component architecture using KVision layouts/widgets | pending | Decide on hierarchy (containers, forms, navigation, theming) by consulting KVision docs for proper syntax and patterns. |
| 3. Implement foundational UI shell in `kvisionApp` | pending | Build base screens with `Core`, `Bootstrap`, and `BootstrapCss`, referencing the researched syntax and ensuring everything stays in Kotlin/JS. |
| 4. Validate against existing app visuals and behaviors | pending | Compare rendered output with main app examples, adjust KVision usage as needed, and update this plan with insights from each search/experiment. |

## Notes
- Reviewed the KVision events documentation so `setEventListener` and `self` keep the change/click wiring idiomatic while keeping the KVision DSL on the Kotlin side. citeturn0search2
- Consulted MDN guidance on hiding file inputs and calling their `click()` via a styled button so the custom control remains accessible while we hit the native picker. citeturn1search0
- Always document the web searches that inform each step in the plan updates or in subsequent status notes.
