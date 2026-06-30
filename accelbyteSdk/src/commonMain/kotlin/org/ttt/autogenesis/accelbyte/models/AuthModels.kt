package org.ttt.autogenesis.accelbyte.models

import kotlin.js.Json
import kotlin.js.json
import org.ttt.autogenesis.accelbyte.modules.RefreshArgs
import org.ttt.autogenesis.accelbyte.buildJs

enum class GrantType(internal val value : String)
{
    AUTHORIZATION_CODE("authorization_code"),
    CLIENT_CREDENTIALS("client_credentials"),
    PASSWORD("password"),
    REFRESH_TOKEN("refresh_token"),
    EXTEND_CLIENT_CREDENTIALS("urn : ietf : params : oauth : grant-type : extend_client_credentials")
}

/**
 * Mirror of the OAuth 2.0 request body accepted by POST /oauth/token.
 * Each field maps to a standard OAuth grant parameter; omit headers that the SDK already configures.
 *
 * The Promise Json returned by IamFacade.oauthToken encapsulates the raw access/refresh token payload
 * (e.g., access_token, expires_in, scope, etc.).
 *
 * @property grantType determines which grant is used; choose PASSWORD, CLIENT_CREDENTIALS, etc.
 * @property username user name for resource-owner grant.
 * @property password password for resource-owner grant.
 * @property clientId OAuth client identifier.
 * @property clientSecret OAuth client secret.
 * @property refreshToken refresh token used when grantType is REFRESH_TOKEN.
 * @property scope optional scope string override (omit to use SDK defaults).
 */
data class OAuthTokenRequest(
    val grantType : GrantType,
    val username : String? = null,
    val password : String? = null,
    val clientId : String? = null,
    val clientSecret : String? = null,
    val refreshToken : String? = null,
    val scope : String? = null
) : AccelByteRequest {
    override fun toJson() : Json = jsonOf(
        "grant_type" to grantType.value,
        "username" to username,
        "password" to password,
        "client_id" to clientId,
        "client_secret" to clientSecret,
        "refresh_token" to refreshToken,
        "scope" to scope
    )
}

/**
 * Request object sent to the RefreshTokenFactory helper when rotating refresh tokens.
 * The helper merges these values with the SDK Axios configuration before invoking refreshToken().
 *
 * The returned Promise Json contains the refreshed credentials (see POST /oauth/token).
 *
 * @property refreshToken refresh token value issued earlier.
 * @property clientId client identifier used to issue the refresh token.
 * @property tokenUrl optional endpoint override for refresh; leave null to use the SDK default.
 */
data class RefreshTokenRequest(
    val refreshToken : String,
    val clientId : String,
    val tokenUrl : String? = null
) : AccelByteRequest {
    override fun toJson() : Json = jsonOf(
        "refreshToken" to refreshToken,
        "clientId" to clientId,
        "tokenUrl" to tokenUrl
    )

    fun toRefreshArgs() : RefreshArgs = buildJs<RefreshArgs>
    {
        axiosConfig = json()
        this.refreshToken = refreshToken
        this.clientId = clientId
        this.tokenUrl = tokenUrl
    }
}

/**
 * Represents any API taking a single token (revocation/introspection).
 * Use toJson() when passing into the IAM endpoints; the promise resolves to the server acknowledgement.
 *
 * @property token The token to be revoked or introspected.
 */
data class TokenMaintenanceRequest(val token : String) : AccelByteRequest
{
    override fun toJson() : Json = json("token" to token)
}

/**
 * OAuth 2.0 token response returned by the IAM token endpoint.
 *
 * Contains the access and refresh tokens along with metadata about the token grant,
 * such as expiration time, token type, and granted scopes.
 *
 * @property accessToken The OAuth access token used to authenticate API requests.
 * @property refreshToken The OAuth refresh token used to obtain a new access token when it expires.
 * @property expiresIn The validity duration of the access token in seconds.
 * @property tokenType The token type, typically Bearer.
 * @property scope The space-separated list of granted OAuth scopes.
 * @property namespace The namespace associated with this token response.
 */
