package accounting

/**
 * Pricing entry for a single model (per-million-token pricing).
 *
 * @param inputPricePerMillion Cost per million input tokens
 * @param outputPricePerMillion Cost per million output tokens
 */
data class ModelPricingEntry(
    val inputPricePerMillion: Double,
    val outputPricePerMillion: Double
)

/**
 * Current model pricing (per 1M tokens) — matches token_tally.py pricing.
 * Extend this map as new models are added.
 */
object ModelPricing
{
    private val currentPricing: Map<String, ModelPricingEntry> = mapOf(
        "qwen.qwen3-235b-a22b-2507-v1:0" to ModelPricingEntry(
            inputPricePerMillion = 0.1133,
            outputPricePerMillion = 0.4532
        ),
        "qwen.qwen3-coder-30b-a3b-v1:0" to ModelPricingEntry(
            inputPricePerMillion = 0.07725,
            outputPricePerMillion = 0.3090
        ),
        "writer.palmyra-x5-v1:0" to ModelPricingEntry(
            inputPricePerMillion = 0.60,
            outputPricePerMillion = 6.00
        )
    )

    /**
     * Maps partial Bedrock model ID strings to short pricing keys.
     * Used to match modelId from trace events to pricing entries.
     */
    private val modelIdAliases: Map<String, String> = mapOf(
        "qwen3-235b" to "qwen.qwen3-235b-a22b-2507-v1:0",
        "qwen3-coder-30b" to "qwen.qwen3-coder-30b-a3b-v1:0",
        "palmyra-x5" to "writer.palmyra-x5-v1:0"
    )

    /**
     * Resolves a full or partial model ID to the short pricing key.
     *
     * @param fullModelId The model ID string from trace metadata
     * @return The short pricing key (e.g. "qwen.qwen3-235b-a22b-2507-v1:0")
     */
    fun resolveShortId(fullModelId: String): String
    {
        for ((alias, shortId) in modelIdAliases)
        {
            if (fullModelId.contains(alias))
            {
                return shortId
            }
        }
        return fullModelId
    }

    /**
     * Calculates cost in USD for given token counts.
     *
     * @param modelId The model ID
     * @param inputTokens Number of input tokens
     * @param outputTokens Number of output tokens
     * @return Cost in USD
     */
    fun calculateCost(modelId: String, inputTokens: Int, outputTokens: Int): Double
    {
        val shortId = resolveShortId(modelId)
        val pricing = currentPricing[shortId] ?: return 0.0
        return (inputTokens / 1_000_000.0 * pricing.inputPricePerMillion) +
            (outputTokens / 1_000_000.0 * pricing.outputPricePerMillion)
    }

    /**
     * Converts total token count to credits.
     * 1,000 tokens == 1 credit.
     *
     * @param inputTokens Number of input tokens
     * @param outputTokens Number of output tokens
     * @return Total credits (input + output tokens divided by 1,000)
     */
    fun tokensToCredits(inputTokens: Int, outputTokens: Int): Double
    {
        return (inputTokens + outputTokens) / 1000.0
    }
}
