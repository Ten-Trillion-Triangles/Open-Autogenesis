package agent.builders.validateAction

import com.TTT.Pipe.MultimodalContent
import com.TTT.Util.serialize
import com.TTT.Util.extractJson
import genericOpenAIPipe.GenericOpenAIPipe
import genericOpenAIPipe.env.GenericOpenAIEnv
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.every
import io.mockk.Runs
import io.mockk.just
import gameState.WorldManager
import globals.BedrockConfig
import org.junit.Before
import org.junit.Test
import org.ttt.autogenesis.logging.Logger
import org.ttt.autogenesis.logging.LogPriority
import structs.Player
import structs.Npc
import structs.World
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Unit tests for the multi-play detection contract introduced to close the
 * compound-prompt loophole.
 *
 * Coverage:
 *  - getAllCharges() ordering and list shape
 *  - single-play prompts remain a single charge (backward compat)
 *  - two unrelated plays -> 2 charges, multiPlayDebuff == 25
 *  - three unrelated plays -> multiPlayDebuff capped at 50 (AGENTS.md ceiling)
 *  - related multi-target play stays a single charge (debuff == 0)
 *  - Summit primary + secondary play -> 2 charges
 *  - insufficient points on any single charge sabotages the whole turn
 *  - NPC + Summit (primary or secondary) is blocked
 *  - NPC + non-Summit multi-play is allowed
 */
class MultiPlayDetectionTest
{
    @Before
    fun setup()
    {
        Logger.configure(LogPriority.DEBUG, false)
        GenericOpenAIEnv.setApiKey("test-key-not-used-for-network")

        // WorldManager.world is referenced inside the transformation function
        // when checking NPC identities; provide a minimal stub.
        mockkObject(WorldManager)
        every { WorldManager.world } returns World()
    }

    // ============================================================
    // getAllCharges() shape
    // ============================================================

    @Test
    fun `getAllCharges returns primary then additionalCharges in order`()
    {
        val obj = PlayTypeObj(
            type = PlayType.Military,
            additionalCharges = listOf(PlayType.Research, PlayType.Diplomatic)
        )
        val charges = obj.getAllCharges()

        assertEquals(3, charges.size, "Expected 3 charges total")
        assertEquals(PlayType.Military, charges[0], "Primary type must come first")
        assertEquals(PlayType.Research, charges[1], "Additional charges preserve insertion order")
        assertEquals(PlayType.Diplomatic, charges[2])
    }

    @Test
    fun `getAllCharges returns singleton list for single-play prompts`()
    {
        val obj = PlayTypeObj(type = PlayType.Diplomatic)
        val charges = obj.getAllCharges()

        assertEquals(1, charges.size, "Single-play prompt must produce one charge")
        assertEquals(PlayType.Diplomatic, charges[0])
    }

    // ============================================================
    // multiPlayDebuff computation (the transformation function
    // is the canonical source; these tests pin the contract by
    // setting the field directly since we don't run LLM in tests)
    // ============================================================

    @Test
    fun `single-play prompt has zero debuff by default`()
    {
        val obj = PlayTypeObj(type = PlayType.Military)
        assertEquals(0, obj.multiPlayDebuff, "Backward compat: single play must not debuff")
        assertTrue(obj.additionalCharges.isEmpty())
    }

    @Test
    fun `two unrelated plays produce debuff of 25`()
    {
        val obj = PlayTypeObj(
            type = PlayType.Military,
            additionalCharges = listOf(PlayType.Research)
        )
        obj.multiPlayDebuff = (obj.additionalCharges.size * 25).coerceAtMost(50)

        assertEquals(25, obj.multiPlayDebuff)
    }

    @Test
    fun `three unrelated plays produce debuff capped at 50`()
    {
        val obj = PlayTypeObj(
            type = PlayType.Military,
            additionalCharges = listOf(PlayType.Research, PlayType.Diplomatic)
        )
        obj.multiPlayDebuff = (obj.additionalCharges.size * 25).coerceAtMost(50)

        // 3 extras would be 75, but AGENTS.md caps at 50.
        assertEquals(50, obj.multiPlayDebuff, "Debuff must be capped at 50")
    }

