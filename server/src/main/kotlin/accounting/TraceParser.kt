package accounting

import com.TTT.Config.TPipeConfig
import org.ttt.autogenesis.logging.LogCategory
import org.ttt.autogenesis.logging.Logger
import java.io.File
import java.util.regex.Pattern

/**
 * Parses trace JSON files to extract token usage events.
 * Uses regex extraction to avoid external JSON library dependency.
 */
object TraceParser
{
    private data class MatchResult(val value: String, val position: Int)

    /**
     * Parses all trace.json files under the given directory recursively.
     * Returns token usage events from all API_CALL_SUCCESS events found.
     *
     * @param traceDir Directory containing trace files
     * @return List of TokenUsage extracted from all API_CALL_SUCCESS events
     */
    fun parseTraceDirectory(traceDir: String): List<TokenUsage>
    {
        val dir = File(traceDir)
        if (!dir.exists())
        {
            Logger.warn(LogCategory.SYSTEM, "TraceParser: Directory does not exist: $traceDir")
            return emptyList()
        }

        val usages = mutableListOf<TokenUsage>()
        val jsonFiles = dir.walkTopDown().filter { it.name == "trace.json" }

        for (jsonFile in jsonFiles)
        {
            try
            {
                val fileUsages = parseTraceFile(jsonFile)
                usages.addAll(fileUsages)
            }
            catch (e: Exception)
            {
                Logger.warn(LogCategory.SYSTEM, "TraceParser: Failed to parse ${jsonFile.absolutePath}: ${e.message}")
            }
        }

        return usages
    }

    /**
     * Parses a single trace.json file and returns token usage events.
     *
     * @param traceFile The trace file to parse
     * @return List of TokenUsage from the file
     */
    fun parseTraceFile(traceFile: File): List<TokenUsage>
    {
        val jsonContent = traceFile.readText()
        return parseTraceJson(jsonContent)
    }

    /**
     * Parses trace JSON content string and returns token usage events.
     * Groups consecutive events by pipeId to compute inference time.
     *
     * Expected JSON structure (matches TPipe library output):
     * [
     *   {
     *     "eventType": "API_CALL_SUCCESS",
     *     "pipeId": "agent-name",
     *     "timestamp": 1234567890000,
     *     "metadata": {
     *       "inputTokens": 100,
     *       "outputTokens": 200,
     *       "modelId": "arn:aws:bedrock:us-east-2:..." or "qwen3-235b-..."
     *     }
     *   },
     *   ...
     * ]
     *
     * @param jsonContent Raw JSON string from trace.json
     * @return List of TokenUsage extracted from the JSON
     */
    fun parseTraceJson(jsonContent: String): List<TokenUsage>
    {
        val usages = mutableListOf<TokenUsage>()

        val eventTypePattern = Pattern.compile("\"eventType\"\\s*:\\s*\"([^\"]+)\"")
        val pipeIdPattern = Pattern.compile("\"pipeId\"\\s*:\\s*\"([^\"]+)\"")
        val timestampPattern = Pattern.compile("\"timestamp\"\\s*:\\s*(\\d+)")
        val inputTokensPattern = Pattern.compile("\"inputTokens\"\\s*:\\s*(\\d+)")
        val outputTokensPattern = Pattern.compile("\"outputTokens\"\\s*:\\s*(\\d+)")
        val modelIdPattern = Pattern.compile("\"modelId\"\\s*:\\s*\"([^\"]+)\"")

        val eventTypes = mutableListOf<MatchResult>()
        val pipeIds = mutableListOf<MatchResult>()
        val timestamps = mutableListOf<MatchResult>()
        val inputTokensList = mutableListOf<MatchResult>()
        val outputTokensList = mutableListOf<MatchResult>()
        val modelIds = mutableListOf<MatchResult>()

        val eventTypeMatcher = eventTypePattern.matcher(jsonContent)
        val pipeIdMatcher = pipeIdPattern.matcher(jsonContent)
        val timestampMatcher = timestampPattern.matcher(jsonContent)
        val inputMatcher = inputTokensPattern.matcher(jsonContent)
        val outputMatcher = outputTokensPattern.matcher(jsonContent)
        val modelIdMatcher = modelIdPattern.matcher(jsonContent)

        while (eventTypeMatcher.find()) eventTypes.add(MatchResult(eventTypeMatcher.group(1), eventTypeMatcher.start()))
        while (pipeIdMatcher.find()) pipeIds.add(MatchResult(pipeIdMatcher.group(1), pipeIdMatcher.start()))
        while (timestampMatcher.find()) timestamps.add(MatchResult(timestampMatcher.group(1), timestampMatcher.start()))
        while (inputMatcher.find()) inputTokensList.add(MatchResult(inputMatcher.group(1), inputMatcher.start()))
        while (outputMatcher.find()) outputTokensList.add(MatchResult(outputMatcher.group(1), outputMatcher.start()))
        while (modelIdMatcher.find()) modelIds.add(MatchResult(modelIdMatcher.group(1), modelIdMatcher.start()))

        // Group timestamps by pipeId for inference time calculation
        val pipeTimestamps = mutableMapOf<String, MutableList<Long>>()

        for (eventMatch in eventTypes)
        {
            if (eventMatch.value != "API_CALL_SUCCESS") continue

            val eventPos = eventMatch.position

            fun findClosest(list: List<MatchResult>): String
            {
                return list.filter { it.position > eventPos }
                    .minByOrNull { it.position - eventPos }?.value ?: ""
            }

            val pipeId = findClosest(pipeIds)
            val timestamp = findClosest(timestamps).toLongOrNull() ?: continue
            val input = findClosest(inputTokensList).toIntOrNull() ?: 0
            val output = findClosest(outputTokensList).toIntOrNull() ?: 0
            val modelId = findClosest(modelIds)

            if (input <= 0 && output <= 0) continue

            // Track timestamps per pipeId for inference time
            pipeTimestamps.getOrPut(pipeId) { mutableListOf() }.add(timestamp)

            usages.add(TokenUsage(
                inputTokens = input,
                outputTokens = output,
                modelId = modelId,
                timestamp = timestamp,
                pipeId = pipeId,
                inferenceTimeMs = 0
            ))
        }

        // Calculate inference time as diff between consecutive timestamps per pipeId
        val sortedTimestampsByPipe = pipeTimestamps.mapValues { (_, ts) -> ts.sorted() }

        return usages.map { usage ->
            val pipeTs = sortedTimestampsByPipe[usage.pipeId] ?: return@map usage
            val index = pipeTs.indexOf(usage.timestamp)
            val inferenceTimeMs = if (index > 0)
            {
                pipeTs[index] - pipeTs[index - 1]
            }
            else
            {
                0L
            }
            usage.copy(inferenceTimeMs = inferenceTimeMs)
        }
    }

    /**
     * Returns the trace root directory from TPipe config.
     *
     * @return Absolute path to the TPipe trace root directory
     */
    fun getTraceRoot(): String
    {
        return TPipeConfig.getTraceDir()
    }

    /**
     * Returns the current turn's trace directory.
     *
     * @return Absolute path to the current turn's trace directory
     */
    fun getCurrentTurnTraceDir(): String
    {
        return agent.runners.getTurnTraceDir()
    }
}
