package structs.responses

/**
 * Rpc response class for handling player connection handshakes.
 */
@kotlinx.serialization.Serializable
data class PlayerConnectResponse(
    var accelByteId: String = "",
    var commanderName: String = ""
)
