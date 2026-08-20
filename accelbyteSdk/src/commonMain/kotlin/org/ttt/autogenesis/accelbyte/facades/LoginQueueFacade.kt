package org.ttt.autogenesis.accelbyte.facades

import kotlin.js.Json
import kotlin.js.Promise
import org.ttt.autogenesis.accelbyte.AccelByteSdkInstance
import org.ttt.autogenesis.accelbyte.modules.LoginQueueModulePackage
import org.ttt.autogenesis.accelbyte.util.propagateJsErrors

/**
 * A thin facade over the AccelByte Login Queue module. Manages login queue configuration and
 * user queue tickets. Used during high-traffic periods when the player cap is reached —
 * clients receive a ticket and poll (or await WS delivery) until a slot opens.
 */
class LoginQueueFacade(private val sdk : AccelByteSdkInstance)
{
    private val v1AdminApi = LoginQueueModulePackage.LoginQueue.V1AdminApi(sdk.rawSdk)
    private val ticketV1Api = LoginQueueModulePackage.LoginQueue.TicketV1Api(sdk.rawSdk)

    /**
     * @return current queue configuration JSON via propagateJsErrors
     */
    fun getConfig() : Promise<Json> = v1AdminApi.getConfig().propagateJsErrors()

    /**
     * @param data JSON patch payload for the queue configuration; @return updated config JSON
     */
    fun updateConfig(data : Json) : Promise<Json> = v1AdminApi.updateConfig(data).propagateJsErrors()

    /**
     * @return current queue status JSON (position, estimated wait, etc.)
     */
    fun getStatus() : Promise<Json> = v1AdminApi.getStatus().propagateJsErrors()

    /**
     * @return the current user's login ticket JSON, or empty if not in queue
     */
    fun getTicket() : Promise<Json> = ticketV1Api.getTicket().propagateJsErrors()

    /**
     * @return deleted ticket JSON (clears the user's position)
     */
    fun deleteTicket() : Promise<Json> = ticketV1Api.deleteTicket().propagateJsErrors()
}