package org.ttt.autogenesis.kvisionapp.audio

import org.ttt.autogenesis.audio.AudioObject
import org.ttt.autogenesis.audio.MusicDecision
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Contract for [MusicRunner].
 *
 * The runner owns the "currently playing music" set and translates a
 * [MusicDecision] into the right sequence of engine play/stop calls.
 * These tests use a recording fake [MusicEngineFacade] so we can
 * assert which play/stop calls the runner makes without spinning up
 * an AudioContext.
 *
 * The engine-level "did the audio actually play" assertion is out of
 * scope for unit tests — the engine is exercised in AudioEngineTest.
 */
class MusicRunnerTest
{
    /**
     * Recording stand-in for [MusicEngineFacade]. Records every play
     * and stop call so the test can assert against them.
     *
     * The [acceptedChannels] set controls [isMusicChannel]; defaults
     * to `setOf("Music")` so legacy tests that pass `channelId = "Music"`
     * continue to work. New tests that exercise the per-category
     * routing can pass an explicit set (e.g., `setOf("Drone", "Melody",
     * "Rhythm", "Harmony")`) to simulate the engine's filter.
     */
    private class FakeEngine(
        val acceptedChannels: Set<String> = setOf("Music")
    ) : MusicEngineFacade
    {
        val played: MutableList<AudioObject> = mutableListOf()
        val stopped: MutableList<Pair<String, Long>> = mutableListOf()
        val musicChannelChecks: MutableList<String> = mutableListOf()

        private var _onTrackEnd: ((String) -> Unit)? = null
        override var onTrackEnd: ((String) -> Unit)?
            get() = _onTrackEnd
            set(value) { _onTrackEnd = value }

        override fun play(obj: AudioObject)
        {
            played.add(obj)
        }

        override fun stop(id: String, fadeOutMs: Long)
        {
            stopped.add(id to fadeOutMs)
        }

        override fun isMusicChannel(channelId: String): Boolean
        {
            musicChannelChecks.add(channelId)
            return channelId in acceptedChannels
        }

        /** Simulate the engine firing its onTrackEnd callback. */
        fun fireOnTrackEnd(objectId: String)
        {
            _onTrackEnd?.invoke(objectId)
        }
    }

    // ─── first schedule ───────────────────────────────────────────────────

    @Test
    fun firstSchedule_startsWithNoFadeOutsAndPlaysAllTracks()
    {
        val fake = FakeEngine()
        val runner = MusicRunner(fake)
        val decision = MusicDecision(
            toPlay = listOf(
                AudioObject(id = "a", resourceName = "D-Track 1", channelId = "Music"),
                AudioObject(id = "b", resourceName = "Melody-Etnahta", channelId = "Music")
            ),
            toFadeOut = emptyList(),
            fadeOutDurationMs = 2000L
        )
        runner.playSchedule(decision)
        assertEquals(0, fake.stopped.size, "nothing to fade out on first schedule")
        assertEquals(2, fake.played.size)
        assertEquals(listOf("a", "b"), fake.played.map { it.id })
    }

    // ─── second schedule fades out the first ──────────────────────────────

    @Test
    fun secondSchedule_fadesOutTheFirstBeforePlayingNewOnes()
    {
        val fake = FakeEngine()
        val runner = MusicRunner(fake)
        val first = MusicDecision(
            toPlay = listOf(
                AudioObject(id = "a", resourceName = "D-Track 1", channelId = "Music"),
                AudioObject(id = "b", resourceName = "Melody-Etnahta", channelId = "Music")
            ),
            toFadeOut = emptyList(),
            fadeOutDurationMs = 2000L
        )
        val second = MusicDecision(
            toPlay = listOf(
                AudioObject(id = "c", resourceName = "R-Track 1", channelId = "Music")
            ),
            toFadeOut = listOf("a", "b"),
            fadeOutDurationMs = 2000L
        )
        runner.playSchedule(first)
        runner.playSchedule(second)
        // Fades use the second's fadeOutDurationMs.
        assertEquals(listOf("a" to 2000L, "b" to 2000L), fake.stopped)
        // New tracks are played.
        assertEquals(3, fake.played.size)
        assertEquals("c", fake.played[2].id)
    }

    // ─── third schedule is clean ──────────────────────────────────────────

