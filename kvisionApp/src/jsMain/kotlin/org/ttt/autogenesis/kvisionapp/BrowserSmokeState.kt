package org.ttt.autogenesis.kvisionapp

import kotlinx.browser.document
import org.ttt.autogenesis.logging.LogCategory
import org.ttt.autogenesis.logging.Logger
import org.ttt.autogenesis.network.ServerExtendTransport
import org.w3c.dom.HTMLDivElement

/**
 * Hidden browser-only smoke marker used by Playwright to observe transport state.
 *
 * The marker is only created when the browser smoke flag is enabled.
 */
object BrowserSmokeState
{
    private const val markerId = "autogenesis-browser-smoke"

    private var enabled = false
    private var transport = ""
    private var connected = false
    private var sessionReady = false
    private var smokeStatus = "idle"
    private var smokeMethod = ""
    private var smokeValue = ""
    private var smokeError = ""

    /**
     * Enables or disables the hidden smoke marker.
     */
    fun install(enabled: Boolean)
    {
        this.enabled = enabled
        Logger.info(LogCategory.SYSTEM, "BrowserSmokeState.install(enabled=$enabled)")
        sync()
    }

    /**
     * Re-applies the hidden marker after the app shell has mounted.
     */
    fun refresh()
    {
        Logger.info(LogCategory.SYSTEM, "BrowserSmokeState.refresh()")
        sync()
    }

    /**
     * Records the currently selected transport.
     */
    fun setTransport(value: ServerExtendTransport)
    {
        transport = value.name.lowercase().replace('_', '-')
        sync()
    }

    /**
     * Marks the transport as connected.
     */
    fun setConnected(value: Boolean)
    {
        connected = value
        sync()
    }

    /**
     * Marks the transport session as ready.
     */
    fun setSessionReady(value: Boolean)
    {
        sessionReady = value
        sync()
    }

    /**
     * Marks the smoke probe as passed.
     */
    fun setProbePassed(method: String, value: String)
    {
        smokeStatus = "passed"
        smokeMethod = method
        smokeValue = value
        smokeError = ""
        sync()
    }

    /**
     * Marks the smoke probe as failed.
     */
    fun setProbeFailed(error: String)
    {
        smokeStatus = "failed"
        smokeError = error
        sync()
    }

    private fun sync()
    {
        if(!enabled)
        {
            return
        }

        val marker = ensureMarker()
        Logger.debug(LogCategory.SYSTEM, "BrowserSmokeState.sync() markerReady=${marker.id}")
        marker.setAttribute("data-testid", "browser-smoke-status")
        marker.setAttribute("data-transport", transport)
        marker.setAttribute("data-connected", connected.toString())
        marker.setAttribute("data-session-ready", sessionReady.toString())
        marker.setAttribute("data-smoke-status", smokeStatus)
        marker.setAttribute("data-smoke-method", smokeMethod)
        marker.setAttribute("data-smoke-value", smokeValue)
        marker.setAttribute("data-smoke-error", smokeError)
        marker.textContent = buildString {
            append("transport=").append(transport)
            append(";connected=").append(connected)
            append(";sessionReady=").append(sessionReady)
            append(";smoke=").append(smokeStatus)
            if(smokeMethod.isNotBlank())
            {
                append(";method=").append(smokeMethod)
            }
            if(smokeValue.isNotBlank())
            {
                append(";value=").append(smokeValue)
            }
            if(smokeError.isNotBlank())
            {
                append(";error=").append(smokeError)
            }
        }
    }

    private fun ensureMarker(): HTMLDivElement
    {
        val existing = document.getElementById(markerId) as? HTMLDivElement
        if(existing != null)
        {
            return existing
        }

        val created = document.createElement("div") as HTMLDivElement
        created.id = markerId
        created.style.display = "none"
        val parent = document.body ?: document.documentElement
        parent?.appendChild(created)
        Logger.debug(
            LogCategory.SYSTEM,
            "BrowserSmokeState.ensureMarker() appended=${parent != null} parent=${parent?.nodeName}"
        )
        return created
    }
}