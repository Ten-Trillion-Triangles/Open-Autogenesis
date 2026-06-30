package account

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.ttt.autogenesis.logging.LogCategory
import org.ttt.autogenesis.logging.Logger
import org.ttt.autogenesis.server.vfs.VirtualFileSystem
import org.ttt.autogenesis.server.vfs.VirtualFileSystemManager
import org.ttt.autogenesis.server.vfs.VfsMode
import org.ttt.autogenesis.network.RpcJson
import structs.account.ByoKeyStatus
import structs.accelbyte.cloudsave.PlayerRecordResponse
import structs.rpcRequests.SubmitByoKeyRequest

/**
 * VFS-backed encrypted store for player BYO API keys.
 *
 * Storage layout: a single user record keyed by [RECORD_KEY] in the existing per-user
 * VFS namespace. The record is a JSON envelope with plaintext metadata (provider, region,
 * keyId, fingerprint, timestamps) and an encrypted secret material blob. Production
 * deployments should ensure the VFS slot has admin-only read+write ACL so the secret is
 * unreachable by the client.
 *
 * Testable seams:
 * - [vfsFactory] is an `internal var` that defaults to [VirtualFileSystemManager.forUser].
 *   Tests inject a fake VFS to exercise the round-trip without touching the real filesystem.
 * - [masterKey] is an `internal var` that lazy-resolves from `BYO_KEY_MASTER_KEY`. Tests
 *   inject a 32-byte array directly.
 *
 * The `effectiveUserId` rewrite (rest-client-* → guest-user) matches the convention used
 * by [proxy.CloudSaveProxy] and [matchmaking.AccountSettingsLookup] so the dev rest-client
 * and the multiplayer client share a single record namespace.
 */
object ByoCredentialStore
{
    /**
     * VFS record key for the BYO credentials slot. Versioned so future schema changes
     * (e.g. multi-provider) can land on `byo_credentials_v2` without a destructive migration.
     */
    const val RECORD_KEY: String = "byo_credentials_v1"

    /**
     * Test seam: factory that yields the per-user [VirtualFileSystem]. Production code
     * never touches this — it always reads [VirtualFileSystemManager.forUser]. Tests
     * replace it with a fake.
     */
    internal var vfsFactory: (String) -> VirtualFileSystem = { VirtualFileSystemManager.forUser(it) }

    /**
     * Test seam: cached master key. Production code calls [resolveMasterKey] which
     * resolves the env var on first use. Tests inject a 32-byte array directly to avoid
     * coupling to the environment.
     */
    internal var masterKey: ByteArray? = null

    /**
     * Resolves the master key from [ByoCredentialsCrypto.loadMasterKeyFromEnv] and caches it.
     * The cache is intentional: env reads are not free, and we want the same key for every
     * call within a JVM lifetime. Tests can override [masterKey] directly.
     */
    fun resolveMasterKey(): ByteArray
    {
        masterKey?.let { return it }
        val resolved = ByoCredentialsCrypto.loadMasterKeyFromEnv()
        masterKey = resolved
        return resolved
    }

    /**
     * Upserts the BYO key for [userId] and returns the new [ByoKeyStatus]. Encrypts the
     * secret material with the master key; preserves [createdAt] from any existing record
     * and resets [lastUsedAt] / [lastError] to defaults.
     *
     * @param userId The AccelByte user id.
     * @param submit The submitted key material.
     * @return A [Result] wrapping the new [ByoKeyStatus] (no secret material).
     */
    suspend fun upsert(userId: String, submit: SubmitByoKeyRequest): Result<ByoKeyStatus>
    {
        if(userId.isBlank()) return Result.failure(IllegalArgumentException("userId is blank"))

        return runCatching {
            validateSubmit(submit)
            val key = resolveMasterKey()
            val payload = ByoCredentialsCrypto.encrypt(submit.secret, key)
            val effective = effectiveUserId(userId)
            val vfs = vfsFactory(effective)
            val now = System.currentTimeMillis()
            val existing = readRecord(vfs, effective)
            val record = ByoCredentialsRecord(
                version = 1,
                provider = submit.provider,
                keyId = submit.keyId,
                keyFingerprint = fingerprintOf(submit.keyId),
                region = submit.region,
                secretCiphertext = ByoCredentialsCrypto.encodeBase64(payload.ciphertext),
                secretIv = ByoCredentialsCrypto.encodeBase64(payload.iv),
                createdAt = existing?.createdAt ?: now,
                lastUsedAt = existing?.lastUsedAt ?: 0L,
                lastError = ""
            )
            writeRecord(vfs, effective, record)
            Logger.info(
                LogCategory.AUTH,
                "ByoCredentialStore: upserted key for user=$userId provider=${record.provider} region=${record.region} fingerprint=${record.keyFingerprint}"
            )
            record.toStatus()
        }.onFailure { err ->
            Logger.error(
                LogCategory.AUTH,
                "ByoCredentialStore: upsert failed for user=$userId: ${err.message}"
            )
        }
    }

