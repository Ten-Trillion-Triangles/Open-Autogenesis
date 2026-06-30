package structs.resume

import kotlinx.serialization.Serializable

/**
 * Pushed from server-extend to the main server (and from the main server
 * to the matching client) when a saved running-game record is detected
 * for the calling player at login.
 *
 * Carries enough state for the client to decide whether to render the
 * modal without an additional round trip:
 *   - userId: the AccelByte user id whose record was found (used by the
 *     client to confirm the modal is for the right account).
 *   - worldRound: round number from the snapshot (display "Resume round N?").
 *   - turnIndex: turn index for the human player (0 if not present).
 *   - hasAi: true if the snapshot includes AI players (so the client can
 *     show the "1 human + 3 AI" subtitle).
 *   - savedAt: ISO-8601 timestamp from the VFS record, for the "Saved 2h ago"
 *     label. Optional because the sentinel fallback path does not carry it.
 */
@Serializable
data class ResumeAvailabilityNotification(
    val userId: String,
    val worldRound: Int,
    val turnIndex: Int,
    val hasAi: Boolean,
    val savedAt: String? = null
)
