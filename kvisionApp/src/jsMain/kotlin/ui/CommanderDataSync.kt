package ui

import commonGlobals.VfsSanitizer
import globals.AccelByteEnv
import globals.World
import kotlinx.serialization.json.Json
import kotlinx.serialization.serializer
import org.ttt.autogenesis.kvisionapp.CommanderCache
import org.ttt.autogenesis.kvisionapp.ServerExtendBridge
import org.ttt.autogenesis.logging.LogCategory
import org.ttt.autogenesis.logging.Logger
import org.ttt.autogenesis.network.RpcJson
import org.ttt.autogenesis.network.RpcInvoker
import structs.Commander
import structs.accelbyte.common.AccelByteJson
import structs.rpcRequests.CommanderRecordRequest
import structs.storage.MasterRecord

/**
 * Shared helper that mirrors the logic from the login sequence for hydrating the player's saved commanders.
 */
internal suspend fun loadSavedCommanders(messageBox: MessageBox)
{
    messageBox.setMessage("Connecting to server-extend for saved commanders...")
    Logger.debug(LogCategory.DATABASE, "CommanderDataSync: ensuring server-extend connection")

    try
    {
        ServerExtendBridge.withTemporaryConnection {
            messageBox.setMessage("Loading master record for ${AccelByteEnv.userId}...")
            Logger.debug(LogCategory.DATABASE, "CommanderDataSync: fetching master record for user ${AccelByteEnv.userId}")
            val masterRecord = try
            {
                fetchMasterRecordViaProxy(messageBox)
            }
            catch(err: Throwable)
            {
                Logger.error(
                    LogCategory.DATABASE,
                    "CommanderDataSync: unable to fetch master record for ${AccelByteEnv.userId}: ${err.message}"
                )
                throw err
            }

            val commanderKeys = masterRecord.commanderKeys
            Logger.debug(LogCategory.DATABASE, "CommanderDataSync: commanderKeys.size=${commanderKeys.size}, isEmpty=${commanderKeys.isEmpty()}")
            Logger.debug(LogCategory.DATABASE, "CommanderDataSync: commanderKeys.size=${commanderKeys.size}, keys=$commanderKeys")
            
            if(commanderKeys.isEmpty())
            {
                messageBox.setMessage("No saved commanders were found.")
                World.availableCommanders.clear()
                Logger.debug(LogCategory.DATABASE, "CommanderDataSync: No commanders found, returning early")
            return@withTemporaryConnection
            }

            Logger.debug(LogCategory.DATABASE, "CommanderDataSync: About to clear World.availableCommanders and process ${commanderKeys.size} commanders")
            World.availableCommanders.clear()
            commanderKeys.forEachIndexed { index, commanderName ->
                Logger.debug(LogCategory.DATABASE, "CommanderDataSync: Processing commander $index: '$commanderName'")
                val sanitizedKey = VfsSanitizer.sanitize(commanderName)
                messageBox.setMessage("Loading commander ${index + 1}/${commanderKeys.size}: $commanderName")
                Logger.debug(
                    LogCategory.DATABASE,
                    "CommanderDataSync: processing commander '$commanderName' (cloudsave key=$sanitizedKey)"
                )

                val cachedCommander = CommanderCache.loadCommanderRecord(sanitizedKey)
                Logger.debug(LogCategory.DATABASE, "CommanderDataSync: cachedCommander for '$commanderName' (key=$sanitizedKey): ${if(cachedCommander != null) "FOUND" else "NULL"}")
                
                val commander = if(cachedCommander != null && cachedCommander.name.isNotBlank())
                {
                    Logger.debug(LogCategory.DATABASE, "CommanderDataSync: Using cached commander: name='${cachedCommander.name}' empire='${cachedCommander.empire}'")
                    Logger.debug(LogCategory.DATABASE, "CommanderDataSync: Using cached commander '$commanderName'")
                    cachedCommander
                }
                else
                {
                    Logger.debug(LogCategory.DATABASE, "CommanderDataSync: Cache miss or invalid, fetching from proxy")
                    fetchCommanderFromProxy(commanderName, sanitizedKey)
                }

                Logger.debug(LogCategory.DATABASE, "CommanderDataSync: Adding commander to World.availableCommanders: name='${commander.name}'")
                World.availableCommanders.add(commander)
            }
        }
    }
    catch(err: Throwable)
    {
        Logger.error(LogCategory.DATABASE, "CommanderDataSync: server-extend connection failed: ${err.message}")
        throw err
    }
}

