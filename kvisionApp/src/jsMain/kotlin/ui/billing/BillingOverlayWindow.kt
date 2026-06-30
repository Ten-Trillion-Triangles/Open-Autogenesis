package ui.billing

import io.kvision.core.AlignItems
import io.kvision.core.Color
import io.kvision.core.CssSize
import io.kvision.core.Display
import io.kvision.core.JustifyContent
import io.kvision.core.Overflow
import io.kvision.core.Position
import io.kvision.core.UNIT
import io.kvision.html.button
import io.kvision.html.div
import io.kvision.modal.Modal
import io.kvision.modal.ModalSize
import io.kvision.panel.SimplePanel
import io.kvision.panel.VPanel
import io.kvision.panel.vPanel
import io.kvision.utils.perc
import io.kvision.utils.px
import kotlinx.coroutines.MainScope
import org.ttt.autogenesis.logging.LogCategory
import org.ttt.autogenesis.logging.Logger

/**
 * Base class for the centered, glassmorphic billing modals (Shop and Usage).
 *
 * Extends KVision's [Modal] (from `io.kvision.modal`) so that show/hide goes
 * through Bootstrap's modal JS, which manages the DOM and the backdrop outside
 * of KVision's virtual DOM tree. Bootstrap's JS works without requiring
 * Bootstrap's CSS — the Modal's show/hide/backdrop logic does not depend on
 * the `.modal`/`.modal-dialog` rules. We override the visual styling with
 * our own CSS in `night-mode.css` (`.billing-modal-window-host`) and apply
 * position/centering inline via [setUpOverlayChrome] so the modal works in
 * projects that do not load Bootstrap CSS.
 *
 * Subclasses override [buildContent] to populate the modal's body. The base
 * handles the title bar, the close (X) button, ESC-to-close, and the
 * click-outside-to-close behavior via Bootstrap.
 */
abstract class BillingOverlayWindow(
    private val modalTitle: String,
    private val modalWidth: Int = 960,
    private val modalMaxHeight: Int = 720,
    extraClassName: String? = null,
    private val onDismiss: (() -> Unit)? = null
) : Modal(
    caption = modalTitle,
    closeButton = true,
    size = ModalSize.LARGE,
    animation = false,
    centered = true,
    scrollable = true,
    escape = true,
    className = if (extraClassName == null) "billing-modal-window-host" else "billing-modal-window-host $extraClassName"
)
{
    protected val scope = MainScope()

    /**
     * The window content. We use a fresh [VPanel] inside the modal body so
     * subclasses can add rows with the same DSL they used before.
     */
    private val contentRoot: VPanel = vPanel(spacing = 0)

    init
    {
        Logger.debug(LogCategory.UI, "BW.init: Modal constructed, sizing window content to ${modalWidth}px")
        // Style the host as a centered, fixed-position overlay. KVision's Modal
        // expects Bootstrap CSS for `.modal { position: fixed; inset: 0; ... }`,
        // but this project ships night-mode.css only. Apply the equivalent
        // rules inline so the modal is positioned and centered correctly
        // regardless of whether Bootstrap CSS is loaded.
        setUpOverlayChrome()

        // Hide Bootstrap's default white modal-content chrome — we have
        // our own billing-modal-window styles in night-mode.css.
        this.addCssClass("billing-modal-window")
        // Header gets our billing-modal-header class for the X button + title.
        this.header.addCssClass("billing-modal-header")
        this.body.addCssClass("billing-modal-body")
        // Footer isn't used by Shop/Usage right now.
        this.footer.hide()

        // Body hosts the subclass content.
        contentRoot.width = CssSize(100, UNIT.perc)
        contentRoot.height = CssSize(100, UNIT.perc)
        contentRoot.addCssClass("billing-modal-content-root")
        this.body.add(contentRoot)
    }

    /**
     * Applies the fixed-position + centered overlay chrome that would
     * normally come from Bootstrap's `.modal { position: fixed; inset: 0; ... }`
     * and `.modal-dialog { margin: auto; }` rules. We do this inline because
     * the project does not load Bootstrap CSS.
     */
    private fun setUpOverlayChrome()
    {
        // Host: fixed, full viewport, flex centering, our dark backdrop.
        this.position = Position.FIXED
        this.top = CssSize(0, UNIT.px)
        this.left = CssSize(0, UNIT.px)
        this.width = CssSize(100, UNIT.perc)
        this.height = CssSize(100, UNIT.perc)
        this.zIndex = 9001
        this.display = Display.FLEX
        this.justifyContent = JustifyContent.CENTER
        this.alignItems = AlignItems.CENTER
        // Override Bootstrap's transparent modal background with our own.
        this.background = io.kvision.core.Background(Color("rgba(2, 4, 12, 0.78)"))
    }

    /**
     * Subclasses MUST call this from their own init block (after all their
     * own field initializers have completed) to populate the body. Calling
     * it from this class's init would race with subclass field init.
     */
    protected fun initContent()
    {
        Logger.debug(LogCategory.UI, "BW.initContent: invoking buildContent on this=" + this::class.simpleName)
        buildContent(contentRoot)
        Logger.debug(LogCategory.UI, "BW.initContent: buildContent returned")
    }

    /**
     * Subclasses override this to populate [host] with their own content.
     */
    protected abstract fun buildContent(host: VPanel)

    /**
     * Back-compat alias for subclasses that were written against the old
     * `buildBody` name. Delegates to [buildContent].
     */
    protected open fun buildBody(host: VPanel)
    {
        buildContent(host)
    }

    /**
     * Shows the modal. Back-compat alias.
     */
    open fun showOverlay()
    {
        Logger.debug(LogCategory.UI, "BillingOverlayWindow: showing $modalTitle")
        this.show()
    }

    /**
     * Hides the modal and runs the onDismiss callback. Back-compat alias.
     */
    open fun dismiss()
    {
        Logger.debug(LogCategory.UI, "BillingOverlayWindow: hiding $modalTitle")
        this.hide()
        onDismiss?.invoke()
    }
}