    @Test
    fun thirdSchedule_doesNotReFadeOutTheSecond()
    {
        val fake = FakeEngine()
        val runner = MusicRunner(fake)
        runner.playSchedule(MusicDecision(
            toPlay = listOf(AudioObject(id = "a", resourceName = "D-Track 1", channelId = "Music")),
            toFadeOut = emptyList(),
            fadeOutDurationMs = 2000L
        ))
        runner.playSchedule(MusicDecision(
            toPlay = listOf(AudioObject(id = "b", resourceName = "R-Track 1", channelId = "Music")),
            toFadeOut = listOf("a"),
            fadeOutDurationMs = 2000L
        ))
        val stopCountAfterSecond = fake.stopped.size
        runner.playSchedule(MusicDecision(
            toPlay = listOf(AudioObject(id = "c", resourceName = "Harmony-1", channelId = "Music")),
            toFadeOut = listOf("b"),
            fadeOutDurationMs = 2000L
        ))
        // Only "b" should have been faded by the third schedule — "a" was
        // already faded by the second.
        assertEquals(stopCountAfterSecond + 1, fake.stopped.size)
        assertEquals("b", fake.stopped.last().first)
    }

    // ─── channel filter ───────────────────────────────────────────────────

    @Test
    fun runnerSkipsNonMusicChannelInToPlay()
    {
        val fake = FakeEngine()
        val runner = MusicRunner(fake)
        val decision = MusicDecision(
            toPlay = listOf(
                AudioObject(id = "m1", resourceName = "D-Track 1", channelId = "Music"),
                // Defensive: the server is supposed to only put Music
                // channel objects in the decision, but if it ever emits
                // a non-Music object the runner must not play it through
                // the music pipeline.
                AudioObject(id = "s1", resourceName = "sfx.click", channelId = "Sfx")
            ),
            toFadeOut = emptyList(),
            fadeOutDurationMs = 2000L
        )
        runner.playSchedule(decision)
        assertEquals(1, fake.played.size, "runner must only schedule Music-channel objects")
        assertEquals("m1", fake.played[0].id)
    }

    // ─── graceful skip on missing resource ────────────────────────────────

    @Test
    fun unknownResourceNameIsLoggedAndSkipped()
    {
        val fake = FakeEngine()
        val runner = MusicRunner(fake)
        val decision = MusicDecision(
            toPlay = listOf(
                AudioObject(id = "good", resourceName = "D-Track 1", channelId = "Music"),
                AudioObject(id = "bad", resourceName = "completely unknown xyzzy", channelId = "Music")
            ),
            toFadeOut = emptyList(),
            fadeOutDurationMs = 2000L
        )
        // Must not throw. Must not call play for the unknown id.
        runner.playSchedule(decision)
        assertEquals(1, fake.played.size, "only the resolvable track is played")
        assertEquals("good", fake.played[0].id)
    }

    // ─── fade duration propagation ────────────────────────────────────────

    @Test
    fun fadeOutDuration_usesDecisionValue()
    {
        val fake = FakeEngine()
        val runner = MusicRunner(fake)
        runner.playSchedule(MusicDecision(
            toPlay = listOf(AudioObject(id = "a", resourceName = "D-Track 1", channelId = "Music")),
            toFadeOut = emptyList(),
            fadeOutDurationMs = 2000L
        ))
        runner.playSchedule(MusicDecision(
            toPlay = listOf(AudioObject(id = "b", resourceName = "R-Track 1", channelId = "Music")),
            toFadeOut = listOf("a"),
            fadeOutDurationMs = 5000L
        ))
        assertEquals(5000L, fake.stopped.single().second)
    }

    // ─── resource name is rewritten to the resolved path ──────────────────

    @Test
    fun resourceNameIsRewrittenToResolvedPathBeforePlay()
    {
        val fake = FakeEngine()
        val runner = MusicRunner(fake)
        runner.playSchedule(MusicDecision(
            toPlay = listOf(AudioObject(id = "a", resourceName = "D-Track 1", channelId = "Music")),
            toFadeOut = emptyList(),
            fadeOutDurationMs = 2000L
        ))
        // The catalog name "D-Track 1" must be replaced with the
        // webpack import path before play.
        val played = fake.played.single()
        assertTrue(
            played.resourceName.startsWith("audio/music/"),
            "resourceName must be rewritten to webpack path, got: ${played.resourceName}"
        )
    }

