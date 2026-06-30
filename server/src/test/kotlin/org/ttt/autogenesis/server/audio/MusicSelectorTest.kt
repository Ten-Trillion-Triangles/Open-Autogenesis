package org.ttt.autogenesis.server.audio

import enums.NpcType
import org.junit.Test
import org.ttt.autogenesis.audio.MusicCategory
import org.ttt.autogenesis.audio.MusicDecision
import org.ttt.autogenesis.audio.AudioObject
import org.ttt.autogenesis.server.audio.MusicSelector
import org.ttt.autogenesis.audio.MusicTrackCatalog
import org.ttt.autogenesis.audio.TurnContext
import structs.Npc
import structs.Player
import structs.Territory
import structs.World
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Unit tests for [MusicSelector]. Each rule of the music spec maps to one
 * or more tests; precedence is also pinned (rule 1 beats rule 2, which
 * beats rule 3, which beats rule 4).
 *
 * The selector takes a [kotlin.random.Random] so deterministic seeds pin
 * the random pick in the rule-4 branch.
 */
class MusicSelectorTest
{
    // ─── rule 1: first turn of the game ───────────────────────────────────

    @Test
    fun `first turn plays Initial Conditions regardless of actor type`()
    {
        // Even if the actor is a Nemesis, rule 1 wins.
        val ctx = TurnContext(
            actorName = "Xilaron",
            roundNumber = 1,
            turnOrderIndex = 0,
            isFirstTurn = true,
            actorIsNemesisOrElderGod = true,
            canWinInNext4Rounds = true,
            currentlyPlayingMusicIds = listOf("a", "b")
        )
        val decision = selector().selectForTurn(ctx)
        assertEquals(1, decision.toPlay.size)
        assertEquals("Initial Conditions wet 1", decision.toPlay[0].resourceName)
        assertEquals(MusicCategory.InitialConditions, MusicTrackCatalog.default.findByName(decision.toPlay[0].resourceName)?.category)
        assertEquals(listOf("a", "b"), decision.toFadeOut)
    }

    @Test
    fun `first turn also fades out when nothing is currently playing`()
    {
        val ctx = TurnContext(
            actorName = "Alice",
            roundNumber = 1,
            turnOrderIndex = 0,
            isFirstTurn = true,
            actorIsNemesisOrElderGod = false,
            canWinInNext4Rounds = false,
            currentlyPlayingMusicIds = emptyList()
        )
        val decision = selector().selectForTurn(ctx)
        assertEquals(1, decision.toPlay.size)
        assertEquals("Initial Conditions wet 1", decision.toPlay[0].resourceName)
        assertEquals(emptyList(), decision.toFadeOut)
    }

    // ─── rule 2: Nemesis / Elder God turn ─────────────────────────────────

    @Test
    fun `nemesis actor plays Nemesis track`()
    {
        val ctx = TurnContext(
            actorName = "Xilaron",
            roundNumber = 5,
            turnOrderIndex = 2,
            isFirstTurn = false,
            actorIsNemesisOrElderGod = true,
            canWinInNext4Rounds = false,
            currentlyPlayingMusicIds = listOf("old1")
        )
        val decision = selector().selectForTurn(ctx)
        assertEquals(1, decision.toPlay.size)
        assertEquals("Nemesis wet 1", decision.toPlay[0].resourceName)
        assertEquals(listOf("old1"), decision.toFadeOut)
    }

    @Test
    fun `elder god actor plays Nemesis track (same as nemesis)`()
    {
        // Per the user's clarification: there is no separate elder-god
        // music file. Both Nemesis and Elder God map to the same track.
        val ctx = TurnContext(
            actorName = "Eleuryiyidict",
            roundNumber = 5,
            turnOrderIndex = 2,
            isFirstTurn = false,
            actorIsNemesisOrElderGod = true,
            canWinInNext4Rounds = false,
            currentlyPlayingMusicIds = listOf("old1")
        )
        val decision = selector().selectForTurn(ctx)
        assertEquals(1, decision.toPlay.size)
        assertEquals("Nemesis wet 1", decision.toPlay[0].resourceName)
    }

