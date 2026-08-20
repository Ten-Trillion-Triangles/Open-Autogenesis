package account

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.ttt.autogenesis.logging.LogCategory
import org.ttt.autogenesis.logging.Logger
import org.ttt.autogenesis.network.RpcCallContext
import org.ttt.autogenesis.network.RpcDirection
import org.ttt.autogenesis.network.RpcJson
import org.ttt.autogenesis.network.RpcMethod
import org.ttt.autogenesis.server.vfs.VirtualFileSystem
import org.ttt.autogenesis.server.vfs.VirtualFileSystemManager
import structs.account.AccountSettings
import structs.account.ByoKeyStatus
import structs.account.ByoKeyTestResult
import structs.rpcRequests.RevokeByoKeyRequest
import structs.rpcRequests.SubmitByoKeyRequest
import structs.rpcRequests.TestByoKeyRequest

/**
 * RPC surface for the BYO API key feature.
 *
 * Wire methods:
 * - `server.extend.submitByoKey` — accept a new key, encrypt, store, flip the `bringYourOwnApiKey` flag.
 * - `server.extend.testByoKey` — local decrypt + format check (no real Bedrock call in v1).
 * - `server.extend.byoKeyStatus` — public metadata snapshot for the UI.
 * - `server.extend.revokeByoKey` — delete the slot and flip the flag back to `false`.
 *
 * Internal-only:
 * - [decryptByoKey] — same-JVM helper called by the inference path (Workstream 2).
 *
 * Security note: the existing codebase pattern is to carry the `userId` in the request
 * body. We follow that pattern for consistency. A future hardening pass should validate
 * the body `userId` against the authenticated session's `accelByteId` from
 * [RpcCallContext.metadata].
 */
object ByoCredentialsRpc
{
    /**
     * Test seam: factory that yields the per-user VFS used by [flipAccountSettingsFlag].
     * Production code never touches this — it always reads
     * [VirtualFileSystemManager.forUser]. Tests override it (e.g. via the `ByoCredentialsRpc`
     * object's `internal var`) to avoid booting the full Server.kt main entry point that
     * initializes the VFS manager.
     */
    internal var vfsFactory: (String) -> VirtualFileSystem = { VirtualFileSystemManager.forUser(it) }

    /**
     * Rpc method: `server.extend.submitByoKey`.
     *
     * Validates the key material, encrypts and stores it, then updates the player's
     * [AccountSettings] to flip `bringYourOwnApiKey` to `true`. Returns the new
     * [ByoKeyStatus] (no secret material).
     */
    @RpcMethod("server.extend.submitByoKey", RpcDirection.SERVER)
    suspend fun submitByoKey(context: RpcCallContext, request: SubmitByoKeyRequest): ByoKeyStatus
    {
        Logger.info(
            LogCategory.AUTH,
            "ByoCredentialsRpc.submitByoKey: userId=${request.userId} provider=${request.provider} region=${request.region}"
        )
        val status = ByoCredentialStore.upsert(request.userId, request).getOrElse { err ->
            Logger.error(
                LogCategory.AUTH,
                "ByoCredentialsRpc.submitByoKey: store rejected the key for userId=${request.userId}: ${err.message}"
            )
            throw err
        }
        flipAccountSettingsFlag(request.userId, bringYourOwnApiKey = true)
        return status
    }

    /**
     * Rpc method: `server.extend.testByoKey`.
     *
     * Performs a local decrypt + AWS-format check. Returns a [ByoKeyTestResult] with a
     * human-readable `message`. The real Bedrock round-trip is wired in Workstream 2's
     * inference path, so this RPC is intentionally network-free.
     */
    @RpcMethod("server.extend.testByoKey", RpcDirection.SERVER)
    suspend fun testByoKey(context: RpcCallContext, request: TestByoKeyRequest): ByoKeyTestResult
    {
        val started = System.currentTimeMillis()
        Logger.info(LogCategory.AUTH, "ByoCredentialsRpc.testByoKey: userId=${request.userId}")
        val decrypted = ByoCredentialStore.getDecrypted(request.userId).getOrNull()
        if(decrypted == null)
        {
            return ByoKeyTestResult(
                success = false,
                message = "No BYO key on file. Submit a key first via 'Submit API key'.",
                latencyMs = System.currentTimeMillis() - started
            )
        }
        val formatError = validateAwsCredentialsFormat(decrypted)
        return if(formatError == null)
        {
            ByoKeyTestResult(
                success = true,
                message = "Decryption succeeded. Key id and secret pass format validation. " +
                    "Real Bedrock validation happens the first time the key is used for inference.",
                latencyMs = System.currentTimeMillis() - started
            )
        }
        else
        {
            ByoKeyTestResult(
                success = false,
                message = formatError,
                latencyMs = System.currentTimeMillis() - started
            )
        }
    }

    /**
     * Rpc method: `server.extend.byoKeyStatus`.
     *
     * Public metadata snapshot for the UI. Returns an empty [ByoKeyStatus] when the
     * user has no key on file (the UI checks [ByoKeyStatus.provider] to decide
     * whether to render the "BYO key active" badge or the "Add your key" button).
     */
    @RpcMethod("server.extend.byoKeyStatus", RpcDirection.SERVER)
    suspend fun byoKeyStatus(context: RpcCallContext, request: TestByoKeyRequest): ByoKeyStatus
    {
        return ByoCredentialStore.status(request.userId).getOrDefault(ByoKeyStatus())
    }

