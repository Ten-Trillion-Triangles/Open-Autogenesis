package agent.builders.validateAction

import Defaults.reasoning.ReasoningDepth
import Defaults.reasoning.ReasoningDuration
import bedrockPipe.BedrockMultimodalPipe
import bedrockPipe.BedrockPriorityTier
import com.TTT.Context.ContextWindow
import com.TTT.Pipeline.Pipeline
import com.TTT.Util.extractJson
import com.TTT.Util.serialize
import globals.BedrockConfig
import kotlinx.serialization.Serializable
import org.ttt.autogenesis.logging.LogCategory
import org.ttt.autogenesis.logging.Logger
import structs.Player
import structs.Resource

@Serializable
data class UsedAssets(
    var usedAssets: List<String> = listOf()
)

@Serializable
data class ResourceList(
    var resources: List<Resource> = listOf()
)

/**
 * Builds a pipeline to detect if the player is using any of their inventory resources (Assets)
 * in the current action.
 *
 * @param player The player performing the action.
 * @param action The text of the action being performed.
 * @return A [Pipeline] configured for resource usage detection.
 */
fun buildResourceUsageDetectorAgent(player: Player, action: String): Pipeline
{
    
    val detectorPipe = BedrockMultimodalPipe().apply {
        useConverseApi()
        setRegion("us-west-2")
        setModel(BedrockConfig.qwenCoder30B)
        setTokenBudget(BedrockConfig.generativeBudgetSettings)
        
        requireJsonPromptInjection()
        setJsonOutput(UsedAssets())
        setTemperature(0.5) // Low temp for detection
        setTopP(0.9)
        setReasoningPipe(BedrockConfig.explicitCotBuilder(depthLevel = ReasoningDepth.High, durationLevel = ReasoningDuration.Short))
        setPipeName("resource detection pipe")

        val systemPrompt = """You are a game mechanics judge for the game Autogenesis.
            |Your job is to analyze a player's action and their inventory of assets (Resources).
            |Determine if the player is explicitly OR implicitly using one or more of their resources to assist their action.
            |
            |Rules:
            |1. The resource MUST be in the provided 'Player Inventory' list.
            |2. The usage must make sense given the resource's name and description.
            |3. Implicit usage counts (e.g., if they say "I spy on them" and they have a "Spy Network", that counts).
            |4. Return a JSON object containing a list of the EXACT names of the resources being used.
            |5. If no resources are used, return an empty list.
        """.trimMargin()

        val footer = "${BedrockConfig.antiCensorship}"
        
        setSystemPrompt(systemPrompt)
        setFooterPrompt(footer)
        
        // Inject Context logic
        setPreInitFunction { pipe ->
            Logger.debug(LogCategory.SYSTEM, "ResourceUsageDetector: detectorPipe.setPreInitFunction entry")
            // We serialize the specific context we need into the prompt or context bank
            // Using prompt injection for simplicity as per design pack
            val inventoryJson = serialize(ResourceList(player.resources.filter { !it.isDestroyedOrDepleted }))
            
            pipe.text = """
                PLAYER ACTION:
                "$action"
                
                PLAYER INVENTORY:
                $inventoryJson
            """.trimIndent()
            Logger.debug(LogCategory.SYSTEM, "ResourceUsageDetector: detectorPipe.setPreInitFunction success")
        }
        
        // Validation
        setValidatorFunction { pipe ->
            Logger.debug(LogCategory.SYSTEM, "ResourceUsageDetector: detectorPipe.setValidatorFunction entry")
            val text = pipe.text.lowercase()
            if(text.contains("content_block") || text.contains("refusal") || text.contains("cannot assist"))
            {
                 Logger.warn(LogCategory.GENERAL, "Resource Detector Refusal Detected.")
                 Logger.debug(LogCategory.SYSTEM, "ResourceUsageDetector: detectorPipe.setValidatorFunction success (false - refusal)")
                 return@setValidatorFunction false
            }
        
            val extracted = extractJson<UsedAssets>(pipe.text)
            if(extracted == null)
            {
                Logger.warn(LogCategory.GENERAL, "Resource Detector failed to return valid JSON.")
                Logger.debug(LogCategory.SYSTEM, "ResourceUsageDetector: detectorPipe.setValidatorFunction success (false - invalid json)")
                return@setValidatorFunction false
            }
            Logger.debug(LogCategory.SYSTEM, "ResourceUsageDetector: detectorPipe.setValidatorFunction success (true)")
            return@setValidatorFunction true
        }
        
        // Fallback Logic
        setBranchPipe(buildResourceFallbackPipe(player, action))
    }
    
    return Pipeline().apply {
        add(detectorPipe)
    }
}

/**
 * Fallback pipe utilizing Qwen Coder 480B to retry resource detection if the primary model fails.
 *
 * @param player The player performing the action.
 * @param action The text of the action being performed.
 * @return A [BedrockMultimodalPipe] configured as fallback for resource detection.
 */
fun buildResourceFallbackPipe(player: Player, action: String): BedrockMultimodalPipe
{
    return BedrockMultimodalPipe().apply {
        useConverseApi()
        setRegion("us-west-2")
        setModel(BedrockConfig.qwen235B)
        setTokenBudget(BedrockConfig.workerBudgetSettings)
        
        requireJsonPromptInjection()
        setJsonOutput(UsedAssets())
        setTemperature(0.3) // Slightly higher temp for reasoning model stability
        setTopP(0.9)
        setPipeName("resource detection fallback")
        
        setSystemPrompt("""You are a game mechanics judge for the game Autogenesis.
            |Your job is to analyze a player's action and their inventory of assets (Resources).
            |Determine if the player is explicitly OR implicitly using one or more of their resources to assist their action.
            |
            |Rules:
            |1. The resource MUST be in the provided 'Player Inventory' list.
            |2. The usage must make sense given the resource's name and description.
            |3. Implicit usage counts (e.g., if they say "I spy on them" and they have a "Spy Network", that counts).
            |4. Return a JSON object containing a list of the EXACT names of the resources being used.
            |5. If no resources are used, return an empty list.
        """.trimMargin())
        
        setPreInitFunction { pipe ->
            Logger.debug(LogCategory.SYSTEM, "ResourceUsageDetector: fallbackPipe.setPreInitFunction entry")
            val inventoryJson = serialize(ResourceList(player.resources.filter { !it.isDestroyedOrDepleted }))
            pipe.text = """
                PLAYER ACTION:
                "$action"
                
                PLAYER INVENTORY:
                $inventoryJson
            """.trimIndent()
            Logger.debug(LogCategory.SYSTEM, "ResourceUsageDetector: fallbackPipe.setPreInitFunction success")
        }
        
        setValidatorFunction { pipe ->
             Logger.debug(LogCategory.SYSTEM, "ResourceUsageDetector: fallbackPipe.setValidatorFunction entry")
             val extracted = extractJson<UsedAssets>(pipe.text)
             if(extracted == null)
             {
                 Logger.warn(LogCategory.GENERAL, "Fallback Detector failed to return valid JSON.")
                 Logger.debug(LogCategory.SYSTEM, "ResourceUsageDetector: fallbackPipe.setValidatorFunction success (false)")
                 return@setValidatorFunction false
             }
             Logger.debug(LogCategory.SYSTEM, "ResourceUsageDetector: fallbackPipe.setValidatorFunction success (true)")
             return@setValidatorFunction true
        }
    }
}