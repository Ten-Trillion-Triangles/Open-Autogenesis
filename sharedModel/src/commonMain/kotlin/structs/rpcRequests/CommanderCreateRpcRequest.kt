package structs.rpcRequests

import structs.requests.CommanderCreateRequest

@kotlinx.serialization.Serializable
data class CommanderCreateRpcRequest(
    var accelbyteId: String = "",
    var commanderRequest: CommanderCreateRequest = CommanderCreateRequest()
)