    // ─── empty schedule is a no-op ────────────────────────────────────────

    @Test
    fun emptySchedule_doesNothing()
    {
        val fake = FakeEngine()
        val runner = MusicRunner(fake)
        runner.playSchedule(MusicDecision(
            toPlay = emptyList(),
            toFadeOut = emptyList(),
            fadeOutDurationMs = 2000L
        ))
        assertEquals(0, fake.played.size)
        assertEquals(0, fake.stopped.size)
    }

    // ─── playMenu / stopMenu ──────────────────────────────────────────────

    @Test
    fun playMenu_playsMenuTrackAndResolvesResourceName()
    {
        val fake = FakeEngine()
        val runner = MusicRunner(fake)
        val menuObj = AudioObject(
            id = "menu-1",
            resourceName = "Xilaron and Eleuryiyidict wet final",
            channelId = "Music"
        )
        val id = runner.playMenu(menuObj)
        assertEquals("menu-1", id)
        assertEquals(1, fake.played.size)
        val played = fake.played.single()
        assertEquals("menu-1", played.id)
        // The catalog name must be replaced with the webpack path.
        assertTrue(
            played.resourceName.startsWith("audio/music/"),
            "menu resourceName must be rewritten to webpack path, got: ${played.resourceName}"
        )
        assertTrue(
            played.resourceName.endsWith(".mp3"),
            "menu path must end in .mp3, got: ${played.resourceName}"
        )
    }

    @Test
    fun playMenu_rejectsNonMusicChannel()
    {
        val fake = FakeEngine()
        val runner = MusicRunner(fake)
        val bad = AudioObject(
            id = "sfx-1",
            resourceName = "sfx.click",
            channelId = "Sfx"
        )
        val id = runner.playMenu(bad)
        assertEquals(null, id)
        assertEquals(0, fake.played.size)
    }

    @Test
    fun stopMenu_fadesOutTheTrackedIdAndIsANoOpForUnknownIds()
    {
        val fake = FakeEngine()
        val runner = MusicRunner(fake)
        val menuObj = AudioObject(
            id = "menu-1",
            resourceName = "Xilaron and Eleuryiyidict wet final",
            channelId = "Music"
        )
        runner.playMenu(menuObj)
        runner.stopMenu("menu-1", fadeOutMs = 1500L)
        assertEquals(listOf("menu-1" to 1500L), fake.stopped)
        // Second stop is a no-op because the id was already removed.
        runner.stopMenu("menu-1", fadeOutMs = 0L)
        assertEquals(1, fake.stopped.size)
        // Unknown id is also a no-op.
        runner.stopMenu("does-not-exist", fadeOutMs = 0L)
        assertEquals(1, fake.stopped.size)
    }

    @Test
    fun playSchedule_crossFadesOutTheMenuTrack()
    {
        val fake = FakeEngine()
        val runner = MusicRunner(fake)
        // 1. Menu plays.
        runner.playMenu(AudioObject(
            id = "menu-1",
            resourceName = "Xilaron and Eleuryiyidict wet final",
            channelId = "Music"
        ))
        assertEquals(1, fake.played.size)
        assertEquals(0, fake.stopped.size)
        // 2. First turn arrives — the runner must fade out the menu
        //    track and play the new decision's tracks.
        val decision = MusicDecision(
            toPlay = listOf(AudioObject(id = "a", resourceName = "D-Track 1", channelId = "Music")),
            toFadeOut = emptyList(),
            fadeOutDurationMs = 2000L
        )
        runner.playSchedule(decision)
        // Menu was faded out with the decision's fade-out duration.
        assertEquals(listOf("menu-1" to 2000L), fake.stopped)
        // Menu id is no longer tracked; the new track is.
        assertEquals(2, fake.played.size)
        // A subsequent playMenu replaces the gameplay track.
        runner.playMenu(AudioObject(
            id = "menu-2",
            resourceName = "Xilaron and Eleuryiyidict wet final",
            channelId = "Music"
        ))
        assertEquals(3, fake.played.size)
        // menu-1 stays in stopped; the gameplay "a" is not faded out
        // explicitly by playMenu (it has its own zero-fade stop).
        assertTrue(fake.stopped.any { it.first == "a" })
    }
    // ─── per-category channel routing (post channel-tree refactor) ───────
    //
    // The audio system's tree has eight music-category channels
    // (Drone, Melody, Rhythm, Harmony, Menu, Start, Nemesis, End)
    // sitting under a "Music" master, plus an independent Sfx
    // channel. The runner's filter used to require channelId == "Music"
    // exactly, which silently dropped any track routed through a
    // category channel. The new filter consults
    // [MusicEngineFacade.isMusicChannel], which knows about the tree.

