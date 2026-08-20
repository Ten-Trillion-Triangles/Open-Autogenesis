package ui.gameplay

import io.kvision.core.*
import structs.Resource
import structs.Territory
import structs.Npc
import structs.Player
import structs.World
import enums.NpcType
import enums.ResourceType
import enums.TerritoryType

object DemoFixtures
{
    fun buildDemoLocalPlayer(): Player
    {
        return Player(
            name = "Commander Juno",
            victoryPoints = 34,
            militaryPoints = 12,
            diplomacyPoints = 2,
            researchPoints = 3,
            summitPoints = 1,
            description = "Recon-focused strategist keeping the frontline stable.",
            resources = buildDemoLocalPlayerResources()
        )
    }

    fun buildDemoOtherPlayers(): List<Player>
    {
        return listOf(
            Player(
                name = "Ambassador Kael",
                victoryPoints = 27,
                militaryPoints = 5,
                diplomacyPoints = 10,
                researchPoints = 4,
                summitPoints = 2,
                description = "Diplomatic powerhouse running simulated trade pacts."
            ),
            Player(
                name = "Marshal Sera",
                victoryPoints = 21,
                militaryPoints = 15,
                diplomacyPoints = 1,
                researchPoints = 1,
                summitPoints = 3,
                description = "Aggressive tactician keeping the tempo high."
            ),
            Player(
                name = "Lord Maple Tree (AI)",
                victoryPoints = 18,
                militaryPoints = 8,
                diplomacyPoints = 4,
                researchPoints = 12,
                summitPoints = 0,
                description = "An enigmatic intelligence focused on defensive growth."
            )
        )
    }

    fun buildDemoPlayers(): MutableList<Player>
    {
        return mutableListOf<Player>().apply {
            add(buildDemoLocalPlayer())
            addAll(buildDemoOtherPlayers())
        }
    }

    fun buildDemoNpcs(): MutableList<Npc>
    {
        return mutableListOf(
            Npc(
                name = "Xol'Thar the Unbound",
                type = NpcType.ElderGod,
                description = "An eldritch force that devours map tiles as easily as air.",
                history = "Awakened by comet fire and drawn to the leylines of the Emberfall Rift.",
                pointValue = 72,
                resources = mutableListOf(
                    Resource(
                        name = "Void Anchor",
                        type = ResourceType.Supernatural,
                        depletable = false,
                        destructible = false,
                        description = "Anchors a sphere of nothingness that destroys any tile it touches.",
                        abilities = "Anchors a sphere of nothingness that destroys any tile it touches."
                    )
                )
            ),
            Npc(
                name = "General Vaskov",
                type = NpcType.Nemesis,
                description = "A former hero who now seeks to unmake every champion in his path.",
                history = "Drilled armies into submission during the Siege of Oakhaven before turning on his own kin.",
                pointValue = 48,
                resources = mutableListOf(
                    Resource(
                        name = "Betrayer's Standard",
                        type = ResourceType.Military,
                        depletable = false,
                        destructible = true,
                        description = "Signals relentless assault everywhere it flies.",
                        abilities = "Grants a temporary +2 attack penalty to every opposing player."
                    )
                )
            ),
            Npc(
                name = "Oracle Maeve",
                type = NpcType.Hostile,
                description = "Sees future conflicts and maneuvers to trigger them.",
                history = "Her eyes glow with fractal sigils burned into the sands of the Zephyr Wastes.",
                pointValue = 29,
                resources = mutableListOf(
                    Resource(
                        name = "Rune of Forecasting",
                        type = ResourceType.Diplomatic,
                        depletable = false,
                        destructible = false,
                        description = "Predicts the next turn outcome with uncanny accuracy.",
                        abilities = "Reveal the next round's first actor and grants a +1 diplomatic bonus."
                    )
                )
            ),
            Npc(
                name = "Acolyte Meros",
                type = NpcType.Active,
                description = "A reformer with a knack for surprising maneuvers.",
                history = "Escaped a coup and now swaggers through the capital with stolen sigils.",
                pointValue = 18
            ),
            Npc(
                name = "Archivist Lune",
                type = NpcType.Passive,
                description = "Houses lore and whispers about the next big battle.",
                history = "A historian whose quill doubles as a ceremonial dagger.",
                pointValue = 12
            ),
            Npc(
                name = "Sentinel Ward",
                type = NpcType.Subordinate,
                description = "A loyal aide supporting the local player's guard.",
                history = "Forged from a sacred automaton and bound to a single commander.",
                pointValue = 8
            )
        )
    }

