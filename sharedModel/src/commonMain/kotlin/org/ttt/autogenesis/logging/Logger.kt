package org.ttt.autogenesis.logging

import kotlinx.serialization.Serializable
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant

/**
 * Defines the priority levels for logging messages.
 * 
 * Higher priority levels indicate more critical messages that should be logged
 * even when the minimum priority threshold is set higher.
 * 
 * @param level The numeric level used for priority comparison
 */
enum class LogPriority(val level : Int)
{
    DEBUG(1),
    INFO(2),
    WARN(3),
    ERROR(4),
    NONE(Int.MAX_VALUE)
}

/**
 * Categorizes log messages by their functional area.
 * 
 * Categories help organize and filter log messages based on the system
 * component or feature that generated them.
 */
enum class LogCategory
{
    GENERAL,
    NETWORK,
    DATABASE,
    UI,
    AUTH,
    SYSTEM,
    LLM
}

/**
 * Represents a single log entry with timestamp, priority, category and message.
 * 
 * This data class is serializable and contains all information needed to
 * format and store a log message across different platforms.
 * 
 * @param timestamp When the log entry was created
 * @param priority The importance level of this log message
 * @param category The functional area this log relates to
 * @param message The actual log message content
 */
@Serializable
data class LogEntry(
    val timestamp : Instant,
    val priority : LogPriority,
    val category : LogCategory,
    val message : String
)

/**
 * Platform-specific log writer implementation.
 * 
 * This expect object provides the actual writing mechanism for log entries,
 * with different implementations for JVM and JS platforms.
 */
expect object LogWriter
{
    /**
     * Writes a log entry to the platform-specific output.
     * 
     * @param entry The log entry to write
     */
    fun write(entry : LogEntry)
    
    /**
     * Configures the log writer with minimum priority and disk saving options.
     * 
     * @param minPriority Minimum priority level to write
     * @param saveToDisk Whether to save logs to persistent storage
     * @param maxLogFiles Maximum number of log files to keep (0 = delete all old logs, N = keep N most recent)
     * @param serverType Server type identifier for log file naming
     */
    fun configure(minPriority : LogPriority, saveToDisk : Boolean, maxLogFiles : Int, serverType : String)
}

/**
 * Main logging interface providing convenient methods for different log levels.
 * 
 * This object manages the global logging configuration and provides methods
 * to log messages at different priority levels with automatic formatting.
 */
object Logger
{
    private var minPriority : LogPriority = LogPriority.INFO
    private var saveToDisk : Boolean = true

    /**
     * Configures the global logging settings.
     * 
     * @param minPriority Minimum priority level to log (default: INFO)
     * @param saveToDisk Whether to save logs to disk (default: true)
     * @param maxLogFiles Maximum number of log files to keep (0 = delete all old logs, N = keep N most recent) (default: 10)
     * @param serverType Server type identifier for log file naming (default: "autogenesis")
     */
    fun configure(
        minPriority : LogPriority = LogPriority.INFO,
        saveToDisk : Boolean = true,
        maxLogFiles : Int = 10,
        serverType : String = "autogenesis"
    )
    {
        this.minPriority = minPriority
        this.saveToDisk = saveToDisk
        LogWriter.configure(minPriority, saveToDisk, maxLogFiles, serverType)
    }

    /**
     * Logs a message with the specified priority and category.
     * 
     * Messages are filtered by the minimum priority level and multi-line
     * messages are converted to single lines for consistent formatting.
     * 
     * @param priority The importance level of this message
     * @param category The functional area this message relates to
     * @param message The message content to log
     */
    fun log(priority : LogPriority, category : LogCategory, message : String)
    {
        if (priority.level < minPriority.level) return

        // Convert multi-line message to single line
        val singleLineMessage = message.replace("\n", " | ").replace("\r", "")

        val entry = LogEntry(
            timestamp = Clock.System.now(),
            priority = priority,
            category = category,
            message = singleLineMessage
        )

        LogWriter.write(entry)
    }

    /**
     * Logs a debug message.
     * 
     * @param category The functional area this message relates to
     * @param message The debug message content
     */
    fun debug(category : LogCategory, message : String) = log(LogPriority.DEBUG, category, message)
    
    /**
     * Logs an info message.
     * 
     * @param category The functional area this message relates to
     * @param message The info message content
     */
    fun info(category : LogCategory, message : String) = log(LogPriority.INFO, category, message)
    
    /**
     * Logs a warning message.
     * 
     * @param category The functional area this message relates to
     * @param message The warning message content
     */
    fun warn(category : LogCategory, message : String) = log(LogPriority.WARN, category, message)
    
    /**
     * Logs an error message.
     * 
     * @param category The functional area this message relates to
     * @param message The error message content
     */
    fun error(category : LogCategory, message : String) = log(LogPriority.ERROR, category, message)
}