    /**
     * Rpc method: `server.extend.revokeByoKey`.
     *
     * Deletes the encrypted slot and flips the player's [AccountSettings.bringYourOwnApiKey]
     * back to `false` so the matchmaking ladder stops ranking them as `BYO_KEY`.
     */
    @RpcMethod("server.extend.revokeByoKey", RpcDirection.SERVER)
    suspend fun revokeByoKey(context: RpcCallContext, request: RevokeByoKeyRequest): Boolean
    {
        Logger.info(LogCategory.AUTH, "ByoCredentialsRpc.revokeByoKey: userId=${request.userId}")
        val deleteResult = ByoCredentialStore.delete(request.userId)
        deleteResult.onFailure { err ->
            Logger.error(
                LogCategory.AUTH,
                "ByoCredentialsRpc.revokeByoKey: store failed for userId=${request.userId}: ${err.message}"
            )
        }
        flipAccountSettingsFlag(request.userId, bringYourOwnApiKey = false)
        return deleteResult.isSuccess
    }

    /**
     * Internal helper used by the inference path. Not exposed as an RPC — same-JVM
     * only. The inference code can call this directly without going through the RPC
     * dispatcher.
     */
    internal suspend fun decryptByoKey(userId: String): DecryptedByoKey?
    {
        if(userId.isBlank()) return null
        return ByoCredentialStore.getDecrypted(userId).getOrNull()
    }

    /**
     * Persists the `bringYourOwnApiKey` flag on the player's [AccountSettings] record.
     * Idempotent: writing the same value is a no-op. We fetch the existing record (or
     * create an empty one), mutate the one field, and write it back via the same VFS
     * path the existing `proxy.CloudSaveProxy` uses.
     */
    private suspend fun flipAccountSettingsFlag(userId: String, bringYourOwnApiKey: Boolean)
    {
        if(userId.isBlank()) return
        val effective = if(userId.startsWith("rest-client")) "guest-user" else userId
        val vfs = vfsFactory(effective)
        val existing: AccountSettings = vfs.fetchUserRecord(effective, ACCOUNT_SETTINGS_KEY)
            .getOrNull()
            ?.let { record ->
                val value = record.value
                if(value == null) AccountSettings()
                else
                {
                    val unwrapped = if(value is JsonObject && value.containsKey("value")) value["value"]!! else value
                    try
                    {
                        RpcJson.decodeFromJsonElement(AccountSettings.serializer(), unwrapped)
                    }
                    catch(_: Throwable)
                    {
                        AccountSettings()
                    }
                }
            }
            ?: AccountSettings().also { it.accelByteUserId = effective }
        if(existing.bringYourOwnApiKey == bringYourOwnApiKey) return
        val updated = existing.copy(bringYourOwnApiKey = bringYourOwnApiKey)
        val payload = RpcJson.encodeToJsonElement(AccountSettings.serializer(), updated)
        val envelope = buildJsonObject { put("value", payload) }
        val writeResult = vfs.saveUserRecord(effective, ACCOUNT_SETTINGS_KEY, envelope)
        writeResult.onSuccess {
            Logger.info(
                LogCategory.DATABASE,
                "ByoCredentialsRpc: flipped AccountSettings.bringYourOwnApiKey=$bringYourOwnApiKey for userId=$userId"
            )
        }
        writeResult.onFailure { err ->
            Logger.error(
                LogCategory.DATABASE,
                "ByoCredentialsRpc: failed to persist flag flip for userId=$userId: ${err.message}"
            )
        }
    }

    /**
     * Validates the access-key-id / secret pair against the AWS access-key format. This
     * is a syntactic check, not a round-trip to AWS — it catches obvious typos and
     * paste errors but cannot detect a revoked or throttled key. The real check happens
     * the first time the DS uses the key for inference (Workstream 2).
     *
     * @return `null` when the key passes; otherwise a human-readable error string.
     */
    private fun validateAwsCredentialsFormat(key: DecryptedByoKey): String?
    {
        if(key.provider != "aws-bedrock") return "Unsupported provider: ${key.provider}"
        if(!AWS_ACCESS_KEY_PATTERN.matches(key.keyId))
        {
            return "Access key id does not match the AWS format (expected AKIA/ASIA + 16 chars)."
        }
        if(key.secret.length < AWS_SECRET_MIN_LENGTH)
        {
            return "Secret access key is too short (got ${key.secret.length} chars, expected >= $AWS_SECRET_MIN_LENGTH)."
        }
        if(key.region !in ByoCredentialStore.SUPPORTED_REGIONS)
        {
            return "Region '${key.region}' is not in the supported Bedrock region list."
        }
        return null
    }

    /** CloudSave record key for [AccountSettings]; mirrors `proxy.CloudSaveProxy`. */
    private const val ACCOUNT_SETTINGS_KEY: String = "account-settings"

    /**
     * AWS access key id pattern: `AKIA` (long-term) or `ASIA` (session/temporary) followed
     * by 16 uppercase alphanumeric characters.
     */
    private val AWS_ACCESS_KEY_PATTERN: Regex = Regex("^(AKIA|ASIA)[A-Z0-9]{16}$")

    /** AWS secret access keys are 40 base64-ish characters; we accept anything >= 30 to allow for rotated keys. */
    private const val AWS_SECRET_MIN_LENGTH: Int = 30
}