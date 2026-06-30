package org.ttt.autogenesis.serverextend

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Test
import org.ttt.autogenesis.network.RestRpcClient
import org.ttt.autogenesis.network.RestRpcClientConfig
import org.ttt.autogenesis.network.RpcDirection
import org.ttt.autogenesis.network.RpcJson
import org.ttt.autogenesis.network.RpcRegistry
import structs.account.AccountPlan
import structs.account.AccountSettings
import structs.account.BillingStatus
import structs.rpcRequests.GetAccountSettingsRequest
import structs.rpcRequests.SaveAccountSettingsRequest

/**
 * Integration test that connects to server-extend at port 7070
 * to test the CloudSaveProxy saveAccountSettings and getAccountSettings RPC methods.
 */
class AccountSettingsRpcTest {

    @Test
    fun `connect to server at 7070 and call saveAccountSettings then getAccountSettings RPC`() = runTest {
        val testUserId = "00000000000000000000000000000000"
        val testDisplayName = "TestPlayer"

        val accountSettingsToSave = AccountSettings(
            accelByteUserId = testUserId,
            displayName = testDisplayName,
            billingStatus = BillingStatus(
                credits = 1234.56,
                plan = AccountPlan.PRO,
                remainingInferenceMinutes = 999L,
                autoRenew = true
            )
        )

        val client = RestRpcClient(
            config = RestRpcClientConfig(
                baseUrl = "http://localhost:7070",
                playerId = "rpc-test-account-settings"
            ),
            rpcRegistry = RpcRegistry(RpcDirection.CLIENT)
        )

        try {
            client.connect()
            println("Connecting to server-extend...")

            // Wait for server session ready confirmation
            withContext(Dispatchers.Default.limitedParallelism(1)) {
                withTimeout(5000) {
                    while (!client.isSessionReady()) {
                        println("Waiting for session ready... isConnected: ${client.isConnected()}, isSessionReady: ${client.isSessionReady()}")
                        delay(50)
                    }
                }
            }

            println("Session ready, saving account settings...")
            val settingsJson = RpcJson.encodeToString(AccountSettings.serializer(), accountSettingsToSave)
            val saveRequest = SaveAccountSettingsRequest(testUserId, settingsJson)

            val saveHandle = client.rpcInvoker.request(
                method = "server.extend.saveAccountSettings",
                params = saveRequest,
                timeoutMillis = 3000L
            )
            val saveResponse = saveHandle.await()
            println("Save RPC Response: $saveResponse")

            println("Retrieving account settings...")
            val getRequest = GetAccountSettingsRequest(testUserId)

            val getHandle = client.rpcInvoker.request(
                method = "server.extend.getAccountSettings",
                params = getRequest,
                timeoutMillis = 3000L
            )
            val getResponse = getHandle.await()
            println("Get RPC Response: $getResponse")

            val retrievedSettings = getResponse.result?.let {
                RpcJson.decodeFromJsonElement(AccountSettings.serializer(), it)
            }

            // Assert the retrieved record matches the saved values
            assert(retrievedSettings != null) { "Retrieved settings should not be null" }
            assert(retrievedSettings!!.accelByteUserId == testUserId) {
                "Expected userId=$testUserId but got ${retrievedSettings.accelByteUserId}"
            }
            assert(retrievedSettings.displayName == testDisplayName) {
                "Expected displayName=$testDisplayName but got ${retrievedSettings.displayName}"
            }
            assert(retrievedSettings.billingStatus.credits == 1234.56) {
                "Expected credits=1234.56 but got ${retrievedSettings.billingStatus.credits}"
            }
            assert(retrievedSettings.billingStatus.plan == AccountPlan.PRO) {
                "Expected plan=PRO but got ${retrievedSettings.billingStatus.plan}"
            }

            println("All assertions passed!")

        } finally {
            client.close()
        }
    }
}
