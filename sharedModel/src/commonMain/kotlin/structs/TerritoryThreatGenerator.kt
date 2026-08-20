package structs

import kotlin.random.Random

fun threatLevelToStatRange(level: Int): IntRange
{
    return when(level)
    {
        1 -> 0..30
        2 -> 31..60
        3 -> 61..90
        4 -> 91..120
        5 -> 121..150
        else -> 0..30
    }
}

fun statValueToThreatLevel(stat: Int): Int
{
    return when(stat)
    {
        in 0..30 -> 1
        in 31..60 -> 2
        in 61..90 -> 3
        in 91..120 -> 4
        in 121..150 -> 5
        else -> 1
    }
}

fun generateThreatStats(): Pair<Int, Int>
{
    val militaryIsPrimary = Random.nextBoolean()
    val primaryLevel = Random.nextInt(1, 6)
    val secondaryLevel = minOf(3, 6 - primaryLevel)
    
    val primaryRange = threatLevelToStatRange(primaryLevel)
    val secondaryRange = threatLevelToStatRange(secondaryLevel)
    
    val primaryStat = primaryRange.random()
    val secondaryStat = secondaryRange.random()
    
    return if (militaryIsPrimary)
    {
        Pair(primaryStat, secondaryStat)
    }
    else
    {
        Pair(secondaryStat, primaryStat)
    }
}

fun initializeAllTerritoryThreats(territories: List<Territory>)
{
    territories.forEach { territory ->
        if (territory.militaryThreatStat == 0 && territory.diplomacyThreatStat == 0)
        {
            val (military, diplomacy) = generateThreatStats()
            territory.militaryThreatStat = military
            territory.diplomacyThreatStat = diplomacy
        }
    }
}