    // ─── rule 3: someone can win in the next 4 rounds ─────────────────────

    @Test
    fun `player actor with canWinInNext4Rounds true plays Terminal Conditions`()
    {
        val ctx = TurnContext(
            actorName = "Alice",
            roundNumber = 12,
            turnOrderIndex = 1,
            isFirstTurn = false,
            actorIsNemesisOrElderGod = false,
            canWinInNext4Rounds = true,
            currentlyPlayingMusicIds = listOf("a", "b", "c")
        )
        val decision = selector().selectForTurn(ctx)
        assertEquals(1, decision.toPlay.size)
        assertEquals("Terminal Conditions wet 1", decision.toPlay[0].resourceName)
        assertEquals(listOf("a", "b", "c"), decision.toFadeOut)
    }

    // ─── rule 4: any other turn ───────────────────────────────────────────

    @Test
    fun `player actor with canWinInNext4Rounds false plays one of each layer`()
    {
        val ctx = TurnContext(
            actorName = "Alice",
            roundNumber = 3,
            turnOrderIndex = 1,
            isFirstTurn = false,
            actorIsNemesisOrElderGod = false,
            canWinInNext4Rounds = false,
            currentlyPlayingMusicIds = listOf("old1")
        )
        val decision = selector().selectForTurn(ctx)
        assertEquals(4, decision.toPlay.size, "rule 4 must play one drone + one melody + one rhythm + one harmony")
        val categories = decision.toPlay.map {
            MusicTrackCatalog.default.findByName(it.resourceName)?.category
        }
        assertEquals(
            setOf(MusicCategory.Drone, MusicCategory.Melody, MusicCategory.Rhythm, MusicCategory.Harmony),
            categories.toSet(),
            "rule 4 must cover all four layers exactly once"
        )
        assertEquals(listOf("old1"), decision.toFadeOut)
    }

    @Test
    fun `rule 4 random pick is deterministic under seeded Random`()
    {
        // Two selectors with the same seed must produce the same picks.
        val ctx = TurnContext(
            actorName = "Alice",
            roundNumber = 3,
            turnOrderIndex = 1,
            isFirstTurn = false,
            actorIsNemesisOrElderGod = false,
            canWinInNext4Rounds = false,
            currentlyPlayingMusicIds = emptyList()
        )
        val a = selector(random = Random(42)).selectForTurn(ctx).toPlay.map { it.resourceName }
        val b = selector(random = Random(42)).selectForTurn(ctx).toPlay.map { it.resourceName }
        assertEquals(a, b, "same seed must yield the same rule-4 picks")
    }

    @Test
    fun `rule 4 every pick comes from the catalog`()
    {
        val ctx = TurnContext(
            actorName = "Alice",
            roundNumber = 3,
            turnOrderIndex = 1,
            isFirstTurn = false,
            actorIsNemesisOrElderGod = false,
            canWinInNext4Rounds = false,
            currentlyPlayingMusicIds = emptyList()
        )
        repeat(20) { seed ->
            val decision = selector(random = Random(seed.toLong())).selectForTurn(ctx)
            for(obj in decision.toPlay)
            {
                assertNotNull(
                    MusicTrackCatalog.default.findByName(obj.resourceName),
                    "rule-4 pick '${obj.resourceName}' must be a catalog entry"
                )
            }
        }
    }

    // ─── precedence ───────────────────────────────────────────────────────

    @Test
    fun `rule 1 beats rule 2 when both could fire`()
    {
        val ctx = TurnContext(
            actorName = "Xilaron",
            roundNumber = 1,
            turnOrderIndex = 0,
            isFirstTurn = true,
            actorIsNemesisOrElderGod = true,
            canWinInNext4Rounds = true,
            currentlyPlayingMusicIds = emptyList()
        )
        val decision = selector().selectForTurn(ctx)
        assertEquals("Initial Conditions wet 1", decision.toPlay[0].resourceName)
    }