internal suspend fun fetchCommanderFromProxy(
    commanderName: String,
    sanitizedKey: String
): Commander
{
    Logger.debug(
        LogCategory.DATABASE,
        "CommanderDataSync: fetching commander '$commanderName' via server-extend proxy (key=$sanitizedKey)"
    )

    val rpcInvoker = ServerExtendBridge.rpcInvoker
        ?: error("RPC invoker unavailable while loading saved commanders")

    val keysToTry = buildCommanderKeyCandidates(commanderName, sanitizedKey)
    val (payloadString, usedKey) = fetchCommanderPayload(rpcInvoker, keysToTry)
        ?: throw IllegalStateException("Commander record '$commanderName' was empty (keys tried=${keysToTry.joinToString()})")

    if(usedKey != sanitizedKey)
    {
        Logger.debug(
            LogCategory.DATABASE,
            "CommanderDataSync: fetched commander '$commanderName' using fallback key '$usedKey'"
        )
    }

    Logger.info(LogCategory.DATABASE, "CommanderDataSync: RAW payload string (length=${payloadString.length}): $payloadString")
    
    val payloadElement = Json.parseToJsonElement(payloadString)
    Logger.info(LogCategory.DATABASE, "CommanderDataSync: Parsed to JsonElement type=${payloadElement::class.simpleName}")
    Logger.info(LogCategory.DATABASE, "CommanderDataSync: JsonElement content: $payloadElement")
    
    val commander = AccelByteJson.decodeFromJsonElement(
        Commander.serializer(),
        payloadElement
    )
    
    Logger.info(LogCategory.DATABASE, "CommanderDataSync: Deserialized Commander - name='${commander.name}' description='${commander.description}' empire='${commander.empire}'")
    Logger.info(LogCategory.DATABASE, "CommanderDataSync: Commander full object: $commander")
    
    CommanderCache.cacheCommanderRecord(sanitizedKey, commander)
    Logger.debug(LogCategory.DATABASE, "CommanderDataSync: cached commander '$commanderName'")
    return commander
}

/**
 * Builds a prioritized list of commander key variants for proxy fetch retries.
 */
private fun buildCommanderKeyCandidates(commanderName: String, sanitizedKey: String): List<String>
{
    return listOf(
        sanitizedKey,
        commanderName,
        commanderName.lowercase(),
        commanderName.uppercase()
    ).distinct()
}

/**
 * Attempts to read commander data using the provided key candidates.
 *
 * @return the fetched payload and the key that succeeded or null if every attempt failed.
 */
private suspend fun fetchCommanderPayload(
    rpcInvoker: RpcInvoker,
    keys: List<String>
): Pair<String, String>?
{
    Logger.debug(LogCategory.DATABASE, "CommanderDataSync.fetchCommanderPayload: Trying ${keys.size} keys: ${keys.joinToString()}")
    
    keys.forEachIndexed { index, key ->
        Logger.debug(LogCategory.DATABASE, "CommanderDataSync.fetchCommanderPayload: Attempt ${index + 1}/${keys.size} with key='$key'")
        
        val response = rpcInvoker.invoke(
            "server.extend.proxyGetCommanderRecord",
            CommanderRecordRequest(
                userId = AccelByteEnv.userId,
                key = key
            ),
            CommanderRecordRequest.serializer()
        )

        Logger.debug(LogCategory.DATABASE, "CommanderDataSync.fetchCommanderPayload: Response for key='$key': error=${response.error}, hasResult=${response.result != null}")

        val payloadString = response.result?.let {
            RpcJson.decodeFromJsonElement(serializer<String>(), it)
        }.orEmpty()

        Logger.debug(LogCategory.DATABASE, "CommanderDataSync.fetchCommanderPayload: Payload for key='$key': length=${payloadString.length}, blank=${payloadString.isBlank()}")

        if(payloadString.isNotBlank())
        {
            Logger.debug(LogCategory.DATABASE, "CommanderDataSync.fetchCommanderPayload: SUCCESS with key='$key'")
            return payloadString to key
        }
    }
    
    Logger.error(LogCategory.DATABASE, "CommanderDataSync.fetchCommanderPayload: FAILED - All ${keys.size} keys returned empty")
    return null
}

internal suspend fun fetchMasterRecordViaProxy(messageBox: MessageBox): MasterRecord
{
    val invoker = ServerExtendBridge.rpcInvoker
        ?: throw IllegalStateException("RPC invoker unavailable for master record fetch")

    val rpcResponse = invoker.invoke(
        "server.extend.getMasterRecord",
        AccelByteEnv.userId,
        serializer()
    )

    val masterRecord = rpcResponse.result?.let {
        RpcJson.decodeFromJsonElement(MasterRecord.serializer(), it)
    } ?: MasterRecord()
    
    Logger.debug(LogCategory.DATABASE, "Client received master record with ${masterRecord.commanderKeys.size} commander keys: ${masterRecord.commanderKeys}")
    Logger.debug(LogCategory.DATABASE, "CommanderDataSync: received master record with ${masterRecord.commanderKeys.size} commander keys: ${masterRecord.commanderKeys}")
    
    return masterRecord
}
