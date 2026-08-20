package org.ttt.autogenesis.server

import java.io.File
import java.io.FileWriter
import java.io.PrintWriter
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * Persists logs received from browsers so they can be reviewed server-side.
 *
 * The HTTP endpoint in [Server.serverModule] forwards POST bodies here when the
 * browser runs in DEBUG mode, which then writes the payload into timestamped
 * files under `~/.autogenesis/logs/`. Each server restart gets its own file and
 * retention is enforced via [cleanupOldLogs] so disk usage stays bounded.
 */
object BrowserLogHandler
{
    private const val MAX_LOG_FILES = 1
    
    private val logDirectory: File by lazy {
        val homeDir = System.getProperty("user.home")
        File(homeDir, ".autogenesis/logs").apply {
            if(!exists())
            {
                mkdirs()
            }
        }
    }
    
    private val logFile: File by lazy {
        cleanupOldLogs()
        val timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd-HHmmss"))
        File(logDirectory, "browser-$timestamp.log")
    }

    /**
     * Appends a browser log message to the current file.
     *
     * The caller (currently `Server.serverModule`'s `/api/browser-log` route)
     * POSTs raw log lines captured via the browser agent. The handler synchronizes
     * writes because multiple requests can arrive concurrently from different tabs,
     * and it always maintains at most [MAX_LOG_FILES] via [cleanupOldLogs].
     *
     * @param message Raw text sent from the browser; no additional serialization is performed.
     */
    fun write(message: String)
    {
        synchronized(this)
        {
            try
            {
                PrintWriter(FileWriter(logFile, true)).use { writer ->
                    writer.println(message)
                }
            }
            catch(e: Exception)
            {
                System.err.println("Failed to write browser log to file: ${e.message}")
            }
        }
    }

    /**
     * Deletes oldest browser log files beyond the [MAX_LOG_FILES] retention window.
     *
     * Every invocation happens before a new log file is created so the current
     * server session can always write to a clean file while stale ones are removed.
     */
    private fun cleanupOldLogs()
    {
        try
        {
            val logFiles = logDirectory.listFiles { file ->
                file.isFile && file.name.startsWith("browser-") && file.name.endsWith(".log")
            }?.toList() ?: return

            if(logFiles.isEmpty()) return

            val sortedFiles = logFiles.sortedByDescending { it.name }
            val filesToDelete = sortedFiles.drop(MAX_LOG_FILES)

            filesToDelete.forEach { file ->
                try
                {
                    if(file.delete())
                    {
                        println("Deleted old browser log file: ${file.name}")
                    }
                }
                catch(e: Exception)
                {
                    System.err.println("Failed to delete browser log file ${file.name}: ${e.message}")
                }
            }
        }
        catch(e: Exception)
        {
            System.err.println("Failed to cleanup old browser logs: ${e.message}")
        }
    }
}
