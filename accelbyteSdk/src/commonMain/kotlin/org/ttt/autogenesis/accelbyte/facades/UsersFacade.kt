package org.ttt.autogenesis.accelbyte.facades

import kotlin.js.Promise
import kotlinx.coroutines.await
import org.ttt.autogenesis.accelbyte.AccelByteSdkInstance
import org.ttt.autogenesis.accelbyte.facades.UserAuthFacade
import org.ttt.autogenesis.accelbyte.models.GrantType
import org.ttt.autogenesis.accelbyte.models.OAuthTokenRequest
import org.ttt.autogenesis.accelbyte.models.OAuthTokenResponse
import org.ttt.autogenesis.accelbyte.models.RegisterUserRequest
import org.ttt.autogenesis.accelbyte.models.RegisterUserResponse
import org.ttt.autogenesis.accelbyte.models.ResetPasswordRequestV3
import org.ttt.autogenesis.accelbyte.models.SendPasswordResetCodeRequest
import org.ttt.autogenesis.accelbyte.models.SendRegisterVerificationCodeRequest
import org.ttt.autogenesis.accelbyte.models.VerifyRegistrationCodeRequest
import org.ttt.autogenesis.accelbyte.modules.UsersApi
import org.ttt.autogenesis.accelbyte.util.mapToUnit
import org.ttt.autogenesis.accelbyte.util.propagateJsErrors
import org.ttt.autogenesis.accelbyte.facades.BasicFacade
import org.ttt.autogenesis.accelbyte.facades.IamFacade
import org.ttt.autogenesis.accelbyte.models.TokenMaintenanceRequest
import org.ttt.autogenesis.accelbyte.models.TokenIntrospectionResponse
import org.ttt.autogenesis.accelbyte.models.UserProfileResponse
import org.ttt.autogenesis.accelbyte.models.UserInfo

/**
 * Wraps the IAM Users API subset needed by the client UI.
 * 
 * Provides high-level user management operations including registration, authentication,
 * password reset, and user information retrieval. Integrates with multiple AccelByte
 * services (IAM, Basic) to provide comprehensive user functionality.
 *
 * @param sdk SDK instance used to resolve the Users API bindings and access other facades
 */
class UsersFacade(private val sdk : AccelByteSdkInstance)
{
    /**
     * Users API instance for direct IAM operations.
     */
    private val usersApi : UsersApi
        get() = UsersApi(sdk.rawSdk)

    /**
     * User authentication facade for login and token management.
     */
    private val userAuthFacade = UserAuthFacade(sdk)

    /**
     * Sends the verification code for the requested email address.
     * 
     * Initiates the user registration process by sending a verification code
     * to the specified email address. The user must provide this code to
     * complete registration via [verifyRegistrationCode].
     *
     * @param request Payload describing the target email and localization preferences
     * @return Promise completed when the backend acknowledges the request
     */
    fun sendRegisterCode(request : SendRegisterVerificationCodeRequest) : Promise<Unit> =
        usersApi.createUserCodeRequest_v3(request.toJson()).propagateJsErrors().mapToUnit()

    /**
     * Verifies that the user supplied the correct registration code.
     * 
     * Validates the verification code sent to the user's email address during
     * the registration process. Must be called before [registerUser] to ensure
     * the email address is verified.
     * 
     * @param request Payload containing email address and verification code
     * @return Promise completed when verification succeeds
     */
    fun verifyRegistrationCode(request : VerifyRegistrationCodeRequest) : Promise<Unit> =
        usersApi.createUserCodeVerify_v3(request.toJson()).propagateJsErrors().mapToUnit()

    /**
     * Registers a new user account using the provided payload.
     * 
     * Creates a new user account with the specified registration details.
     * The email address must be verified via [verifyRegistrationCode] before
     * calling this method.
     * 
     * @param request Complete registration payload including user details and policies
     * @return Promise resolving to the created user's information
     */
    fun registerUser(request : RegisterUserRequest) : Promise<RegisterUserResponse> =
        usersApi.createUser_v3(request.toJson())
            .propagateJsErrors()
            .then { RegisterUserResponse.fromJson(it.data) }

    /**
     * Sends a password reset verification code to the configured namespace.
     * 
     * Initiates the password reset process by sending a verification code to
     * the user's email address. The user must provide this code via [resetPassword]
     * to complete the password reset.
     *
     * @param request Payload describing the target email and language hints
     * @return Promise completed when the backend acknowledges the request
     */
    fun sendPasswordResetCode(request : SendPasswordResetCodeRequest) : Promise<Unit> =
        usersApi.createUserForgot_ByNS_v3(request.toJson()).propagateJsErrors().mapToUnit()

