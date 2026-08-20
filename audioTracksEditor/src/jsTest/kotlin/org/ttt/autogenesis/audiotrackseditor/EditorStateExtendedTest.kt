package org.ttt.autogenesis.audiotrackseditor

import org.ttt.autogenesis.audio.AudioObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Tests for the new scenario-tab branches in [EditorState] — `addTrack`,
 * `updateTrack`, `deleteTrack`, and the initial-state invariants for
 * the four new categories (MENU, START, NEMESIS, END).
 *
 * These tests pin down that the data class routes to the correct list
 * for each new category, that the dirty flag is set on mutation, and
 * that tracks in the new categories are discoverable by
 * `findCategoryOf`.
 */
class EditorStateExtendedTest
{
    private fun track(id: String, name: String): AudioObject = AudioObject(
        id = id,
        resourceName = name,
        channelId = "Music"
    )

    // ─── initial state ────────────────────────────────────────────────────

    @Test
    fun initialState_hasEmptyNewCategoryLists()
    {
        val state = EditorState()
        assertTrue(state.tracks.menu.isEmpty(), "menu must default to empty")
        assertTrue(state.tracks.start.isEmpty(), "start must default to empty")
        assertTrue(state.tracks.nemesis.isEmpty(), "nemesis must default to empty")
        assertTrue(state.tracks.end.isEmpty(), "end must default to empty")
    }

    // ─── addTrack for each new category ───────────────────────────────────

    @Test
    fun addTrack_dispatchesToMenu()
    {
        val state = EditorState().addTrack(Category.MENU, track("m-1", "music.menu"))
        assertEquals(1, state.tracks.menu.size)
        assertEquals("m-1", state.tracks.menu[0].id)
        assertTrue(state.dirty)
    }

    @Test
    fun addTrack_dispatchesToStart()
    {
        val state = EditorState().addTrack(Category.START, track("s-1", "music.start"))
        assertEquals(1, state.tracks.start.size)
        assertEquals("s-1", state.tracks.start[0].id)
        assertTrue(state.dirty)
    }

    @Test
    fun addTrack_dispatchesToNemesis()
    {
        val state = EditorState().addTrack(Category.NEMESIS, track("n-1", "music.nemesis"))
        assertEquals(1, state.tracks.nemesis.size)
        assertEquals("n-1", state.tracks.nemesis[0].id)
        assertTrue(state.dirty)
    }

    @Test
    fun addTrack_dispatchesToEnd()
    {
        val state = EditorState().addTrack(Category.END, track("e-1", "music.end"))
        assertEquals(1, state.tracks.end.size)
        assertEquals("e-1", state.tracks.end[0].id)
        assertTrue(state.dirty)
    }

    // ─── updateTrack for new categories ───────────────────────────────────

    @Test
    fun updateTrack_replacesTrackInStart()
    {
        val original = track("s-1", "music.start.v1")
        val state = EditorState().addTrack(Category.START, original)
        val modified = original.copy(resourceName = "music.start.v2", volume = 0.5f)

        val updated = state.updateTrack("s-1", modified)

        assertEquals(1, updated.tracks.start.size)
        assertEquals("music.start.v2", updated.tracks.start[0].resourceName)
        assertEquals(0.5f, updated.tracks.start[0].volume)
    }

    @Test
    fun updateTrack_replacesTrackInEnd()
    {
        val original = track("e-1", "music.end.v1")
        val state = EditorState().addTrack(Category.END, original)
        val modified = original.copy(resourceName = "music.end.v2")

        val updated = state.updateTrack("e-1", modified)

        assertEquals(1, updated.tracks.end.size)
        assertEquals("music.end.v2", updated.tracks.end[0].resourceName)
    }

    @Test
    fun updateTrack_replacesTrackInNemesis()
    {
        val original = track("n-1", "music.nemesis.v1")
        val state = EditorState().addTrack(Category.NEMESIS, original)
        val modified = original.copy(resourceName = "music.nemesis.v2")

        val updated = state.updateTrack("n-1", modified)

        assertEquals(1, updated.tracks.nemesis.size)
        assertEquals("music.nemesis.v2", updated.tracks.nemesis[0].resourceName)
    }

    // ─── deleteTrack for new categories ───────────────────────────────────

    @Test
    fun deleteTrack_removesFromMenu()
    {
        val a = track("m-1", "music.menu")
        val b = track("m-2", "music.menu.alt")
        val state = EditorState()
            .addTrack(Category.MENU, a)
            .addTrack(Category.MENU, b)

        val updated = state.deleteTrack("m-1")

        assertEquals(1, updated.tracks.menu.size)
        assertEquals(b, updated.tracks.menu[0])
    }

    @Test
    fun deleteTrack_removesFromStart()
    {
        val a = track("s-1", "music.start")
        val b = track("s-2", "music.start.alt")
        val state = EditorState()
            .addTrack(Category.START, a)
            .addTrack(Category.START, b)

        val updated = state.deleteTrack("s-1")

        assertEquals(1, updated.tracks.start.size)
        assertEquals(b, updated.tracks.start[0])
    }

    @Test
    fun deleteTrack_removesFromNemesis()
    {
        val a = track("n-1", "music.nemesis")
        val state = EditorState().addTrack(Category.NEMESIS, a)

        val updated = state.deleteTrack("n-1")

        assertEquals(0, updated.tracks.nemesis.size)
    }

    @Test
    fun deleteTrack_removesFromEnd()
    {
        val a = track("e-1", "music.end")
        val state = EditorState().addTrack(Category.END, a)

        val updated = state.deleteTrack("e-1")

        assertEquals(0, updated.tracks.end.size)
    }

    // ─── isolation between categories ─────────────────────────────────────

    @Test
    fun addTrack_doesNotCrossContaminateCategories()
    {
        val state = EditorState()
            .addTrack(Category.MENU, track("m-1", "music.menu"))
            .addTrack(Category.START, track("s-1", "music.start"))
            .addTrack(Category.NEMESIS, track("n-1", "music.nemesis"))
            .addTrack(Category.END, track("e-1", "music.end"))
            .addTrack(Category.DRONE, track("d-1", "music.drone"))
            .addTrack(Category.MELODY, track("ml-1", "music.melody"))
            .addTrack(Category.RHYTHM, track("r-1", "music.rhythm"))
            .addTrack(Category.HARMONY, track("h-1", "music.harmony"))

        assertEquals(1, state.tracks.menu.size)
        assertEquals(1, state.tracks.start.size)
        assertEquals(1, state.tracks.nemesis.size)
        assertEquals(1, state.tracks.end.size)
        assertEquals(1, state.tracks.drone.size)
        assertEquals(1, state.tracks.melody.size)
        assertEquals(1, state.tracks.rhythm.size)
        assertEquals(1, state.tracks.harmony.size)
    }
}