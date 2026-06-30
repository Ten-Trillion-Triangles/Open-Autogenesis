package org.ttt.autogenesis.audiotrackseditor

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.ttt.autogenesis.audio.AudioObject
import structs.audio.AudioTracks

/**
 * Immutable state for the audio tracks editor. Every mutating operation
 * returns a new [EditorState] instance, leaving the receiver untouched; this
 * keeps the state class safe to pass into a future view-renderer and easy to
 * serialise for round-trip persistence in the JSON-driven config store.
 *
 * @property tracks Backing storage for the four [Category]-keyed track lists
 * @property selectedCategory Which category tab the editor is currently showing
 * @property modalState Current modal open state, or `null` if no modal is shown
 * @property dirty `true` once the in-memory state has diverged from the
 *   last-persisted snapshot
 * @property lastError Most recent user-facing error message, or `null` if
 *   there is nothing to surface
 */
@Serializable
data class EditorState(
    val tracks: AudioTracks = AudioTracks(),
    val selectedCategory: Category = Category.DRONE,
    val modalState: ModalState? = null,
    val dirty: Boolean = false,
    val lastError: String? = null
)
{
    /**
     * Append [track] to the list of the given [category] and mark the state
     * dirty. Returns a new [EditorState] with a fresh [AudioTracks] instance
     * so the original state is not mutated through the shared [MutableList]
     * fields.
     *
     * @param category Which category list the track should be appended to
     * @param track The audio track to add
     * @return A new [EditorState] with the track appended and [dirty] set
     */
    fun addTrack(category: Category, track: AudioObject): EditorState
    {
        val updatedTracks = when (category)
        {
            Category.DRONE -> tracks.copy(drone = tracks.drone.toMutableList().apply { add(track) })
            Category.MELODY -> tracks.copy(melody = tracks.melody.toMutableList().apply { add(track) })
            Category.RHYTHM -> tracks.copy(rhythm = tracks.rhythm.toMutableList().apply { add(track) })
            Category.HARMONY -> tracks.copy(harmony = tracks.harmony.toMutableList().apply { add(track) })
            Category.MENU -> tracks.copy(menu = tracks.menu.toMutableList().apply { add(track) })
            Category.START -> tracks.copy(start = tracks.start.toMutableList().apply { add(track) })
            Category.NEMESIS -> tracks.copy(nemesis = tracks.nemesis.toMutableList().apply { add(track) })
            Category.END -> tracks.copy(end = tracks.end.toMutableList().apply { add(track) })
        }
        return copy(tracks = updatedTracks, dirty = true)
    }

    /**
     * Replace the track with the matching [trackId] (across all four
     * category lists) with the new [track]. The category is preserved. If
     * no track with that id is present, the state is returned unchanged
     * and [dirty] is not set.
     *
     * @param trackId Identifier of the track to replace
     * @param track The replacement track
     * @return A new [EditorState] reflecting the replacement, or `this` if
     *   no matching track was found
     */
    fun updateTrack(trackId: String, track: AudioObject): EditorState
    {
        val category = findCategoryOf(trackId) ?: return this
        val updatedTracks = when (category)
        {
            Category.DRONE -> tracks.copy(drone = tracks.drone.map { if (it.id == trackId) track else it }.toMutableList())
            Category.MELODY -> tracks.copy(melody = tracks.melody.map { if (it.id == trackId) track else it }.toMutableList())
            Category.RHYTHM -> tracks.copy(rhythm = tracks.rhythm.map { if (it.id == trackId) track else it }.toMutableList())
            Category.HARMONY -> tracks.copy(harmony = tracks.harmony.map { if (it.id == trackId) track else it }.toMutableList())
            Category.MENU -> tracks.copy(menu = tracks.menu.map { if (it.id == trackId) track else it }.toMutableList())
            Category.START -> tracks.copy(start = tracks.start.map { if (it.id == trackId) track else it }.toMutableList())
            Category.NEMESIS -> tracks.copy(nemesis = tracks.nemesis.map { if (it.id == trackId) track else it }.toMutableList())
            Category.END -> tracks.copy(end = tracks.end.map { if (it.id == trackId) track else it }.toMutableList())
        }
        return copy(tracks = updatedTracks, dirty = true)
    }

    /**
     * Remove the track with the matching [trackId] from whichever category
     * list contains it. If no track with that id is present, the state is
     * returned unchanged.
     *
     * @param trackId Identifier of the track to delete
     * @return A new [EditorState] with the track removed and [dirty] set,
     *   or `this` if no matching track was found
     */
    fun deleteTrack(trackId: String): EditorState
    {
        if (findTrack(trackId) == null) return this
        val updatedTracks = tracks.copy(
            drone = tracks.drone.filter { it.id != trackId }.toMutableList(),
            melody = tracks.melody.filter { it.id != trackId }.toMutableList(),
            rhythm = tracks.rhythm.filter { it.id != trackId }.toMutableList(),
            harmony = tracks.harmony.filter { it.id != trackId }.toMutableList(),
            menu = tracks.menu.filter { it.id != trackId }.toMutableList(),
            start = tracks.start.filter { it.id != trackId }.toMutableList(),
            nemesis = tracks.nemesis.filter { it.id != trackId }.toMutableList(),
            end = tracks.end.filter { it.id != trackId }.toMutableList()
        )
        return copy(tracks = updatedTracks, dirty = true)
    }

    /**
     * Update which category tab the editor is showing. Does not modify
     * [dirty]; selecting a tab is a presentation-only change.
     *
     * @param category The category to mark as selected
     * @return A new [EditorState] with the updated [selectedCategory]
     */
    fun selectCategory(category: Category): EditorState
    {
        return copy(selectedCategory = category)
    }

    /**
     * Open the modal in "create new track" mode, with a freshly minted
     * draft identifier.
     *
     * @return A new [EditorState] with [modalState] set to [ModalState.New]
     */
    fun openNewTrackModal(): EditorState
    {
        return copy(modalState = ModalState.New(generateUuid()))
    }

    /**
     * Open the modal in "edit existing track" mode, pointing at the
     * track with the given [trackId].
     *
     * @param trackId Identifier of the existing track to edit
     * @return A new [EditorState] with [modalState] set to [ModalState.Edit]
     */
    fun openEditTrackModal(trackId: String): EditorState
    {
        return copy(modalState = ModalState.Edit(trackId))
    }

    /**
     * Close any open modal.
     *
     * @return A new [EditorState] with [modalState] set to `null`
     */
    fun closeModal(): EditorState
    {
        return copy(modalState = null)
    }

    /**
     * Record (or clear, by passing `null`) the most recent user-facing
     * error message.
     *
     * @param message The error message to surface, or `null` to clear
     * @return A new [EditorState] with the updated [lastError]
     */
    fun setError(message: String?): EditorState
    {
        return copy(lastError = message)
    }

    /**
     * Mark the in-memory state as having been persisted, clearing
     * [dirty].
     *
     * @return A new [EditorState] with [dirty] set to `false`
     */
    fun markClean(): EditorState
    {
        return copy(dirty = false)
    }

    /**
     * Locate the [AudioObject] with the given [trackId] across all four
     * category lists, or `null` if no such track exists.
     */
    private fun findTrack(trackId: String): AudioObject?
    {
        return (tracks.drone + tracks.melody + tracks.rhythm + tracks.harmony +
                tracks.menu + tracks.start + tracks.nemesis + tracks.end)
            .firstOrNull { it.id == trackId }
    }

    /**
     * Determine which [Category] contains the track with the given
     * [trackId], or `null` if no such track exists.
     */
    private fun findCategoryOf(trackId: String): Category?
    {
        if (tracks.drone.any { it.id == trackId }) return Category.DRONE
        if (tracks.melody.any { it.id == trackId }) return Category.MELODY
        if (tracks.rhythm.any { it.id == trackId }) return Category.RHYTHM
        if (tracks.harmony.any { it.id == trackId }) return Category.HARMONY
        if (tracks.menu.any { it.id == trackId }) return Category.MENU
        if (tracks.start.any { it.id == trackId }) return Category.START
        if (tracks.nemesis.any { it.id == trackId }) return Category.NEMESIS
        if (tracks.end.any { it.id == trackId }) return Category.END
        return null
    }
}

/**
 * Generate a 32-character hexadecimal identifier suitable for use as a
 * draft track id. Uses a 16-byte random payload from [kotlin.random.Random]
 * which is available on both JVM and Kotlin/JS targets.
 */
private fun generateUuid(): String
{
    val bytes = kotlin.random.Random.nextBytes(16)
    return bytes.joinToString("") { byte ->
        val hex = (byte.toInt() and 0xFF).toString(16)
        if (hex.length == 1) "0$hex" else hex
    }
}
