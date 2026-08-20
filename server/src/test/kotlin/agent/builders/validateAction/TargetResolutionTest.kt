package agent.builders.validateAction

import kotlin.test.Test
import kotlin.test.assertEquals
import structs.Territory

class TargetResolutionTest
{
    @Test
    fun replacesTypoWithCanonicalName()
    {
        val targetType = ActionTargetTypeObj(
            type = ActionTargetType.Territory,
            targets = listOf("north ridgee")
        )

        val territories = listOf(Territory(name = "North Ridge"))
        val resolved = resolveTerritoryTargets(targetType, "Commander Shepard", territories)

        assertEquals(listOf("North Ridge"), resolved.targets)
    }

    @Test
    fun leavesExactMatchIntact()
    {
        val targetType = ActionTargetTypeObj(
            type = ActionTargetType.Territory,
            targets = listOf("North Ridge")
        )

        val territories = listOf(Territory(name = "North Ridge"))
        val resolved = resolveTerritoryTargets(targetType, "Commander Shepard", territories)

        assertEquals(listOf("North Ridge"), resolved.targets)
    }
}