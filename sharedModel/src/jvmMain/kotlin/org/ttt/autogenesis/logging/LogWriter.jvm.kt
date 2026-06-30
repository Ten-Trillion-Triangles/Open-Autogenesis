package org.ttt.autogenesis.logging

import java.io.File
import java.io.FileWriter
import java.io.PrintWriter
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * JVM-specific implementation of LogWriter that writes to console and files.
 * 
 * This implementation creates log files in the user's home directory under
 * .autogenesis/logs/ with daily rotation based on timestamps.
 */
actual object LogWriter
{
    private var minPriority : LogPriority = LogPriority.INFO
    private var saveToDisk : Boolean = true
    private var maxLogFiles : Int = 10
    private var serverType : String = "autogenesis"
    
    /**
     * The directory where log files are stored.
     * 
     * Creates ~/.autogenesis/logs/ if it doesn't exist.
     */
    private val logDirectory : File by lazy {
        val homeDir = System.getProperty("user.home")
        File(homeDir, ".autogenesis/logs").apply {
            if (!exists())
            {
                mkdirs()
            }
        }
    }
    
    /**
     * The current log file with timestamp per server instance.
     * 
     * Format: {serverType}-YYYY-MM-DD-HHmmss.log
     * Creates a fresh log file for each server restart.
     */
    private val logFile : File by lazy {
        val timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd-HHmmss"))
        File(logDirectory, "$serverType-$timestamp.log")
    }

    /**
     * Configures the log writer with minimum priority and disk saving options.
     * 
     * @param minPriority Minimum priority level to write
     * @param saveToDisk Whether to save logs to persistent storage
     * @param maxLogFiles Maximum number of log files to keep (0 = delete all old logs, N = keep N most recent)
     * @param serverType Server type identifier for log file naming
     */
    actual fun configure(minPriority : LogPriority, saveToDisk : Boolean, maxLogFiles : Int, serverType : String)
    {
        this.minPriority = minPriority
        this.saveToDisk = saveToDisk
        this.maxLogFiles = maxLogFiles
        this.serverType = serverType
        cleanupOldLogs()
    }

    /**
     * Writes a log entry to console and optionally to disk file.
     * 
     * All entries are printed to console. If saveToDisk is enabled and the
     * priority is not NONE, the entry is also appended to the daily log file.
     * 
     * @param entry The log entry to write
     */
    actual fun write(entry : LogEntry)
    {
        if (entry.priority.level < minPriority.level) return

        val formattedMessage = formatEntry(entry)

        // Print to console
        println(formattedMessage)

        // Write to file if enabled
        if (saveToDisk && entry.priority != LogPriority.NONE)
        {
            synchronized(this)
            {
                try
                {
                    PrintWriter(FileWriter(logFile, true)).use { writer ->
                        writer.println(formattedMessage)
                    }
                }
                catch (e : Exception)
                {
                    System.err.println("Failed to write log to file: ${e.message}")
                }
            }
        }
    }

    /**
     * Cleans up old log files based on the maxLogFiles retention policy.
     * 
     * If maxLogFiles == 0: deletes all old log files for this server type
     * If maxLogFiles > 0: keeps the N most recent log files, deletes the rest
     */
    private fun cleanupOldLogs()
    {
        try
        {
            val logFiles = logDirectory.listFiles { file ->
                file.isFile && file.name.startsWith("$serverType-") && file.name.endsWith(".log")
            }?.toList() ?: return

            if (logFiles.isEmpty()) return

            // Sort by filename (timestamp) descending (most recent first)
            val sortedFiles = logFiles.sortedByDescending { it.name }

            val filesToDelete = if (maxLogFiles == 0)
            {
                // Delete all old logs
                sortedFiles
            }
            else
            {
                // Keep maxLogFiles most recent, delete the rest
                sortedFiles.drop(maxLogFiles)
            }

            filesToDelete.forEach { file ->
                try
                {
                    if (file.delete())
                    {
                        println("Deleted old log file: ${file.name}")
                    }
                }
                catch (e : Exception)
                {
                    System.err.println("Failed to delete log file ${file.name}: ${e.message}")
                }
            }
        }
        catch (e : Exception)
        {
            System.err.println("Failed to cleanup old logs: ${e.message}")
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