data class OAuthTokenResponse(
    val accessToken : String,
    val refreshToken : String,
    val expiresIn : Int,
    val tokenType : String,
    val scope : String,
    val namespace : String?
) : AccelByteResponse {
    companion object
    {
        fun fromJson(json : Json) : OAuthTokenResponse {
            // TypeScript SDK returns full Axios response: { data: {...}, status: 200, headers: {...} }
            // We need to extract the data property to get the actual OAuth response
            val actualData = json.asDynamic().data ?: json
            
            return OAuthTokenResponse(
                accessToken = actualData.unsafeCast<Json>().requireString("access_token"),
                refreshToken = actualData.unsafeCast<Json>().requireString("refresh_token"),
                expiresIn = actualData.unsafeCast<Json>().requireInt("expires_in"),
                tokenType = actualData.unsafeCast<Json>().requireString("token_type"),
                scope = actualData.unsafeCast<Json>().requireString("scope"),
                namespace = actualData.unsafeCast<Json>().optString("namespace")
            )
        }
    }
}

/**
 * OAuth 2.0 token refresh response returned by the IAM token endpoint.
 *
 * Contains the refreshed access and refresh tokens along with updated metadata.
 *
 * @property accessToken The new OAuth access token.
 * @property refreshToken The new OAuth refresh token.
 * @property expiresIn The validity duration of the new access token in seconds.
 * @property tokenType The token type, typically Bearer.
 * @property scope The space-separated list of granted OAuth scopes.
 * @property namespace The namespace associated with this token response.
 */
data class TokenRefreshResponse(
    val accessToken : String,
    val refreshToken : String,
    val expiresIn : Int,
    val tokenType : String,
    val scope : String,
    val namespace : String?
) : AccelByteResponse {
    companion object
    {
        fun fromJson(json : Json) : TokenRefreshResponse {
            val actualData = json.asDynamic().data ?: json
            return TokenRefreshResponse(
                accessToken = actualData.unsafeCast<Json>().requireString("access_token"),
                refreshToken = actualData.unsafeCast<Json>().requireString("refresh_token"),
                expiresIn = actualData.unsafeCast<Json>().requireInt("expires_in"),
                tokenType = actualData.unsafeCast<Json>().requireString("token_type"),
                scope = actualData.unsafeCast<Json>().requireString("scope"),
                namespace = actualData.unsafeCast<Json>().optString("namespace")
            )
        }
    }
}

/**
 * Token introspection response from the IAM service.
 *
 * Returns the validity and metadata of a given access token.
 *
 * @property active Whether the token is currently active and valid.
 * @property clientId The OAuth client ID that owns this token.
 * @property username The resource owner username associated with this token.
 * @property scope The space-separated list of granted OAuth scopes.
 * @property exp The token expiration time as a Unix timestamp.
 * @property iat The token issuance time as a Unix timestamp.
 * @property namespace The namespace associated with this token.
 */
data class TokenIntrospectionResponse(
    val active : Boolean,
    val clientId : String,
    val username : String?,
    val scope : String,
    val exp : Long?,
    val iat : Long?,
    val namespace : String?
) : AccelByteResponse {
    companion object
    {
        fun fromJson(json : Json) : TokenIntrospectionResponse {
            val actualData = json.asDynamic().data ?: json
            return TokenIntrospectionResponse(
                active = actualData.unsafeCast<Json>().requireBoolean("active"),
                clientId = actualData.unsafeCast<Json>().requireString("client_id"),
                username = actualData.unsafeCast<Json>().optString("username"),
                scope = actualData.unsafeCast<Json>().requireString("scope"),
                exp = actualData.unsafeCast<Json>().optLong("exp"),
                iat = actualData.unsafeCast<Json>().optLong("iat"),
                namespace = actualData.unsafeCast<Json>().optString("namespace")
            )
        }
    }
}
