package globals

import env.bedrockEnv
import org.ttt.autogenesis.logging.LogCategory
import org.ttt.autogenesis.logging.Logger
import structs.account.AccountSettings
import java.util.concurrent.atomic.AtomicReference

/**
 * Captures the per-turn AWS Bedrock credentials so a player who brings their
 * own API key (BYO mode) gets billed by their own provider for the turn's
 * inference, not by the platform. The resolver is the seam between
 * [accounting.BillingSync]'s cost-class detection and the global `bedrockEnv`
 * credentials that the TPipe SDK actually consults when issuing Bedrock calls.
 *
 * Phase 6 of feature/live-pvp-and-billing.
 *
 * # Lifecycle
 *  1. Caller invokes [snapshot] *before* resolving the active actor. The
 *     snapshot captures whatever credentials are currently in `bedrockEnv`
 *     (typically the platform record loaded by [AwsCredentialsBootstrap]).
 *  2. The caller looks up the player's [AccountSettings.bringYourOwnApiKey]
 *     flag. If `true` and [ByoKeyProvider.getDecrypted] returns a usable
 *     key, the resolver swaps `bedrockEnv` over to the BYO key.
 *  3. After the turn's billing flush completes, the caller invokes [restore]
 *     to put the platform credentials back so the *next* turn's actor isn't
 *     billed under the previous player's credentials.
 *
 * # Provider seam
 *  The resolver does not own the BYO store. It calls into [byoKeyProvider],
 *  a static field that `server-extend` installs at startup. The default
 *  provider returns `null` for every user, which keeps the BYO path a no-op
 *  until the real provider is wired. This avoids a cyclic gradle dependency
 *  (server-extend already depends on server).
 *
 * # Safety
 *  - The resolver never crashes a turn. Any failure (missing BYO slot,
 *    decrypt error, malformed key) falls back to the platform credentials
 *    and logs WARN. The cost-class downgrade mirrors the
 *    [matchmaking.AccountSettingsLookup.costClassFor] PRO-downgrade for the
 *    same "flag set, no slot" case so the resolver and the matchmaking
 *    cost-class stay in lockstep.
 *  - The snapshot/restore is per-coroutine, not per-JVM. [snapshot] returns a
 *    [CredentialSnapshot] that the caller must pass to [restore]; there is no
 *    implicit "current" snapshot. This avoids the trap of a long-running
 *    coroutine restoring the wrong snapshot on a later turn.
 */
object BedrockCredentialResolver
{
    private val lastSnapshot = AtomicReference<CredentialSnapshot?>(null)

    /**
     * The provider consulted to fetch a player's BYO key material. Default
     * implementation returns `null` for every user (no BYO). The real
     * provider is installed by `server-extend` at startup via
     * [installByoKeyProvider].
     */
    @Volatile
    private var byoKeyProvider: ByoKeyProvider = ByoKeyProvider { null }

    /**
     * Installs (or clears with `null`) the BYO key provider. The caller is
     * responsible for restoring the previous value in a teardown step.
     *
     * @return The previous provider, for restore in teardown.
     */
    @JvmStatic
    fun installByoKeyProvider(provider: ByoKeyProvider?): ByoKeyProvider
    {
        val previous = byoKeyProvider
        byoKeyProvider = provider ?: ByoKeyProvider { null }
        return previous
    }

    /**
     * Immutable snapshot of the current Bedrock credential state. Returned by
     * [snapshot] and consumed by [restore].
     *
     * @property accessKeyId The current access key id.
     * @property secretAccessKey The current secret access key.
     * @property region The current AWS region. Tracked separately because
     *                  `bedrockEnv` does not expose a public region getter; we
     *                  read it from the AB_REGION / AWS_REGION env var.
     * @property byoKey `true` when the snapshot was captured from a BYO
     *                 context (i.e., the resolver had previously swapped the
     *                 keys to a player's BYO key).
     */
    data class CredentialSnapshot(
        val accessKeyId: String,
        val secretAccessKey: String,
        val region: String,
        val byoKey: Boolean
    )

    /**
     * Captures the current Bedrock credential state and, if the active player
     * has a usable BYO key, swaps the global `bedrockEnv` over to the player's
     * key. The returned snapshot must be passed to [restore] after the turn's
     * billing flush completes.
     *
     * @param actorAccelByteId The AccelByte user id of the player whose turn
     *                         is about to run. When blank, the resolver is a
     *                         no-op and returns a snapshot of the current state.
     * @return A [CredentialSnapshot] representing the *original* (pre-swap)
     *         credential state. The caller passes this to [restore] after the
     *         turn so the next turn is not billed under the previous player's
     *         BYO key.
     */
    suspend fun snapshot(actorAccelByteId: String): CredentialSnapshot
    {
        val original = captureCurrentSnapshot()
        if (actorAccelByteId.isNotBlank() && shouldUseByoKey(actorAccelByteId))
        {
            val swapped = swapToByoKey(actorAccelByteId)
            if (swapped)
            {
                Logger.info(
                    LogCategory.AUTH,
                    "BedrockCredentialResolver: swapped to BYO key for actor=$actorAccelByteId (region=${swappedRegionFor(actorAccelByteId)})"
                )
            }
        }
        lastSnapshot.set(original)
        return original
    }

