package gameInit

import agent.prompts.Prompts
import enums.CommanderTrait
import enums.CommanderType
import kotlin.random.Random

data class AiDescriptor(
    val key: String,
    val name: String,
    val prompt: String,
    val shortDescription: String,
    val commanderType: CommanderType,
    val commanderTrait: CommanderTrait
)

/**
 * Loads AI descriptors directly from the shared prompt definitions.
 */
object AiPromptProvider
{
    private val typeOverrides = mapOf(
        "zzs" to CommanderType.Land,
        "hc" to CommanderType.Land,
        "nzg" to CommanderType.Land,
        "bmd" to CommanderType.Land,
        "ivd" to CommanderType.Land,
        "bg" to CommanderType.Land,
        "njb" to CommanderType.Land,
        "bob" to CommanderType.Land,
        "dave" to CommanderType.Land,
        "robert" to CommanderType.Land,
        "qlb" to CommanderType.Land,
        "zsr" to CommanderType.Land
    )

    private val traitOverrides = mapOf(
        "zzs" to CommanderTrait.Diplomatic,
        "hc" to CommanderTrait.Warlord,
        "nzg" to CommanderTrait.Balanced,
        "bmd" to CommanderTrait.Warlord,
        "ivd" to CommanderTrait.Researcher,
        "bg" to CommanderTrait.Balanced,
        "njb" to CommanderTrait.Diplomatic,
        "bob" to CommanderTrait.Balanced,
        "dave" to CommanderTrait.Warlord,
        "robert" to CommanderTrait.Warlord,
        "qlb" to CommanderTrait.Warlord,
        "zsr" to CommanderTrait.Balanced
    )

    private val random = Random.Default

    /**
     * Tokens that mark the end of the name portion when they appear in title position
     * (e.g., "Shitty Bob the great..." -> "Shitty Bob"). These are common sentence
     * starters / sentence-restart words found in the prompt templates.
     */
    private val nameSentenceRestarters = setOf("You", "Your", "He", "She", "They", "It", "As", "An", "A", "The", "While", "When", "Where", "Who", "Which", "That")

    /**
     * Abbreviations whose trailing period is part of the token (e.g., "Dr." in
     * "Dr. Percival Thrustmore") and must not be treated as a sentence terminator.
     */
    private val nameAbbreviationTokens = setOf(
        "Dr.", "Mr.", "Mrs.", "Ms.", "St.", "Jr.", "Sr.", "Prof.", "Gen.", "Gov.",
        "Sgt.", "Cpl.", "Pvt.", "Capt.", "Lt.", "Col.", "Hon.", "Rev."
    )

    private val descriptors = Prompts.promptMap.map { (key, value) ->
        AiDescriptor(
            key = key,
            name = parseName(value, key),
            prompt = value.trim().lines().joinToString(" ") { it.trim() },
            shortDescription = Prompts.shortDescriptionMap[key] ?: value.take(150),
            commanderType = typeOverrides[key] ?: CommanderType.Land,
            commanderTrait = traitOverrides[key] ?: CommanderTrait.Balanced
        )
    }.toList().ifEmpty {
        listOf(AiDescriptor(
            key = "default",
            name = "AI Commander",
            prompt = "An unnamed AI; extrapolate from the world.",
            shortDescription = "An unnamed AI commander.",
            commanderType = CommanderType.Land,
            commanderTrait = CommanderTrait.Balanced
        ))
    }

    /**
     * Extracts the multi-word display name from a prompt of the form
     * "You are <Name...><. | , | You/Your/...>". Captures successive capitalised
     * tokens until it hits a sentence terminator, an abbreviation-disallowed period,
     * a known sentence-restarter word, or the first lowercase word once at least one
     * name token has been collected.
     *
     * Examples:
     * - "You are Shitty Bob the great..." -> "Shitty Bob"
     * - "You are Officer Dave. You are..." -> "Officer Dave"
     * - "You are Bigwang McDouchebag. You are..." -> "Bigwang McDouchebag"
     * - "You are Zuzusarogorata Suguruzands, High Priest..." -> "Zuzusarogorata Suguruzands"
     * - "You are Dr. Percival Thrustmore. As an..." -> "Dr. Percival Thrustmore"
     *
     * Falls back to [fallbackKey] upper-cased when the prompt does not start with
     * "You are " or no usable name token can be extracted (e.g., the malformed "bg"
     * prompt that begins with "You Big Googar.").
     */
    private fun parseName(prompt: String, fallbackKey: String): String
    {
        val namePrefix = "You are "
        val trimmed = prompt.trim()
        if(!trimmed.startsWith(namePrefix, ignoreCase = true))
        {
            return fallbackKey.uppercase()
        }

        val remainder = trimmed.substring(namePrefix.length)
        val tokens = remainder.split(Regex("\\s+"))
        val nameTokens = mutableListOf<String>()
        for(token in tokens)
        {
            if(token.isEmpty())
            {
                continue
            }
            val stripped = token.trimEnd('.', ',', '!', '?', ';', ':')
            if(stripped.isEmpty())
            {
                // Token was pure punctuation; stop.
                break
            }

            val isAbbreviation = token in nameAbbreviationTokens
            val isSentenceRestarter = stripped in nameSentenceRestarters
            val startsLowercase = token.first().isLowerCase()

            if(nameTokens.isNotEmpty() && (isSentenceRestarter || (startsLowercase && !isAbbreviation)))
            {
                break
            }

            val tokenToAdd = if(isAbbreviation) token else stripped
            nameTokens.add(tokenToAdd)

            // Trailing sentence punctuation terminates the name (unless this token
            // is a recognised abbreviation, in which case more name words may follow).
            if(token != stripped && !isAbbreviation)
            {
                break
            }
        }

        val name = nameTokens.joinToString(" ").trim()
        return name.ifBlank { fallbackKey.uppercase() }
    }

    fun randomDescriptors(count: Int): List<AiDescriptor>
    {
        if(descriptors.isEmpty() || count <= 0)
        {
            return emptyList()
        }

        // Return a shuffled sub-list to ensure uniqueness, or repeat if count > descriptors.size
        val shuffled = descriptors.shuffled(random)
        if(count <= shuffled.size)
        {
            return shuffled.take(count)
        }

        // If we need more than we have, take all and then add random repeats
        return shuffled + List(count - shuffled.size) { descriptors[random.nextInt(descriptors.size)] }
    }

    /**
     * Returns descriptors that match any of the given substrings. Each substring is
     * compared case-insensitively against (a) the descriptor [AiDescriptor.key]
     * (exact match) and (b) the descriptor [AiDescriptor.name] (substring match).
     * Each descriptor key appears at most once (first match per substring wins).
     * Preserves the order of [substrings] in the returned list. Returns an empty
     * list when [substrings] is empty.
     *
     * @param substrings list of substrings to match against descriptor keys and names
     * @return filtered list of matching descriptors
     */
    fun descriptorsMatching(substrings: List<String>): List<AiDescriptor>
    {
        if(substrings.isEmpty())
        {
            return emptyList()
        }

        val result = mutableListOf<AiDescriptor>()
        val usedKeys = mutableSetOf<String>()

        for(substring in substrings)
        {
            for(descriptor in descriptors)
            {
                if(descriptor.key in usedKeys)
                {
                    continue
                }
                if(descriptor.key.equals(substring, ignoreCase = true) ||
                    descriptor.name.contains(substring, ignoreCase = true))
                {
                    result.add(descriptor)
                    usedKeys.add(descriptor.key)
                    break
                }
            }
        }

        return result
    }
}