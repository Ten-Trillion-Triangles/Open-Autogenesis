package rpc

import structs.Player

/**
 * Defines login and logout handshake rpc's for the client and server.
 */

data class clientConnectionResponse(
    var socketId: String
)

data class serverConnectionResponse(
    var accelbyteId: String = "",
    var commander: Player = Player()
)


