package globals

/**
 * Global data class to house important data to transporting the critical websocket connection
 * info the game server to ensure async callbacks can be made outside the initial scope of
 * the rpc function.
 */
object WebsocketConfig
{
    var websocketId: String = ""
}