    fun buildDemoTurnOrder(players: List<Player>, npcs: List<Npc>): MutableList<String>
    {
        return mutableListOf<String>().apply {
            addAll(players.map { it.name }.filter { it.isNotBlank() })
            addAll(npcs.sortedByDescending { it.type.ordinal }.map { it.name })
        }
    }

    fun buildDemoLocalPlayerResources(): MutableList<Resource>
    {
        return mutableListOf(
            Resource(
                name = "Volunteer Infantry Squad",
                type = ResourceType.Military,
                depletable = true,
                destructible = true,
                description = "Trained volunteers ready to delay enemy pushes.",
                abilities = "Sacrifice to earn a defensive bonus for the current round."
            ),
            Resource(
                name = "Diplomatic Envoy Team",
                type = ResourceType.Diplomatic,
                depletable = false,
                destructible = false,
                description = "Envoys crafting alliances to offset military losses.",
                abilities = "Temporarily lowers enemy influence in a contested territory."
            ),
            Resource(
                name = "Technomantic Beacon",
                type = ResourceType.Technological,
                depletable = true,
                destructible = false,
                description = "A beacon bridging rune magic and circuitry.",
                abilities = "Reveals one hidden territory and grants the next tech action +2."
            )
        )
    }

    fun buildDemoMapTiles(localPlayer: Player, otherPlayers: List<Player>, npcs: List<Npc>): MutableList<Territory>
    {
        val elderGod = npcs.first { it.type == NpcType.ElderGod }
        val nemesis = npcs.first { it.type == NpcType.Nemesis }
        val hostile = npcs.first { it.type == NpcType.Hostile }
        val active = npcs.first { it.type == NpcType.Active }
        val passive = npcs.first { it.type == NpcType.Passive }
        val subordinate = npcs.first { it.type == NpcType.Subordinate }

        val otherPlayerNames = otherPlayers.map { it.name }

        return mutableListOf(
            Territory(
                name = "Highpass Citadel",
                type = TerritoryType.Land,
                description = "Stone fortress overlooking the trade pass.",
                ruler = localPlayer.name,
                resource = Resource(
                    name = "Citadel Ward",
                    type = ResourceType.Military,
                    depletable = false,
                    destructible = true,
                    description = "Network of watchers and barricades.",
                    abilities = "Creates a defensive buffer that repels the first hostile incursion."
                )
            ),
            Territory(
                name = "Skyreach Monastery",
                type = TerritoryType.Land,
                description = "Sanctum of diplomatic sages and arcane scribes.",
                ruler = otherPlayerNames.getOrNull(0) ?: passive.name,
                resource = Resource(
                    name = "Harmony Scrolls",
                    type = ResourceType.Diplomatic,
                    depletable = false,
                    destructible = false,
                    description = "Encodes a sacred pact that softens enemy resolve.",
                    abilities = "Grants +2 diplomatic points to its owner."
                )
            ),
            Territory(
                name = "Ironshore Docks",
                type = TerritoryType.Land,
                description = "Busy shipyard turned staging ground.",
                ruler = otherPlayerNames.getOrNull(1) ?: hostile.name,
                resource = Resource(
                    name = "Shipwright's Cache",
                    type = ResourceType.Military,
                    depletable = true,
                    destructible = true,
                    description = "Stockpile of munitions and hull plating.",
                    abilities = "Adds +1 military point per turn while controlled."
                )
            ),
            Territory(
                name = "Obsidian Rift",
                type = TerritoryType.Land,
                description = "Blighted crack in the world where eldritch energies pulse.",
                ruler = elderGod.name
            ),
            Territory(
                name = "Nemesis Spire",
                type = TerritoryType.Land,
                description = "A jagged tower that radiates dread.",
                ruler = nemesis.name
            ),
            Territory(
                name = "Zephyr Fields",
                type = TerritoryType.Land,
                description = "Fields of windswept grass popular with active NPCs.",
                ruler = active.name
            )
        )
    }

    fun buildDemoWorld(): World
    {
        val localPlayer = buildDemoLocalPlayer()
        val otherPlayers = buildDemoOtherPlayers()
        val players = mutableListOf<Player>().apply {
            add(localPlayer)
            addAll(otherPlayers)
        }
        val npcs = buildDemoNpcs()
        val turnOrder = buildDemoTurnOrder(players, npcs)

        return World(
            name = "Demo: Vanguard Skirmish",
            storyScenario = "Test layout world to preview the stats widget.",
            points = 82,
            roundNumber = 3,
            mapTiles = buildDemoMapTiles(localPlayer, otherPlayers, npcs),
            activePlayers = players,
            npc = npcs,
            turnOrder = turnOrder
        )
    }
}