package structs.accelbyte.auth

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import structs.accelbyte.common.AccelByteJson
import structs.accelbyte.common.AccelByteSerializable

@Serializable
enum class GrantType(val value: String) {
    @SerialName("authorization_code") AUTHORIZATION_CODE("authorization_code"),
    @SerialName("client_credentials") CLIENT_CREDENTIALS("client_credentials"),
    @SerialName("password") PASSWORD("password"),
    @SerialName("refresh_token") REFRESH_TOKEN("refresh_token"),
    @SerialName("urn:ietf:params:oauth:grant-type:extend_client_credentials")
    EXTEND_CLIENT_CREDENTIALS("urn:ietf:params:oauth:grant-type:extend_client_credentials")
}

@Serializable
data class OAuthTokenRequest(
    @SerialName("grant_type") val grantType: GrantType,
    val username: String? = null,
    val password: String? = null,
    @SerialName("client_id") val clientId: String? = null,
    @SerialName("client_secret") val clientSecret: String? = null,
    @SerialName("refresh_token") val refreshToken: String? = null,
    val scope: String? = null
) : AccelByteSerializable {
    override fun toJsonString(): String = AccelByteJson.encodeToString(OAuthTokenRequest.serializer(), this)
}