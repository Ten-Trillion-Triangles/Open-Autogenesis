package org.ttt.autogenesis.accelbyte.facades

import kotlin.js.Promise
import org.ttt.autogenesis.accelbyte.AccelByteSdkInstance
import org.ttt.autogenesis.accelbyte.buildJs
import org.ttt.autogenesis.accelbyte.models.OAuthTokenRequest
import org.ttt.autogenesis.accelbyte.models.OAuthTokenResponse
import org.ttt.autogenesis.accelbyte.models.RefreshTokenRequest
import org.ttt.autogenesis.accelbyte.models.TokenIntrospectionResponse
import org.ttt.autogenesis.accelbyte.models.TokenMaintenanceRequest
import org.ttt.autogenesis.accelbyte.models.TokenRefreshResponse
import org.ttt.autogenesis.accelbyte.modules.Iam
import org.ttt.autogenesis.accelbyte.modules.OAuth20Api
import org.ttt.autogenesis.accelbyte.modules.RefreshToken
import org.ttt.autogenesis.accelbyte.modules.RefreshTokenConstructor
import org.ttt.autogenesis.accelbyte.util.mapJson
import org.ttt.autogenesis.accelbyte.util.mapToUnit
import org.ttt.autogenesis.accelbyte.util.propagateJsErrors

/**
 * Convenience wrapper over `@accelbyte/sdk-iam` for obtaining, revoking, introspecting, and
 * refreshing OAuth tokens.
 *
 * Each method now deserializes the underlying JSON payload into a specific response model,
 * so callers receive typed credential metadata rather than raw `Json`.
 *
 * The facade propagates promise rejections from the underlying Axios call (HTTP errors, network failures).
 */
class IamFacade(private val sdk : AccelByteSdkInstance)
{
    private val oauthApi : OAuth20Api
        get() = Iam.OAuth20Api(sdk.rawSdk)

    /**
     * Exchanges OAuth credentials for access and refresh tokens using various grant types.
     * 
     * Supports multiple authentication flows including password, client credentials, 
     * authorization code, and refresh token grants. Essential for user login and service authentication.
     *
     * @param request OAuthTokenRequest containing grant type, credentials, and optional scope
     * @return `Promise<OAuthTokenResponse>` containing token metadata
     *         - `access_token: string` - Bearer token for API authentication
     *         - `refresh_token: string` - Token for obtaining new access tokens
     *         - `expires_in: number` - Access token lifetime in seconds
     *         - `token_type: string` - Always "Bearer"
     *         - `scope: string` - Granted permissions scope
     *         - `namespace: string` - Associated namespace
     * @throws AuthenticationException for invalid credentials
     * @throws ValidationException for malformed request parameters
     * @throws NetworkException on connection failures
     */
    fun oauthToken(request : OAuthTokenRequest) : Promise<OAuthTokenResponse> =
        oauthApi.postOauthToken_v3(request.toJson())
            .propagateJsErrors()
            .mapJson(OAuthTokenResponse::fromJson)

    /**
     * Revokes an access or refresh token, making it immediately invalid for future use.
     * 
     * Use this for logout functionality or security cleanup. Revoked tokens cannot be used
     * for API calls or token refresh operations.
     *
     * @param request TokenMaintenanceRequest containing the token to revoke
     * @return `Promise<Unit>` that completes when the token has been revoked
     * @throws AuthenticationException if token is already invalid
     * @throws ValidationException for malformed token format
     * @throws NetworkException on connection failures
     */
    fun revokeToken(request : TokenMaintenanceRequest) : Promise<Unit> =
        oauthApi.postOauthRevoke_v3(request.toJson())
            .propagateJsErrors()
            .mapToUnit()

    /**
     * Retrieves metadata and validation status for an access or refresh token.
     * 
     * Returns detailed information about token validity, permissions, and expiration.
     * Useful for token validation and debugging authentication issues.
     *
     * @param request TokenMaintenanceRequest containing the token to inspect
     * @return `Promise<TokenIntrospectionResponse>` with token metadata and expiration status
     *         - `active: boolean` - Whether token is valid and not expired
     *         - `client_id: string` - Client that issued the token
     *         - `username: string` - Associated user (if applicable)
     *         - `scope: string` - Token permissions
     *         - `exp: number` - Expiration timestamp (Unix epoch)
     *         - `iat: number` - Issued at timestamp
     *         - `namespace: string` - Token namespace
     * @throws ValidationException for malformed token format
     * @throws NetworkException on connection failures
     */
    fun introspectToken(request : TokenMaintenanceRequest) : Promise<TokenIntrospectionResponse> =
        oauthApi.postOauthIntrospect_v3(request.toJson())
            .propagateJsErrors()
            .mapJson(TokenIntrospectionResponse::fromJson)

    /**
     * Obtains new access and refresh tokens using an existing refresh token.
     * 
     * Implements secure token rotation - the old refresh token is invalidated and a new
     * pair is issued. Use this to maintain user sessions without re-authentication.
     *
     * @param request RefreshTokenRequest with refresh token and client credentials
     * @return `Promise<TokenRefreshResponse>` returning the refreshed credentials
     *         - `access_token: string` - New bearer token for API authentication
     *         - `refresh_token: string` - New refresh token (old one is invalidated)
     *         - `expires_in: number` - New access token lifetime in seconds
     *         - `token_type: string` - Always "Bearer"
     *         - `scope: string` - Maintained permissions scope
     * @throws AuthenticationException if refresh token is expired or invalid
     * @throws ValidationException for malformed request parameters
     * @throws NetworkException on connection failures
     */
    fun refreshSession(request : RefreshTokenRequest) : Promise<TokenRefreshResponse> {
        val refresher = RefreshToken(
            buildJs<RefreshTokenConstructor> {
                config = request.toRefreshArgs()
            }
        )
        return refresher.refreshToken()
            .propagateJsErrors()
            .mapJson(TokenRefreshResponse::fromJson)
    }
}
