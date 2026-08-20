package matchmaking

import account.ByoCredentialStore
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.ttt.autogenesis.logging.LogCategory
import org.ttt.autogenesis.logging.Logger
import org.ttt.autogenesis.network.RpcJson
import org.ttt.autogenesis.server.vfs.VirtualFileSystem
import org.ttt.autogenesis.server.vfs.VirtualFileSystemManager
import structs.account.AccountSettings
import structs.account.CostClass
import structs.account.costClass
import structs.account.subsidyCapacity

/**
 * Resolves a player's [AccountSettings] (and the derived cost-class metadata) for
 * the matchmaking path. Reads directly from the virtual filesystem the same way
 * [proxy.CloudSaveProxy.getAccountSettings] does, so we do not need an extra RPC
 * hop and can be invoked from a background coroutine on the matchmaking hot path.
 *
 * The lookup is best-effort: a missing or unparseable record returns an empty
 * [AccountSettings], which the algorithm treats as [CostClass.FREE] with no
 * subsidy. That is the correct safe default (the player pays nothing, so the
 * operator should not subsidize them).
 *
 * **BYO slot self-healing:** if [AccountSettings.bringYourOwnApiKey] is `true` but
 * the encrypted BYO slot in the VFS is missing or unparseable, this lookup
 * downgrades the cost class to [CostClass.PRO] rather than leaving the player at
 * [CostClass.BYO_KEY]. This prevents a half-broken state (flag set, no key) from
 * ranking a player above PRO in the matchmaker.
 */
open class AccountSettingsLookup
{
    /**
     * Test seam: factory that yields the per-user [VirtualFileSystem]. Production
     * code never touches this — it always reads [VirtualFileSystemManager.forUser].
     * Tests can override it (e.g. via an anonymous subclass) to avoid booting the
     * full Server.kt main entry point that initializes the VFS manager.
     */
    protected open fun vfsFactoryFor(userId: String): VirtualFileSystem =
        VirtualFileSystemManager.forUser(userId)

    /**
     * Returns the [AccountSettings] for [userId], or an empty record if the
     * user has no cloud-save entry yet.
     */
    open suspend fun fetch(userId: String): AccountSettings
    {
        if(userId.isBlank()) return AccountSettings()
        val effective = if(userId.startsWith("rest-client")) "guest-user" else userId
        val vfs = vfsFactoryFor(effective)
        val result = vfs.fetchUserRecord(effective, ACCOUNT_SETTINGS_KEY)
        return result.fold(
            onSuccess = { response ->
                val value = response.value ?: return@fold AccountSettings()
                val unwrapped = if(value is JsonObject && value.containsKey("value"))
                {
                    value["value"]!!
                }
                else
                {
                    value
                }
                try {
                    RpcJson.decodeFromJsonElement(AccountSettings.serializer(), unwrapped)
                }
                catch(err: Throwable) {
                    Logger.warn(
                        LogCategory.DATABASE,
                        "AccountSettingsLookup: failed to deserialize settings for userId=$userId: ${err.message}"
                    )
                    AccountSettings()
                }
            },
            onFailure = { err ->
                Logger.debug(
                    LogCategory.DATABASE,
                    "AccountSettingsLookup: no settings record for userId=$userId (${err.message})"
                )
                AccountSettings()
            }
        )
    }

    /**
     * Convenience wrapper that returns the [CostClass] for [userId]. Treats any
     * error as [CostClass.FREE].
     *
     * If the settings record claims `bringYourOwnApiKey = true` but the BYO slot
     * is missing in the VFS, returns [CostClass.PRO] and logs a one-time warning.
     */
    open suspend fun costClassFor(userId: String): CostClass
    {
        val settings = fetch(userId)
        if(settings.bringYourOwnApiKey)
        {
            val slotExists = byoSlotExists(userId)
            if(!slotExists)
            {
                Logger.warn(
                    LogCategory.AUTH,
                    "AccountSettingsLookup: userId=$userId has bringYourOwnApiKey=true but no BYO slot; downgrading to PRO"
                )
                return CostClass.PRO
            }
            return CostClass.BYO_KEY
        }
        return settings.costClass()
    }

    /**
     * Convenience wrapper that returns the subsidy capacity for [userId]. Returns
     * 0 on any error (safe default).
     *
     * Same downgrade logic as [costClassFor]: a `bringYourOwnApiKey = true` user
     * with no slot in the VFS is treated as PRO, which gives them subsidy capacity
     * 3 instead of the BYO_KEY max of 4.
     */
    open suspend fun subsidyFor(userId: String): Int
    {
        val settings = fetch(userId)
        if(settings.bringYourOwnApiKey)
        {
            if(!byoSlotExists(userId))
            {
                // Match the cost-class downgrade. 3 is the documented PRO subsidy.
                return CostClass.PRO.subsidyCapacity
            }
            return settings.subsidyCapacity()
        }
        return settings.subsidyCapacity()
    }

    /**
     * Builds the ticket-attribute map stamped on every
     * [net.accelbyte.sdk.api.match2.models.ApiMatchTicketRequest] so the custom
     * matchmaker can score the player without a second round-trip to cloud save.
     *
     * The keys are stable strings; missing or unparseable values land as the
     * FREE defaults. A player with the BYO flag set but no slot lands as PRO with
     * `byo_api_key = false` so the matchmaker's subsidy ranking is consistent
     * with [costClassFor].
     */
    open suspend fun attributesFor(userId: String): Map<String, JsonPrimitive>
    {
        val settings = fetch(userId)
        val slotExists = if(settings.bringYourOwnApiKey) byoSlotExists(userId) else false
        val klass: CostClass = when
        {
            settings.bringYourOwnApiKey && slotExists -> CostClass.BYO_KEY
            settings.bringYourOwnApiKey && !slotExists -> CostClass.PRO
            else -> settings.costClass()
        }
        val subsidy = klass.subsidyCapacity
        val credits = settings.billingStatus.credits
        return mapOf(
            "cost_class" to JsonPrimitive(klass.name),
            "cost_subsidy" to JsonPrimitive(subsidy),
            "wallet_credits" to JsonPrimitive(credits),
            "byo_api_key" to JsonPrimitive(slotExists)
        )
    }

    /**
     * Hook used by [costClassFor] and [attributesFor] to decide whether a player
     * claiming `bringYourOwnApiKey = true` actually has a usable BYO key in the
     * VFS. The default implementation probes [ByoCredentialStore] directly.
     *
     * Tests can override this to avoid touching the real VFS, e.g. by returning
     * `true` for a user they have configured and `false` for everyone else.
     */
    protected open suspend fun byoSlotExists(userId: String): Boolean
    {
        if(userId.isBlank()) return false
        return ByoCredentialStore.exists(userId).getOrDefault(false)
    }

    private companion object
    {
        /** Mirrors the cloud-save key used by [proxy.CloudSaveProxy]. */
        const val ACCOUNT_SETTINGS_KEY = "account-settings"
    }
}