    @Test
    fun playSchedule_acceptsEveryMusicCategoryChannel()
    {
        val fake = FakeEngine(acceptedChannels = setOf(
            "Drone", "Melody", "Rhythm", "Harmony",
            "Menu", "Start", "Nemesis", "End"
        ))
        val runner = MusicRunner(fake)
        val decision = MusicDecision(
            toPlay = listOf(
                AudioObject(id = "d1", resourceName = "D-Track 1",   channelId = "Drone"),
                AudioObject(id = "m1", resourceName = "Melody-Tippeha", channelId = "Melody"),
                AudioObject(id = "r1", resourceName = "R-Track 1",   channelId = "Rhythm"),
                AudioObject(id = "h1", resourceName = "Harmony-E",   channelId = "Harmony"),
                AudioObject(id = "menu", resourceName = "Xilaron and Eleuryiyidict wet final", channelId = "Menu"),
                AudioObject(id = "start", resourceName = "Initial Conditions wet 1", channelId = "Start"),
                AudioObject(id = "nemesis", resourceName = "Nemesis wet 1", channelId = "Nemesis"),
                AudioObject(id = "end", resourceName = "Terminal Conditions wet 1", channelId = "End")
            ),
            toFadeOut = emptyList(),
            fadeOutDurationMs = 0L
        )
        runner.playSchedule(decision)
        assertEquals(8, fake.played.size, "every music-category object should pass the filter")
        assertEquals(
            setOf("d1", "m1", "r1", "h1", "menu", "start", "nemesis", "end"),
            fake.played.map { it.id }.toSet()
        )
    }

    @Test
    fun playSchedule_rejectsSfxChannelObject()
    {
        val fake = FakeEngine(acceptedChannels = setOf("Music", "Melody"))
        val runner = MusicRunner(fake)
        val decision = MusicDecision(
            toPlay = listOf(
                AudioObject(id = "good", resourceName = "D-Track 1",   channelId = "Melody"),
                AudioObject(id = "bad",  resourceName = "sfx.click",   channelId = "Sfx")
            ),
            toFadeOut = emptyList(),
            fadeOutDurationMs = 0L
        )
        runner.playSchedule(decision)
        assertEquals(1, fake.played.size, "only the music-channel object should play")
        assertEquals("good", fake.played[0].id)
        // The engine saw the filter check for both channel ids.
        assertTrue("Sfx" in fake.musicChannelChecks, "Sfx object should have been checked against the music filter")
        assertTrue("Melody" in fake.musicChannelChecks, "Melody object should have been checked against the music filter")
    }

    @Test
    fun playSchedule_rejectsUnknownChannelId()
    {
        val fake = FakeEngine(acceptedChannels = setOf("Music"))
        val runner = MusicRunner(fake)
        val decision = MusicDecision(
            toPlay = listOf(
                AudioObject(id = "x", resourceName = "D-Track 1", channelId = "Bogus")
            ),
            toFadeOut = emptyList(),
            fadeOutDurationMs = 0L
        )
        runner.playSchedule(decision)
        assertEquals(0, fake.played.size, "unknown channel id should be rejected by the filter")
    }

    @Test
    fun playMenu_acceptsPerCategoryChannel()
    {
        val fake = FakeEngine(acceptedChannels = setOf("Menu"))
        val runner = MusicRunner(fake)
        val id = runner.playMenu(AudioObject(
            id = "menu-x",
            resourceName = "Xilaron and Eleuryiyidict wet final",
            channelId = "Menu"
        ))
        assertEquals("menu-x", id)
        assertEquals(1, fake.played.size)
    }

