package structs

private const val EMPTY_NORMALIZED_NAME = ""

private fun String.normalizeTerritoryName(): String
{
    return trim().lowercase()
}

private val Territory.normalizedTerritoryName: String
    get() = name.normalizeTerritoryName()

private const val FUZZY_TERRITORY_DISTANCE_THRESHOLD = 3
private const val TOKEN_SIMILARITY_THRESHOLD = 2
private const val TOKEN_OVERLAP_RATIO_THRESHOLD = 0.75

private fun String.levenshteinDistance(other: String): Int
{
    if(this == other) return 0
    if(this.isEmpty()) return other.length
    if(other.isEmpty()) return this.length

    var previousRow = IntArray(other.length + 1) { it }
    val currentRow = IntArray(other.length + 1)

    for(i in 1..this.length)
    {
        currentRow[0] = i
        for(j in 1..other.length)
        {
            val cost = if(this[i - 1] == other[j - 1]) 0 else 1
            currentRow[j] = minOf(
                previousRow[j] + 1,
                currentRow[j - 1] + 1,
                previousRow[j - 1] + cost
            )
        }
        previousRow = currentRow.copyOf()
    }

    return currentRow[other.length]
}

/**
 * Determines whether the territory name matches the provided value after normalization.
 *
 * Matching is case- and whitespace-insensitive so that callers can rely on a consistent, normalized name.
 *
 * @param candidate The input name to compare against the territory.
 * @return `true` when the names match after normalization; `false` otherwise.
 */
fun Territory.matchesTerritoryName(candidate: String?): Boolean
{
    if(candidate.isNullOrBlank())
    {
        return false
    }

    val normalizedCandidate = candidate.normalizeTerritoryName()
    if(normalizedCandidate == EMPTY_NORMALIZED_NAME)
    {
        return false
    }

    return normalizedTerritoryName == normalizedCandidate
}

/**
 * Searches within the collection for the first territory whose normalized name matches the provided input.
 *
 * Callers can provide raw user input (or LLM output) without worrying about casing or trailing whitespace.
 *
 * @param name The territory name to locate.
 * @return The first matching territory, or `null` if none are found.
 */
fun Collection<Territory>.findTerritoryByName(name: String?): Territory?
{
    if(name.isNullOrBlank())
    {
        return null
    }

    val normalizedName = name.normalizeTerritoryName()
    if(normalizedName == EMPTY_NORMALIZED_NAME)
    {
        return null
    }

    return firstOrNull { it.normalizedTerritoryName == normalizedName }
}

fun Collection<Territory>.resolveTerritoryName(name: String?, maxDistance: Int = FUZZY_TERRITORY_DISTANCE_THRESHOLD): Territory?
{
    val exactMatch = findTerritoryByName(name)
    if(exactMatch != null)
    {
        return exactMatch
    }

    if(name.isNullOrBlank())
    {
        return null
    }

    val normalizedCandidate = name.normalizeTerritoryName()
    if(normalizedCandidate == EMPTY_NORMALIZED_NAME)
    {
        return null
    }

    var bestMatch: Territory? = null
    var bestDistance = Int.MAX_VALUE

    forEach { territory ->
        val distance = territory.normalizedTerritoryName.levenshteinDistance(normalizedCandidate)
        if(distance < bestDistance)
        {
            bestDistance = distance
            bestMatch = territory
        }
    }

    if(bestMatch != null && bestDistance <= maxDistance)
    {
        return bestMatch
    }

    val candidateTokens = normalizedCandidate.split("\\s+".toRegex()).filter { it.isNotBlank() }
    return findTokenBasedMatch(normalizedCandidate, candidateTokens)
}

private fun String.tokenize(): List<String>
{
    return split("\\s+".toRegex())
        .mapNotNull { it.trim().takeIf { text -> text.isNotBlank() } }
}

private fun String.isTokenSimilar(candidate: String): Boolean
{
    if(this == candidate) return true
    if(this.contains(candidate) || candidate.contains(this)) return true
    return this.levenshteinDistance(candidate) <= TOKEN_SIMILARITY_THRESHOLD
}

private fun Collection<Territory>.findTokenBasedMatch(normalizedCandidate: String, candidateTokens: List<String>): Territory?
{
    if(candidateTokens.size < 2)
    {
        return null
    }

    var bestMatch: Territory? = null
    var bestScore = 0.0
    var bestDistance = Int.MAX_VALUE

    forEach { territory ->
        val territoryTokens = territory.normalizedTerritoryName.tokenize()
        var overlap = 0.0
        candidateTokens.forEach { token ->
            if(territoryTokens.any { it.isTokenSimilar(token) })
            {
                overlap += 1.0
            }
        }
        if(overlap == 0.0)
        {
            return@forEach
        }

        val score = overlap / candidateTokens.size
        val distance = territory.normalizedTerritoryName.levenshteinDistance(normalizedCandidate)

        if(score > bestScore || (score == bestScore && distance < bestDistance))
        {
            bestScore = score
            bestDistance = distance
            bestMatch = territory
        }
    }

    return if(bestScore >= TOKEN_OVERLAP_RATIO_THRESHOLD) bestMatch else null
}

/**
 * Finds a territory within this world by name after applying normalization rules.
 *
 * @param name Territory name to resolve.
 */
fun World.findTerritoryByName(name: String?): Territory?
{
    return mapTiles.findTerritoryByName(name)
}

fun World.resolveTerritoryName(name: String?): Territory?
{
    return mapTiles.resolveTerritoryName(name)
}

/**
 * Finds a territory tracked by the player, covering the starting tile and captured holdings.
 *
 * @param name Territory name to resolve.
 */
fun Player.findTerritoryByName(name: String?): Territory?
{
    if(startingTile.matchesTerritoryName(name))
    {
        return startingTile
    }

    return capturedTerritory.findTerritoryByName(name)
}

/**
 * Finds a territory captured by the NPC that matches the provided name.
 *
 * @param name Territory name to resolve.
 */
fun Npc.findTerritoryByName(name: String?): Territory?
{
    return capturedTerritory.findTerritoryByName(name)
}
