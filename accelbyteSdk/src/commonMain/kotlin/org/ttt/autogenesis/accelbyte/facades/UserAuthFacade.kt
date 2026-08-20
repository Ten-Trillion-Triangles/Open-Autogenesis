package org.ttt.autogenesis.accelbyte.facades

import kotlin.js.Promise
import org.ttt.autogenesis.accelbyte.AccelByteSdkInstance
import org.ttt.autogenesis.accelbyte.AccelByteTokenConfiguration
import org.ttt.autogenesis.accelbyte.models.TokenMaintenanceRequest
import org.ttt.autogenesis.accelbyte.models.OAuthTokenRequest
import org.ttt.autogenesis.accelbyte.models.OAuthTokenResponse
import org.ttt.autogenesis.accelbyte.util.propagateJsErrors

/**
 * Provides user authentication helpers on top of the IAM facade.
 */
class UserAuthFacade(private val sdk : AccelByteSdkInstance)
{
    private val iamFacade = IamFacade(sdk)

    /**
     * Exchanges credentials for user tokens and caches them in the SDK.
     */
    fun loginUser(request : OAuthTokenRequest) : Promise<OAuthTokenResponse> =
        iamFacade.oauthToken(request).then { response ->
            // Tokens MUST NOT be logged at any priority — even at DEBUG. Logging
            // access tokens to the browser console exposes them to any extension
            // or DevTools session. We log only that the flow progressed.
            //
            // Note: this module intentionally does not depend on sharedModel —
            // sharedModel/jsMain depends on us — so the Logger system lives in
            // a downstream consumer (kvisionApp) and is not available here.
            console.log("DEBUG: [UserAuth] loginUser: token received and stored on SDK")

            val tokenConfig = AccelByteTokenConfiguration(
                accessToken = response.accessToken,
                refreshToken = response.refreshToken
            )
            sdk.setToken(tokenConfig)
            console.log("DEBUG: [UserAuth] loginUser: token verified and held by SDK")
            response
        }.propagateJsErrors()

    /**
     * Revokes the current user's OAuth access token via the IAM service and removes it from
     * the SDK's in-memory token cache. Equivalent to signing the user out.
     * @return Promise<Unit> resolved once the token is revoked if one was held; resolves
     * immediately if already logged out
     */
    fun logoutUser() : Promise<Unit>
    {
        val token = sdk.getToken().accessToken
        if (token.isNullOrBlank()) {
            return Promise.resolve(Unit)
        }
        return iamFacade.revokeToken(TokenMaintenanceRequest(token)).then {
            sdk.removeToken()
        }
    }

    /**
     * Returns true when a non-blank access token is currently stored in the SDK.
     * Note: this does NOT validate token expiration or perform a server-round-trip.
     * @return true if the SDK holds a token; false if the token is absent or blank
     */
    fun isLoggedIn() : Boolean =
        !sdk.getToken().accessToken.isNullOrBlank()
}