    @Test
    fun playMenu_rejectsSfxChannel()
    {
        val fake = FakeEngine(acceptedChannels = setOf("Music"))
        val runner = MusicRunner(fake)
        val id = runner.playMenu(AudioObject(
            id = "menu-x",
            resourceName = "Xilaron and Eleuryiyidict wet final",
            channelId = "Sfx"
        ))
        assertEquals(null, id, "Sfx is not a music channel, playMenu should refuse it")
        assertEquals(0, fake.played.size)
    }

    // ─── [Bug fix — music transitions] toFadeOut honored alongside local ─
    //
    // Pre-fix the runner only faded out ids it personally tracked in
    // `currentMusicIds`. The server's authoritative `decision.toFadeOut`
    // list was ignored. This left ids that the server had scheduled but
    // the runner did not know about (e.g. an `audio.schedulePlay` that
    // landed on a Music channel via a different code path, or a stale
    // server-side `playingObjects` entry from before a late-join)
    // playing through every transition. The fix unions the two sources.

    @Test
    fun playSchedule_fadesOutServerToFadeOutEvenWhenLocalIsEmpty()
    {
        // Server says "fade out X and Y" but the runner hasn't started
        // anything itself (e.g. the client just received a late-join
        // sync from the server). The fade-out must still happen.
        val fake = FakeEngine()
        val runner = MusicRunner(fake)
        val decision = MusicDecision(
            toPlay = listOf(AudioObject(id = "c", resourceName = "D-Track 1", channelId = "Music")),
            toFadeOut = listOf("x", "y"),
            fadeOutDurationMs = 2000L
        )
        runner.playSchedule(decision)
        assertEquals(
            setOf("x" to 2000L, "y" to 2000L),
            fake.stopped.toSet(),
            "server-provided toFadeOut ids must be faded out even when runner had no local ids"
        )
    }

    @Test
    fun playSchedule_fadesOutLocalMenuWhenServerToFadeOutIsEmpty()
    {
        // The runner has a menu track in `currentMusicIds` but the
        // server doesn't know about it (the menu music is client-side
        // only — the server's playingObjects never had it). The fade-out
        // must still happen so the next game's music cross-fades out
        // of the menu cleanly.
        val fake = FakeEngine()
        val runner = MusicRunner(fake)
        runner.playMenu(AudioObject(
            id = "menu-1",
            resourceName = "Xilaron and Eleuryiyidict wet final",
            channelId = "Music"
        ))
        val decision = MusicDecision(
            toPlay = listOf(AudioObject(id = "a", resourceName = "D-Track 1", channelId = "Music")),
            toFadeOut = emptyList(),
            fadeOutDurationMs = 2000L
        )
        runner.playSchedule(decision)
        assertEquals(
            listOf("menu-1" to 2000L),
            fake.stopped,
            "locally-tracked menu id must be faded out even when server's toFadeOut is empty"
        )
    }

    @Test
    fun playSchedule_dedupesUnionOfLocalAndServerToFadeOut()
    {
        // Both sides know about "a" (the local set and the server list).
        // The engine must see exactly one stop call for "a".
        val fake = FakeEngine()
        val runner = MusicRunner(fake)
        runner.playSchedule(MusicDecision(
            toPlay = listOf(AudioObject(id = "a", resourceName = "D-Track 1", channelId = "Music")),
            toFadeOut = emptyList(),
            fadeOutDurationMs = 2000L
        ))
        runner.playSchedule(MusicDecision(
            toPlay = listOf(AudioObject(id = "b", resourceName = "R-Track 1", channelId = "Music")),
            toFadeOut = listOf("a"),
            fadeOutDurationMs = 2000L
        ))
        assertEquals(1, fake.stopped.size, "shared id must be deduped to a single stop call")
        assertEquals("a", fake.stopped.single().first)
    }

