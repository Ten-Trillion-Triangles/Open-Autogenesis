@file:JsModule("@accelbyte/sdk-iam")
@file:JsNonModule

package org.ttt.autogenesis.accelbyte.modules

import kotlin.js.Json
import kotlin.js.Promise
import org.ttt.autogenesis.accelbyte.AccelByteSDK
import org.ttt.autogenesis.accelbyte.SdkSetConfigParam

external val Iam : IamNamespace

/**
 * Namespace interface for IAM-related APIs and services.
 * Contains factories for OAuth 2.0 flows.
 */
external interface IamNamespace
{
    fun OAuth20Api(sdk : AccelByteSDK, args : SdkSetConfigParam = definedExternally) : OAuth20Api
}

/**
 * OAuth 2.0 API interface for authentication operations.
 * Provides methods for token management and validation.
 */
external interface OAuth20Api
{
    /**
     * Posts OAuth token request.
     * @param data Token request data
     * @return Promise resolving to token response
     */
    fun postOauthToken_v3(data : Json) : Promise<Json>
    
    /**
     * Revokes OAuth token.
     * @param data Token revocation data
     * @return Promise resolving to revocation response
     */
    fun postOauthRevoke_v3(data : Json) : Promise<Json>
    
    /**
     * Introspects OAuth token.
     * @param data Token introspection data
     * @return Promise resolving to introspection response
     */
    fun postOauthIntrospect_v3(data : Json) : Promise<Json>
}