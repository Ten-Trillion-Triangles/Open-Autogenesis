package ui.gameplay

import io.kvision.core.Container
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import org.ttt.autogenesis.kvisionapp.WebSocketRpcBridge
import org.ttt.autogenesis.logging.LogCategory
import org.ttt.autogenesis.logging.Logger
import org.ttt.autogenesis.network.RpcMessage.AgentWorkStreamData
import org.ttt.autogenesis.network.AgentWorkStreamSubscriptionRequest

/**
 * Manages the hidden agent work stream window and RPC subscription for streaming data.
 */
object AgentWorkStreamManager
{
    private var parentContainer: Container? = null
    private var streamWindow: AgentWorkStreamWindow? = null
    private val uiScope = MainScope()
    private var subscribed = false

    /**
     * Attaches the stream manager to the UI root container so it can display the window when needed.
     */
    fun init(parent: Container)
    {
        parentContainer = parent
    }

    /**
     * Displays the agent work stream window and starts the server subscription.
     */
    fun openStream()
    {
        Logger.info(LogCategory.UI, "AgentWorkStreamManager.openStream: Starting")
        if (streamWindow == null)
        {
            streamWindow = AgentWorkStreamWindow(::hideStreamInternal)
            Logger.info(LogCategory.UI, "AgentWorkStreamManager.openStream: Created new window")
        }
        val window = streamWindow ?: return

        if (window.parent == null)
        {
            parentContainer?.add(window)
            Logger.info(LogCategory.UI, "AgentWorkStreamManager.openStream: Added window to parent")
        }
        window.show()
        window.focus()
        Logger.info(LogCategory.UI, "AgentWorkStreamManager.openStream: Window shown and focused, visible=${window.visible}")
        subscribe(true)
    }

    /**
     * Hides the window and stops delivering stream updates.
     */
    fun hideStream()
    {
        hideStreamInternal()
        streamWindow?.hide()
    }

    private fun hideStreamInternal()
    {
        if (streamWindow?.visible == true)
        {
            streamWindow?.hide()
        }
        subscribe(false)
    }

    /**
     * Renders incoming chunks while the window is visible and marks the stream complete when signaled.
     */
    fun handleStream(data: AgentWorkStreamData)
    {
        val window = streamWindow
        Logger.debug(LogCategory.UI, "AgentWorkStreamManager.handleStream: window=$window, visible=${window?.visible}, contentLen=${data.content.length}, isComplete=${data.isComplete}")
        if (window?.visible != true)
        {
            Logger.warn(LogCategory.UI, "AgentWorkStreamManager.handleStream: Dropping data because window not visible (window=$window, visible=${window?.visible})")
            return
        }

        if (data.content.isNotBlank())
        {
            window.appendChunk(data.content)
        }
        if (data.isComplete)
        {
            window.markComplete()
        }
    }

    /**
     * Appends a thinking update from the server to the stream window.
     */
    fun appendThinking(data: org.ttt.autogenesis.network.ThinkingUpdateData)
    {
        val window = streamWindow
        Logger.debug(LogCategory.UI, "AgentWorkStreamManager.appendThinking: window=$window, visible=${window?.visible}")
        if (window?.visible != true)
        {
            Logger.warn(LogCategory.UI, "AgentWorkStreamManager.appendThinking: Dropping data because window not visible")
            return
        }

        window.appendThinking(data)
    }

    private fun subscribe(active: Boolean)
    {
        if (subscribed == active)
        {
            return
        }
        subscribed = active

        uiScope.launch {
            try {
                WebSocketRpcBridge.rpcInvoker?.invoke(
                    "game.agentWorkStream.subscribe",
                    AgentWorkStreamSubscriptionRequest(active)
                )
            }
            catch (err: Throwable) {
                Logger.warn(LogCategory.UI, "AgentWorkStreamManager: Subscription change failed: ${err.message}")
            }
        }
    }
}
