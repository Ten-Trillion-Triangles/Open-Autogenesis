package ui

import io.kvision.core.AlignItems
import io.kvision.core.Background
import io.kvision.core.Color
import io.kvision.core.Display
import io.kvision.core.JustifyContent
import io.kvision.core.Position
import io.kvision.core.TextAlign
import io.kvision.core.UNIT
import io.kvision.html.Button
import io.kvision.html.button
import io.kvision.html.h3
import io.kvision.html.p
import io.kvision.panel.SimplePanel
import io.kvision.panel.hPanel
import io.kvision.panel.vPanel
import io.kvision.utils.perc
import io.kvision.utils.px

/**
 * Three-button modal that asks "Resume saved game?" when the [ui.MainMenu]
 * discovers a saved snapshot for the current human player.
 *
 * The dialog is intentionally minimal: a single short paragraph of
 * context plus three big buttons (Resume / New Game / Cancel) so the
 * player can recover a session or start fresh without first having to
 * wade through a full commander / game-type flow. Reuses the existing
 * `commander-selection-overlay` CSS class (dark backdrop, full-viewport
 * fixed position) and the `btn-primary-action` / `btn-secondary-action`
 * button styles so the visual fits the rest of the menu.
 *
 * The owning widget is responsible for what each callback means —
 * see [ui.MainMenu.beginResumeSession] for the Resume path,
 * [ui.MainMenu.beginSinglePlayerSession] for the New Game path, and
 * the standard hide-on-cancel flow for the third option.
 */
class ResumeOrNewDialog(
    private val onResume: () -> Unit,
    private val onNewGame: () -> Unit,
    private val onCancel: () -> Unit
) : SimplePanel(className = "commander-selection-overlay")
{
    private lateinit var resumeButton: Button
    private lateinit var newGameButton: Button
    private lateinit var cancelButton: Button

    init
    {
        // Stable e2e selector — see kvisionApp-e2e/probes/resume-e2e.mjs.
        // The dialog is rendered into KEnv.mainRoot, not the appStack, so
        // Playwright probes need an unambiguous data-testid to find it.
        // The `commander-selection-overlay` class is shared with
        // CommanderSelectionDialog, so it is NOT a reliable unique
        // selector when multiple overlays could be mounted.
        addCssClass("resume-or-new-dialog")
        setAttribute("data-testid", "resume-or-new-dialog")

        position = Position.FIXED
        top = 0.px
        left = 0.px
        width = 100.perc
        height = 100.perc
        zIndex = 9100
        background = Background(Color("rgba(0, 0, 0, 0.85)"))
        display = Display.FLEX
        justifyContent = JustifyContent.CENTER
        alignItems = AlignItems.CENTER

        vPanel(className = "commander-selection-window") {
            width = 720.px
            padding = 24.px
            alignItems = AlignItems.CENTER

            h3("Saved game found") {
                textAlign = TextAlign.CENTER
                marginBottom = 12.px
            }

            p(
                "A previous run was saved for this account. " +
                    "Resume the saved match, start a new game (which discards the save), or cancel to stay in the menu."
            ) {
                textAlign = TextAlign.CENTER
                marginBottom = 24.px
            }

            hPanel(spacing = 16, alignItems = AlignItems.CENTER, justify = JustifyContent.CENTER) {
                width = 100.perc

                cancelButton = button("Cancel") {
                    addCssClass("btn-secondary-action")
                    width = 200.px
                    height = 56.px
                    fontSize = io.kvision.core.CssSize(20, UNIT.px)
                    onClick {
                        this@ResumeOrNewDialog.hide()
                        onCancel()
                    }
                }

                newGameButton = button("New Game") {
                    addCssClass("btn-secondary-action")
                    width = 200.px
                    height = 56.px
                    fontSize = io.kvision.core.CssSize(20, UNIT.px)
                    onClick {
                        this@ResumeOrNewDialog.hide()
                        onNewGame()
                    }
                }

                resumeButton = button("Resume") {
                    addCssClass("btn-primary-action")
                    width = 200.px
                    height = 56.px
                    fontSize = io.kvision.core.CssSize(20, UNIT.px)
                    // Stable e2e selector — see kvisionApp-e2e/probes/resume-e2e.mjs.
                    setAttribute("data-testid", "resume-dialog-resume")
                    onClick {
                        this@ResumeOrNewDialog.hide()
                        onResume()
                    }
                }
            }
        }
    }

    /**
     * Removes the dialog from the DOM.
     */
    override fun hide()
    {
        super.hide()
        this.visible = false
        this.parent?.remove(this)
    }
}
