package ui.gameplay

import io.kvision.core.*
import io.kvision.form.text.TextInput
import io.kvision.form.text.textInput
import io.kvision.html.*
import io.kvision.panel.hPanel
import io.kvision.panel.vPanel
import io.kvision.utils.perc
import io.kvision.utils.px
import io.kvision.window.Window
import kotlinx.browser.window
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.ttt.autogenesis.kvisionapp.WebSocketRpcBridge
import kotlin.random.Random

/**
 * Floating window for NeuralLink conversations.
 *
 * Streaming uses a bounded in-memory buffer and timed UI flushes to keep memory stable
 * under long-running answers.
 */
class NeuralLinkWindow(
    val windowId: String,
    windowTitle: String,
    var commandContext: String = "/ask",
    private val demoMode: Boolean = false
) : Window(
    caption = windowTitle,
    className = "neural-link-window"
)
{
    companion object {
        private const val STREAM_FLUSH_INTERVAL_MS = 120L
        private const val MAX_MESSAGE_COUNT = 200
        private const val MAX_TOTAL_CHARS = 1_000_000
    }

    private data class RenderedMessage(
        val component: Div,
        val charCount: Int
    )

    var onWindowClosed: (() -> Unit)? = null

    private val contentPanel = vPanel(spacing = 10) {
        width = 100.perc
        padding = 10.px
        overflow = Overflow.AUTO
        addCssClass("neural-tab-content-panel")
    }

    private lateinit var inputField: TextInput

    private val typingScope = MainScope()
    private var demoJob: Job? = null
    private var flushJob: Job? = null

    private var messageCount = 0
    private val renderedMessages = ArrayDeque<RenderedMessage>()
    private var totalRenderedChars = 0

    private var activeStreamId: String? = null
    private var lastSequence = 0L
    private val pendingDeltaBuffer = StringBuilder()
    private val activeStreamText = StringBuilder()
    private var activeStreamComponent: Div? = null

    init {
        width = 800.px
        height = 600.px
        isResizable = true
        isDraggable = true

        val offset = Random.nextInt(50) - 25
        val browserWidth = window.innerWidth
        val browserHeight = window.innerHeight
        val startLeft = (browserWidth - 800) / 2
        val startTop = (browserHeight - 600) / 2

        left = (startLeft + offset).px
        top = (startTop + offset).px

        val closeBtn = Button("", icon = "fas fa-times") {
            position = Position.ABSOLUTE
            right = 10.px
            top = 10.px
            color = Color.hex(0x00ffff)
            background = Background(Color("transparent"))
            border = Border(width = 0.px)
            fontSize = 18.px
            cursor = Cursor.POINTER
            zIndex = 100
            onClick {
                NeuralLinkManager.closeWindow(windowId)
            }
        }
        captionContainer.add(closeBtn)

        background = Background(Color("rgba(10, 15, 30, 0.95)"))
        border = Border(1.px, BorderStyle.SOLID, Color.hex(0x00ffff))
        boxShadow = BoxShadow(0.px, 0.px, 15.px, 0.px, Color.hex(0x00ffff))
        padding = 0.px
        addCssClass("neural-window")

        justifyItems = JustifyItems.CENTER
        captionContainer.justifyItems = JustifyItems.CENTER
        captionContainer.alignSelf = AlignItems.CENTER
        captionContainer.justifySelf = JustifyItems.CENTER
        captionContainer.align = Align.CENTER

        val rootPanel = vPanel {
            width = 100.perc
            height = 100.perc
            overflow = Overflow.HIDDEN
        }
        add(rootPanel)

        contentPanel.flexGrow = 1
        contentPanel.overflow = Overflow.AUTO
        contentPanel.minHeight = 0.px
        contentPanel.height = null
        rootPanel.add(contentPanel)

        val inputContainer = hPanel(spacing = 10) {
            width = 100.perc
            height = 50.px
            padding = 10.px
            background = Background(Color("rgba(0, 0, 0, 0.8)"))
            borderTop = Border(1.px, BorderStyle.SOLID, Color.hex(0x333333))
            flexShrink = 0
            setStyle("margin-top", "auto")
            addCssClass("neural-input-container")

            this@NeuralLinkWindow.inputField = textInput {
                width = 100.perc
                placeholder = "Transmit command..."
                addCssClass("neural-input")
                color = Color.hex(0x00ff00)
                background = Background(Color("transparent"))
                border = Border(width = 0.px)

                onEvent {
                    keypress = { e ->
                        if (e.key == "Enter") {
                            sendPrompt()
                        }
                    }
                }
            }

            button("SEND", className = "btn-neural-send") {
                width = 80.px
                onClick {
                    sendPrompt()
                }
            }
        }
        rootPanel.add(inputContainer)

        logSystemMessage("Secure channel established with $windowTitle.")

        if (demoMode) {
            startDemoSimulation()
        }
    }

    private fun sendPrompt()
    {
        val text = inputField.value ?: return
        if (text.isBlank()) {
            return
        }

        logUserMessage(text)
        inputField.value = ""

        if (demoMode) {
            MainScope().launch {
                delay(500)
                streamStart("demo", commandContext)
                streamDelta("demo", 1, "Command acknowledged. Processing... [DEMO]")
                delay(800)
                streamDelta("demo", 2, " Execution complete.")
                streamEnd("demo")
            }
            return
        }

        MainScope().launch {
            val invoker = WebSocketRpcBridge.rpcInvoker
            if (invoker != null) {
                try {
                    val command = "$commandContext $text"
                    invoker.invoke("server.sendPrompt", "[$windowId] $command")
                }
                catch (e: Exception) {
                    logSystemMessage("Transmission failed: ${e.message}")
                }
            }
        }
    }

    /**
     * Prepares the active stream region and command prefix for a newly reported stream ID.
     */
    fun streamStart(streamId: String, incomingCommandContext: String? = null)
    {
        if (incomingCommandContext != null) {
            commandContext = incomingCommandContext
        }

        if (activeStreamId != streamId) {
            clearActiveStreamDisplay()
            activeStreamId = streamId
            lastSequence = 0
            pendingDeltaBuffer.clear()
            activeStreamText.clear()
            ensureActiveStreamDisplay()
        }

        ensureFlushJobRunning()
    }

    /**
     * Enqueue a delta if it arrives in the expected sequence window and fits under the maximum buffer size.
     */
    fun streamDelta(streamId: String, sequence: Long, delta: String)
    {
        if (delta.isEmpty()) {
            return
        }

        if (activeStreamId != streamId) {
            return
        }

        if (sequence <= lastSequence) {
            return
        }

        lastSequence = sequence
        pendingDeltaBuffer.append(delta)

        if (pendingDeltaBuffer.length > MAX_TOTAL_CHARS) {
            val trimAmount = pendingDeltaBuffer.length - MAX_TOTAL_CHARS
            pendingDeltaBuffer.deleteRange(0, trimAmount)
        }
    }

    /**
     * Flushes buffered text, renders the final markdown subset, and stores it as a bounded history entry.
     */
    fun streamEnd(streamId: String, finalText: String? = null)
    {
        if (activeStreamId != streamId) {
            return
        }

        flushPendingDeltaBuffer()
        val completedText = finalText ?: activeStreamText.toString()
        clearActiveStreamDisplay()
        stopFlushJob()

        if (completedText.isBlank()) {
            activeStreamId = null
            return
        }

        val renderedPanel = MarkdownSubsetRenderer.buildPanel(completedText)
        val wrapper = Div(className = "neural-log-agent") {
            color = Color.hex(0x00ffff)
            fontFamily = "Courier New"
            fontSize = calculateFontSize()
            add(renderedPanel)
        }
        appendBoundedMessage(wrapper, completedText.length)

        activeStreamId = null
        lastSequence = 0
        activeStreamText.clear()
        pendingDeltaBuffer.clear()
    }

    fun streamError(streamId: String, errorText: String)
    {
        streamEnd(streamId, if (errorText.isBlank()) "Stream error." else errorText)
    }

    private fun ensureFlushJobRunning()
    {
        if (flushJob != null) {
            return
        }

        flushJob = typingScope.launch {
            while (true) {
                delay(STREAM_FLUSH_INTERVAL_MS)
                flushPendingDeltaBuffer()
            }
        }
    }

    private fun stopFlushJob()
    {
        flushJob?.cancel()
        flushJob = null
    }

    private fun flushPendingDeltaBuffer()
    {
        if (pendingDeltaBuffer.isEmpty()) {
            return
        }

        activeStreamText.append(pendingDeltaBuffer)
        pendingDeltaBuffer.clear()

        if (activeStreamText.length > MAX_TOTAL_CHARS) {
            val trimAmount = activeStreamText.length - MAX_TOTAL_CHARS
            activeStreamText.deleteRange(0, trimAmount)
        }

        ensureActiveStreamDisplay()
        activeStreamComponent?.content = activeStreamText.toString()
        scrollToBottom()
    }

    private fun ensureActiveStreamDisplay()
    {
        if (activeStreamComponent != null) {
            return
        }

        val streamView = Div(className = "neural-log-agent neural-stream-active") {
            color = Color.hex(0x00ffff)
            fontFamily = "Courier New"
            fontSize = calculateFontSize()
            whiteSpace = io.kvision.core.WhiteSpace.PREWRAP
        }

        contentPanel.add(streamView)
        activeStreamComponent = streamView
        scrollToBottom()
    }

    private fun clearActiveStreamDisplay()
    {
        val component = activeStreamComponent ?: return
        contentPanel.remove(component)
        activeStreamComponent = null
    }

    private fun logUserMessage(text: String)
    {
        val message = Div(className = "neural-log-user") {
            content = "> $text"
            color = Color.hex(0xaaaaaa)
            marginBottom = 5.px
            fontSize = calculateFontSize()
            whiteSpace = io.kvision.core.WhiteSpace.PREWRAP
        }

        appendBoundedMessage(message, text.length)
    }

    fun logSystemMessage(text: String)
    {
        val message = Div(className = "neural-log-system") {
            content = "SYSTEM > $text"
            color = Color.hex(0xff0000)
            fontStyle = io.kvision.core.FontStyle.ITALIC
            fontSize = calculateFontSize()
            whiteSpace = io.kvision.core.WhiteSpace.PREWRAP
        }

        appendBoundedMessage(message, text.length)
    }

    private fun appendBoundedMessage(component: Div, charCount: Int)
    {
        contentPanel.add(component)
        renderedMessages.addLast(RenderedMessage(component, charCount))
        totalRenderedChars += charCount
        messageCount += 1

        while (renderedMessages.size > MAX_MESSAGE_COUNT || totalRenderedChars > MAX_TOTAL_CHARS) {
            val oldest = renderedMessages.removeFirstOrNull() ?: break
            contentPanel.remove(oldest.component)
            totalRenderedChars -= oldest.charCount
        }

        if (totalRenderedChars < 0) {
            totalRenderedChars = 0
        }

        scrollToBottom()
    }

    private fun calculateFontSize(): io.kvision.core.CssSize
    {
        val size = maxOf(10, 14 - (messageCount / 5))
        return size.px
    }

    private fun scrollToBottom()
    {
        window.setTimeout({
            val el = contentPanel.getElement()
            if (el != null) {
                el.scrollTop = el.scrollHeight.toDouble()
            }
        }, 10)
    }

    private fun startDemoSimulation()
    {
        demoJob = typingScope.launch {
            val messages = listOf(
                "Analyzing tactical probabilities...",
                "Optimizing resource allocation protocols...",
                "Intercepted encrypted transmission.",
                "System diagnostics: Nominal."
            )

            var demoSequence = 0L
            while (true) {
                delay(Random.nextLong(3000, 8000))
                val message = messages.random()
                streamStart("demo-loop", commandContext)
                message.forEach { char ->
                    delay(Random.nextLong(20, 80))
                    demoSequence += 1
                    streamDelta("demo-loop", demoSequence, char.toString())
                }
                streamEnd("demo-loop")
            }
        }
    }

    override fun dispose()
    {
        stopFlushJob()
        demoJob?.cancel()
        typingScope.cancel()
        super.dispose()
    }

    override fun close()
    {
        stopFlushJob()
        demoJob?.cancel()
        typingScope.cancel()
        super.close()
        onWindowClosed?.invoke()
    }
}
