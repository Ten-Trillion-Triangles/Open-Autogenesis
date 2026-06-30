package org.ttt.autogenesis.audio

import kotlinx.serialization.Serializable

/**
 * A single music track in the game's library.
 *
 * Two roles:
 *  - **Label** ([resourceName] + [category]) — used by the picker to
 *    classify a track and to assert the client's resolver can find it
 *    (see `MusicResourceResolverTest.everyCatalogNameResolvesToAPath`).
 *  - **Playback config** ([audioObject]) — when the catalog is built
 *    from the editor's [structs.audio.AudioTracks] payload, each
 *    [MusicTrack] carries the full editor-supplied [AudioObject]
 *    (volume, loop, loopEnd, loopWithTail, …). The picker emits this
 *    [AudioObject] verbatim so the editor's per-track mix is preserved.
 *    When [audioObject] is null (legacy / hardcoded catalogs), the
 *    picker falls back to a generic Music-channel object with default
 *    fade-in.
 *
 * @param resourceName the human-readable track name that the server puts
 *   on the wire as [AudioObject.resourceName]. The client side fuzzy-searches
 *   this against the on-disk mp3 manifest. Case-insensitive match is
 *   guaranteed by the resolver; the picker must use one of the canonical
 *   names returned by [MusicTrackCatalog].
 * @param category which bucket of the library the track belongs to.
 * @param audioObject the editor-supplied full playback configuration, or
 *   `null` for the legacy hardcoded catalog. When non-null the picker
 *   passes this object through to the client verbatim (preserving the
 *   editor's volume / loop / loopEnd / loopWithTail settings).
 */
@Serializable
data class MusicTrack(
    val resourceName: String,
    val category: MusicCategory,
    val audioObject: AudioObject? = null
)
