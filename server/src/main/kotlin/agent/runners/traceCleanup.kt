package agent.runners

import com.TTT.Config.TPipeConfig
import com.TTT.Debug.TraceFormat
import com.TTT.Pipeline.Pipeline
import com.TTT.Pipeline.Splitter
import com.TTT.Util.writeStringToFile
import org.ttt.autogenesis.logging.LogCategory
import org.ttt.autogenesis.logging.Logger
import java.io.File

private var currentTurnFolderName: String? = null

/**
 * Sets the current turn folder name for trace organization.
 * Example: "Round_1_Turn_0_Commander_Shepard"
 */
fun setCurrentTurnFolderName(name: String?)
{
    currentTurnFolderName = name
}

/**
 * Returns the base directory for the current turn's traces.
 * If no turn is active, returns the root trace directory.
 */
fun getTurnTraceDir(): String
{
    val baseDir = TPipeConfig.getTraceDir()
    val baseFile = File(baseDir)
    return if (currentTurnFolderName == null) 
    {
        baseFile.absolutePath
    } 
    else 
    {
        File(baseFile, currentTurnFolderName!!).absolutePath
    }
}

/**
 * Helper utility to save both JSON and HTML trace reports to a specific subfolder in the debug directory.
 *
 * @param subFolder The name of the subdirectory (e.g., "Judge", "ReversalAgent").
 * @param pipeline The pipeline agent to generate reports from.
 * @param fileName The base name for the trace files (default is "trace").
 */
fun saveSystemTrace(subFolder: String, pipeline: Pipeline, fileName: String = "trace")
{
    val dir = File(File(getTurnTraceDir()), subFolder)
    if(!dir.exists()) dir.mkdirs()
    
    val jsonContent = pipeline.getTraceReport(TraceFormat.JSON)
    val htmlContent = pipeline.getTraceReport(TraceFormat.HTML)
    
    writeStringToFile("${dir.absolutePath}/$fileName.json", jsonContent)
    writeStringToFile("${dir.absolutePath}/$fileName.html", htmlContent)
    
    Logger.debug(LogCategory.SYSTEM, "Saved system traces (JSON/HTML) to ${dir.absolutePath}/$fileName.*")
}

/**
 * Helper utility to save both JSON and HTML trace reports from a Splitter.
 */
fun saveSystemTrace(subFolder: String, splitter: Splitter, fileName: String = "trace")
{
    val dir = File(File(getTurnTraceDir()), subFolder)
    if(!dir.exists()) dir.mkdirs()
    
    val jsonContent = splitter.getTraceReport(TraceFormat.JSON)
    val htmlContent = splitter.getTraceReport(TraceFormat.HTML)
    
    writeStringToFile("${dir.absolutePath}/$fileName.json", jsonContent)
    writeStringToFile("${dir.absolutePath}/$fileName.html", htmlContent)
    
    Logger.debug(LogCategory.SYSTEM, "Saved system traces (JSON/HTML) from Splitter to ${dir.absolutePath}/$fileName.*")
}

/**
 * Removes all files/directories under the configured trace root so that each turn starts clean.
 */
fun clearTraceDirectory()
{
    val traceDirPath = TPipeConfig.getTraceDir()
    val traceDir = File(traceDirPath)
    if(!traceDir.exists())
    {
        if(traceDir.mkdirs())
        {
            Logger.info(LogCategory.SYSTEM, "Trace directory created at $traceDirPath")
        }
        else
        {
            Logger.warn(LogCategory.SYSTEM, "Unable to create trace directory at $traceDirPath")
        }
        return
    }

    val entries = traceDir.listFiles()
    if(entries.isNullOrEmpty())
    {
        Logger.debug(LogCategory.SYSTEM, "Trace directory already empty: $traceDirPath")
        return
    }

    var deleted = 0
    var failed = 0
    for(entry in entries)
    {
        try
        {
            if(entry.deleteRecursively())
            {
                deleted++
            }
            else
            {
                failed++
                Logger.warn(LogCategory.SYSTEM, "Unable to delete trace entry: ${entry.absolutePath}")
            }
        }
        catch(e: Exception)
        {
            failed++
            Logger.warn(LogCategory.SYSTEM, "Exception while deleting trace entry ${entry.absolutePath}: ${e.message}")
        }
    }

    if(failed == 0)
    {
        Logger.info(LogCategory.SYSTEM, "Trace directory cleared ($deleted entries) at $traceDirPath")
    }
    else
    {
        Logger.warn(LogCategory.SYSTEM, "Trace directory partially cleared: $deleted deleted, $failed failed at $traceDirPath")
    }

    if(!traceDir.exists() && !traceDir.mkdirs())
    {
        Logger.warn(LogCategory.SYSTEM, "Trace directory path no longer exists and could not be re-created: $traceDirPath")
    }
}
