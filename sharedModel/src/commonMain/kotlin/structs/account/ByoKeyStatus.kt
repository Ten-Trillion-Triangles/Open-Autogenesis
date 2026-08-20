package structs.account

import kotlinx.serialization.Serializable

/**
 * Public status snapshot for a player's BYO API key slot.
 *
 * Returned to the KVision client by `server.extend.byoKeyStatus` so the UI can render
 * "BYO key: •••A4F2 (us-east-1)" and a Revoke button. Never carries the secret itself.
 *
 * @property provider Provider identifier, e.g. `aws-bedrock`. Reserved for future multi-provider
 *                    expansion (the v1 store only accepts `aws-bedrock`).
 * @property keyFingerprint Last four characters of the access key id (or a stable 4-char hash of
 *                          the secret) so the player can identify which key is on file.
 * @property region The region the key is pinned to. Currently always one of the Bedrock-supported
 *                  regions (validated server-side at submit time).
 * @property createdAt Epoch milliseconds when the key was first stored.
 * @property lastUsedAt Epoch milliseconds when the key was last successfully used for inference,
 *                     or `0` if it has never been used.
 * @property lastError Truncated last-error string from the most recent BYO inference attempt,
 *                    or `""` if the key has never failed.
 */
@Serializable
data class ByoKeyStatus(
    var provider: String = "",
    var keyFingerprint: String = "",
    var region: String = "",
    var createdAt: Long = 0L,
    var lastUsedAt: Long = 0L,
    var lastError: String = ""
)

/**
 * Result of a `server.extend.testByoKey` call. Performs a local decrypt + format check; it does
 * NOT make a real Bedrock call (Workstream 2 wires the real round-trip into the inference path).
 *
 * @property success `true` when decryption succeeded and the key passed format validation.
 * @property message Human-readable detail suitable for surfacing in the KVision toast.
 * @property latencyMs Wall-clock duration of the decrypt+validate step in milliseconds.
 */
@Serializable
data class ByoKeyTestResult(
    var success: Boolean = false,
    var message: String = "",
    var latencyMs: Long = 0L
)