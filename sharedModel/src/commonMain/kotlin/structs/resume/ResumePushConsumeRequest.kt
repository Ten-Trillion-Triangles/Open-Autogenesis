package structs.resume

import kotlinx.serialization.Serializable

/**
 * Client → server RPC: the user has consumed the resume-availability push
 * by clicking Resume, New Game, or Cancel. The server re-arms the dedupe
 * set for this userId, so a subsequent `client.resumeAvailable` push will
 * actually go through.
 *
 * Wire name: `server.consumeResumePush` (RpcDirection.SERVER — the main
 * server is the receiver; the KVision client invokes it from the browser
 * over the WebSocket RPC bridge).
 *
 * Why a separate RPC and not a flag on the existing push: the push goes
 * server → client. The consume must go client → server. A single
 * directional channel would couple them incorrectly. The current design
 * keeps the two directions independent so a future "push" can fire
 * without depending on the consume wire being wired.
 */
@Serializable
data class ResumePushConsumeRequest(
    val userId: String
)