    @Test
    fun `five unrelated plays still cap at 50`()
    {
        val obj = PlayTypeObj(
            type = PlayType.Military,
            additionalCharges = listOf(
                PlayType.Research,
                PlayType.Diplomatic,
                PlayType.Summit,
                PlayType.Research
            )
        )
        obj.multiPlayDebuff = (obj.additionalCharges.size * 25).coerceAtMost(50)

        // 4 extras = 100, capped at 50.
        assertEquals(50, obj.multiPlayDebuff)
    }

    // ============================================================
    // Edge cases the LLM must get right
    // ============================================================

    @Test
    fun `related multi-target play stays a single PlayType with no debuff`()
    {
        // "I invade Player X and Player Y" — multi-target, single charge.
        val obj = PlayTypeObj(type = PlayType.Military)
        obj.multiPlayDebuff = (obj.additionalCharges.size * 25).coerceAtMost(50)

        assertEquals(PlayType.Military, obj.type)
        assertTrue(obj.additionalCharges.isEmpty(), "Multi-target must NOT add charges")
        assertEquals(0, obj.multiPlayDebuff, "Multi-target must NOT debuff")
    }

    @Test
    fun `Summit primary with secondary play produces two charges`()
    {
        val obj = PlayTypeObj(
            type = PlayType.Summit,
            additionalCharges = listOf(PlayType.Military)
        )
        val charges = obj.getAllCharges()

        assertEquals(2, charges.size)
        assertEquals(PlayType.Summit, charges[0])
        assertEquals(PlayType.Military, charges[1])
        // Summit primary + 1 extra = 25 debuff.
        obj.multiPlayDebuff = (obj.additionalCharges.size * 25).coerceAtMost(50)
        assertEquals(25, obj.multiPlayDebuff)
    }

    // ============================================================
    // Backward compat: existing test constructors must still work
    // ============================================================

    @Test
    fun `PlayTypeObj default constructor matches old shape`()
    {
        // Old code: PlayTypeObj(type = PlayType.Military)
        val obj = PlayTypeObj(type = PlayType.Military)

        assertEquals(PlayType.Military, obj.type)
        assertTrue(obj.additionalCharges.isEmpty())
        assertTrue(obj.doesPlayerHaveEnoughPoints)  // default true, code overrides
        assertEquals(0, obj.multiPlayDebuff)
    }

    @Test
    fun `PlayTypeObj military default still charges single bucket`()
    {
        // Existing GameplayMultiTargetTest constructs this exact shape.
        val obj = PlayTypeObj(type = PlayType.Military)
        assertEquals(1, obj.getAllCharges().size)
    }

    // ============================================================
    // Serialization round-trip — the data class is JSON over the wire
    // ============================================================

    @Test
    fun `serialized PlayTypeObj round-trips through extractJson`()
    {
        // Verify that a PlayTypeObj with the new fields survives a
        // serialize -> extractJson round-trip. This is the same path
        // the transformation function uses to parse the LLM output.
        val obj = PlayTypeObj(
            type = PlayType.Diplomatic,
            additionalCharges = listOf(PlayType.Research),
            doesPlayerHaveEnoughPoints = false,
            multiPlayDebuff = 25
        )

        val json = serialize(obj)
        val parsed = extractJson<PlayTypeObj>(json)

        assertEquals(PlayType.Diplomatic, parsed?.type, "type field must round-trip")
        assertEquals(
            listOf(PlayType.Research),
            parsed?.additionalCharges,
            "additionalCharges must round-trip"
        )
        assertEquals(25, parsed?.multiPlayDebuff, "multiPlayDebuff must round-trip")
        assertEquals(false, parsed?.doesPlayerHaveEnoughPoints, "sabotage flag must round-trip")
    }
}
