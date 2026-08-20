package ui

import io.kvision.core.AlignItems
import io.kvision.core.Background
import io.kvision.core.Border
import io.kvision.core.BorderStyle
import io.kvision.core.Color
import io.kvision.core.CssSize
import io.kvision.core.Display
import io.kvision.core.FlexWrap
import io.kvision.core.JustifyContent
import io.kvision.core.Position
import io.kvision.core.TextAlign
import io.kvision.core.UNIT
import io.kvision.html.button
import io.kvision.html.h3
import io.kvision.html.icon
import io.kvision.html.p
import io.kvision.modal.ModalSize
import io.kvision.panel.SimplePanel
import io.kvision.panel.hPanel
import io.kvision.panel.vPanel
import io.kvision.utils.px

/**
 * Standalone box that can be used to display messages in real time. Contains message text,
 * a spinner that can be toggled on or off, and confirm/cancel callbacks which are also
 * optional.
 */
class MessageBox(
    var boxTitle: String = "",
    var message: String = "",
    var showThrobber: Boolean = false,
    var showOk: Boolean = false,
    var showCancel: Boolean = false,
    var boxSize: ModalSize = ModalSize.LARGE,
    var onConfirm: (() -> Unit)? = null,
    var onCancel: (() -> Unit)? = null
) : SimplePanel(className = "autogenesis-message-box-overlay")
{

    private lateinit var textMessage: io.kvision.html.P
    private lateinit var loadingIcon: io.kvision.html.Icon
    private lateinit var cancelButton: io.kvision.html.Button
    private lateinit var okButton: io.kvision.html.Button
    private lateinit var titleString: io.kvision.html.H3

    init
    {
        // Overlay styles (Backdrop)
        position = Position.FIXED
        top = CssSize(0, UNIT.px)
        left = CssSize(0, UNIT.px)
        width = CssSize(100, UNIT.perc)
        height = CssSize(100, UNIT.perc)
        zIndex = 9000
        addCssClass("autogenesis-message-box-overlay")
        display = Display.FLEX
        justifyContent = JustifyContent.CENTER
        alignItems = AlignItems.CENTER

        // Top level box that fills the space.
        val rootPanel = vPanel(className = "autogenesis-message-box-content") {
            width = CssSize(800, UNIT.px)
            height = CssSize(600, UNIT.px)
            justifyContent = JustifyContent.SPACEBETWEEN // Space out title, content, buttons

            // Title
            titleString = h3(boxTitle) {
                textAlign = TextAlign.CENTER
                fontSize = CssSize(32, UNIT.px)
                marginTop = CssSize(0, UNIT.px)
            }

            /**
             * Vertical panel that stacks the message, the spinner, and then the response buttons. Controls the
             * alignment and rendering of everything here.
             */
            val verticalFillBox = vPanel {
                justifyContent = JustifyContent.CENTER
                alignItems = AlignItems.CENTER
                spacing = 20
                width = CssSize(100, UNIT.perc)

                // Text message to display.
                textMessage = p(content = message) {
                    fontSize = CssSize(24, UNIT.px)
                    textAlign = TextAlign.INITIAL
                }

                loadingIcon = icon("fas fa-spinner fa-spin") {
                    visible = showThrobber
                    fontSize = CssSize(32, UNIT.px)
                    color = Color("white")
                }
            }

            // Button Row
            val buttonRowBox = hPanel(
                wrap = FlexWrap.NOWRAP,
                justify = JustifyContent.CENTER,
                spacing = 20,
                alignItems = AlignItems.CENTER,
            ) {
                width = CssSize(100, UNIT.perc)

                /**
                 * Cancels the message box, sends a cancel signal if bound, then tears down the entire widget.
                 */
                cancelButton = button("Cancel") {
                    width = CssSize(200, UNIT.px)
                    height = CssSize(50, UNIT.px)
                    padding = CssSize(0, UNIT.px)
                    visible = showCancel
                    fontSize = CssSize(24, UNIT.px)
                    addCssClass("btn-secondary-action")
                    onClick {
                        onCancel?.invoke()
                        this@MessageBox.hide()
                    }
                }

                /**
                 * Sends a confirmation signal if bound, then tears down the entire widget.
                 */
                okButton = button("OK") {
                    width = CssSize(200, UNIT.px)
                    height = CssSize(50, UNIT.px)
                    padding = CssSize(0, UNIT.px)
                    visible = showOk
                    fontSize = CssSize(24, UNIT.px)
                    addCssClass("btn-secondary-action")
                    onClick {
                        onConfirm?.invoke()
                        this@MessageBox.hide()
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

    /**
     * Sets the title of the message box.
     *
     * @param newTitle The new title to display.
     * @return This MessageBox instance for chaining.
     */
    fun setTitle(newTitle: String): MessageBox
    {
        boxTitle = newTitle
        titleString.content = boxTitle
        titleString.refresh()
        return this
    }

    /**
     * Sets the message content of the message box.
     *
     * @param newMessage The new message to display.
     * @return This MessageBox instance for chaining.
     */
    fun setMessage(newMessage: String): MessageBox
    {
        message = newMessage
        textMessage.content = newMessage
        textMessage.refresh()
        return this
    }

    /**
     * Sets the visibility of the loading throbber.
     *
     * @param show True to show the throbber, false to hide it.
     * @param hideButtons If true, automatically hides OK and Cancel buttons while throbber is active.
     * @return This MessageBox instance for chaining.
     */
    fun setThrobber(show: Boolean, hideButtons: Boolean = false): MessageBox
    {
        showThrobber = show
        loadingIcon.visible = show
        loadingIcon.refresh()
        if (show && hideButtons)
        {
            setButtons(ok = false, cancel = false)
        }
        return this
    }

    /**
     * Sets the visibility of the OK and Cancel buttons.
     *
     * @param ok True to show the OK button, false to hide it.
     * @param cancel True to show the Cancel button, false to hide it.
     * @return This MessageBox instance for chaining.
     */
    fun setButtons(ok: Boolean = showOk, cancel: Boolean = showCancel): MessageBox
    {
        showOk = ok
        showCancel = cancel
        okButton.visible = ok
        cancelButton.visible = cancel
        okButton.refresh()
        cancelButton.refresh()
        return this
    }
}