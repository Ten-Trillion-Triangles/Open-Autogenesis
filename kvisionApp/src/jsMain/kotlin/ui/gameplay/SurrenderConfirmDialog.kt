package ui.gameplay

import io.kvision.core.AlignItems
import io.kvision.core.Col
import io.kvision.core.Color
import io.kvision.core.FlexDirection
import io.kvision.core.FontWeight
import io.kvision.core.JustifyContent
import io.kvision.core.Overflow
import io.kvision.core.Position
import io.kvision.core.TextAlign
import io.kvision.core.TextShadow
import io.kvision.html.button
import io.kvision.html.h4
import io.kvision.html.p
import io.kvision.panel.SimplePanel
import io.kvision.panel.hPanel
import io.kvision.panel.vPanel
import io.kvision.utils.perc
import io.kvision.utils.px
import org.ttt.autogenesis.logging.LogCategory
import org.ttt.autogenesis.logging.Logger

/**
 * Yes/No confirmation dialog for surrender.
 *
 * Re-implemented as a [SimplePanel] with the `login-widget-window` class
 * (auto-height fixed-position popup) instead of KVision's `Modal`. The
 * previous `Modal` version needed ~110 lines of CSS workarounds in
 * night-mode.css to fake the modal shell because the project ships
 * night-mode.css only (no Bootstrap CSS). The new `SimplePanel` version
 * renders correctly out of the box through the same proven popup chrome
 * used by [SettingsWidget] and [DelegateWidget] — the existing family of
 * in-game popups in this app.
 *
 * Constructed once and attached to [KEnv.mainRoot] (NOT [SettingsWidget]
 * as a child) so its `position: fixed` resolves against the viewport.
 * SettingsWidget has `backdrop-filter: blur(12px)` via `.login-widget-window`,
 * which would otherwise establish SettingsWidget as the dialog's containing
 * block and break the positioning (kvision skill pitfall #5).
 *
 * Visibility is managed by [Component.visible] via the inherited
 * [Widget.show] / [Widget.hide]. We do NOT override them and we do NOT
 * touch the `display` CSS property — `display` is reserved for flex
 * layout and toggling it on the outer SimplePanel reorders the children
 * out of their flex container. Initial state is `visible = false`.
 *
 * Layout mirrors [SettingsWidget]: pinned header, scrollable middle
 * `vPanel`, pinned footer (button row). The button row lives OUTSIDE
 * the scrollable vPanel so NO/YES are always visible at the bottom of
 * the dialog regardless of how tall the warning text is or how short
 * the viewport is. If the buttons lived inside the scrollable body,
 * they would scroll out of view on short viewports and the user would
 * click whatever button was visible at the bottom — typically YES,
 * which would fire surrender when they meant cancel. (That was Bug #1.)
 *
 * @param onConfirm Invoked when the user clicks YES. The dialog is
 *   already hidden when this fires.
 */