    /**
     * Returns the decrypted [DecryptedByoKey] for [userId], or `null` if no key is on file.
     * Returns a failed [Result] only on infrastructure errors (VFS unreachable, decrypt
     * failure); the "not configured" case is a successful `null`.
     */
    suspend fun getDecrypted(userId: String): Result<DecryptedByoKey?>
    {
        if(userId.isBlank()) return Result.success(null)
        return runCatching {
            val effective = effectiveUserId(userId)
            val vfs = vfsFactory(effective)
            val record = readRecord(vfs, effective) ?: return@runCatching null
            val key = resolveMasterKey()
            val secret = ByoCredentialsCrypto.decrypt(
                EncryptedPayload(
                    ciphertext = ByoCredentialsCrypto.decodeBase64(record.secretCiphertext),
                    iv = ByoCredentialsCrypto.decodeBase64(record.secretIv)
                ),
                key
            )
            DecryptedByoKey(
                provider = record.provider,
                keyId = record.keyId,
                secret = secret,
                region = record.region
            )
        }
    }

    /**
     * Returns the [ByoKeyStatus] for [userId] without decrypting the secret. If no record
     * is on file the status is the default empty [ByoKeyStatus] — callers can detect
     * "not configured" via [ByoKeyStatus.provider].
     */
    suspend fun status(userId: String): Result<ByoKeyStatus>
    {
        if(userId.isBlank()) return Result.success(ByoKeyStatus())
        return runCatching {
            val effective = effectiveUserId(userId)
            val vfs = vfsFactory(effective)
            val record = readRecord(vfs, effective)
            record?.toStatus() ?: ByoKeyStatus()
        }
    }

    /**
     * Returns `true` if a record exists for [userId]. This is the cheap "should I
     * downgrade this BYO-flagged account?" probe used by [matchmaking.AccountSettingsLookup].
     */
    suspend fun exists(userId: String): Result<Boolean>
    {
        if(userId.isBlank()) return Result.success(false)
        return runCatching {
            val effective = effectiveUserId(userId)
            val vfs = vfsFactory(effective)
            readRecord(vfs, effective) != null
        }
    }

    /**
     * Deletes the BYO record for [userId]. Idempotent: deleting a missing record is a
     * successful no-op so the revoke RPC can be safely retried.
     */
    suspend fun delete(userId: String): Result<Unit>
    {
        if(userId.isBlank()) return Result.success(Unit)
        return runCatching {
            val effective = effectiveUserId(userId)
            val vfs = vfsFactory(effective)
            val hadRecord = readRecord(vfs, effective) != null
            if(hadRecord)
            {
                // The CloudVFS does not currently implement deleteUserRecord, so we
                // tombstone the record with an empty payload instead. The next read will
                // return a missing record and the cost-class lookup will downgrade.
                writeRecord(vfs, effective, ByoCredentialsRecord.TOMBSTONE)
                Logger.info(LogCategory.AUTH, "ByoCredentialStore: tombstoned record for user=$userId")
            }
        }
    }

    /**
     * Records a successful or failed use of the BYO key. Called by the inference path
     * (Workstream 2) so the operator can see last-error / last-used at a glance.
     *
     * @param userId The acting user.
     * @param success `true` to bump [lastUsedAt], `false` to record the [errorMessage] in
     *                [lastError] and leave [lastUsedAt] untouched.
     * @param errorMessage Truncated error text. Ignored when [success] is `true`.
     */
    suspend fun recordUsage(userId: String, success: Boolean, errorMessage: String = ""): Result<Unit>
    {
        if(userId.isBlank()) return Result.success(Unit)
        return runCatching {
            val effective = effectiveUserId(userId)
            val vfs = vfsFactory(effective)
            val existing = readRecord(vfs, effective) ?: return@runCatching
            val updated = existing.copy(
                lastUsedAt = if(success) System.currentTimeMillis() else existing.lastUsedAt,
                lastError = if(success) "" else errorMessage.take(MAX_LAST_ERROR_LENGTH)
            )
            if(updated != existing)
            {
                writeRecord(vfs, effective, updated)
            }
        }
    }

