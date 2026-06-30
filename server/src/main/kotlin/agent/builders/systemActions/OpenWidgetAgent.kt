package agent.builders.systemActions

import Defaults.reasoning.ReasoningDepth
import Defaults.reasoning.ReasoningDuration
import bedrockPipe.BedrockMultimodalPipe
import com.TTT.Pipeline.Pipeline
import com.TTT.Pipe.MultimodalContent
import com.TTT.PipeContextProtocol.PcpContext
import com.TTT.PipeContextProtocol.bindFunction
import com.TTT.Util.extractJson
import globals.BedrockConfig
import kotlinx.coroutines.runBlocking
import org.ttt.autogenesis.logging.LogCategory
import org.ttt.autogenesis.logging.Logger
import org.ttt.autogenesis.network.OpenWidgetData
import org.ttt.autogenesis.network.OpenWidgetType
import org.ttt.autogenesis.server.AgentWorkStreamDispatcher
import org.ttt.autogenesis.server.UiSignalRpcHandlers
import kotlinx.serialization.Serializable

@Serializable
data class OpenUiDecision(
    val isActionable: Boolean = false,
    val widget: OpenWidgetType? = null,
    val confidence: Double = 0.0,
    val reasoning: String = "",
    val suggestions: List<OpenWidgetType> = emptyList(),
    val tabId: String? = null,
    val title: String? = null,
    val commandContext: String? = null
)

data class OpenWidgetPipeline(
    val decisionPipeline: Pipeline,
    val pcpPipeline: Pipeline,
    val pcpPipe: BedrockMultimodalPipe
)

class OpenWidgetTool(private val connectionId: String)
{
    fun openWidget(widget: OpenWidgetType, tabId: String? = null, title: String? = null, commandContext: String? = null): String
    {
        val openRequest = OpenWidgetData(
            widget = widget,
            tabId = tabId?.takeIf { it.isNotBlank() },
            title = title?.takeIf { it.isNotBlank() },
            commandContext = if(commandContext.isNullOrBlank()) "/ask" else commandContext
        )

        runBlocking {
            Logger.info(LogCategory.UI, "OpenWidgetTool: sending widget=${widget.name} to connection=$connectionId tab=${openRequest.tabId ?: "N/A"}")
            UiSignalRpcHandlers.sendOpenWidget(connectionId, openRequest)
        }

        return "Open request queued"
    }
}

fun buildOpenWidgetPipeline(connectionId: String, userPrompt: String, targetTabId: String): OpenWidgetPipeline
{
    val decisionPipe = createDecisionPipe(userPrompt, targetTabId)
    val decisionPipeline = Pipeline().apply {
        pipelineName = "open widget decision pipeline"
        add(decisionPipe)
    }
    Logger.debug(LogCategory.SYSTEM, "OpenWidgetAgent: decision pipeline configured for connection=$connectionId tab=$targetTabId")

    val tool = OpenWidgetTool(connectionId)
    val context = PcpContext().bindFunction("open_widget", tool::openWidget)
    Logger.info(LogCategory.SYSTEM, "OpenWidgetAgent: bound PCP tool open_widget for connection=$connectionId tab=$targetTabId")

    val pcpPipe = createPcpPipe(userPrompt, targetTabId).apply {
        setPcPContext(context)
    }
    Logger.debug(LogCategory.SYSTEM, "OpenWidgetAgent: PCP pipeline configured for connection=$connectionId tab=$targetTabId")

    val workStreamCallback: suspend (String) -> Unit = { chunk ->
        if(chunk.isNotEmpty())
        {
            AgentWorkStreamDispatcher.appendChunk(connectionId, chunk)
        }
    }

    pcpPipe.enableStreaming().streamingCallbacks {
        add(workStreamCallback)
    }

    val pcpPipeline = Pipeline().apply {
        pipelineName = "open widget pipeline"
        add(pcpPipe)
        setPipelineCompletionCallback { _, _ ->
            AgentWorkStreamDispatcher.notifyPipelineComplete(connectionId)
        }
    }

    return OpenWidgetPipeline(decisionPipeline, pcpPipeline, pcpPipe)
}

private fun createDecisionPipe(userPrompt: String, targetTabId: String): BedrockMultimodalPipe
{
    return configureBasePipe(userPrompt, targetTabId, false).apply {
        setJsonOutput(OpenUiDecision())
        requireJsonPromptInjection()
        setPipeName("open widget decision pipe")
    }
}

private fun createPcpPipe(userPrompt: String, targetTabId: String): BedrockMultimodalPipe
{
    return configureBasePipe(userPrompt, targetTabId, true).apply {
        setPipeName("open widget PCP pipe")
    }
}

private fun configureBasePipe(userPrompt: String, targetTabId: String, isPcp: Boolean): BedrockMultimodalPipe
{
    return BedrockMultimodalPipe().apply {
        setRegion("us-west-2")
        useConverseApi()
        setModel(BedrockConfig.qwen235B)
        setTokenBudget(BedrockConfig.workerBudgetSettings)
        setReasoningPipe(BedrockConfig.structuredCotBuilder(depthLevel = ReasoningDepth.High, durationLevel = ReasoningDuration.Short))
        setSystemPrompt(buildSystemPrompt(userPrompt, targetTabId, isPcp))

        setPostGenerateFunction {
            val result = it.text
            Logger.debug(LogCategory.LLM, "OpenWidget Agent PCP output: $result")
        }
    }
}

private fun buildSystemPrompt(userPrompt: String, targetTabId: String, isPcp: Boolean): String
{
    val base = """
        |You are the Open UI Agent responsible for opening widgets requested by the player.
        |Input: "$userPrompt"
        |Requested tab: $targetTabId
        |
        |Available widgets:
        |- WORLD_STATS
        |- PLAYER_RESOURCES
        |- STATS
        |- SETTINGS
        |- PROMPT_STATUS
        |- NEURAL_LINK
        |- AGENT_STREAM
        |- DELEGATE - opens the per-player AI delegate guidance panel
        |
        |If you are confident which widget the user wants, call the open_widget function with the widget enum and any optional tab/title/context arguments.
        |If you are unsure or the request is ambiguous, explain why you cannot determine the correct widget.
        |Do not invent new widget names or perform actions you cannot validate.
    """.trimMargin()

    return if (isPcp) {
        base + "\n\n" + """
            |CRITICAL INSTRUCTION FOR FUNCTION CALLS:
            |When outputting the JSON to call open_widget, you must use the 'callParams' object for your arguments.
            |DO NOT place arguments inside 'tPipeContextOptions.params'.
            |
            |Example correct format:
            |{
            |    "tPipeContextOptions": {
            |        "functionName": "open_widget"
            |    },
            |    "callParams": {
            |        "widget": "SETTINGS",
            |        "tabId": "Commander"
            |    }
            |}
        """.trimMargin()
    } else {
        base
    }
}
