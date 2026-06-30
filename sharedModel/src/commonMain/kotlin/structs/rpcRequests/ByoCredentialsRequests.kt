package structs.rpcRequests

import kotlinx.serialization.Serializable

/**
 * Request to `server.extend.submitByoKey`. Carries the player's secret material over the
 * encrypted WebSocket and is immediately encrypted with the server-side master key before
 * being written to the VFS-backed BYO slot. Never persisted to the client side.
 *
 * @property userId The AccelByte user id. The existing codebase pattern is to carry the
 *                  userId in the body; a future hardening pass should validate this against
 *                  the authenticated session.
 * @property provider Provider identifier. Must be `aws-bedrock` in v1; the store rejects any
 *                    other value with a 400.
 * @property keyId The access key id (e.g. `AKIA...`). Visible to the client by design.
 * @property secret The secret access key. The RPC handler reads this once, encrypts it, and
 *                  zeroes the local reference; the secret never appears in logs or persisted
 *                  state.
 * @property region AWS region the key is pinned to. Validated against the supported Bedrock
 *                  region list at submit time.
 */
@Serializable
data class SubmitByoKeyRequest(
    val userId: String = "",
    val provider: String = "aws-bedrock",
    val keyId: String = "",
    val secret: String = "",
    val region: String = ""
)

/**
 * Request to `server.extend.testByoKey` and `server.extend.byoKeyStatus`. The user id is
 * taken from the body. The placeholder field exists so future expansion (e.g. an explicit
 * provider override) can land without a request-shape migration on the client.
 */
@Serializable
data class TestByoKeyRequest(
    val userId: String = "",
    val placeholder: String = ""
)

/**
 * Request to `server.extend.revokeByoKey`. The user id is bound to the body, not the
 * authenticated session (a future hardening pass should validate this).
 */
@Serializable
data class RevokeByoKeyRequest(
    val userId: String = "",
    val placeholder: String = ""
)
