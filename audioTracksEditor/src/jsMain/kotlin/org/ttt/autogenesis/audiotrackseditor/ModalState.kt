package org.ttt.autogenesis.audiotrackseditor

import kotlinx.serialization.Serializable

/**
 * Marker sealed class for the editor modal lifecycle. The two variants capture
 * the two reasons a modal can be open: a fresh [New] draft that has not been
 * associated with a persisted track yet, and an [Edit] of an existing track
 * identified by [trackId].
 */
@Serializable
sealed class ModalState
{
    /**
     * Identifier of the track the modal is operating on. For [New], this is
     * a freshly minted draft id that has not yet been written to the
     * underlying [org.ttt.autogenesis.audio.AudioTracks] store.
     */
    abstract val trackId: String

    /**
     * The modal is open in "create new track" mode. The [trackId] is a
     * draft identifier generated on the client and used to key the in-flight
     * form state until the user confirms the create.
     *
     * @param trackId Newly minted draft identifier for the in-flight create
     */
    @Serializable
    data class New(override val trackId: String) : ModalState()

    /**
     * The modal is open in "edit existing track" mode, with [trackId]
     * pointing at the [org.ttt.autogenesis.audio.AudioObject.id] of the
     * track being edited.
     *
     * @param trackId Identifier of the existing track being edited
     */
    @Serializable
    data class Edit(override val trackId: String) : ModalState()
}
