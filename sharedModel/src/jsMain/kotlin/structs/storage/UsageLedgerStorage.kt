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
import structs.account.UsageLedger

actual object UsageLedgerStorage
{
    private var jsSdkFactory : (() -> AccelByteSdkInstance)? = null

    actual fun configureSdk(provider : (() -> Any)?)
    {
        jsSdkFactory = provider?.let { factory ->
            {
                (factory.invoke() as? AccelByteSdkInstance)
                    ?: error("UsageLedgerStorage JS provider must return AccelByteSdkInstance")
            }
        }
    }

    actual suspend fun fetchUsageLedger(userId : String) : Result<UsageLedger>
    {
        return runCatching()
        {
            val facade = resolveFacade()
            val recordJson = facade.fetchRecord(userId, USAGE_LEDGER_KEY).await()
            recordJson.toUsageLedger(default = UsageLedger(accelByteUserId = userId))
        }
    }

    actual suspend fun saveUsageLedger(userId : String, ledger : UsageLedger) : Result<UsageLedger>
    {
        return runCatching()
        {
            val facade = resolveFacade()
            val payload = ledger.toPayload()
            val response = runCatching()
            {
                facade.updateRecord(userId, USAGE_LEDGER_KEY, payload).await()
            }.recoverCatching()
            {
                facade.createRecord(userId, USAGE_LEDGER_KEY, payload).await()
            }.getOrThrow()
            response.toUsageLedger(default = ledger)
        }
    }

    private fun resolveFacade() : PlayerRecordFacade
    {
        val factory = jsSdkFactory ?: error("UsageLedgerStorage JS SDK provider is missing")
        return PlayerRecordFacade(factory())
    }

    private fun Json?.toUsageLedger(default : UsageLedger) : UsageLedger
    {
        if (this == null) return default
        val jsonString = JSON.stringify(this)
        val record = JSON.parse<Json>(jsonString)
        val playerResponse = AccelByteJson.decodeFromString(PlayerRecordResponse.serializer(), JSON.stringify(record))
        return playerResponse.toUsageLedger(default)
    }

    private fun PlayerRecordResponse.toUsageLedger(default : UsageLedger) : UsageLedger
    {
        val valueElement = this.value ?: return default
        val jsonString = JSON.stringify(valueElement)
        return AccelByteJson.decodeFromString(UsageLedger.serializer(), jsonString)
    }

    private fun UsageLedger.toPayload() : Json
    {
        val recordJson = JSON.parse<Json>(AccelByteJson.encodeToString(UsageLedger.serializer(), this))
        return json("value" to recordJson)
    }
}
