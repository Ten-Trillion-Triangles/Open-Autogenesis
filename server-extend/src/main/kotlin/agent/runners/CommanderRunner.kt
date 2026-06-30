package agent.runners

import agent.builders.buildCommanderCreationAgent
import com.TTT.Config.TPipeConfig
import com.TTT.Context.ContextWindow
import com.TTT.Debug.TraceConfig
import com.TTT.Debug.TraceDetailLevel
import com.TTT.Debug.TraceFormat
import com.TTT.Pipe.MultimodalContent
import com.TTT.Util.serialize
import com.TTT.Util.writeStringToFile
import commonGlobals.VfsSanitizer
import globals.ExtendConfig
import kotlinx.serialization.json.Json
import org.ttt.autogenesis.logging.LogCategory
import org.ttt.autogenesis.logging.Logger
import org.ttt.autogenesis.network.RpcCallContext
import org.ttt.autogenesis.network.RpcDirection
import org.ttt.autogenesis.network.RpcMethod
import structs.requests.CommanderCreateRequest
import structs.rpcRequests.CommanderCreateRpcRequest
import structs.storage.MasterRecord
import structs.storage.MasterRecordStorage
import org.ttt.autogenesis.server.vfs.VirtualFileSystemManager

object ExtendCommanderRunner
{

    @RpcMethod("extend.saveCommander", RpcDirection.SERVER)
    suspend fun saveCommander(context: RpcCallContext, traits: CommanderCreateRpcRequest) : Boolean
    {
        val masterRecord = MasterRecordStorage.fetchMasterRecord(traits.accelbyteId).getOrElse { MasterRecord() }
        Logger.info(LogCategory.DATABASE, "extend.saveCommander invoked for ${traits.commanderRequest.name} (user=${traits.accelbyteId})")
        val agenticPipeline = buildCommanderCreationAgent() //Build agent.
        val requestAsJson = serialize(traits.commanderRequest) //Get request to json.
        val newContextWindow = ContextWindow() //Required boilerplate.
        newContextWindow.contextElements.add(requestAsJson) //Always save generic data to the context elements.

        agenticPipeline.context = newContextWindow //Bind context to our pipeline for this task.

        try
        {
            //Run the agent to determine the core values of the commander.
            agenticPipeline.enableTracing(TraceConfig(detailLevel = TraceDetailLevel.DEBUG))
            agenticPipeline.init(true)
            val result = agenticPipeline.execute(MultimodalContent(requestAsJson))
            val combinedContext = agenticPipeline.context
            val commanderAsJson = combinedContext.contextElements[0]

            if(ExtendConfig.debugMode)
            {
                val trace = agenticPipeline.getTraceReport(TraceFormat.HTML)
                writeStringToFile("${TPipeConfig.getTraceDir()}/SaveCommander.html", trace)
            }




            //Save the agent to disk or CloudSave.
            val vfs = VirtualFileSystemManager.forUser(traits.accelbyteId)
            val originalKey = traits.commanderRequest.name
            val sanitizedKey = VfsSanitizer.sanitize(originalKey)
            
            Logger.info(LogCategory.DATABASE, "extend.saveCommander: Saving commander originalKey='$originalKey' sanitizedKey='$sanitizedKey'")
            
            val recordResult = vfs.saveUserRecordFromJsonString(
                traits.accelbyteId,
                originalKey,
                commanderAsJson)
            
            if(recordResult.isSuccess)
            {
                Logger.info(LogCategory.DATABASE, "extend.saveCommander: Commander record saved successfully")
                
                val commanderKey = traits.commanderRequest.name
                if(!masterRecord.commanderKeys.contains(commanderKey))
                {
                    Logger.info(LogCategory.DATABASE, "extend.saveCommander: Adding '$commanderKey' to master record (currently has ${masterRecord.commanderKeys.size} keys)")
                    masterRecord.commanderKeys.add(commanderKey)
                }
                else
                {
                    Logger.debug(LogCategory.DATABASE, "extend.saveCommander: Key '$commanderKey' already in master record")
                }

                //Update the master record if we were able to save as expected.
                val masterSaveResult = MasterRecordStorage.saveMasterRecord(traits.accelbyteId, masterRecord)
                if(masterSaveResult.isFailure)
                {
                    Logger.warn(
                        LogCategory.DATABASE,
                        "Failed to update master record for user ${traits.accelbyteId} key=$commanderKey: ${masterSaveResult.exceptionOrNull()?.message}"
                    )
                }
                Logger.info(LogCategory.DATABASE, "Commander record saved for ${traits.commanderRequest.name} (${traits.accelbyteId})")
            }
            else
            {
                Logger.warn(LogCategory.DATABASE, "Failed to save commander ${traits.commanderRequest.name}: ${recordResult.exceptionOrNull()?.message}")
            }
            return recordResult.isSuccess
        }

        catch (e: Exception)
        {
            Logger.error(LogCategory.DATABASE, "Failed to save commander ${traits.commanderRequest.name}: ${e.message}")
        }

        return false
    }

}
