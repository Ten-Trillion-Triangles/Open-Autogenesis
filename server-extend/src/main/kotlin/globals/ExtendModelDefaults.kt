package globals

import env.bedrockEnv
import org.ttt.autogenesis.config.ConfigSource

/**
 * Global object that houses all the preset aws bedrock models commonly used in TPipe systems.
 */
object ExtendModelDefaults
{
    val deepseekModelName: String get() = ConfigSource.property("bedrock.local.properties", "bedrock.deepseekModelName")
    val claudeModelName: String get() = ConfigSource.property("bedrock.local.properties", "bedrock.claudeModelName")
    val novaModelName = "amazon.nova-lite-v1:0" //us-east-2
    val novaProModelName: String get() = ConfigSource.property("bedrock.local.properties", "bedrock.novaProModelName")
    val gptOssModelName = "openai.gpt-oss-20b-1:0" //us-west-2
    val gptOss120bModelName = "openai.gpt-oss-120b-1:0" //us-west-2

    //us-east-2
    val llamaMaverick: String get() = ConfigSource.property("bedrock.local.properties", "bedrock.llamaMaverick")
    val llama70B: String get() = ConfigSource.property("bedrock.local.properties", "bedrock.llama70B")
    val llama405B: String get() = ConfigSource.property("bedrock.local.properties", "bedrock.llama405B")

    //us-east-1
    val jambaModelName = "ai21.jamba-1-5-large-v1:0"

    //us-west-2
    /**
     * General purpose version of R1 supposedly far better at creative writing. Supports reasoning being turned
     * on or off.
     */
    val deepseekV31 = "deepseek.v3-v1:0"


    //us-west-2
    /**
     * 235B parameter mixture of experts model. Supports reasoning. Instruct style assitant.
     */
    val qwen235B = "qwen.qwen3-235b-a22b-2507-v1:0"

    /**
     * Condensed version. Supposedly good at writing. Supports reasoning.
     */
    val qwen32B = "qwen.qwen3-32b-v1:0"

    /**
     * Supposedly optimized for coding. Supports reasoning.
     */
    val qwenCoder480B = "qwen.qwen3-coder-480b-a35b-v1:0"

    /**
     * Mixture of experts version of coder.
     */
    val qwenCoder30B = "qwen.qwen3-coder-30b-a3b-v1:0"

    /**
     * Palmyra by Writer */
    val PalmyraX5: String get() = ConfigSource.property("bedrock.local.properties", "bedrock.PalmyraX5")

    init {
        bind()
    }

    fun bind()
    {
        /**
         * Required boilerplate to map us to the arn, or inference ID. This is because most models cannot be
         * invoked directly, and must be bound to a profile.
         */
        try {
            bedrockEnv.bindInferenceProfile("deepseek.r1-v1:0", ConfigSource.property("bedrock.local.properties", "bedrock.deepseekModelName"))
            bedrockEnv.bindInferenceProfile("amazon.nova-pro-v1:0", ConfigSource.property("bedrock.local.properties", "bedrock.novaProModelName"))
            bedrockEnv.bindInferenceProfile("amazon.nova-lite-v1:0", ConfigSource.property("bedrock.local.properties", "bedrock.novaLiteModelName"))
            bedrockEnv.bindInferenceProfile(llamaMaverick, ConfigSource.property("bedrock.local.properties", "bedrock.llamaMaverick"))
            bedrockEnv.bindInferenceProfile(llama70B, ConfigSource.property("bedrock.local.properties", "bedrock.llama70B"))
            bedrockEnv.bindInferenceProfile(llama405B, ConfigSource.property("bedrock.local.properties", "bedrock.llama405B"))
            bedrockEnv.bindInferenceProfile("writer.palmyra-x5-v1:0", ConfigSource.property("bedrock.local.properties", "bedrock.PalmyraX5"))
            bedrockEnv.bindInferenceProfile(PalmyraX5, ConfigSource.property("bedrock.local.properties", "bedrock.PalmyraX5"))

            bedrockEnv.loadInferenceConfig()
        } catch (e: Exception) {
            // Silently handle or log if possible
        }
    }
}