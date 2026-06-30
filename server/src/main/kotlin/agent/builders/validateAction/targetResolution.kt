package agent.builders.validateAction

import org.ttt.autogenesis.logging.LogCategory
import org.ttt.autogenesis.logging.Logger
import structs.Territory
import structs.resolveTerritoryName

/**
 * Resolves territory target strings before the judge sees them. Exact matches pass through unchanged,
 * but fuzzy inputs are rerouted to the nearest official territory name so downstream pipes operate on valid tiles.
 *
 * @param targetType Detected target data coming from the TPipe stack.
 * @param actorName Name of the actor (player or NPC) that emitted the action.
 * @param tiles The canonical map tiles to compare against.
 * @return A copy of [targetType] whose `targets` list contains canonical territory names where possible.
 */
fun resolveTerritoryTargets(targetType: ActionTargetTypeObj, actorName: String, tiles: Collection<Territory>): ActionTargetTypeObj
{
    if(targetType.type != ActionTargetType.Territory || targetType.targets.isEmpty())
    {
        return targetType
    }

    var changed = false
    val resolvedTargets = targetType.targets.map { rawTarget ->
        val resolved = tiles.resolveTerritoryName(rawTarget)
        if(resolved != null)
        {
            val canonicalName = resolved.name
            if(!canonicalName.equals(rawTarget.trim(), ignoreCase = true))
            {
                changed = true
                Logger.info(LogCategory.LLM, "Resolved territory target '$rawTarget' to canonical tile '$canonicalName' for $actorName")
            }
            canonicalName
        }
        else
        {
            rawTarget
        }
    }

    return if(changed) { targetType.copy(targets = resolvedTargets) } else { targetType }
}