    /**
     * Resets the user password using the latest verification code.
     * 
     * Completes the password reset process using the verification code sent
     * via [sendPasswordResetCode]. The new password will be set for the account
     * associated with the email address.
     *
     * @param request Payload containing the verification code, email, and new password
     * @return Promise completed when password reset succeeds
     */
    fun resetPassword(request : ResetPasswordRequestV3) : Promise<Unit> =
        usersApi.createUserReset_v3(request.toJson()).propagateJsErrors().mapToUnit()

    /**
     * Performs a password grant login using the cached SDK client configuration.
     * 
     * Authenticates the user with username and password, returning an OAuth token
     * that can be used for subsequent API calls. The token is automatically stored
     * in the SDK instance for future use.
     * 
     * @param username User's username or email address
     * @param password User's password
     * @param scope Optional OAuth scope for the token (defaults to SDK configuration)
     * @return Promise resolving to OAuth token response with access and refresh tokens
     */
    fun loginWithUsername(username : String, password : String, scope : String? = null) : Promise<OAuthTokenResponse>
    {
        val request = OAuthTokenRequest(
            grantType = GrantType.PASSWORD,
            username = username,
            password = password,
            // clientId is provided via Basic Auth header in interceptor
            scope = scope
        )

        return userAuthFacade.loginUser(request)
    }

    /**
     * Logs in the user but suspends (awaits) the result inside Kotlin coroutines.
     * 
     * Coroutine-friendly version of [loginWithUsername] that can be used within
     * suspend functions to avoid Promise-based callback handling.
     * 
     * @param username User's username or email address
     * @param password User's password
     * @param scope Optional OAuth scope for the token
     * @return OAuth token response with access and refresh tokens
     */
    suspend fun loginWithUsernameSuspend(username : String, password : String, scope : String? = null) : OAuthTokenResponse =
        loginWithUsername(username, password, scope).await()

    /**
     * Registers a user with a coroutine-friendly signature.
     * 
     * Coroutine-friendly version of [registerUser] that can be used within
     * suspend functions to avoid Promise-based callback handling.
     * 
     * @param request Complete registration payload including user details and policies
     * @return Created user's information from the registration response
     */
    suspend fun registerUserSuspend(request : RegisterUserRequest) : RegisterUserResponse =
        registerUser(request).await()

    /**
     * Indicates whether the SDK currently holds a non-blank access token for the user.
     * 
     * Checks if the user is currently authenticated by verifying that a valid
     * access token is stored in the SDK instance. Does not validate token expiration
     * or perform server-side verification.
     * 
     * @return True if user appears to be logged in, false otherwise
     */
    fun isLoggedIn() : Boolean =
        userAuthFacade.isLoggedIn()

    /**
     * Retrieves the currently logged-in user's information.
     * 
     * Aggregates data from Basic (profile) and IAM (introspection) services to provide
     * complete user information including userId, displayName, and username. Requires
     * the user to be authenticated with a valid access token.
     * 
     * @return Promise resolving to aggregated user information
     * @throws Exception if user is not logged in or token is invalid
     */
    fun getCurrentUserInfo() : Promise<UserInfo>
    {
        val token = sdk.getToken().accessToken
        if (token.isNullOrBlank()) {
            return Promise.reject(Exception("Not logged in"))
        }

        val iamFacade = IamFacade(sdk)

        // Parse JWT token to extract user info (avoiding CORS issues with Basic API)
        return iamFacade.introspectToken(TokenMaintenanceRequest(token)).then { introspection ->
            // Decode JWT to get userId and display_name from payload
            val parts = token.split(".")
            var userId: String? = null
            var displayName: String? = null
            
            if (parts.size >= 2) {
                try {
                    // Use dynamic JS calls to decode JWT
                    val window = js("window")
                    val payload = window.atob(parts[1]) as String
                    val json = js("JSON.parse")(payload)
                    userId = json.sub as? String  // JWT 'sub' claim is the user ID
                    displayName = json.display_name as? String
                } catch (e: Throwable) {
                    console.asDynamic().error("Failed to decode JWT token", e)
                }
            }

            UserInfo(
                userId = userId ?: "unknown",
                displayName = displayName ?: "Unknown User",
                username = introspection.username ?: "unknown"
            )
        }
    }
}