    @Test
    fun `rule 2 beats rule 3 when both could fire`()
    {
        val ctx = TurnContext(
            actorName = "Xilaron",
            roundNumber = 5,
            turnOrderIndex = 2,
            isFirstTurn = false,
            actorIsNemesisOrElderGod = true,
            canWinInNext4Rounds = true,
            currentlyPlayingMusicIds = emptyList()
        )
        val decision = selector().selectForTurn(ctx)
        assertEquals("Nemesis wet 1", decision.toPlay[0].resourceName)
    }

    // ─── AudioObject shape ────────────────────────────────────────────────

    @Test
    fun `produced AudioObjects target the Music channel with loop true and fade in`()
    {
        val ctx = TurnContext(
            actorName = "Alice",
            roundNumber = 1,
            turnOrderIndex = 0,
            isFirstTurn = true,
            actorIsNemesisOrElderGod = false,
            canWinInNext4Rounds = false,
            currentlyPlayingMusicIds = emptyList()
        )
        val decision = selector().selectForTurn(ctx)
        val obj = decision.toPlay[0]
        assertEquals("Music", obj.channelId, "music selector must target the Music channel")
        assertTrue(obj.loop, "music tracks must loop (so they survive between turn-decisions)")
        assertEquals(decision.fadeInDurationMs, obj.fadeInDurationMs, "fadeInDurationMs must be propagated to every AudioObject")
    }

    @Test
    fun `decision is fully data-driven, no side effects`()
    {
        // Two calls with the same context must produce equal decisions
        // (modulo rule-4 random picks — we use a seeded Random and check
        // structural equality of the wrapper).
        val ctx = TurnContext(
            actorName = "Alice",
            roundNumber = 3,
            turnOrderIndex = 1,
            isFirstTurn = false,
            actorIsNemesisOrElderGod = false,
            canWinInNext4Rounds = false,
            currentlyPlayingMusicIds = listOf("x")
        )
        val s = selector(random = Random(7))
        val first = s.selectForTurn(ctx)
        val second = s.selectForTurn(ctx)
        // toFadeOut is fully deterministic; toPlay is deterministic for non-rule-4
        // branches and seeded-deterministic for rule 4.
        assertEquals(first.toFadeOut, second.toFadeOut)
        assertEquals(first.fadeInDurationMs, second.fadeInDurationMs)
        assertEquals(first.fadeOutDurationMs, second.fadeOutDurationMs)
    }

    // ─── helpers ──────────────────────────────────────────────────────────

    private fun selector(random: Random = Random(0)) = MusicSelector(
        catalog = MusicTrackCatalog.default,
        random = random,
        fadeOutMs = 2000L,
        fadeInMs = 2000L
    )

    // ─── reselectRandomLayers: scenario-bound returns null ───────────────

    @Test
    fun `reselectRandomLayers returns null when previous decision was initial conditions (rule 1)`()
    {
        val initial = MusicTrackCatalog.default.initialConditions.first()
        val previous = MusicDecision(
            toPlay = listOf(
                AudioObject(
                    resourceName = initial.resourceName,
                    channelId = "Start",
                    fadeInDurationMs = 0
                )
            ),
            toFadeOut = emptyList(),
            fadeOutDurationMs = 2000L,
            fadeInDurationMs = 2000L
        )
        assertNull(selector().reselectRandomLayers(previous))
    }

    @Test
    fun `reselectRandomLayers returns null when previous decision was nemesis (rule 2)`()
    {
        val nemesis = MusicTrackCatalog.default.nemesis.first()
        val previous = MusicDecision(
            toPlay = listOf(
                AudioObject(
                    resourceName = nemesis.resourceName,
                    channelId = "Nemesis",
                    fadeInDurationMs = 0
                )
            ),
            toFadeOut = emptyList(),
            fadeOutDurationMs = 2000L,
            fadeInDurationMs = 2000L
        )
        assertNull(selector().reselectRandomLayers(previous))
    }

