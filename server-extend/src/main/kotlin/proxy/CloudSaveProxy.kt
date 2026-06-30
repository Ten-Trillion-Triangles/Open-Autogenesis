package proxy

import accelbyte.cloudsave.AdminUserRecord
import com.TTT.Util.deserialize
import com.TTT.Util.serialize
import io.ktor.serialization.kotlinx.json.DefaultJson
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.serializer
import commonGlobals.VfsSanitizer
import org.ttt.autogenesis.logging.LogCategory
import org.ttt.autogenesis.logging.Logger
import org.ttt.autogenesis.network.RpcCallContext
import org.ttt.autogenesis.network.RpcDirection
import org.ttt.autogenesis.network.RpcJson
import org.ttt.autogenesis.network.RpcMethod
import org.ttt.autogenesis.server.vfs.VirtualFileSystemManager
import structs.accelbyte.common.AccelByteJson
import structs.rpcRequests.CommanderRecordRequest
import structs.rpcRequests.GetAccountSettingsRequest
import structs.rpcRequests.SaveAccountSettingsRequest
import structs.storage.MasterRecord
import structs.account.AccountSettings

private const val MASTER_RECORD_KEY = "master-record"
private const val ACCOUNT_SETTINGS_KEY = "account-settings"


/**
 * Client proxy object to allow the kvision app to bypass any cors restrictions for AccelByte endpoints that do not
 * correctly include cors headers. Allows the extend server to query specific user data for the game client. This data
 * largely pertains to things that are either just visual, player stats, or public knowledge data.
 */
object CloudSaveProxy
{
    /**
     * Rpc proxy function to bypass cors and allow the client to get it's master record which holds the user's
     * saved commander data and saved story data from prior games.
     */
    @RpcMethod("server.extend.getMasterRecord", RpcDirection.SERVER)
    suspend fun getMasterRecord(context: RpcCallContext, userId: String) : MasterRecord
    {
        Logger.info(LogCategory.DATABASE, "CloudSaveProxy: getMasterRecord called with userId=$userId")

        // In local dev, rest-client-<timestamp> connections should read from the shared
        // guest-user local directory so that commanders created via WebSocket are visible.
        val effectiveUserId = if (userId.isBlank() || userId.startsWith("rest-client")) "guest-user" else userId
        val vfs = VirtualFileSystemManager.forUser(effectiveUserId)
        val result = vfs.fetchUserRecord(effectiveUserId, MASTER_RECORD_KEY)
        
        Logger.debug(LogCategory.DATABASE, "CloudSaveProxy: fetchUserRecord returned with success=${result.isSuccess}")
        
        return result.fold(
            onSuccess = { response ->
                Logger.info(LogCategory.DATABASE, "CloudSaveProxy: getRecord succeeded for user=$userId (hasValue=${response.value != null})")
                val value = response.value
                if(value == null)
                {
                    Logger.debug(LogCategory.DATABASE, "CloudSaveProxy: master-record missing for user=$userId, returning empty record")
                    MasterRecord()
                }
                else
                {
                    try {
                        // Null-safe traversal: an empty/malformed AccelByte record
                        // (e.g. a 200 response with value=null wrapped in a
                        // {"value": null} envelope, or a partial JSON body) used
                        // to surface a kotlinx-serialization internal
                        // `IndexOutOfBoundsException` (BUG #5). Resolve the
                        // inner value to null when it is JsonNull or missing,
                        // and return an empty record instead of feeding JsonNull
                        // into decodeFromJsonElement.
                        val innerValue: kotlinx.serialization.json.JsonElement? =
                            if (value is kotlinx.serialization.json.JsonObject && value.containsKey("value"))
                            {
                                val raw = value["value"]
                                if (raw is kotlinx.serialization.json.JsonNull) null else raw
                            }
                            else
                            {
                                value
                            }
                        if (innerValue == null)
                        {
                            Logger.debug(LogCategory.DATABASE, "CloudSaveProxy: master-record inner value is null for user=$userId, returning empty record")
                            MasterRecord()
                        }
                        else
                        {
                            val masterRecord = AccelByteJson.decodeFromJsonElement(MasterRecord.serializer(), innerValue)
                            Logger.info(LogCategory.DATABASE, "CloudSaveProxy: master record deserialized with ${masterRecord.commanderKeys.size} commander keys")
                            masterRecord
                        }
                    } catch (e: Exception) {
                        Logger.error(LogCategory.DATABASE, "CloudSaveProxy: Failed to deserialize master record for user=$userId: ${e.message}")
                        MasterRecord()
                    }
                }
            },
            onFailure = { err ->
                Logger.warn(LogCategory.DATABASE, "CloudSaveProxy: failed to fetch master record for user=$userId: ${err.message}")
                MasterRecord()
            }
        )
    }