    /**
     * Restores the credential state captured by [snapshot]. Safe to call with
     * a `null` snapshot (logs WARN and returns without touching `bedrockEnv`).
     */
    fun restore(snapshot: CredentialSnapshot?)
    {
        if (snapshot == null)
        {
            Logger.warn(
                LogCategory.AUTH,
                "BedrockCredentialResolver.restore: null snapshot; leaving bedrockEnv untouched"
            )
            return
        }
        try
        {
            bedrockEnv.setKeys(snapshot.accessKeyId, snapshot.secretAccessKey)
            Logger.debug(
                LogCategory.AUTH,
                "BedrockCredentialResolver: restored platform keys (byoKey=${snapshot.byoKey})"
            )
        }
        catch (err: Throwable)
        {
            Logger.warn(
                LogCategory.AUTH,
                "BedrockCredentialResolver.restore: failed to set keys: ${err.message}"
            )
        }
        lastSnapshot.set(null)
    }

    /**
     * Returns the snapshot most recently captured by [snapshot] without
     * touching `bedrockEnv`. Intended for diagnostics and tests; production
     * code should rely on the snapshot returned by [snapshot] rather than
     * reading this field.
     */
    fun lastSnapshotForTest(): CredentialSnapshot? = lastSnapshot.get()

    /**
     * Resolves whether the actor should be billed under their own BYO key.
     * Mirrors the cost-class downgrade in
     * [matchmaking.AccountSettingsLookup.costClassFor] so a "flag set, slot
     * missing" player still lands on platform creds (not on a phantom BYO
     * context that would silently fail Bedrock calls).
     */
    private suspend fun shouldUseByoKey(actorAccelByteId: String): Boolean
    {
        if (!AwsCredentialsBootstrap.initializedForTest())
        {
            return false
        }
        val settings = readAccountSettings(actorAccelByteId) ?: return false
        if (!settings.bringYourOwnApiKey) return false
        // Even when the flag is set, require a usable decrypted slot.
        val slot = runCatching { byoKeyProvider.getDecrypted(actorAccelByteId) }
            .getOrElse { err ->
                Logger.warn(
                    LogCategory.AUTH,
                    "BedrockCredentialResolver: byoKeyProvider threw for actor=$actorAccelByteId: ${err.message}"
                )
                null
            }
        if (slot == null)
        {
            Logger.warn(
                LogCategory.AUTH,
                "BedrockCredentialResolver: actor=$actorAccelByteId has bringYourOwnApiKey=true but no decrypted slot; falling back to platform creds (cost-class PRO)"
            )
            return false
        }
        return true
    }

    /**
     * Reads the active player's [AccountSettings] from cloud save. Production
     * code goes through the gRPC bridge; tests can swap the [accounting.BillingSync]
     * invoker override. This helper is intentionally narrow: it only reads
     * the bringYourOwnApiKey flag, so a transient bridge failure is non-fatal.
     */
    private suspend fun readAccountSettings(actorAccelByteId: String): AccountSettings?
    {
        return try
        {
            val settings = accounting.BillingSync.getAccountSettings(actorAccelByteId)
            if (settings.accelByteUserId.isBlank()) null else settings
        }
        catch (err: Throwable)
        {
            Logger.warn(
                LogCategory.AUTH,
                "BedrockCredentialResolver: failed to read settings for actor=$actorAccelByteId: ${err.message}"
            )
            null
        }
    }

    /**
     * Returns the AWS region the BYO key is pinned to. Reads from the
     * encrypted slot; returns the platform region as a fallback.
     */
    private suspend fun swappedRegionFor(actorAccelByteId: String): String
    {
        val slot = runCatching { byoKeyProvider.getDecrypted(actorAccelByteId) }.getOrNull()
        return slot?.region ?: platformRegion()
    }

    /**
     * Captures the current `bedrockEnv` state. Falls back to the env-configured
     * region when the resolver has not yet observed a region (the
     * `bedrockEnv` API does not expose a public region getter).
     */
    private fun captureCurrentSnapshot(): CredentialSnapshot
    {
        val (ak, sk) = try
        {
            bedrockEnv.getKeys()
        }
        catch (err: Throwable)
        {
            "" to ""
        }
        return CredentialSnapshot(
            accessKeyId = ak,
            secretAccessKey = sk,
            region = platformRegion(),
            byoKey = false
        )
    }

    /**
     * Swaps the global `bedrockEnv` to the player's BYO key. Returns `true` on
     * success, `false` on any failure (caller falls back to platform creds).
     */
    private suspend fun swapToByoKey(actorAccelByteId: String): Boolean
    {
        val slot = runCatching { byoKeyProvider.getDecrypted(actorAccelByteId) }.getOrNull() ?: return false
        return try
        {
            bedrockEnv.setKeys(slot.accessKeyId, slot.secretAccessKey)
            true
        }
        catch (err: Throwable)
        {
            Logger.warn(
                LogCategory.AUTH,
                "BedrockCredentialResolver: failed to set BYO keys for actor=$actorAccelByteId: ${err.message}"
            )
            false
        }
    }

    /**
     * Returns the platform region from `AB_REGION` / `AWS_REGION` env vars.
     * Used for the snapshot's region field; not the source of truth for the
     * actual SDK region (which `bedrockEnv` configures internally).
     */
    private fun platformRegion(): String
    {
        return System.getenv("AB_REGION")
            ?: System.getenv("AWS_REGION")
            ?: "us-west-2"
    }
}

/**
 * Test-only access to the [AwsCredentialsBootstrap.initialized] flag. The
 * production class declares the field as `private var`; this extension widens
 * the visibility to package-internal so [BedrockCredentialResolver] can guard
 * against an un-bootstrapped environment.
 */
internal fun AwsCredentialsBootstrap.initializedForTest(): Boolean
{
    return try
    {
        val field = AwsCredentialsBootstrap::class.java.getDeclaredField("initialized")
        field.isAccessible = true
        field.getBoolean(AwsCredentialsBootstrap)
    }
    catch (err: Throwable)
    {
        false
    }
}
