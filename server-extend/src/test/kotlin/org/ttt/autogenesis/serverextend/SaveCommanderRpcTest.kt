package org.ttt.autogenesis.serverextend

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Test
import org.junit.Ignore
import org.ttt.autogenesis.network.RestRpcClient
import org.ttt.autogenesis.network.RestRpcClientConfig
import org.ttt.autogenesis.network.RpcDirection
import org.ttt.autogenesis.network.RpcRegistry
import structs.requests.CommanderCreateRequest
import structs.rpcRequests.CommanderCreateRpcRequest

/**
 * Integration test that connects to server at port 7070
 */
class SaveCommanderRpcTest {
    
    @Test
    @Ignore("Integration test requires running server at port 7070")
    fun `connect to server at 7070 and call saveCommander RPC`() = runTest {
        val rpcRequest = CommanderCreateRpcRequest(
            accelbyteId = "test-accelbyte-id-from-unittest",
            commanderRequest = CommanderCreateRequest(
                name = "Test Commander from Unit Test",
                description = "A brave test commander from integration test", 
                empireDescription = "Test Empire of Testing"
            )
        )
        
        val client = RestRpcClient(
            config = RestRpcClientConfig(
                baseUrl = "http://localhost:7070",
                playerId = "unit-test-player-123"
            ),
            rpcRegistry = RpcRegistry(RpcDirection.CLIENT)
        )
        
        try {
            client.connect()
            println("Connecting to server...")
            
            // Wait for server session ready confirmation
            withContext(Dispatchers.Default.limitedParallelism(1)) {
                withTimeout(5000) {
                    while (!client.isSessionReady()) {
                        println("Waiting for session ready... isConnected: ${client.isConnected()}, isSessionReady: ${client.isSessionReady()}")
                        delay(50)
                    }
                }
            }
            
            println("Session ready, sending RPC...")
            val handle = client.rpcInvoker.request(
                method = "extend.saveCommander", 
                params = rpcRequest,
                timeoutMillis = 3000L
            )
            val response = handle.await()
            println("RPC Response: $response")
            
        } finally {
            client.close()
        }
    }
}