    @Test
    fun `reselectRandomLayers returns null when previous decision was terminal conditions (rule 3) (endgame guard)`()
    {
        val terminal = MusicTrackCatalog.default.terminalConditions.first()
        val previous = MusicDecision(
            toPlay = listOf(
                AudioObject(
                    resourceName = terminal.resourceName,
                    channelId = "End",
                    fadeInDurationMs = 0
                )
            ),
            toFadeOut = emptyList(),
            fadeOutDurationMs = 2000L,
            fadeInDurationMs = 2000L
        )
        assertNull(selector().reselectRandomLayers(previous))
    }

    @Test
    fun `reselectRandomLayers returns null when previous decision mixed one rule-4 track with a terminal track`()
    {
        val terminal = MusicTrackCatalog.default.terminalConditions.first()
        val drone = MusicTrackCatalog.default.drone.first()
        val previous = MusicDecision(
            toPlay = listOf(
                AudioObject(
                    resourceName = drone.resourceName,
                    channelId = "Drone",
                    fadeInDurationMs = 0
                ),
                AudioObject(
                    resourceName = terminal.resourceName,
                    channelId = "End",
                    fadeInDurationMs = 0
                )
            ),
            toFadeOut = emptyList(),
            fadeOutDurationMs = 2000L,
            fadeInDurationMs = 2000L
        )
        assertNull(selector().reselectRandomLayers(previous))
    }

    // ─── reselectRandomLayers: rule 4 happy path ──────────────────────────

    @Test
    fun `reselectRandomLayers returns fresh rule-4 decision when previous decision was rule 4`()
    {
        val drone = MusicTrackCatalog.default.drone.first()
        val melody = MusicTrackCatalog.default.melody.first()
        val rhythm = MusicTrackCatalog.default.rhythm.first()
        val harmony = MusicTrackCatalog.default.harmony.first()
        val previous = MusicDecision(
            toPlay = listOf(
                AudioObject(resourceName = drone.resourceName, channelId = "Drone"),
                AudioObject(resourceName = melody.resourceName, channelId = "Melody"),
                AudioObject(resourceName = rhythm.resourceName, channelId = "Rhythm"),
                AudioObject(resourceName = harmony.resourceName, channelId = "Harmony")
            ),
            toFadeOut = emptyList(),
            fadeOutDurationMs = 2000L,
            fadeInDurationMs = 2000L
        )
        val result = selector().reselectRandomLayers(previous)
        assertNotNull(result)
        assertEquals(4, result.toPlay.size, "reroll must produce one drone + one melody + one rhythm + one harmony")
        val categories = result.toPlay.map {
            MusicTrackCatalog.default.findByName(it.resourceName)?.category
        }
        assertEquals(
            setOf(MusicCategory.Drone, MusicCategory.Melody, MusicCategory.Rhythm, MusicCategory.Harmony),
            categories.toSet(),
            "reroll must cover all four random layers exactly once"
        )
    }

    @Test
    fun `reselectRandomLayers toFadeOut contains the previous toPlay ids`()
    {
        val drone = MusicTrackCatalog.default.drone.first()
        val melody = MusicTrackCatalog.default.melody.first()
        val rhythm = MusicTrackCatalog.default.rhythm.first()
        val harmony = MusicTrackCatalog.default.harmony.first()
        val previousIds = listOf("prev-drone", "prev-melody", "prev-rhythm", "prev-harmony")
        val previous = MusicDecision(
            toPlay = listOf(
                AudioObject(id = previousIds[0], resourceName = drone.resourceName, channelId = "Drone"),
                AudioObject(id = previousIds[1], resourceName = melody.resourceName, channelId = "Melody"),
                AudioObject(id = previousIds[2], resourceName = rhythm.resourceName, channelId = "Rhythm"),
                AudioObject(id = previousIds[3], resourceName = harmony.resourceName, channelId = "Harmony")
            ),
            toFadeOut = emptyList(),
            fadeOutDurationMs = 2000L,
            fadeInDurationMs = 2000L
        )
        val result = selector().reselectRandomLayers(previous)
        assertNotNull(result)
        assertEquals(previousIds, result.toFadeOut, "reroll's toFadeOut must be the previous toPlay ids in order")
    }