    @Test
    fun playSchedule_fadesOutUnionWhenLocalAndServerDiffer()
    {
        // The local set has "a" + "b" + "menu" (menu is the only
        // id the server doesn't know about). The server's toFadeOut is
        // ["a", "b", "c"] (server has a "c" the local set lost
        // track of, presumably from a missed play call earlier). The
        // union is {a, b, c, menu} and all four must be faded out.
        val fake = FakeEngine()
        val runner = MusicRunner(fake)
        runner.playMenu(AudioObject(
            id = "menu",
            resourceName = "Xilaron and Eleuryiyidict wet final",
            channelId = "Music"
        ))
        // After playMenu, currentMusicIds = {menu}. Add "a" + "b"
        // by scheduling a music decision that starts them.
        runner.playSchedule(MusicDecision(
            toPlay = listOf(
                AudioObject(id = "a", resourceName = "D-Track 1", channelId = "Music"),
                AudioObject(id = "b", resourceName = "R-Track 1", channelId = "Music")
            ),
            toFadeOut = emptyList(),
            fadeOutDurationMs = 2000L
        ))
        // At this point currentMusicIds = {a, b} (the menu was stopped
        // by playMenu via the menu-→-game cross-fade path). Now the
        // server sends a new decision that the runner doesn't recognize
        // "c" for. Local = {a, b}, server = {a, b, c} -> union = {a, b, c}.
        runner.playSchedule(MusicDecision(
            toPlay = listOf(AudioObject(id = "d", resourceName = "R-Track 1", channelId = "Music")),
            toFadeOut = listOf("a", "b", "c"),
            fadeOutDurationMs = 1500L
        ))
        val stoppedIds = fake.stopped.map { it.first }.toSet()
        assertTrue("a" in stoppedIds, "local id 'a' must be faded")
        assertTrue("b" in stoppedIds, "local id 'b' must be faded")
        assertTrue("c" in stoppedIds, "server-only id 'c' must be faded")
    }

    // ─── onTrackEnd: pre-picked "play after initial conditions ends" batch ─

    @Test
    fun `playSchedule with onTrackEnd plays the initial track and arms the pending batch`()
    {
        val fake = FakeEngine()
        val runner = MusicRunner(fake)
        val initial = AudioObject(
            id = "init-1",
            resourceName = "Initial Conditions wet 1",
            channelId = "Music"
        )
        val pending = listOf(
            AudioObject(id = "d-1", resourceName = "D-Track 1", channelId = "Music"),
            AudioObject(id = "m-1", resourceName = "Melody-Etnahta", channelId = "Music"),
            AudioObject(id = "r-1", resourceName = "R-Track 1", channelId = "Music"),
            AudioObject(id = "h-1", resourceName = "Harmony-1", channelId = "Music")
        )
        runner.playSchedule(MusicDecision(
            toPlay = listOf(initial),
            toFadeOut = emptyList(),
            fadeOutDurationMs = 2000L,
            onTrackEnd = pending
        ))
        // Initial track is started. Compare by id (preserved through
        // MusicResourceResolver resolution) — the resourceName gets
        // rewritten to the manifest path.
        assertEquals(listOf(initial.id), fake.played.map { it.id })
        // Pending state is armed: when the engine fires onTrackEnd for
        // the initial track, the runner should apply the pending batch.
        fake.fireOnTrackEnd(initial.id)
        assertEquals(
            listOf(initial.id) + pending.map { it.id },
            fake.played.map { it.id }
        )
    }

    @Test
    fun `engine onTrackEnd for a non-pending id is ignored`()
    {
        val fake = FakeEngine()
        val runner = MusicRunner(fake)
        val initial = AudioObject(
            id = "init-1",
            resourceName = "Initial Conditions wet 1",
            channelId = "Music"
        )
        val pending = listOf(
            AudioObject(id = "d-1", resourceName = "D-Track 1", channelId = "Music"),
            AudioObject(id = "m-1", resourceName = "Melody-Etnahta", channelId = "Music"),
            AudioObject(id = "r-1", resourceName = "R-Track 1", channelId = "Music"),
            AudioObject(id = "h-1", resourceName = "Harmony-1", channelId = "Music")
        )
        runner.playSchedule(MusicDecision(
            toPlay = listOf(initial),
            toFadeOut = emptyList(),
            fadeOutDurationMs = 2000L,
            onTrackEnd = pending
        ))
        // Engine fires onTrackEnd for a different id (not the pending key).
        fake.fireOnTrackEnd("some-other-id")
        // Only the initial track was played — the pending batch was NOT triggered.
        assertEquals(listOf(initial.id), fake.played.map { it.id })
    }

