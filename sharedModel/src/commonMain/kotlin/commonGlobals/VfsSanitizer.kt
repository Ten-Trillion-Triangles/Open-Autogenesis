package commonGlobals

import kotlin.math.absoluteValue

object VfsSanitizer
{
    /**
     * Replace whitespace sequences with a single dash and strip every other character
     * that is not an alphabetic letter. Consecutive dashes are collapsed and
     * any leading/trailing dash is trimmed. If the result is empty, fall back to a
     * deterministic placeholder that avoids collisions where possible.
     */
    fun sanitize(value : String) : String
    {
        val collapsedSpaces = value.trim().replace("\\s+".toRegex(), "-")
        val allowedCharacters = collapsedSpaces.mapNotNull { char ->
            when
            {
                char.isLetter() -> char
                char == '-' -> char
                else -> null
            }
        }.joinToString("")

        val normalized = allowedCharacters
            .replace("-{2,}".toRegex(), "-")
            .trim('-')

        return normalized.ifBlank { "sanitized-${value.hashCode().absoluteValue}" }
    }
}
