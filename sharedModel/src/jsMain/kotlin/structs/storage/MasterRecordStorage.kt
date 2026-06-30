package structs.storage

import kotlin.js.Json
import kotlin.js.JSON
import kotlin.js.json
import kotlinx.coroutines.await
import kotlinx.serialization.serializer
import org.ttt.autogenesis.accelbyte.AccelByteSdkInstance
import org.ttt.autogenesis.accelbyte.facades.PlayerRecordFacade
import structs.accelbyte.cloudsave.PlayerRecordResponse
import structs.accelbyte.common.AccelByteJson

actual object MasterRecordStorage
{
    private var jsSdkFactory : (() -> AccelByteSdkInstance)? = null

    actual fun configureSdk(provider : (() -> Any)?)
    {
        jsSdkFactory = provider?.let { factory ->
            {
                (factory.invoke() as? AccelByteSdkInstance)
                    ?: error("MasterRecordStorage JS provider must return AccelByteSdkInstance")
            }
        }
    }

    actual suspend fun fetchMasterRecord(userId : String) : Result<MasterRecord>
    {
        return runCatching()
        {
            val facade = resolveFacade()
            val recordJson = facade.fetchRecord(userId, MASTER_RECORD_KEY).await()
            recordJson.toMasterRecord()
        }
    }

    actual suspend fun saveMasterRecord(userId : String, record : MasterRecord) : Result<MasterRecord>
    {
        return runCatching()
        {
            val facade = resolveFacade()
            val payload = record.toPayload()
            val response = runCatching()
            {
                facade.updateRecord(userId, MASTER_RECORD_KEY, payload).await()
            }.recoverCatching()
            {
                facade.createRecord(userId, MASTER_RECORD_KEY, payload).await()
            }.getOrThrow()
            response.toMasterRecord(default = record)
        }
    }

    private fun resolveFacade() : PlayerRecordFacade
    {
        val factory = jsSdkFactory ?: error("MasterRecordStorage JS SDK provider is missing")
        return PlayerRecordFacade(factory())
    }

    private fun Json?.toMasterRecord(default : MasterRecord = MasterRecord()) : MasterRecord
    {
        return this?.let { json ->
            val jsonString = JSON.stringify(json)
            val record = JSON.parse<Json>(jsonString)
            val playerResponse = AccelByteJson.decodeFromString(PlayerRecordResponse.serializer(), JSON.stringify(record))
            playerResponse.toMasterRecord(default)
        } ?: default
    }

    private fun PlayerRecordResponse.toMasterRecord(default : MasterRecord = MasterRecord()) : MasterRecord
    {
        val valueElement = this.value ?: return default
        val jsonString = JSON.stringify(valueElement)
        return AccelByteJson.decodeFromString(MasterRecord.serializer(), jsonString)
    }

    private fun MasterRecord.toPayload() : Json
    {
        val recordJson = JSON.parse<Json>(AccelByteJson.encodeToString(MasterRecord.serializer(), this))
        return json("value" to recordJson)
    }
}
