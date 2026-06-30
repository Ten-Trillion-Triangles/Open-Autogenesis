package org.ttt.autogenesis.logging

import kotlinx.browser.localStorage
import kotlinx.browser.window
import kotlin.js.Date

/**
 * JavaScript-specific implementation of LogWriter using console and localStorage.
 * 
 * This implementation writes to the browser console with appropriate log levels
 * and optionally stores logs in localStorage for persistence across sessions.
 */
actual object LogWriter
{
    private var minPriority : LogPriority = LogPriority.INFO
    private var sendToServer : Boolean = false
    private const val SERVER_LOG_ENDPOINT = "http://127.0.0.1:9080/api/browser-log"
    
    // In-memory buffer for batching logs to the server
    private val logBuffer = mutableListOf<String>()
    private var flushIntervalId: Int? = null
    private const val MAX_BUFFER_SIZE = 100
    private const val FLUSH_INTERVAL_MS = 3000

    /**
     * Configures the log writer with minimum priority and server logging options.
     * 
     * @param minPriority Minimum priority level to write
     * @param saveToDisk Ignored in browser (localStorage persistence removed for performance)
     * @param maxLogFiles Ignored in browser
     * @param serverType Server type identifier
     */
    actual fun configure(minPriority : LogPriority, saveToDisk : Boolean, maxLogFiles : Int, serverType : String)
    {
        this.minPriority = minPriority
        this.sendToServer = (minPriority == LogPriority.DEBUG)
        
        // Initialize periodic flush if not already running
        if (sendToServer && flushIntervalId == null) {
            flushIntervalId = kotlinx.browser.window.setInterval({ flush() }, FLUSH_INTERVAL_MS)
        }
    }

    /**
     * Writes a log entry to browser console and appends to the in-memory buffer for server dispatch.
     * 
     * Uses appropriate console methods based on priority level (console.error,
     * console.warn, etc.). Logs are batched and sent to the server in DEBUG mode.
     * 
     * @param entry The log entry to write
     */
    actual fun write(entry : LogEntry)
    {
        if (entry.priority.level < minPriority.level) return

        val formattedMessage = formatEntry(entry)

        // Console output with appropriate log level
        when (entry.priority) {
            LogPriority.DEBUG -> console.log(formattedMessage)
            LogPriority.INFO -> console.info(formattedMessage)
            LogPriority.WARN -> console.warn(formattedMessage)
            LogPriority.ERROR -> console.error(formattedMessage)
            else -> console.log(formattedMessage)
        }

        // Buffer for server dispatch if in DEBUG mode
        if (sendToServer && entry.priority != LogPriority.NONE)
        {
            logBuffer.add(formattedMessage)
            
            // Immediate flush if buffer is getting large
            if (logBuffer.size >= MAX_BUFFER_SIZE) {
                flush()
            }
        }
    }

    /**
     * Dispatches all buffered logs to the server in a single batch.
     */
    private fun flush()
    {
        if (logBuffer.isEmpty()) return
        
        // Snapshot the current buffer and clear it immediately to avoid duplicates 
        // if another log arrives while fetch is pending.
        val batch = logBuffer.toList()
        logBuffer.clear()
        
        val payload = batch.joinToString("\n")
        
        try
        {
            kotlinx.browser.window.fetch(SERVER_LOG_ENDPOINT, js("""{
                method: "POST",
                headers: { "Content-Type": "text/plain" },
                body: payload
            }""").unsafeCast<dynamic>()).then({}, {})
        }
        catch (e : dynamic)
        {
            // Silently ignore - server might not be running
        }
    }

    /**
     * Formats a log entry into a readable string.
     * 
     * Format: timestamp [PRIORITY] [CATEGORY]: message
     * 
     * @param entry The log entry to format
     * @return Formatted log message string
     */
    private fun formatEntry(entry : LogEntry) : String
    {
        return "${entry.timestamp} [${entry.priority}] [${entry.category}]: ${entry.message}"
    }
}
