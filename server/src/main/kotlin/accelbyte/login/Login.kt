package accelbyte.login

import accelbyte.AccelByteSdkProvider
import net.accelbyte.sdk.api.iam.models.LegalAcceptedPoliciesRequest
import net.accelbyte.sdk.api.iam.models.ModelSendRegisterVerificationCodeRequest
import net.accelbyte.sdk.api.iam.models.ModelUserCreateRequestV3
import net.accelbyte.sdk.api.iam.models.ModelUserCreateResponseV3
import net.accelbyte.sdk.api.iam.models.ModelVerifyRegistrationCode
import net.accelbyte.sdk.api.iam.operations.users.PublicCreateUserV3
import net.accelbyte.sdk.api.iam.operations.users.PublicSendRegistrationCode
import net.accelbyte.sdk.api.iam.operations.users.PublicVerifyRegistrationCode
import net.accelbyte.sdk.api.iam.wrappers.Users
import net.accelbyte.sdk.core.repository.TokenRefresh
import java.time.Instant

private const val DEFAULT_USER_SCOPE = "commerce account social publishing analytics"
private const val DEFAULT_AUTH_TYPE = "EMAILPASSWD"

/**
 * Provides AccelByte IAM authentication and user registration functionality.
 * 
 * Handles user login, registration code verification, and user account creation
 * through the AccelByte SDK. Requires AB_NAMESPACE environment variable.
 */
object Login
{
    private val sdk get() = AccelByteSdkProvider.sdk
    private val users by lazy { Users(sdk) }
    private val namespace get() = AccelByteSdkProvider.namespace

    /**
     * Response data for user login operations.
     * 
     * @param success Whether the login was successful
     * @param accessToken User access token if login succeeded
     * @param refreshToken User refresh token if login succeeded
     * @param tokenExpiresAt When the access token expires
     * @param refreshTokenExpiresAt When the refresh token expires
     * @param error Error message if login failed
     */
    data class LoginResponse(
        val success: Boolean,
        val accessToken: String?,
        val refreshToken: String?,
        val tokenExpiresAt: Instant?,
        val refreshTokenExpiresAt: Instant?,
        val error: String?
    )

    /**
     * Result data for registration-related actions.
     * 
     * @param success Whether the action was successful
     * @param error Error message if action failed
     */
    data class RegistrationActionResult(val success: Boolean, val error: String?)

    /**
     * Request data for user registration.
     * 
     * @param email User email address
     * @param password User password
     * @param displayName User display name
     * @param country User country code
     * @param dateOfBirth User date of birth
     * @param code Registration verification code
     * @param reachMinimumAge Whether user meets minimum age requirement
     * @param authType Authentication type
     * @param uniqueDisplayName Unique display name if required
     * @param acceptedPolicies List of accepted legal policies
     */
    data class RegisterRequest(
        val email: String,
        val password: String,
        val displayName: String,
        val country: String,
        val dateOfBirth: String,
        val code: String,
        val reachMinimumAge: Boolean = true,
        val authType: String = DEFAULT_AUTH_TYPE,
        val uniqueDisplayName: String? = null,
        val acceptedPolicies: List<LegalAcceptedPoliciesRequest>? = null
    )

    /**
     * Result data for user registration operations.
     * 
     * @param success Whether the registration was successful
     * @param response AccelByte user creation response if successful
     * @param error Error message if registration failed
     */
    data class RegisterResult(
        val success: Boolean,
        val response: ModelUserCreateResponseV3?,
        val error: String?
    )

    /**
     * Authenticates a user with AccelByte IAM.
     * 
     * @param username User's username or email
     * @param password User's password
     * @param scope OAuth scope for the login session
     * @return [LoginResponse] containing authentication tokens or error details
     */
    fun loginUser(username : String, password : String, scope : String = DEFAULT_USER_SCOPE) : LoginResponse
    {
        var success = false
        var thrown: Throwable? = null

        try {
            success = sdk.loginUser(username, password, scope)
        } catch (ex: Exception) {
            thrown = ex
        }

        val error = when {
            thrown != null -> thrown.message ?: thrown.toString()
            !success -> "loginUser returned false"
            else -> null
        }

        val snapshot = captureTokens()
        return LoginResponse(
            success = success,
            accessToken = snapshot.accessToken,
            refreshToken = snapshot.refreshToken,
            tokenExpiresAt = snapshot.tokenExpiresAt,
            refreshTokenExpiresAt = snapshot.refreshTokenExpiresAt,
            error = error
        )
    }

    /**
     * Returns true when a non-expired user access token is currently stored.
     */
    fun isUserLoggedIn() : Boolean
    {
        val snapshot = captureTokens()
        if (snapshot.accessToken.isNullOrBlank()) {
            return false
        }
        val expiresAt = snapshot.tokenExpiresAt ?: return true
        return expiresAt.isAfter(Instant.now())
    }