class SurrenderConfirmDialog(
    private val onConfirm: () -> Unit
) : SimplePanel(className = "login-widget-window")
{
    init
    {
        Logger.debug(LogCategory.UI, "SurrenderConfirmDialog: constructed")

        // Popup chrome — mirrors SettingsWidget.kt:53-77 and DelegateWidget.kt:62-87.
        // Auto-height pattern (kvision skill pitfall 28b): modal sizes to its
        // content rather than filling the safe band, so there is no void
        // between the warning text and the buttons on tall viewports.
        //
        // 420px width matches the previous Modal version's narrow-confirm look.
        // Horizontal centering via `left: calc(50% - 210px)` (not `translateX`)
        // to avoid clobbering the `dialogFadeIn` animation's `transform` keyframes.
        // zIndex 1011 sits one above SettingsWidget (1010) so the dialog floats
        // over its parent and the GameHistoryWindow sidebar.
        width = 420.px
        position = Position.FIXED
        top = 120.px
        setStyle("left", "calc(50% - 210px)")
        setStyle("max-width", "calc(100vw - 40px)")
        setStyle("max-height", "calc(100vh - 340px)")
        zIndex = 1011
        padding = 30.px
        setStyle("box-sizing", "border-box")
        overflow = Overflow.HIDDEN

        // Flex layout for the dialog body: column, header at top,
        // scrollable vPanel in the middle (flexGrow=1), button row
        // pinned at the bottom outside the scrollable area.
        display = io.kvision.core.Display.FLEX
        flexDirection = FlexDirection.COLUMN
        alignItems = AlignItems.CENTER
        justifyContent = JustifyContent.FLEXSTART

        // Animation — matches SettingsWidget. Safe to use `transform` here
        // because horizontal centering is via `calc()`, not `translateX`.
        setStyle("animation", "dialogFadeIn 0.3s ease-out")

        // Start hidden. We control visibility via `visible = true/false`
        // in show()/hide() — do not also set `display = NONE` here, that
        // would fight the flex layout and cause children to render at
        // the wrong size on first show.
        visible = false

        // Header (pinned at top of flex column)
        h4("Surrender Match") {
            color = Color.name(Col.CYAN)
            fontSize = 28.px
            fontWeight = FontWeight.BOLD
            marginBottom = 16.px
            textAlign = TextAlign.CENTER
            textShadow = TextShadow(0.px, 0.px, 5.px, Color.name(Col.CYAN))
        }

        // Body (scrollable middle band). The warning text lives here.
        // `flexGrow = 1` makes this band fill the available vertical
        // space between the header and the button row. `overflow = AUTO`
        // scrolls internally if the text is taller than the band.
        // `min-height: 0` is required for `overflow: auto` to engage on
        // a flex child (kvision skill pitfall #28b).
        vPanel(spacing = 16, alignItems = AlignItems.CENTER) {
            width = 100.perc
            flexGrow = 1
            overflow = Overflow.AUTO
            setStyle("min-height", "0")

            p("Surrendering ends your match immediately and removes you from the turn order. This cannot be undone.") {
                color = Color.name(Col.WHITE)
                fontSize = 16.px
                textAlign = TextAlign.CENTER
                marginBottom = 8.px
            }
        }

        // Button row (pinned at bottom of the dialog, OUTSIDE the
        // scrollable vPanel). This is the fix for Bug #1 — buttons
        // must always be visible, never scrolled below the fold.
        hPanel(
            spacing = 12,
            justify = JustifyContent.CENTER,
            alignItems = AlignItems.CENTER
        ) {
            width = 100.perc
            marginTop = 16.px

            button("NO", className = "btn btn-secondary") {
                width = 130.px
                height = 48.px
                fontSize = 16.px
                fontWeight = FontWeight.BOLD
                color = Color.name(Col.WHITE)
                onClick {
                    Logger.debug(LogCategory.UI, "SurrenderConfirmDialog: NO clicked, hiding")
                    // MUST use `this@SurrenderConfirmDialog.hide()` — bare `hide()`
                    // resolves to the BUTTON's inherited Widget.hide() (which hides
                    // just this button, not the dialog). Discovered empirically
                    // 2026-06-24: clicking NO was removing the NO button instead of
                    // the dialog panel. Same pattern as SettingsWidget.kt:264 and
                    // DelegateWidget.kt:207, 276.
                    this@SurrenderConfirmDialog.hide()
                }
            }

            button("YES, SURRENDER", className = "btn btn-surrender-confirm") {
                width = 200.px
                height = 48.px
                fontSize = 16.px
                fontWeight = FontWeight.BOLD
                color = Color.name(Col.WHITE)
                setStyle("background", "linear-gradient(135deg, #8b1a1a, #b22222)")
                setStyle("border", "1px solid #ff4444")
                setStyle("cursor", "pointer")
                onClick {
                    Logger.debug(LogCategory.UI, "SurrenderConfirmDialog: YES clicked, hiding and invoking onConfirm")
                    this@SurrenderConfirmDialog.hide()
                    onConfirm()
                }
            }
        }
    }

    /**
     * Show the dialog. Re-implements the inherited [Widget.show] because the
     * KVision default only adds the `hidden` class — and night-mode.css does
     * not provide a `.hidden { display: none }` rule (Bootstrap is intentionally
     * not loaded). We must explicitly set `display = FLEX` to restore the
     * flex layout that positions the header / scrollable body / button row.
     *
     * Mirrors SettingsWidget.show() at line 273-282.
     */
    override fun show()
    {
        display = io.kvision.core.Display.FLEX
        visible = true
    }

    /**
     * Hide the dialog. Mirrors SettingsWidget.hide() at lines 297-301 and
     * DelegateWidget.hide() at lines 238-243 — both proven patterns in this
     * codebase. We must set BOTH `display = NONE` (to remove the element from
     * the flex layout) AND `visible = false` (for KVision's virtual DOM
     * consistency).
     */
    override fun hide()
    {
        display = io.kvision.core.Display.NONE
        visible = false
    }
}
