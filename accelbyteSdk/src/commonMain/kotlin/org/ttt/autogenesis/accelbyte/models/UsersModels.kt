package org.ttt.autogenesis.accelbyte.models

import kotlin.js.Json

/**
 * Request model for IAM's `createUserCodeRequest_v3` endpoint.
 * 
 * Sends a verification code to the specified email address for user registration.
 * The language tag is optional and controls the localization of the email content.
 * 
 * @param emailAddress Target email address for verification code delivery
 * @param languageTag Optional language tag for email localization (e.g., "en-US")
 */
data class SendRegisterVerificationCodeRequest(
    val emailAddress : String,
    val languageTag : String? = null
) : AccelByteRequest
{
    /**
     * Converts this request to JSON format for API transmission.
     * 
     * @return JSON object containing emailAddress and optional languageTag
     */
    override fun toJson() : Json = jsonOf(
        "emailAddress" to emailAddress,
        "languageTag" to languageTag
    )

    companion object
    {
        /**
         * Creates a SendRegisterVerificationCodeRequest from JSON data.
         * 
         * @param json JSON object containing request data
         * @return Parsed SendRegisterVerificationCodeRequest instance
         */
        fun fromJson(json : Json) : SendRegisterVerificationCodeRequest = SendRegisterVerificationCodeRequest(
            emailAddress = json.requireString("emailAddress"),
            languageTag = json.optString("languageTag")
        )
    }
}

/**
 * Request payload for IAM's `/iam/v3/public/namespaces/{namespace}/users/forgot` endpoint.
 * 
 * Initiates a password reset flow by sending a verification code to the user's email.
 * The language tag controls the localization of the reset email content.
 * 
 * @param emailAddress Email address of the user requesting password reset
 * @param languageTag Optional language tag for email localization
 */
data class SendPasswordResetCodeRequest(
    val emailAddress : String,
    val languageTag : String? = null
) : AccelByteRequest
{
    /**
     * Converts this request to JSON format for API transmission.
     * 
     * @return JSON object containing emailAddress and optional languageTag
     */
    override fun toJson() : Json = jsonOf(
        "emailAddress" to emailAddress,
        "languageTag" to languageTag
    )
}

/**
 * Aggregated user information from Basic and IAM services.
 * 
 * This data class combines user profile data from the Basic service (userId, displayName)
 * with authentication data from the IAM service (username). Used to provide a complete
 * view of the currently logged-in user's information.
 * 
 * @param userId Unique identifier for the user from Basic service
 * @param displayName User's display name from their profile
 * @param username Username from IAM token introspection (may be null)
 */
data class UserInfo(
    val userId : String,
    val displayName : String,
    val username : String?
)


/**
 * Payload sent to IAM's `/iam/v3/public/namespaces/{namespace}/users/reset` endpoint.
 * 
 * Completes the password reset flow using the verification code sent to the user's email.
 * All fields except languageTag and clientId are required for successful password reset.
 * 
 * @param code Verification code received via email
 * @param emailAddress Email address associated with the account
 * @param newPassword New password to set for the account
 * @param languageTag Optional language tag for response localization
 * @param clientId Optional client identifier for the requesting application
 */
data class ResetPasswordRequestV3(
    val code : String,
    val emailAddress : String,
    val newPassword : String,
    val languageTag : String? = null,
    val clientId : String? = null
) : AccelByteRequest
{
    /**
     * Converts this request to JSON format for API transmission.
     * 
     * @return JSON object containing all password reset parameters
     */
    override fun toJson() : Json = jsonOf(
        "clientId" to clientId,
        "code" to code,
        "emailAddress" to emailAddress,
        "languageTag" to languageTag,
        "newPassword" to newPassword
    )
}

/**
 * Request for validating a verification code issued to an email address.
 * 
 * Used to verify that the user received and can provide the correct verification
 * code sent to their email during the registration process.
 * 
 * @param emailAddress Email address that received the verification code
 * @param code Verification code provided by the user
 */
data class VerifyRegistrationCodeRequest(
    val emailAddress : String,
    val code : String
) : AccelByteRequest
{
    /**
     * Converts this request to JSON format for API transmission.
     * 
     * @return JSON object containing emailAddress and verification code
     */
    override fun toJson() : Json = jsonOf(
        "emailAddress" to emailAddress,
        "code" to code
    )
}

/**
 * Policy acceptance payload included when consenting to legal documents during registration.
 * 
 * Represents the user's acceptance of a specific policy version during account creation.
 * All policy-related identifiers must match the legal documents configured in the namespace.
 * 
 * @param isAccepted Whether the user accepted this policy (must be true for registration)
 * @param localizedPolicyVersionId Identifier for the localized version of the policy
 * @param policyId Unique identifier for the policy document
 * @param policyVersionId Identifier for the specific version of the policy
 */