    /**
     * Sends a registration verification code to the specified email address.
     * 
     * @param email Email address to send verification code to
     * @param languageTag Optional language tag for localization
     * @return [RegistrationActionResult] indicating success or failure
     */
    fun sendRegistrationCode(email : String, languageTag : String? = null) : RegistrationActionResult
    {
        val requestBuilder = ModelSendRegisterVerificationCodeRequest.builder()
            .emailAddress(email)
        languageTag?.let { requestBuilder.languageTag(it) }
        val operation = PublicSendRegistrationCode.builder()
            .namespace(namespace)
            .body(requestBuilder.build())
            .build()

        return executePublicAction {
            users.publicSendRegistrationCode(operation)
        }
    }

    /**
     * Verifies a registration code for the specified email address.
     * 
     * @param email Email address associated with the verification code
     * @param code Verification code to validate
     * @return [RegistrationActionResult] indicating success or failure
     */
    fun verifyRegistrationCode(email : String, code : String) : RegistrationActionResult
    {
        val request = ModelVerifyRegistrationCode.builder()
            .emailAddress(email)
            .code(code)
            .build()
        val operation = PublicVerifyRegistrationCode.builder()
            .namespace(namespace)
            .body(request)
            .build()

        return executePublicAction {
            users.publicVerifyRegistrationCode(operation)
        }
    }

    /**
     * Registers a new user account with AccelByte IAM.
     * 
     * @param payload [RegisterRequest] containing user registration data
     * @return [RegisterResult] containing registration response or error details
     */
    fun registerUser(payload : RegisterRequest) : RegisterResult
    {
        val builder = ModelUserCreateRequestV3.builder()
            .authType(payload.authType)
            .code(payload.code)
            .country(payload.country)
            .dateOfBirth(payload.dateOfBirth)
            .displayName(payload.displayName)
            .emailAddress(payload.email)
            .password(payload.password)
            .reachMinimumAge(payload.reachMinimumAge)
        payload.uniqueDisplayName?.let { builder.uniqueDisplayName(it) }
        payload.acceptedPolicies?.let { builder.acceptedPolicies(it) }

        val request = builder.build()
        val operation = PublicCreateUserV3.builder()
            .namespace(namespace)
            .body(request)
            .build()

        return runAuthenticated {
            users.publicCreateUserV3(operation)
        }.fold(
            onSuccess = { RegisterResult(success = true, response = it, error = null) },
            onFailure = { RegisterResult(success = false, response = null, error = it.message ?: it.toString()) }
        )
    }

    /**
     * Executes a public AccelByte action with authentication handling.
     * 
     * @param action Action to execute
     * @return [RegistrationActionResult] indicating success or failure
     */
    private inline fun executePublicAction(action : () -> Unit) : RegistrationActionResult
    {
        return runAuthenticated(action).fold(
            onSuccess = { RegistrationActionResult(true, null) },
            onFailure = { RegistrationActionResult(false, it.message ?: it.toString()) }
        )
    }

    /**
     * Runs an action with client authentication, ensuring valid client token.
     * 
     * @param action Action to execute with authentication
     * @return Result containing action result or authentication error
     */
    private inline fun <T> runAuthenticated(action : () -> T) : Result<T>
    {
        if (!ensureClientToken()) {
            return Result.failure(IllegalStateException("Unable to authenticate AccelByte client before calling IAM public APIs."))
        }
        return runCatching { action() }
    }

    /**
     * Ensures a valid client token is available for AccelByte API calls.
     * 
     * @return True if client token is available or successfully obtained
     */
    private fun ensureClientToken() : Boolean
    {
        val tokenRepo = sdk.sdkConfiguration.tokenRepository
        val hasToken = runCatching { tokenRepo.isTokenAvailable() }.getOrDefault(false)
        if (hasToken) {
            return true
        }
        return runCatching { sdk.loginClient() }.getOrDefault(false)
    }

    /**
     * Snapshot of current authentication tokens and expiration times.
     * 
     * @param accessToken Current access token
     * @param refreshToken Current refresh token
     * @param tokenExpiresAt When access token expires
     * @param refreshTokenExpiresAt When refresh token expires
     */
    private data class TokenSnapshot(
        val accessToken: String?,
        val refreshToken: String?,
        val tokenExpiresAt: Instant?,
        val refreshTokenExpiresAt: Instant?
    )

    /**
     * Captures current authentication tokens from the SDK token repository.
     * 
     * @return [TokenSnapshot] containing current token state
     */
    private fun captureTokens() : TokenSnapshot
    {
        val tokenRepo = sdk.sdkConfiguration.tokenRepository
        val accessToken = runCatching { tokenRepo.getToken() }.getOrNull()?.takeUnless { it.isNullOrBlank() }
        val refreshRepo = tokenRepo as? TokenRefresh
        val refreshToken = refreshRepo?.let { runCatching { it.getRefreshToken() }.getOrNull()?.takeUnless { it.isNullOrBlank() } }
        val tokenExpiresAt = refreshRepo?.let { runCatching { it.getTokenExpiresAt() }.getOrNull()?.toInstant() }
        val refreshTokenExpiresAt = refreshRepo?.let { runCatching { it.getRefreshTokenExpiresAt() }.getOrNull()?.toInstant() }
        return TokenSnapshot(accessToken, refreshToken, tokenExpiresAt, refreshTokenExpiresAt)
    }
}
