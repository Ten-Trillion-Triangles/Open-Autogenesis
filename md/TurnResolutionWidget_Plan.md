# Turn Resolution Widget Implementation Plan

## Goal
Implement a complex, multi-page `TurnResolutionWidget` within `GameplayUI` to visualize the AI agent's turn progression, stream results, and allow user interaction for counter-plays.

## User Review Required
> [!IMPORTANT]
> This widget will be added as a second page to the central `StackPanel` in `GameplayUI`. This means the map will be hidden while this widget is active. Is this the desired behavior? (Assumed YES based on "widget that exists in the second page of the stack panel where the map viewer is").

## Proposed Changes

### 1. New Widget: `TurnResolutionWidget`
**Location:** `kvisionApp/src/jsMain/kotlin/ui/gameplay/TurnResolutionWidget.kt`
*   **Base:** `StackPanel` (for internal page switching).
*   **Layout:**
    *   **Main Content Area:** A container for the active page.
    *   **Agent Progress Bar:** Fixed at the bottom. Flashing icons for: Planning, Writing, Judging, World Update.
*   **State Management:** Methods to switch between phases (`showStart()`, `showIntent()`, `showStory()`, `showJudgement()`, `showCounterPlay()`).
*   **Styling:** STRICTLY reuse existing KVision styles and classes (e.g., `SimplePanel` with standard backgrounds/borders) to match `StatsWidget` and other UI elements. Avoid "cowboy coding" new styles. Use KVision animation DSL if possible, or minimal specific CSS classes in `style.css` only for complex animations like fading/sliding.

### 2. Sub-Widgets (Pages)
These will be internal classes or separate files if complex.
*   **`TurnStartPage`**:
    *   Big text: "Turn Starting".
    *   Button: "Acknowledge" (or "Begin").
*   **`TurnIntentPage`**:
    *   Text: "Player X is [Action]...".
    *   Visual: Spinner / "Moving Dodad" animation.
*   **`StoryStreamingPage`**:
    *   Text Area: For streaming content (simulated typing effect).
    *   Transitions: Text fading in.
*   **`JudgementSummaryPage`**:
    *   Result summary (Success/Fail).
    *   Resources gained/lost.
    *   Button: "Continue".
*   **`CounterPlayPage`**:
    *   Alert: "Event Occurring!".
    *   Options: "Ignore", "Respond".
    *   **Input Handling:** Do NOT create a new input box. When "Respond" is clicked, focus and enable the main `CommandBox` at the bottom of the screen. The widget should guide the user to type there.

### 3. Integration into `GameplayUI`
**Location:** `kvisionApp/src/jsMain/kotlin/ui/gameplay/GameplayUI.kt`
*   Add `turnResolutionWidget` as the second child of `centerStackPanel`.
*   Ensure `mapViewer` is index 0.
*   Add methods/callbacks to switch `centerStackPanel` index to show/hide the turn resolution.
*   **CommandBox Logic:** update `GameplayUI` to disable the `CommandBox` during most turn phases, but enable it specifically for the "Respond" action in `CounterPlayPage`.

### 4. Modifications to `CommandBox.kt`
*   Add public methods/properties to control enabled/disabled interactability.
*   Add method to clear/focus the text area programmatically.

### 5. Styles & Animations
**Location:** `kvisionApp/src/jsMain/resources/css/style.css` (Modify existing).
*   Add generic animation classes: `fade-in`, `slide-in`, `pulse` (for icons) if not already present.
*   Ensure these fit the existing "technomantic/sci-fi" theme.

## Verification Plan
1.  **Manual Verification**:
    *   Trigger the widget visibility (via a temporary button or console command).
    *   Step through each page: Start -> Intent -> Story -> Judgement -> CounterPlay.
    *   Verify animations and layout.
    *   Verify "Close"/"Finish" returns to Map.
