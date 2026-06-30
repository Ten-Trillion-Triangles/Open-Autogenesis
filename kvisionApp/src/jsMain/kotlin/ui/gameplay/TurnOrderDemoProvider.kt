package ui.gameplay

import org.ttt.autogenesis.network.TurnOrderAnnouncementData
import org.ttt.autogenesis.network.TurnOrderParticipant

object TurnOrderDemoProvider
{
    fun createDemoAnnouncement(): TurnOrderAnnouncementData
    {
        val demoWorld = DemoFixtures.buildDemoWorld()
        val turnOrder = demoWorld.turnOrder
        val participants = turnOrder.map { name ->
            val isPlayer = demoWorld.activePlayers.any { it.name == name }
            val npcType = demoWorld.npc.firstOrNull { it.name == name }?.type
            TurnOrderParticipant(
                name = name,
                isPlayer = isPlayer,
                npcType = npcType
            )
        }

        return TurnOrderAnnouncementData(
            roundNumber = demoWorld.roundNumber,
            participants = participants,
            firstActor = turnOrder.firstOrNull()
        )
    }
}