    @Test
    fun `playSchedule replaces the pending batch`()
    {
        val fake = FakeEngine()
        val runner = MusicRunner(fake)
        val initial = AudioObject(
            id = "init-1",
            resourceName = "Initial Conditions wet 1",
            channelId = "Music"
        )
        val firstPending = listOf(
            AudioObject(id = "d-1", resourceName = "D-Track 1", channelId = "Music"),
            AudioObject(id = "m-1", resourceName = "Melody-Etnahta", channelId = "Music"),
            AudioObject(id = "r-1", resourceName = "R-Track 1", channelId = "Music"),
            AudioObject(id = "h-1", resourceName = "Harmony-1", channelId = "Music")
        )
        val next = AudioObject(id = "next-1", resourceName = "D-Track 2", channelId = "Music")
        val secondPending = listOf(
            AudioObject(id = "d-2", resourceName = "D-Track 3", channelId = "Music"),
            AudioObject(id = "m-2", resourceName = "Melody-Siluk", channelId = "Music"),
            AudioObject(id = "r-2", resourceName = "R-Track 4", channelId = "Music"),
            AudioObject(id = "h-2", resourceName = "Harmony-Y", channelId = "Music")
        )
        // First turn: play initial, arm firstPending.
        runner.playSchedule(MusicDecision(
            toPlay = listOf(initial),
            toFadeOut = emptyList(),
            fadeOutDurationMs = 2000L,
            onTrackEnd = firstPending
        ))
        // Second turn arrives BEFORE the initial track ends: play next, replace pending.
        runner.playSchedule(MusicDecision(
            toPlay = listOf(next),
            toFadeOut = listOf(initial.id),
            fadeOutDurationMs = 2000L,
            onTrackEnd = secondPending
        ))
        // Simulate the initial track ending — the pending batch was replaced
        // by the second decision, so the first pending batch must NOT fire.
        fake.fireOnTrackEnd(initial.id)
        val playedIdsAfterInitial = fake.played.map { it.id }.toSet()
        for (obj in firstPending) {
            assertTrue(
                obj.id !in playedIdsAfterInitial,
                "first pending batch's id='${obj.id}' must not be applied after the pending was replaced"
            )
        }
        // Now simulate the 'next' track ending — the second pending batch applies.
        // Capture the post-apply state of fake.played (not the stale
        // snapshot from before the second fireOnTrackEnd).
        fake.fireOnTrackEnd(next.id)
        val playedIdsAfterNext = fake.played.map { it.id }.toSet()
        for (obj in secondPending) {
            assertTrue(
                obj.id in playedIdsAfterNext,
                "second pending batch's id='${obj.id}' must be applied after 'next' ends"
            )
        }
    }

    @Test
    fun `playSchedule with onTrackEnd also stops the previous music as before`()
    {
        // The new onTrackEnd path must NOT regress the existing union
        // fade-out behavior: when a new playSchedule arrives, the union
        // of (locally tracked ids) and (server's toFadeOut) is still
        // faded out before the new track starts.
        val fake = FakeEngine()
        val runner = MusicRunner(fake)
        // First schedule: some prior music.
        val prior = AudioObject(id = "prior-1", resourceName = "D-Track 1", channelId = "Music")
        runner.playSchedule(MusicDecision(
            toPlay = listOf(prior),
            toFadeOut = emptyList(),
            fadeOutDurationMs = 2000L
        ))
        // Second schedule: initial conditions track, with a pending batch.
        val initial = AudioObject(
            id = "init-1",
            resourceName = "Initial Conditions wet 1",
            channelId = "Music"
        )
        runner.playSchedule(MusicDecision(
            toPlay = listOf(initial),
            toFadeOut = emptyList(),
            fadeOutDurationMs = 1500L,
            onTrackEnd = listOf(
                AudioObject(id = "d-1", resourceName = "D-Track 2", channelId = "Music"),
                AudioObject(id = "m-1", resourceName = "Melody-Tippeha", channelId = "Music"),
                AudioObject(id = "r-1", resourceName = "R-Track 3", channelId = "Music"),
                AudioObject(id = "h-1", resourceName = "Harmony-3", channelId = "Music")
            )
        ))
        // The prior track must have been faded out with the SECOND decision's
        // fadeOutDurationMs (the runner uses the incoming decision's fade time).
        val stoppedForPrior = fake.stopped.find { it.first == prior.id }
        assertNotNull(stoppedForPrior, "prior track 'prior-1' must be faded out by the new playSchedule")
        assertEquals(1500L, stoppedForPrior.second, "fade-out duration must come from the incoming decision")
    }
}