    /**
     * Internal helper for the RPCs. The UserRecordModels envelope wraps a `value` JsonElement
     * around our payload; we extract it before decoding.
     */
    private suspend fun readRecord(vfs: VirtualFileSystem, userId: String): ByoCredentialsRecord?
    {
        val result = vfs.fetchUserRecord(userId, RECORD_KEY)
        val record = result.getOrNull() ?: return null
        val value = record.value ?: return null
        val unwrapped = if(value is JsonObject && value.containsKey("value")) value["value"]!! else value
        return try
        {
            val decoded = RpcJson.decodeFromJsonElement(ByoCredentialsRecord.serializer(), unwrapped)
            // A version=0 record is the tombstone written by [delete] when the VFS has no
            // delete op. Treat it as missing so the cost-class lookup downgrades cleanly.
            if(decoded.version == 0) null else decoded
        }
        catch(_: Throwable)
        {
            // Corrupt or unparseable record — treat as missing so the cost-class lookup
            // downgrades the player out of BYO_KEY rather than crashing the matchmaker.
            Logger.warn(
                LogCategory.AUTH,
                "ByoCredentialStore: failed to deserialize record for user=$userId; treating as missing"
            )
            null
        }
    }

    private suspend fun writeRecord(vfs: VirtualFileSystem, userId: String, record: ByoCredentialsRecord): PlayerRecordResponse
    {
        val payload = RpcJson.encodeToJsonElement(ByoCredentialsRecord.serializer(), record)
        val envelope = buildJsonObject {
            put("value", payload)
        }
        val result = vfs.saveUserRecord(userId, RECORD_KEY, envelope)
        return result.getOrThrow()
    }

    private fun validateSubmit(submit: SubmitByoKeyRequest)
    {
        require(submit.provider == SUPPORTED_PROVIDER) {
            "Unsupported provider '${submit.provider}'; only '$SUPPORTED_PROVIDER' is supported in v1"
        }
        require(submit.keyId.isNotBlank()) { "keyId is required" }
        require(submit.secret.isNotBlank()) { "secret is required" }
        require(submit.region in SUPPORTED_REGIONS) {
            "Region '${submit.region}' is not in the supported Bedrock region list"
        }
    }

    private fun effectiveUserId(userId: String): String =
        if(userId.startsWith("rest-client")) "guest-user" else userId

    private fun fingerprintOf(keyId: String): String
    {
        val trimmed = keyId.trim()
        return if(trimmed.length <= 4) trimmed else trimmed.takeLast(4)
    }

    /** Wire-format version constant. Bumped when the envelope layout changes. */
    const val SUPPORTED_PROVIDER: String = "aws-bedrock"

    /**
     * Subset of AWS regions where Bedrock is available as of 2026. Validated client-side
     * via the region dropdown in the KVision modal and server-side at submit time. Source:
     * AWS Bedrock regional availability documentation.
     */
    val SUPPORTED_REGIONS: List<String> = listOf(
        "us-east-1",
        "us-west-2",
        "eu-central-1",
        "eu-west-1",
        "eu-west-3",
        "ap-northeast-1",
        "ap-southeast-1",
        "ap-southeast-2"
    )

    /** Maximum length of the persisted [ByoCredentialsRecord.lastError] string. */
    const val MAX_LAST_ERROR_LENGTH: Int = 256
}

/**
 * In-memory representation of a BYO key record. Serialized to JSON via [RpcJson] and wrapped
 * in a `value` envelope by [ByoCredentialStore.writeRecord]. The secret material is stored
 * as base64 ciphertext + base64 IV; the master key never appears in this struct.
 */
@Serializable
data class ByoCredentialsRecord(
    val version: Int = 1,
    val provider: String = "",
    val keyId: String = "",
    val keyFingerprint: String = "",
    val region: String = "",
    val secretCiphertext: String = "",
    val secretIv: String = "",
    val createdAt: Long = 0L,
    val lastUsedAt: Long = 0L,
    val lastError: String = ""
)
{
    fun toStatus(): ByoKeyStatus = ByoKeyStatus(
        provider = provider,
        keyFingerprint = keyFingerprint,
        region = region,
        createdAt = createdAt,
        lastUsedAt = lastUsedAt,
        lastError = lastError
    )

    companion object
    {
        /** Sentinel value written by [ByoCredentialStore.delete] when the VFS has no delete op. */
        val TOMBSTONE: ByoCredentialsRecord = ByoCredentialsRecord(version = 0)
    }
}

/**
 * Plaintext view of a BYO key, only ever materialised in memory after a successful
 * decrypt. Never serialised, never logged, never returned over the wire to the client.
 *
 * @property provider Provider identifier (e.g. `aws-bedrock`).
 * @property keyId The access key id (e.g. `AKIA...`). Not a secret by AWS convention but
 *                 identifying; the inference path needs it to build the AWS credentials.
 * @property secret The secret access key.
 * @property region AWS region the key is pinned to.
 */
data class DecryptedByoKey(
    val provider: String,
    val keyId: String,
    val secret: String,
    val region: String
)
