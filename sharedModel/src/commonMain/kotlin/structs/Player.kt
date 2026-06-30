package structs

import enums.CommanderTrait
import enums.CommanderType
import interfaces.Actor
import interfaces.ActorInternals

/**
 * Data class that defines the player. Contains all player traits, all owned resources and territory,
 * and current points values a player has.
 */
@kotlinx.serialization.Serializable
data class Player(
    var name: String = "",
    var commanderType: CommanderType = CommanderType.Land,
    var trait: CommanderTrait = CommanderTrait.Balanced,
    var description: String = "",
    var shortDescription: String = "",
    var history: String = "",
    var startingTile: Territory = Territory(),
    var victoryPoints: Int = 0,
    var militaryPoints: Int = 0,
    var diplomacyPoints: Int = 0,
    var researchPoints: Int = 0,
    var summitPoints: Int = 0,
    var resources: MutableList<Resource> = mutableListOf(),
    var capturedTerritory: MutableList<Territory> = mutableListOf(),
    var capturedNemesis: MutableList<Npc> = mutableListOf(),
    var luckPoints: Int = 0, //Affects the chances of an event flipping positively or negatively regardless of all other factors
    var reputation: Int = 0, //0-100 value. Stat that can be buffed by research. Affects the legitimacy stat.
    var might: Int = 0, //0-100 modifies readiness as a buff that can be obtained via research or other factors.
    var wealth: Int = 0, //0-100 modifies the stagnation stat. Can be buffed through research actions.
    var militaryReadiness: Int = 70, //0-100 value. Affects luck, and harden/soften for military plays.
    var legitimacy: Int = 70, //0-100 value. Affected by reputation and stagnation on diplomatic actions.
    var stagnation: Int = 0, //0-100 value. Affects economic and resource based actions.
    /**
     * True when this player has surrendered via the `game.surrender` RPC.
     * Surrendered players are removed from `world.turnOrder` (so their turn is
     * skipped), have their non-destroyed tiles released to the unowned pool
     * (`ruler = ""`), and are filtered out of the player-count-aware threshold
     * computation in `TurnHarness.captureEndGameContext`. The player is kept
     * in `world.activePlayers` and `playerStats` so end-of-game placements,
     * billing reports, and history still see them. Default false so existing
     * serialized worlds and snapshots remain compatible.
     */
    var isSurrendered: Boolean = false,


    /**
     * Free-form guidance authored by the player for the AI that takes over this character
     * when the player is unreachable (see [org.ttt.autogenesis.server.PlayerDelegateRpcHandlers]
     * and the `##PLAYER DELEGATE GUIDANCE##` context slot in [agent.builders.playerAgent.buildPlayerAgent]).
     *
     * Stored on the player so it travels with the world snapshot (AccelByte CloudSave via
     * [org.ttt.autogenesis.server.TurnHarness.serializeCurrentWorldSnapshotToUserRecord]) and
     * is broadcast to every connected client via `UiSignalRpcHandlers.broadcastWorldUpdate`.
     * Null/blank means "no guidance" and the agent falls back to the default PLAY TO WIN behavior.
     */
    var delegateInstructions: String? = null,


) : Actor
{
    override fun getInternals(): ActorInternals
    {
        return ActorInternals(name = name, isNpc = false)
    }
}
