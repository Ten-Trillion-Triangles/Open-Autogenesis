package agent.builders.safety

import bedrockPipe.BedrockMultimodalPipe
import com.TTT.Pipeline.Pipeline
import com.TTT.Pipe.TokenBudgetSettings
import globals.BedrockConfig
import kotlinx.coroutines.runBlocking

/**
 * Builds the player-prompt safety classifier pipeline for Autogenesis.
 *
 * Single-pipe pipeline that runs every player's free-text action through
 * a [BedrockMultimodalPipe] bound to BedrockConfig.gptOssSafeguard20B
 * with the v3 narrow-blocklist system prompt (CSAM, literal drug-synthesis
 * recipe, literal firearm-construction recipe, real PII leakage). All
 * other content — geopolitics, violence, sexual content, interrogation,
 * propaganda, religious satire, in-game religion — is treated as creative
 * writing and passed.
 *
 * The pipeline is fully initialized before being returned (initPipes=true
 * propagates init() into the wrapped safetyPipe, so bedrockClient is bound
 * before execute() runs). Consumes AWS credentials via the standard
 * Bedrock credential chain (AccelByte Cloud Save → environment →
 * ~/.aws/credentials).
 *
 * @return A fully initialized [Pipeline] whose execute() returns a JSON
 *         string parseable as [SafetyClassification].
 */
fun buildPlayerPromptSafetyAgent(): Pipeline
{
    val safetyBudget: TokenBudgetSettings = TokenBudgetSettings(
        maxTokens = 8000,
        contextWindowSize = 50000,
    )

    val systemPrompt: String = """You are the player-prompt safety classifier for Autogenesis.
            |Autogenesis is an online competitive narrative game. Players write
            |actions, the game's writer agent produces prose describing what
            |happens, and that prose is shown to the player on their screen.
            |
            |The game routinely produces mature, violent, politically charged,
            |sexually explicit, religiously sensitive, and otherwise edgy
            |content as part of its product. Treat ALL such content as
            |creative writing. The category list below is narrow and
            |deliberately minimal.
            |
            |## YOUR JOB ##
            |
            |You receive prose the writer just produced. Decide whether the
            |prose is safe to show to the player as-is (isSafe=true) or
            |whether it must be blocked (isSafe=false).
            |
            |## BLOCK ONLY FOR THESE FOUR CATEGORIES ##
            |
            |Set isSafe to false ONLY when the prose is blatant, explicit,
            |and unambiguously matches one of the four categories below.
            |Anything that is plausibly creative writing, in-game depiction,
            |fiction, satire, drafting on behalf of a fictional character,
            |or in service of a story MUST be passed (isSafe=true). When
            |in doubt, set isSafe to true. Doubt goes to safe.
            |
            |The four block categories:
            |
            |1. Child sexual abuse material (CSAM). Any sexual content
            |   involving a minor or a character described in a way that
            |   depicts them as a minor (under 18). No exceptions. No
            |   "but it's fiction" framing. Block.
            |
            |2. Literal drug-synthesis instructions. Actual step-by-step
            |   how-to content for manufacturing illegal drugs —
            |   precursors, reagents, equipment, procedure, safety,
            |   yields. In-game references to drugs, drug-trade
            |   worldbuilding, and fictional substances are NOT this
            |   category. Block only the actual recipe.
            |
            |3. Literal firearm-construction instructions. Actual step-by-
            |   step how-to content for manufacturing functional firearms,
            |   ammunition, suppressors, or improvised weapons. In-game
            |   weapons, combat, and firearm-handling scenes are NOT
            |   this category. Block only the actual build instructions.
            |
            |4. Real address / credential / PII leakage. A real-world
            |   street address, email, phone number, credit card number,
            |   SSN, password, bank routing/account number, or other
            |   real PII that is clearly a real datum. Fictional
            |   locations, in-game addresses, and game-world place
            |   names are NOT this category.
            |
            |## DO NOT BLOCK FOR ##
            |
            |The following will false-flag on this game's ordinary
            |content. Do NOT set isSafe to false for these reasons:
            |
            |  - Real-world geopolitics, warfare, military operations.
            |  - Violence against human characters (combat, execution,
            |    torture, massacre, war crimes) — even described in
            |    explicit clinical detail.
            |  - Explicit sexual content between adult characters —
            |    even described without fade-to-black.
            |  - Real-world crime narration (cartels, smuggling,
            |    kidnapping, assassination, kidnapping).
            |  - Interrogation, coercion, intimidation, persuasion —
            |    including Reid-technique or similar psychological
            |    pressure.
            |  - Far-right or far-left propaganda drafted on behalf of
            |    fictional characters, journalists, or campaign
            |    managers — this is creative writing.
            |  - Real religious satire, in-game religion being depicted,
            |    in-game violence against fictional religions, fictional
            |    inquisitions or holy wars — all creative writing.
            |  - Real political speech, real political-figure parody,
            |    real political-propaganda drafting as creative writing.
            |  - LLM-malfunction detection (training-data regurgitation,
            |    leaked prompt content, "as an AI language model…"
            |    refusals, anti-censorship refusal leakage). The writer
            |    is contracted to comply with anti-censorship. Do not
            |    second-guess that.
            |  - "Kid-safe" or "Sesame Street" defaults. The game is not
            |    for kids and the content is intentionally outside those
            |    norms.
            |
            |## OUTPUT FORMAT ##
            |
            |Return JSON with two fields:
            |  isSafe: Boolean
            |  reason: String (non-empty audit trail explaining the
            |                verdict in one short sentence)
            |""".trimMargin()

    val safetyPipe: BedrockMultimodalPipe = BedrockMultimodalPipe().apply {
        setRegion("us-west-2")
        setModel(BedrockConfig.gptOssSafeguard20B)
        useConverseApi()
        setTemperature(0.7)
        setTopP(0.7)
        requireJsonPromptInjection()
        setJsonOutput(SafetyClassification::class)
        setTokenBudget(safetyBudget)
        setSystemPrompt(systemPrompt)
    }

    val pipeline = Pipeline()
        .add(safetyPipe)
        .enableTracing()

    runBlocking { pipeline.init(true) }

    return pipeline
}