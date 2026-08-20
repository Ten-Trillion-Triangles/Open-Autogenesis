package accounting

import com.TTT.Config.TPipeConfig
import java.io.File

/**
 * Test fixture: writes a synthetic [trace.json] that [TraceParser.parseTraceJson]
 * can parse. The format mirrors what TPipe emits when an LLM call succeeds.
 */
data class TraceEvent(
    val pipeId: String,
    val modelId: String,
    val inputTokens: Int,
    val outputTokens: Int,
    val timestamp: Long = System.currentTimeMillis()
)

/**
 * Helper for the billing integration test suite. Synthesises on-disk trace
 * directories that match the layout produced by TPipe at runtime.
 */
object TraceFixture
{
    private const val TRACE_FILE_NAME = "trace.json"

    /**
     * Writes a synthetic [trace.json] under
     * `<TPipeConfig.getTraceDir()>/<folderName>/trace.json` containing one
     * `API_CALL_SUCCESS` event per entry in [events].
     *
     * Folder name format: "Round_N_Turn_M_ActorName" — matches the
     * convention used by `agent.runners.getTurnTraceDir()`.
     *
     * @param folderName The turn folder name (e.g., "Round_1_Turn_0_Commander_TestPlayer").
     * @param events The list of [TraceEvent]s to serialise into the trace file.
     */
    fun writeTraceJson(folderName: String, events: List<TraceEvent>)
    {
        val folder = File(TPipeConfig.getTraceDir(), folderName)
        if (!folder.exists())
        {
            folder.mkdirs()
        }
        val traceFile = File(folder, TRACE_FILE_NAME)

        val jsonBuilder = StringBuilder()
        jsonBuilder.append("[")
        events.forEachIndexed { index, event ->
            if (index > 0) jsonBuilder.append(",")
            jsonBuilder.append("{")
            jsonBuilder.append("\"eventType\":\"API_CALL_SUCCESS\",")
            jsonBuilder.append("\"pipeId\":\"").append(jsonEscape(event.pipeId)).append("\",")
            jsonBuilder.append("\"timestamp\":").append(event.timestamp).append(",")
            jsonBuilder.append("\"metadata\":{")
            jsonBuilder.append("\"inputTokens\":").append(event.inputTokens).append(",")
            jsonBuilder.append("\"outputTokens\":").append(event.outputTokens).append(",")
            jsonBuilder.append("\"modelId\":\"").append(jsonEscape(event.modelId)).append("\"")
            jsonBuilder.append("}}")
        }
        jsonBuilder.append("]")

        traceFile.writeText(jsonBuilder.toString())
    }

    /**
     * Recursively deletes the trace folder under [TPipeConfig.getTraceDir]
     * with the given [folderName]. No-op if the folder does not exist.
     *
     * @param folderName The turn folder to remove.
     */
    fun cleanupTraceFolder(folderName: String)
    {
        val folder = File(TPipeConfig.getTraceDir(), folderName)
        if (folder.exists())
        {
            folder.deleteRecursively()
        }
    }

    private fun jsonEscape(value: String): String
    {
        val out = StringBuilder(value.length + 2)
        for (ch in value)
        {
            when (ch)
            {
                '\\' -> out.append("\\\\")
                '"' -> out.append("\\\"")
                '\n' -> out.append("\\n")
                '\r' -> out.append("\\r")
                '\t' -> out.append("\\t")
                '\b' -> out.append("\\b")
                '' -> out.append("\\f")
                else -> if (ch.code < 0x20)
                {
                    out.append(String.format("\\u%04x", ch.code))
                }
                else
                {
                    out.append(ch)
                }
            }
        }
        return out.toString()
    }
}