    @Test
    fun `reselectRandomLayers random pick is deterministic under seeded Random`()
    {
        val drone = MusicTrackCatalog.default.drone.first()
        val melody = MusicTrackCatalog.default.melody.first()
        val rhythm = MusicTrackCatalog.default.rhythm.first()
        val harmony = MusicTrackCatalog.default.harmony.first()
        val previous = MusicDecision(
            toPlay = listOf(
                AudioObject(resourceName = drone.resourceName, channelId = "Drone"),
                AudioObject(resourceName = melody.resourceName, channelId = "Melody"),
                AudioObject(resourceName = rhythm.resourceName, channelId = "Rhythm"),
                AudioObject(resourceName = harmony.resourceName, channelId = "Harmony")
            ),
            toFadeOut = emptyList(),
            fadeOutDurationMs = 2000L,
            fadeInDurationMs = 2000L
        )
        val a = selector(random = Random(7)).reselectRandomLayers(previous)
        val b = selector(random = Random(7)).reselectRandomLayers(previous)
        assertNotNull(a)
        assertNotNull(b)
        assertEquals(a.toPlay.map { it.resourceName }, b.toPlay.map { it.resourceName })
        assertEquals(a.toFadeOut, b.toFadeOut)
    }

    @Test
    fun `reselectRandomLayers preserves the configured fade durations`()
    {
        val drone = MusicTrackCatalog.default.drone.first()
        val melody = MusicTrackCatalog.default.melody.first()
        val rhythm = MusicTrackCatalog.default.rhythm.first()
        val harmony = MusicTrackCatalog.default.harmony.first()
        val previous = MusicDecision(
            toPlay = listOf(
                AudioObject(resourceName = drone.resourceName, channelId = "Drone"),
                AudioObject(resourceName = melody.resourceName, channelId = "Melody"),
                AudioObject(resourceName = rhythm.resourceName, channelId = "Rhythm"),
                AudioObject(resourceName = harmony.resourceName, channelId = "Harmony")
            ),
            toFadeOut = emptyList(),
            fadeOutDurationMs = 2000L,
            fadeInDurationMs = 2000L
        )
        val s = MusicSelector(
            catalog = MusicTrackCatalog.default,
            random = Random(0),
            fadeOutMs = 1234L,
            fadeInMs = 5678L
        )
        val result = s.reselectRandomLayers(previous)
        assertNotNull(result)
        assertEquals(1234L, result.fadeOutDurationMs)
        assertEquals(5678L, result.fadeInDurationMs)
    }

    // ─── rule 1: first turn also pre-picks a rule-4 batch in onTrackEnd ───