data class AcceptedPolicy(
    val isAccepted : Boolean,
    val localizedPolicyVersionId : String,
    val policyId : String,
    val policyVersionId : String
) : AccelByteRequest
{
    /**
     * Converts this policy acceptance to JSON format for API transmission.
     * 
     * @return JSON object containing all policy acceptance parameters
     */
    override fun toJson() : Json = jsonOf(
        "isAccepted" to isAccepted,
        "localizedPolicyVersionId" to localizedPolicyVersionId,
        "policyId" to policyId,
        "policyVersionId" to policyVersionId
    )
}

/**
 * Request payload for IAM's `createUser_v3` endpoint.
 * 
 * Creates a new user account with the provided registration details and policy acceptances.
 * Most fields have sensible defaults, but emailAddress, displayName, password, username,
 * and code are required for successful registration.
 * 
 * @param authType Authentication type for the account (default: "EMAILPASSWD")
 * @param code Verification code received via email
 * @param country Country code for the user (default: "US")
 * @param dateOfBirth Optional date of birth in ISO format
 * @param displayName Display name for the user
 * @param emailAddress Email address for the account
 * @param password Account password
 * @param username Unique username for the account
 * @param passwordMd5Sum Optional MD5 hash of the password
 * @param reachMinimumAge Whether user meets minimum age requirements (default: true)
 * @param uniqueDisplayName Optional unique display name
 * @param acceptedPolicies List of policies accepted during registration
 */
data class RegisterUserRequest(
    val authType : String = "EMAILPASSWD",
    val code : String,
    val country : String = "US",
    val dateOfBirth : String? = null,
    val displayName : String,
    val emailAddress : String,
    val password : String,
    val username : String,
    val passwordMd5Sum : String? = null,
    val reachMinimumAge : Boolean = true,
    val uniqueDisplayName : String? = null,
    val acceptedPolicies : List<AcceptedPolicy>? = null
) : AccelByteRequest
{
    /**
     * Converts this registration request to JSON format for API transmission.
     * 
     * @return JSON object containing all registration parameters
     */
    override fun toJson() : Json = jsonOf(
        "PasswordMD5Sum" to passwordMd5Sum,
        "acceptedPolicies" to acceptedPolicies?.map { it.toJson() }?.toTypedArray(),
        "authType" to authType,
        "code" to code,
        "country" to country,
        "dateOfBirth" to dateOfBirth,
        "username" to username,
        "displayName" to displayName,
        "emailAddress" to emailAddress,
        "password" to password,
        "reachMinimumAge" to reachMinimumAge,
        "uniqueDisplayName" to uniqueDisplayName
    )
}

/**
 * Legacy payload matching the `/iam/v2/public/namespaces/{namespace}/users` contract.
 * 
 * All keys remain Pascal-cased because the V2 endpoint expects that casing.
 * This is maintained for backward compatibility with older API versions.
 * 
 * @param authType Authentication type for the account (default: "EMAILPASSWD")
 * @param country Country code for the user
 * @param displayName Display name for the user
 * @param loginId Login identifier (typically email or username)
 * @param password Account password
 * @param passwordMd5Sum Optional MD5 hash of the password
 */
data class LegacyRegisterUserRequest(
    val authType : String = "EMAILPASSWD",
    val country : String,
    val displayName : String,
    val loginId : String,
    val password : String,
    val passwordMd5Sum : String? = null
) : AccelByteRequest
{
    /**
     * Converts this legacy request to JSON format with Pascal-cased keys.
     * 
     * @return JSON object with Pascal-cased keys for V2 API compatibility
     */
    override fun toJson() : Json = jsonOf(
        "AuthType" to authType,
        "Country" to country,
        "DisplayName" to displayName,
        "LoginId" to loginId,
        "Password" to password,
        "PasswordMD5Sum" to passwordMd5Sum
    )
}

/**
 * Response returned from IAM after a successful public registration.
 * 
 * Contains the complete user information as stored in the IAM system after
 * successful account creation and verification.
 * 
 * @param authType Authentication type used for the account
 * @param country Country code associated with the account
 * @param dateOfBirth Date of birth in ISO format
 * @param displayName User's display name
 * @param emailAddress Email address associated with the account
 * @param namespace Namespace where the account was created
 * @param uniqueDisplayName Unique display name if specified
 * @param userId Unique identifier assigned to the new user
 */
data class RegisterUserResponse(
    val authType : String,
    val country : String,
    val dateOfBirth : String,
    val displayName : String,
    val emailAddress : String,
    val namespace : String,
    val uniqueDisplayName : String?,
    val userId : String
) : AccelByteResponse
{
    companion object
    {
        /**
         * Creates a RegisterUserResponse from JSON data returned by the API.
         * 
         * @param json JSON object containing registration response data
         * @return Parsed RegisterUserResponse instance
         */
        fun fromJson(json : Json) : RegisterUserResponse = RegisterUserResponse(
            authType = json.requireString("authType"),
            country = json.requireString("country"),
            dateOfBirth = json.requireString("dateOfBirth"),
            displayName = json.requireString("displayName"),
            emailAddress = json.requireString("emailAddress"),
            namespace = json.requireString("namespace"),
            uniqueDisplayName = json.optString("uniqueDisplayName"),
            userId = json.requireString("userId")
        )
    }
}
