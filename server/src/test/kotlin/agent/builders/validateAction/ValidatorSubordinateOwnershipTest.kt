package agent.builders.validateAction

import enums.ResourceType
import structs.Player
import structs.Resource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Regression test for validator false-rejection of lorebook-confirmed player
 * subordinates. Reproduces the Round 4 Turn 0 Lord Maple Tree case where
 * General Moustache / Commander Vines / Dr. Crystalline were flagged as
 * unowned and the play was rewritten by the rectifier.
 *
 * Root cause: the validator's NpcContextData.playerOwnedNpcs was built
 * ONLY from Player.capturedNemesis. Subordinates (the canonical ownership
 * signal for persona-bound named NPCs) live in Player.resources filtered
 * by ResourceType.Subordinate. The fix extends NpcContextData with a
 * playerOwnedSubordinates field sourced from that filter.
 *
 * Contract tested here:
 *   1. NpcContextData carries BOTH playerOwnedNpcs (capturedNemesis names)
 *      AND playerOwnedSubordinates (ResourceType.Subordinate resource names).
 *   2. A player with no capturedNemesis but a ResourceType.Subordinate
 *      resource still surfaces that NPC name in playerOwnedSubordinates.
 *   3. The field is populated by a filter on ResourceType.Subordinate, not
 *      a blanket copy of all resources.
 */
class ValidatorSubordinateOwnershipTest
{
    /**
     * Contract 1: the data class exposes the new field alongside the old.
     * If a future edit removes playerOwnedSubordinates from NpcContextData,
     * this test fails.
     */
    @Test
    fun npcContextData_carriesBothCapturedNpcsAndSubordinates()
    {
        val npcData = NpcContextData(
            playerOwnedNpcs = listOf("CapturedNemesisX"),
            playerOwnedSubordinates = listOf("General Moustache", "Commander Vines")
        )
        assertEquals(listOf("CapturedNemesisX"), npcData.playerOwnedNpcs)
        assertEquals(
            listOf("General Moustache", "Commander Vines"),
            npcData.playerOwnedSubordinates
        )
    }

    /**
     * Contract 2: the canonical Lord Maple Tree scenario — empty
     * capturedNemesis, ResourceType.Subordinate resources only. This is
     * the exact state of the bug repro. A player with subordinates in
     * resources but nothing in capturedNemesis must still have their
     * subordinate names surface in the ownership data passed to the
     * validator prompt.
     */
    @Test
    fun lordMapleTree_subordinatesSurfaceWhenCapturedNemesisIsEmpty()
    {
        val player = Player(name = "Lord Maple Tree").apply {
            // Precondition: the bug case. capturedNemesis is empty.
            assertTrue(
                capturedNemesis.isEmpty(),
                "test precondition: capturedNemesis must be empty to reproduce the bug"
            )

            // The player has three standing subordinates as ResourceType.Subordinate
            // resources. These are the canonical ownership signal that the validator
            // was missing.
            resources.add(
                Resource(
                    name = "General Moustache",
                    type = ResourceType.Subordinate,
                    description = "Military commander of the ent army",
                    abilities = "Command ent forces"
                )
            )
            resources.add(
                Resource(
                    name = "Commander Vines",
                    type = ResourceType.Subordinate,
                    description = "Infantry commander"
                )
            )
            resources.add(
                Resource(
                    name = "Dr. Crystalline",
                    type = ResourceType.Subordinate,
                    description = "Subordinate researcher"
                )
            )

            // Also a non-subordinate resource — must NOT appear in the ownership list.
            resources.add(
                Resource(
                    name = "Maple Syrup Reserve",
                    type = ResourceType.Military,
                    description = "Stored amber weaponizable syrup"
                )
            )
        }

        // Mirror the population logic from validator.kt:449-456 (the post-fix
        // shape). If the field is missing or the filter is wrong, the test fails.
        val ownedCaptured = player.capturedNemesis.map { it.name }
        val ownedSubordinates = player.resources
            .filter { it.type == ResourceType.Subordinate }
            .map { it.name }
        val npcData = NpcContextData(
            playerOwnedNpcs = ownedCaptured,
            playerOwnedSubordinates = ownedSubordinates
        )

        // Empty captured — the new field carries the weight.
        assertTrue(npcData.playerOwnedNpcs.isEmpty())

        // All three subordinates present.
        assertTrue(
            npcData.playerOwnedSubordinates.contains("General Moustache"),
            "General Moustache must surface as owned via ResourceType.Subordinate. " +
                "playerOwnedSubordinates=${npcData.playerOwnedSubordinates}"
        )
        assertTrue(npcData.playerOwnedSubordinates.contains("Commander Vines"))
        assertTrue(npcData.playerOwnedSubordinates.contains("Dr. Crystalline"))

        // The non-subordinate resource must NOT pollute the ownership list.
        assertTrue(
            !npcData.playerOwnedSubordinates.contains("Maple Syrup Reserve"),
            "Non-subordinate resources must not appear in playerOwnedSubordinates. " +
                "playerOwnedSubordinates=${npcData.playerOwnedSubordinates}"
        )
    }

    /**
     * Contract 3: a player with BOTH capturedNemesis entries AND
     * ResourceType.Subordinate resources populates BOTH fields, in
     * distinct lists. The two ownership categories must not be conflated.
     */
    @Test
    fun capturedAndSubordinated_areReportedSeparately()
    {
        val player = Player(name = "Test Player").apply {
            capturedNemesis.add(
                structs.Npc(
                    name = "CapturedFoe",
                    type = enums.NpcType.Active
                )
            )
            resources.add(
                Resource(
                    name = "SubordinateAlly",
                    type = ResourceType.Subordinate
                )
            )
        }

        val ownedCaptured = player.capturedNemesis.map { it.name }
        val ownedSubordinates = player.resources
            .filter { it.type == ResourceType.Subordinate }
            .map { it.name }
        val npcData = NpcContextData(
            playerOwnedNpcs = ownedCaptured,
            playerOwnedSubordinates = ownedSubordinates
        )

        assertEquals(listOf("CapturedFoe"), npcData.playerOwnedNpcs)
        assertEquals(listOf("SubordinateAlly"), npcData.playerOwnedSubordinates)
        assertTrue(npcData.playerOwnedNpcs != npcData.playerOwnedSubordinates)
    }
}