    @Test
    fun `first turn decision also includes a pre-picked random batch in onTrackEnd`()
    {
        val ctx = TurnContext(
            actorName = "Alice",
            roundNumber = 1,
            turnOrderIndex = 0,
            isFirstTurn = true,
            actorIsNemesisOrElderGod = false,
            canWinInNext4Rounds = false,
            currentlyPlayingMusicIds = emptyList()
        )
        val decision = selector().selectForTurn(ctx)
        // Rule 1 still plays the Initial Conditions track in toPlay.
        assertEquals(1, decision.toPlay.size)
        assertEquals("Initial Conditions wet 1", decision.toPlay[0].resourceName)
        // And now also attaches a pre-picked rule-4 batch in onTrackEnd
        // so the client can fade it in the moment the initial track ends.
        val pending = assertNotNull(
            decision.onTrackEnd,
            "first-turn decision must include a non-null onTrackEnd batch"
        )
        assertEquals(4, pending.size, "onTrackEnd batch must have one track per layer (drone/melody/rhythm/harmony)")
        // Each pending track must come from the correct layer so the
        // audio channels line up with the rule-4 fall-through behaviour.
        val pendingCategories = pending.map { obj ->
            MusicTrackCatalog.default.findByName(obj.resourceName)?.category
        }
        assertEquals(
            listOf(
                MusicCategory.Drone,
                MusicCategory.Melody,
                MusicCategory.Rhythm,
                MusicCategory.Harmony
            ),
            pendingCategories,
            "onTrackEnd must be ordered Drone, Melody, Rhythm, Harmony (same as rule 4)"
        )
    }

    @Test
    fun `first turn onTrackEnd uses the same picks rule 4 would have produced`()
    {
        // The server is source-of-truth for the batch, so two
        // independent calls with the same Random seed and a non-first
        // turn must produce the same four resource names as the first
        // turn's onTrackEnd.
        val firstCtx = TurnContext(
            actorName = "Alice",
            roundNumber = 1,
            turnOrderIndex = 0,
            isFirstTurn = true,
            actorIsNemesisOrElderGod = false,
            canWinInNext4Rounds = false,
            currentlyPlayingMusicIds = emptyList()
        )
        val firstDecision = selector().selectForTurn(firstCtx)
        val onTrackEndNames = assertNotNull(firstDecision.onTrackEnd).map { it.resourceName }

        val rule4Ctx = TurnContext(
            actorName = "Bob",
            roundNumber = 2,
            turnOrderIndex = 1,
            isFirstTurn = false,
            actorIsNemesisOrElderGod = false,
            canWinInNext4Rounds = false,
            currentlyPlayingMusicIds = emptyList()
        )
        val rule4Decision = selector().selectForTurn(rule4Ctx)
        val rule4Names = rule4Decision.toPlay.map { it.resourceName }

        assertEquals(rule4Names, onTrackEndNames, "onTrackEnd picks must match a parallel rule-4 pick under the same Random seed")
    }

    @Test
    fun `non-first turn decision does not attach an onTrackEnd batch`()
    {
        // Rule 2 (Nemesis / Elder God)
        val nemesisCtx = TurnContext(
            actorName = "Xilaron",
            roundNumber = 5,
            turnOrderIndex = 0,
            isFirstTurn = false,
            actorIsNemesisOrElderGod = true,
            canWinInNext4Rounds = false,
            currentlyPlayingMusicIds = emptyList()
        )
        assertNull(selector().selectForTurn(nemesisCtx).onTrackEnd, "rule 2 (nemesis) must not attach onTrackEnd")

        // Rule 3 (terminal conditions)
        val terminalCtx = TurnContext(
            actorName = "Alice",
            roundNumber = 8,
            turnOrderIndex = 0,
            isFirstTurn = false,
            actorIsNemesisOrElderGod = false,
            canWinInNext4Rounds = true,
            currentlyPlayingMusicIds = emptyList()
        )
        assertNull(selector().selectForTurn(terminalCtx).onTrackEnd, "rule 3 (terminal) must not attach onTrackEnd")

        // Rule 4 (any other turn)
        val rule4Ctx = TurnContext(
            actorName = "Bob",
            roundNumber = 9,
            turnOrderIndex = 0,
            isFirstTurn = false,
            actorIsNemesisOrElderGod = false,
            canWinInNext4Rounds = false,
            currentlyPlayingMusicIds = emptyList()
        )
        assertNull(selector().selectForTurn(rule4Ctx).onTrackEnd, "rule 4 (default layers) must not attach onTrackEnd")
    }
}