    /**
     * Proxy rpc function to allow the web client to bypass cors and retrieve a given user record.
     * Allows only records found on the master record which are the publicly available records a user can access.
     */
    @RpcMethod("server.extend.proxyGetCommanderRecord", RpcDirection.SERVER)
    suspend fun proxyGetCommanderRecord(context: RpcCallContext, request: CommanderRecordRequest) : String
    {
        Logger.info(LogCategory.DATABASE, "CloudSaveProxy.proxyGetCommanderRecord: START userId=${request.userId} requestKey='${request.key}'")

        val effectiveUserId = if (request.userId.isBlank() || request.userId.startsWith("rest-client")) "guest-user" else request.userId
        val vfs = VirtualFileSystemManager.forUser(effectiveUserId)

        val masterRecord = vfs.fetchUserRecord(effectiveUserId, MASTER_RECORD_KEY).fold(
            onSuccess = {
                response ->
                val masterRecordValue = response.value ?: return@fold ""

                try
                {
                    val actualValue = if(masterRecordValue is kotlinx.serialization.json.JsonObject && masterRecordValue.containsKey("value"))
                    {
                        masterRecordValue["value"]!!
                    }
                    else
                    {
                        masterRecordValue
                    }
                    val masterRecordAsObject = Json.decodeFromJsonElement(serializer<MasterRecord>(), actualValue)
                    Logger.info(LogCategory.DATABASE, "CloudSaveProxy.proxyGetCommanderRecord: Master record has ${masterRecordAsObject.commanderKeys.size} keys")
                    
                    val sanitizedRequestKey = VfsSanitizer.sanitize(request.key)
                    
                    val matchingKey = masterRecordAsObject.commanderKeys.firstOrNull { storedKey ->
                        VfsSanitizer.sanitize(storedKey) == sanitizedRequestKey
                    }

                    if(matchingKey != null)
                    {
                        val sanitizedMatchingKey = VfsSanitizer.sanitize(matchingKey)
                        Logger.info(LogCategory.DATABASE, "CloudSaveProxy.proxyGetCommanderRecord: Fetching commander with sanitizedKey='$sanitizedMatchingKey'")
                        
                        vfs.fetchUserRecord(effectiveUserId, sanitizedMatchingKey).fold(
                            onSuccess = { cmdResponse ->
                                val commanderRecordValue = cmdResponse.value ?: return@fold ""
                                
                                val actualCommanderValue = if(commanderRecordValue is kotlinx.serialization.json.JsonObject && commanderRecordValue.containsKey("value"))
                                {
                                    commanderRecordValue["value"]!!
                                }
                                else
                                {
                                    commanderRecordValue
                                }
                                
                                return@fold serialize(actualCommanderValue)
                            },
                            onFailure = { err ->
                                Logger.error(LogCategory.DATABASE, "CloudSaveProxy.proxyGetCommanderRecord: Commander fetch FAILED for key='$sanitizedMatchingKey': ${err.message}")
                                return@fold ""
                            }
                        )
                    }
                    else
                    {
                        Logger.error(LogCategory.DATABASE, "CloudSaveProxy.proxyGetCommanderRecord: Key '${request.key}' not found in master record")
                        return@fold ""
                    }
                }
                catch (err: Exception)
                {
                    Logger.error(LogCategory.DATABASE, "CloudSaveProxy.proxyGetCommanderRecord: Exception: ${err.message}")
                    return@fold ""
                }
            },

            onFailure = { err ->
                Logger.error(LogCategory.DATABASE, "CloudSaveProxy.proxyGetCommanderRecord: Master record fetch FAILED: ${err.message}")
                return@fold ""
            }
        )

        return masterRecord
    }

