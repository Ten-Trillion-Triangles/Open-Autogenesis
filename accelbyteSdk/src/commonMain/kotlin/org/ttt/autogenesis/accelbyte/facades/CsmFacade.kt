package org.ttt.autogenesis.accelbyte.facades

import kotlin.js.Promise
import org.ttt.autogenesis.accelbyte.AccelByteSdkInstance
import org.ttt.autogenesis.accelbyte.models.CsmMessageListResponse
import org.ttt.autogenesis.accelbyte.modules.CsmModulePackage
import org.ttt.autogenesis.accelbyte.modules.MessagesApi
import org.ttt.autogenesis.accelbyte.util.mapJson
import org.ttt.autogenesis.accelbyte.util.propagateJsErrors

/**
 * Wraps Customer Service Management (CSM) operations for message handling. Methods return raw JSON
 * containing message arrays, status information, or error payloads; callers should map these into their domain models.
 *
 * JSON Response Structures:
 * - Message List: `{ "messages": [{ "id": "string", "content": "string", "type": "string", "createdAt": "ISO8601", "status": "string" }], "paging": {...} }`
 *
 * @param sdk AccelByte SDK instance used to resolve CSM APIs.
 */
class CsmFacade(private val sdk : AccelByteSdkInstance)
{
    private val messagesApi : MessagesApi
        get() = CsmModulePackage.Csm.MessagesApi(sdk.rawSdk)

    /**
     * Retrieves all customer service messages for the current context.
     *
     * @return `Promise<CsmMessageListResponse>` with paging metadata
     * @throws NetworkException on connection failures
     * @throws AuthenticationException if session token is invalid
     * @throws AuthorizationException if user lacks permission to read messages
     */
    fun listMessages() : Promise<CsmMessageListResponse> =
        messagesApi.getMessages()
            .propagateJsErrors()
            .mapJson(CsmMessageListResponse::fromJson)
}
