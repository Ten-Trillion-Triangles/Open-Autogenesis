package ui.gameplay

import io.kvision.core.*
import io.kvision.html.*
import io.kvision.panel.vPanel
import io.kvision.window.Window
import io.kvision.utils.px
import io.kvision.utils.perc
import kotlinx.browser.window
import org.ttt.autogenesis.logging.LogCategory
import org.ttt.autogenesis.logging.Logger

/**
 * Floating window that renders the agent work stream lines while the stream is active.
 */
class AgentWorkStreamWindow(
    private val onCloseCallback: () -> Unit
) : Window(
    caption = "Agent Work Stream",
    className = "neural-link-window"
)
{
    private val contentPanel = vPanel(spacing = 6) {
        width = 100.perc
        height = 100.perc
        padding = 10.px
        overflow = Overflow.AUTO
        addCssClass("neural-tab-content-panel")
    }

    private val statusBadge = span("Idle", className = "neural-status-badge") {
        fontSize = 12.px
        color = Color.hex(0x00ffff)
    }

    private var messageCount = 0
    private lateinit var closeBtn: Button
    private val messageNodes = ArrayDeque<Pair<Container, Int>>()
    private var storedChars = 0
    private var currentlyStreaming = false
    private var streamAttemptLogged = false

    private companion object {
        const val MAX_STORED_CHARS = 12000
    }

    init
    {
        width = 640.px
        height = 420.px
        isResizable = true
        isDraggable = true
        background = Background(Color("rgba(5, 12, 28, 0.95)"))
        border = Border(1.px, BorderStyle.SOLID, Color.hex(0x00ffff))
        boxShadow = BoxShadow(0.px, 0.px, 10.px, 0.px, Color.hex(0x00ffff))

        // Align window in viewport center similar to other floating windows.
        val browserWidth = kotlinx.browser.window.innerWidth
        val browserHeight = kotlinx.browser.window.innerHeight
        val startLeft = (browserWidth - 640) / 2
        val startTop = (browserHeight - 420) / 2
        left = startLeft.px
        top = startTop.px

        justifyItems = JustifyItems.CENTER
        captionContainer.justifyItems = JustifyItems.CENTER
        captionContainer.alignItems = AlignItems.CENTER
        captionContainer.alignSelf = AlignItems.CENTER
        captionContainer.justifySelf = JustifyItems.CENTER
        captionContainer.align = Align.CENTER

        closeBtn = Button("", icon = "fas fa-times") {
            position = Position.ABSOLUTE
            right = 10.px
            top = 10.px
            color = Color.hex(0x00ffff)
            background = Background(Color("transparent"))
            border = Border(width = 0.px)
            fontSize = 16.px
            cursor = Cursor.POINTER
            zIndex = 100
            onClick {
                onCloseCallback()
            }
        }
        captionContainer.add(closeBtn)
        captionContainer.add(statusBadge)

        val rootPanel = vPanel(spacing = 0) {
            width = 100.perc
            height = 100.perc
            overflow = Overflow.HIDDEN
            padding = 0.px
        }
        add(rootPanel)

        contentPanel.flexGrow = 1
        contentPanel.overflow = Overflow.AUTO
        contentPanel.minHeight = 0.px
        rootPanel.add(contentPanel)
    }

    /**
     * Adds a new line of text, scrolling the view if necessary.
     */
    fun appendChunk(text: String)
    {
        if (text.isBlank())
        {
            return
        }
        if(!streamAttemptLogged)
        {
            Logger.info(LogCategory.UI, "AgentWorkStreamWindow: Streaming data received for the first time (chunkLength=${text.length}).")
            streamAttemptLogged = true
        }
        val entry = p(text) {
            color = Color.hex(0x00ffff)
            fontFamily = "Courier New"
            fontSize = calculateFontSize()
            marginBottom = 4.px
        }
        contentPanel.add(entry)
        messageNodes.addLast(entry to text.length)
        storedChars += text.length
        trimOldMessages()
        messageCount++
        scrollToBottom()
        setStreaming(true)
    }

    fun appendThinking(data: org.ttt.autogenesis.network.ThinkingUpdateData)
    {
        if (data.thinking.isBlank())
        {
            return
        }
        if(!streamAttemptLogged)
        {
            Logger.info(LogCategory.UI, "AgentWorkStreamWindow: Thinking data received for the first time (thinkingLength=${data.thinking.length}).")
            streamAttemptLogged = true
        }
        val badge = if (data.isPlayer) "[PLAYER]" else "[NPC]"
        val thinkingText = "🧠 $badge ${data.characterName}: ${data.thinking}"
        val entry = p(thinkingText) {
            color = Color.hex(0x888888)
            fontFamily = "monospace"
            fontSize = calculateFontSize()
            marginBottom = 4.px
        }
        contentPanel.add(entry)
        messageNodes.addLast(entry to thinkingText.length)
        storedChars += thinkingText.length
        trimOldMessages()
        messageCount++
        scrollToBottom()
        setStreaming(true)
    }

    private fun trimOldMessages()
    {
        while (storedChars > MAX_STORED_CHARS && messageNodes.isNotEmpty())
        {
            val (oldEntry, length) = messageNodes.removeFirst()
            contentPanel.remove(oldEntry)
            storedChars -= length
        }
    }

    /**
     * Marks the incoming stream as complete in the UI.
     */
    fun markComplete()
    {
        setStreaming(false)
    }

    private fun setStreaming(active: Boolean)
    {
        if(currentlyStreaming != active)
        {
            currentlyStreaming = active
            Logger.info(LogCategory.UI, "AgentWorkStreamWindow: Streaming ${if(active) "activated" else "deactivated"} (storedChars=$storedChars).")
        }
        statusBadge.content = if (active) "Streaming..." else "Idle"
    }

    private fun calculateFontSize(): CssSize
    {
        return 18.px
    }

    private fun scrollToBottom()
    {
        window.setTimeout({
            val el = contentPanel.getElement()
            if (el != null)
            {
                el.scrollTop = el.scrollHeight.toDouble()
            }
        }, 10)
    }

    override fun close() {
        Logger.info(LogCategory.UI, "AgentWorkStreamWindow: close invoked by user.")
        super.close()
        onCloseCallback()
    }

    override fun show()
    {
        Logger.info(LogCategory.UI, "AgentWorkStreamWindow: show invoked.")
        super.show()
        scrollToBottom()
        Logger.info(LogCategory.UI, "AgentWorkStreamWindow: show completed. visible=$visible")
    }
}