    /**
     * Retrieves a player's account settings from cloud save.
     * Returns an empty AccountSettings if no record exists.
     *
     * @param context RPC call context
     * @param request The request containing the user ID
     * @return AccountSettings, or empty AccountSettings if not found/error
     */
    @RpcMethod("server.extend.getAccountSettings", RpcDirection.SERVER)
    suspend fun getAccountSettings(context: RpcCallContext, request: GetAccountSettingsRequest): AccountSettings
    {
        val userId = request.userId
        Logger.info(LogCategory.DATABASE, "CloudSaveProxy: getAccountSettings called with userId=$userId")

        val effectiveUserId = if (userId.isBlank() || userId.startsWith("rest-client")) "guest-user" else userId
        val vfs = VirtualFileSystemManager.forUser(effectiveUserId)
        val result = vfs.fetchUserRecord(effectiveUserId, ACCOUNT_SETTINGS_KEY)

        return result.fold(
            onSuccess = { response ->
                val value = response.value
                if (value == null)
                {
                    Logger.debug(LogCategory.DATABASE, "CloudSaveProxy: account-settings missing for user=$userId, returning empty")
                    AccountSettings()
                }
                else
                {
                    try
                    {
                        val actualValue = if (value is kotlinx.serialization.json.JsonObject && value.containsKey("value"))
                        {
                            value["value"]!!
                        }
                        else
                        {
                            value
                        }
                        val settings = RpcJson.decodeFromJsonElement(AccountSettings.serializer(), actualValue)
                        Logger.info(LogCategory.DATABASE, "CloudSaveProxy: getAccountSettings succeeded for user=$userId")
                        settings
                    }
                    catch (e: Exception)
                    {
                        Logger.error(LogCategory.DATABASE, "CloudSaveProxy: Failed to deserialize account settings for user=$userId: ${e.message}")
                        AccountSettings()
                    }
                }
            },
            onFailure = { err ->
                Logger.warn(LogCategory.DATABASE, "CloudSaveProxy: failed to fetch account settings for user=$userId: ${err.message}")
                AccountSettings()
            }
        )
    }

    /**
     * Saves a player's account settings to cloud save.
     *
     * @param context RPC call context
     * @param request The request containing userId and JSON settings
     * @return True if save succeeded, false otherwise
     */
    @RpcMethod("server.extend.saveAccountSettings", RpcDirection.SERVER)
    suspend fun saveAccountSettings(context: RpcCallContext, request: SaveAccountSettingsRequest): Boolean
    {
        val userId = request.userId
        Logger.info(LogCategory.DATABASE, "CloudSaveProxy: saveAccountSettings called with userId=$userId")

        val effectiveUserId = if (userId.isBlank() || userId.startsWith("rest-client")) "guest-user" else userId
        val vfs = VirtualFileSystemManager.forUser(effectiveUserId)
        val result = vfs.saveUserRecordFromJsonString(effectiveUserId, ACCOUNT_SETTINGS_KEY, request.accountSettingsJson)

        return result.fold(
            onSuccess = {
                Logger.info(LogCategory.DATABASE, "CloudSaveProxy: saveAccountSettings succeeded for user=$userId")
                true
            },
            onFailure = { err ->
                Logger.error(LogCategory.DATABASE, "CloudSaveProxy: saveAccountSettings failed for user=$userId: ${err.message}")
                false
            }
        )
    }
}
