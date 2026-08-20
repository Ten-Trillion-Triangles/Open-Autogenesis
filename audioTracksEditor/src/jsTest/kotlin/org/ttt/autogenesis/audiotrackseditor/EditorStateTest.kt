package org.ttt.autogenesis.audiotrackseditor

import kotlinx.serialization.json.Json
import org.ttt.autogenesis.audio.AudioObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Unit tests for the [EditorState] data class — the pure-functional core of
 * the audio tracks editor UI. Each test pins down one invariant so that the
 * DOM layer in a later phase can rely on the state class's behaviour without
 * needing to re-derive the rules from the implementation.
 */
class EditorStateTest
{
    private fun sampleTrack(id: String = "test-id"): AudioObject = AudioObject(
        id = id,
        resourceName = "test.resource",
        channelId = "Music",
        volume = 0.8f,
        panning = 0.0f,
        speed = 1.0f,
        loop = false
    )

    @Test
    fun initialStateIsEmpty()
    {
        val state = EditorState()

        assertTrue(state.tracks.drone.isEmpty())
        assertTrue(state.tracks.melody.isEmpty())
        assertTrue(state.tracks.rhythm.isEmpty())
        assertTrue(state.tracks.harmony.isEmpty())
        assertEquals(Category.DRONE, state.selectedCategory)
        assertNull(state.modalState)
        assertFalse(state.dirty)
        assertNull(state.lastError)
    }

    @Test
    fun addTrackAppendsToCategoryListAndSetsDirty()
    {
        val empty = EditorState()
        val sample = sampleTrack("add-1")

        val updated = empty.addTrack(Category.DRONE, sample)

        assertEquals(1, updated.tracks.drone.size)
        assertEquals(sample, updated.tracks.drone[0])
        assertTrue(updated.dirty)
        assertTrue(updated.tracks.melody.isEmpty())
    }

    @Test
    fun addTrackDispatchesToCorrectCategory()
    {
        val empty = EditorState()
        val drone = sampleTrack("d-1")
        val melody = sampleTrack("m-1")
        val rhythm = sampleTrack("r-1")
        val harmony = sampleTrack("h-1")

        val withDrone = empty.addTrack(Category.DRONE, drone)
        val withMelody = withDrone.addTrack(Category.MELODY, melody)
        val withRhythm = withMelody.addTrack(Category.RHYTHM, rhythm)
        val withHarmony = withRhythm.addTrack(Category.HARMONY, harmony)

        assertEquals(1, withHarmony.tracks.drone.size)
        assertEquals(1, withHarmony.tracks.melody.size)
        assertEquals(1, withHarmony.tracks.rhythm.size)
        assertEquals(1, withHarmony.tracks.harmony.size)
    }

    @Test
    fun updateTrackReplacesById()
    {
        val original = sampleTrack("upd-1")
        val state = EditorState().addTrack(Category.MELODY, original)
        val modified = original.copy(resourceName = "renamed.resource", volume = 0.5f)

        val updated = state.updateTrack("upd-1", modified)

        assertEquals(1, updated.tracks.melody.size)
        assertEquals("renamed.resource", updated.tracks.melody[0].resourceName)
        assertEquals(0.5f, updated.tracks.melody[0].volume)
    }

    @Test
    fun updateTrackWithUnknownIdIsNoOp()
    {
        val original = sampleTrack("keep-1")
        val state = EditorState().addTrack(Category.RHYTHM, original)

        val updated = state.updateTrack("does-not-exist", original.copy(resourceName = "ignored"))

        assertEquals(1, updated.tracks.rhythm.size)
        assertEquals(original, updated.tracks.rhythm[0])
    }

    @Test
    fun deleteTrackRemovesById()
    {
        val a = sampleTrack("del-a")
        val b = sampleTrack("del-b")
        val state = EditorState()
            .addTrack(Category.HARMONY, a)
            .addTrack(Category.HARMONY, b)

        val updated = state.deleteTrack("del-a")

        assertEquals(1, updated.tracks.harmony.size)
        assertEquals(b, updated.tracks.harmony[0])
    }

    @Test
    fun deleteTrackWithUnknownIdIsNoOp()
    {
        val a = sampleTrack("keep-a")
        val state = EditorState().addTrack(Category.DRONE, a)

        val updated = state.deleteTrack("nope")

        assertEquals(1, updated.tracks.drone.size)
        assertEquals(a, updated.tracks.drone[0])
    }

    @Test
    fun selectCategoryChangesSelectedCategory()
    {
        val state = EditorState()

        val updated = state.selectCategory(Category.RHYTHM)

        assertEquals(Category.RHYTHM, updated.selectedCategory)
        assertFalse(updated.dirty)
    }

    @Test
    fun openNewTrackModalSetsModalStateToNewWithNonBlankDraftId()
    {
        val state = EditorState()

        val updated = state.openNewTrackModal()

        val modal = updated.modalState
        assertNotNull(modal)
        assertTrue(modal is ModalState.New)
        assertTrue(modal.trackId.isNotBlank())
    }

    @Test
    fun openEditTrackModalSetsModalStateToEditWithGivenId()
    {
        val state = EditorState()

        val updated = state.openEditTrackModal("edit-me-7")

        val modal = updated.modalState
        assertNotNull(modal)
        assertTrue(modal is ModalState.Edit)
        assertEquals("edit-me-7", modal.trackId)
    }

    @Test
    fun closeModalSetsModalStateToNull()
    {
        val open = EditorState().openNewTrackModal()
        assertNotNull(open.modalState)

        val closed = open.closeModal()

        assertNull(closed.modalState)
    }

    @Test
    fun setErrorSetsAndClearsLastError()
    {
        val state = EditorState()

        val withError = state.setError("boom")
        assertEquals("boom", withError.lastError)

        val cleared = withError.setError(null)
        assertNull(cleared.lastError)
    }

    @Test
    fun markCleanSetsDirtyToFalse()
    {
        val dirty = EditorState().addTrack(Category.DRONE, sampleTrack("clean-1"))
        assertTrue(dirty.dirty)

        val clean = dirty.markClean()

        assertFalse(clean.dirty)
    }

    @Test
    fun roundTripThroughJsonEncodeDecodePreservesAllFields()
    {
        val original = EditorState()
            .addTrack(Category.DRONE, sampleTrack("rt-d-1"))
            .addTrack(Category.MELODY, sampleTrack("rt-m-1"))
            .addTrack(Category.RHYTHM, sampleTrack("rt-r-1"))
            .addTrack(Category.HARMONY, sampleTrack("rt-h-1"))
            .selectCategory(Category.HARMONY)
            .openEditTrackModal("rt-m-1")
            .setError("transient")

        val json = Json.encodeToString(EditorState.serializer(), original)
        val decoded = Json.decodeFromString(EditorState.serializer(), json)

        assertEquals(original, decoded)
        assertEquals(Category.HARMONY, decoded.selectedCategory)
        assertEquals(1, decoded.tracks.drone.size)
        assertEquals(1, decoded.tracks.melody.size)
        assertEquals(1, decoded.tracks.rhythm.size)
        assertEquals(1, decoded.tracks.harmony.size)
        assertTrue(decoded.dirty)
        assertEquals("transient", decoded.lastError)
        val modal = decoded.modalState
        assertNotNull(modal)
        assertTrue(modal is ModalState.Edit)
        assertEquals("rt-m-1", modal.trackId)
    }
}