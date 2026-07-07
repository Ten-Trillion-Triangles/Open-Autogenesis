package globals

import io.kvision.panel.Root
import ui.LoginPage

/**
 * KVision env object that houses global references to KVision widgets, settings, and roots.
 */
object KEnv
{
    var mainRoot: Root? = null
    var appStack: io.kvision.panel.StackPanel? = null

    /**
     * Tracks whether a local development server extension was detected during startup.
     */
    var isLocalDevMode: Boolean = false

    /**
     * If true, the application will bypass the login screen and go directly to the main menu.
     */
    var skipLogin: Boolean = false

    /**
     * If true, the application exposes widget instances on the
     * browser `window` object so the Playwright e2e harness can
     * drive them without a full server stack.
     *
     * Specifically:
     *  - `window.mapViewer` — the [ui.MapViewer] instance once the
     *    gameplay UI has mounted
     *  - `window.gameplayUI` — the [ui.gameplay.GameplayUI] instance
     *
     * Both assignments are gated on this flag; in production
     * builds the flag is `false` and the window surface is
     * untouched. Driven by the `?testMode=true` query parameter
     * read in [org.ttt.autogenesis.kvisionapp.main] (see
     * [org.ttt.autogenesis.kvisionapp.AutogenesisApp]).
     */
    var testMode: Boolean = false

    /**
     * Global reference to the current LoginPage instance, used by DebugSignalBridge
     * to trigger guestLogin() when the Python debug server sends LOGIN_AS_GUEST.
     */
    var currentLoginPage: LoginPage? = null

    /**
     * Global reference to the current GameplayUI instance, used by DebugSignalBridge
     * to drive UI actions (open widgets, execute commands, etc.) from the Python controller.
     */
    var currentGameplayUI: ui.gameplay.GameplayUI? = null

    /**
     * Valid widget names for AUTOGENESIS_BOOT_WIDGET. Order is the order
     * they appear in Logger.error listings on bad input — keep the most-
     * common debugging widgets first.
     *
     * Canonical names (case-sensitive at the Kotlin/JS side):
     *   "MainMenu"                    — full MainMenu (current default)
     *   "MapViewer"                   — bare MapViewer (no GameplayUI shell)
     *   "GameplayUI"                  — full GameplayUI (assumes a game is loaded)
     *   "DebugConsole"                — DebugConsole singleton trigger surface
     *   "CollectionOverlay"           — CollectionOverlay (commander collection)
     *   "ResumeOrNewDialog"           — ResumeOrNewDialog (post-login resume prompt)
     *   "CommanderSelectionDialog"    — CommanderSelectionDialog (wizard step 1)
     */
    val VALID_BOOT_WIDGETS: Set<String> = setOf(
        "MainMenu",
        "MapViewer",
        "GameplayUI",
        "DebugConsole",
        "CollectionOverlay",
        "ResumeOrNewDialog",
        "CommanderSelectionDialog",
    )

    /**
     * Resolved boot widget name. Null = no override (use default MainMenu
     * after skipLogin). Non-null = mount this widget directly at boot.
     *
     * Source priority: window.__AUTOGENESIS_BOOT_WIDGET__ > ?bootWidget URL
     * param > build-time process.env.AUTOGENESIS_BOOT_WIDGET. Strict
     * validation: unknown values cause Main.kt to Logger.error and abort
     * the boot (NOT fall back to MainMenu — the operator typed something
     * specific and we want the typo to surface).
     */
    var bootWidget: String? = null

    /**
     * Demo mode for layout-testing individual widgets WITHOUT requiring a
     * live game state or running servers.
     *
     * Values:
     *   OFF — production; everything real (current default)
     *   WIDGETS — every widget's internal `demoMode` flag is set to true.
     *     The widgets run their self-contained demo loops (mock history
     *     entries, simulated agent streams, automated step transitions,
     *     etc.) but the bridges stay live and the game state is real.
     *     Use this for visual QA of widget chrome over a real round.
     *   FULL — same as WIDGETS plus: bridge .connect(...) calls in
     *     Main.kt are SKIPPED entirely. World.localPlayer and any other
     *     GameState reads fall back to DemoFixtures (Commander Juno,
     *     Ambassador Kael, …) when data is otherwise null. Use this for
     *     fully self-contained layout testing without the SERVER /
     *     SERVER-EXTEND / AccelByte stack.
     *
     * Source priority (resolved in Main.kt::resolveDemoMode):
     *   window.__AUTOGENESIS_DEMO_MODE__ > ?demoMode URL param >
     *   build-time process.env.AUTOGENESIS_DEMO_MODE (webpack DefinePlugin).
     */
    enum class DemoMode
    {
        OFF,
        WIDGETS,
        FULL;

        companion object
        {
            /**
             * Case-insensitive. Empty/off/false/0 → OFF. Throws on
             * unknown values so the caller can surface them in
             * Logger.error.
             */
            fun fromValue(value: String): DemoMode
            {
                val trimmed = value.trim()
                return when (trimmed.lowercase())
                {
                    "", "off", "false", "0" -> OFF
                    "widgets", "widget" -> WIDGETS
                    "full" -> FULL
                    else -> throw IllegalArgumentException(
                        "Unknown demo mode '$value'. Valid values: OFF (or empty), widgets, full."
                    )
                }
            }
        }
    }

    /** Case-folded valid values used by Logger.error listings on bad input. */
    val VALID_DEMO_MODES: Set<String> = setOf("off", "widgets", "full")

    /** Resolved demo mode. Default OFF. */
    var demoMode: DemoMode = DemoMode.OFF

    /**
     * Updates the stored root background so future UI navigation keeps the same cover image.
     *
     * @param path Relative asset path for the new background image.
     */
    fun setBackgroundImage(path: String)
    {
        mainRoot?.setStyle("background-image", "url($path)")
        mainRoot?.setStyle("background-repeat", "no-repeat")
        mainRoot?.setStyle("background-size", "cover")
        mainRoot?.setStyle("background-attachment", "fixed")
        mainRoot?.setStyle("background-position", "center")
    }
}
