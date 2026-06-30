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
