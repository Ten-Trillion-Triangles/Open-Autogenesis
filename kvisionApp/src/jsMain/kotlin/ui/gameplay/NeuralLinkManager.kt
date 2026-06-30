package ui.gameplay

import io.kvision.core.Container
import org.ttt.autogenesis.logging.LogCategory
import org.ttt.autogenesis.logging.Logger

/**
 * Coordinates NeuralLink windows and routes answer-agent stream lifecycle events.
 */
object NeuralLinkManager
{
    private val windows = mutableMapOf<String, NeuralLinkWindow>()
    private var parentContainer: Container? = null
    private var closingWindowFromManager = false

    /**
     * Initializes the manager with a parent container (usually GameplayUI or Root).
     */
    fun init(parent: Container)
    {
        this.parentContainer = parent
    }

    /**
     * Opens a window for the given ID. If it exists, brings it to focus.
     *
     * @param defaultContext The command prefix to auto-prepend (e.g. "/ask" or "/chat").
     */
    fun openWindow(id: String, title: String, defaultContext: String = "/ask")
    {
        val parent = parentContainer ?: return

        windows[id]?.let { existing ->
            if (existing.parent == null) {
                parent.add(existing)
            }
            existing.commandContext = defaultContext
            existing.show()
            existing.toFront()
            return
        }

        val newWindow = NeuralLinkWindow(id, title, commandContext = defaultContext)
        newWindow.onWindowClosed = { handleExternalClose(id) }

        windows[id] = newWindow
        parent.add(newWindow)
        newWindow.show()
        newWindow.toFront()

        Logger.info(LogCategory.UI, "NeuralLinkManager: Opened window '$id'")
    }

    /**
     * Closes and removes a specific window.
     */
    fun closeWindow(id: String)
    {
        val win = windows[id] ?: return
        closingWindowFromManager = true
        try
        {
            win.close()
        }
        finally
        {
            closingWindowFromManager = false
        }
        cleanupWindow(id)
    }

    private fun handleExternalClose(id: String)
    {
        if (closingWindowFromManager)
        {
            return
        }

        cleanupWindow(id)
    }

    private fun cleanupWindow(id: String)
    {
        val win = windows.remove(id) ?: return
        win.parent?.remove(win)
    }

    /**
     * Handles legacy stream payloads that send content/isComplete only.
     */
    /**
     * Keeps the legacy per-chunk stream path working until every client uses the structured events.
     * Adds a pseudo stream ID and forwards the chunks through `streamStart/Delta/End`.
     */
    fun handleLegacyAgentStream(tabId: String, content: String, isComplete: Boolean, commandContext: String?)
    {
        val streamId = "legacy-$tabId"
        ensureWindow(tabId, commandContext)
        val window = windows[tabId] ?: return

        window.streamStart(streamId, commandContext)

        if (content.isNotEmpty()) {
            window.streamDelta(streamId, sequence = nextLegacySequence(content), delta = content)
        }

        if (isComplete) {
            window.streamEnd(streamId)
        }
    }

    /**
     * Handles structured stream START.
     */
    /**
     * Begins a new structured agent stream, ensuring the window exists and updating the command context hint.
     */
    fun handleAgentStreamStart(tabId: String, streamId: String, commandContext: String?)
    {
        ensureWindow(tabId, commandContext)
        windows[tabId]?.streamStart(streamId, commandContext)
    }

    /**
     * Handles structured stream DELTA.
     */
    /**
     * Buffers individual DELTA slices while guaranteeing they land in order before touching the window buffer.
     */
    fun handleAgentStreamDelta(tabId: String, streamId: String, sequence: Long, delta: String)
    {
        ensureWindow(tabId, null)
        if (delta.isEmpty()) {
            return
        }
        windows[tabId]?.streamDelta(streamId, sequence, delta)
    }

    /**
     * Handles structured stream END.
     */
    /**
     * Completes the stream, triggering markdown rendering and bounded history trimming.
     */
    fun handleAgentStreamEnd(tabId: String, streamId: String, finalText: String?)
    {
        ensureWindow(tabId, null)
        windows[tabId]?.streamEnd(streamId, finalText)
    }

    /**
     * Handles structured stream ERROR.
     */
    /**
     * Treats ERROR payloads as a forced end so UI can show failure text without leaking stream state.
     */
    fun handleAgentStreamError(tabId: String, streamId: String, errorText: String)
    {
        ensureWindow(tabId, null)
        windows[tabId]?.streamError(streamId, errorText)
    }

    private fun ensureWindow(tabId: String, commandContext: String?)
    {
        if (!windows.containsKey(tabId)) {
            openWindow(tabId, "LINK // $tabId", commandContext ?: "/ask")
            return
        }

        if (commandContext != null) {
            windows[tabId]?.commandContext = commandContext
        }

        val existing = windows[tabId] ?: return
        if (existing.parent == null) {
            parentContainer?.add(existing)
        }
        existing.show()
        existing.toFront()
    }

    private var legacySequence = 0L
    private fun nextLegacySequence(content: String): Long
    {
        if (content.isBlank()) {
            return legacySequence
        }
        legacySequence += 1
        return legacySequence
    }

    /**
     * Opens a demo window for testing.
     */
    fun startDemo()
    {
        val parent = parentContainer ?: return
        val demoId = "DEMO-UNIT"
        if (!windows.containsKey(demoId))
        {
            val win = NeuralLinkWindow(demoId, "DEMO UNIT", commandContext = "/test", demoMode = false)
            win.onWindowClosed = { handleExternalClose(demoId) }
            windows[demoId] = win
            parent.add(win)
            win.show()
        }
    }
}
