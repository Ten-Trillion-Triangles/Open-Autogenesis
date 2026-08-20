package org.ttt.autogenesis.accelbyte.models

import kotlin.js.Json

/**
 * Full user account data returned by the IAM user endpoint.
 *
 * Contains the complete profile for a user including their user ID, namespace,
 * display name, email, username, and account timestamps.
 *
 * @property userId The unique identifier for the user. Required field.
 * @property namespace The namespace that this user belongs to. Required field.
 * @property displayName The user's display name. Null if not set.
 * @property email The user's email address. Null if not set.
 * @property userName The user's username. Null if not set.
 * @property createdAt ISO-8601 timestamp of when the account was created. Null if not available.
 * @property updatedAt ISO-8601 timestamp of the last account update. Null if not available.
 * @throws Exception if the JSON is missing [userId] or [namespace].
 */
data class UserResponse(
    val userId: String,
    val namespace: String,
    val displayName: String?,
    val email: String?,
    val userName: String?,
    val createdAt: String?,
    val updatedAt: String?
) : AccelByteResponse {
    companion object {
        fun fromJson(json: Json): UserResponse {
            val actualData = json.asDynamic().data?.unsafeCast<Json>() ?: json
            return UserResponse(
                userId = actualData.unsafeCast<Json>().requireString("userId"),
                namespace = actualData.unsafeCast<Json>().requireString("namespace"),
                displayName = actualData.unsafeCast<Json>().optString("displayName"),
                email = actualData.unsafeCast<Json>().optString("email"),
                userName = actualData.unsafeCast<Json>().optString("userName"),
                createdAt = actualData.unsafeCast<Json>().optString("createdAt"),
                updatedAt = actualData.unsafeCast<Json>().optString("updatedAt")
            )
        }
    }
}

/**
 * Public-facing user profile returned by public user endpoints.
 *
 * Contains only the fields that are safe to expose publicly. Compare to [UserResponse]
 * which contains sensitive fields like email.
 *
 * @property userId The unique identifier for the user. Required field.
 * @property namespace The namespace that this user belongs to. Required field.
 * @property displayName The user's display name. Null if not set.
 * @property userName The user's username. Null if not set.
 * @throws Exception if the JSON is missing [userId] or [namespace].
 */
data class PublicUserResponse(
    val userId: String,
    val namespace: String,
    val displayName: String?,
    val userName: String?
) : AccelByteResponse {
    companion object {
        fun fromJson(json: Json): PublicUserResponse {
            val actualData = json.asDynamic().data?.unsafeCast<Json>() ?: json
            return PublicUserResponse(
                userId = actualData.unsafeCast<Json>().requireString("userId"),
                namespace = actualData.unsafeCast<Json>().requireString("namespace"),
                displayName = actualData.unsafeCast<Json>().optString("displayName"),
                userName = actualData.unsafeCast<Json>().optString("userName")
            )
        }
    }
}

/**
 * A ban record applied to a user account.
 *
 * Represents an active or historical ban on a user's account, including the ban reason,
 * activation status, and optional expiration time.
 *
 * @property userId The unique identifier of the banned user. Required field.
 * @property banId The unique identifier of this ban record. Required field.
 * @property ban The ban title or short description, e.g., "Permanent Ban" or "7-Day Suspension". Required field.
 * @property reason The detailed reason for the ban. Null if no reason was provided.
 * @property active Whether the ban is currently active. Required field. When `false`, the ban has been lifted or expired.
 * @property expiresAt ISO-8601 timestamp of when the ban expires. Null for permanent bans.
 * @throws Exception if the JSON is missing [userId], [banId], [ban], or [active].
 */
data class UserBanResponse(
    val userId: String,
    val banId: String,
    val ban: String,
    val reason: String?,
    val active: Boolean,
    val expiresAt: String?
) : AccelByteResponse {
    companion object {
        fun fromJson(json: Json): UserBanResponse {
            val actualData = json.asDynamic().data?.unsafeCast<Json>() ?: json
            return UserBanResponse(
                userId = actualData.unsafeCast<Json>().requireString("userId"),
                banId = actualData.unsafeCast<Json>().requireString("banId"),
                ban = actualData.unsafeCast<Json>().requireString("ban"),
                reason = actualData.unsafeCast<Json>().optString("reason"),
                active = actualData.unsafeCast<Json>().requireBoolean("active"),
                expiresAt = actualData.unsafeCast<Json>().optString("expiresAt")
            )
        }
    }
}

/**
 * A named IAM role with an associated list of permissions.
 *
 * Roles are used to grant users a set of permissions that determine what
 * API operations they are allowed to perform.
 *
 * @property id The unique identifier for the role. Required field.
 * @property name The human-readable name of the role, e.g., "Admin" or "Moderator". Required field.
 * @property description A short description explaining what this role is for. Null if not set.
 * @property permissions List of permission strings granted by this role. Each string is typically a namespaced permission identifier, e.g., "ADMIN:USER:READ". Null if no permissions are assigned.
 * @property createdAt ISO-8601 timestamp of when the role was created. Null if not available.
 * @throws Exception if the JSON is missing [id] or [name].
 */
data class RoleResponse(
    val id: String,
    val name: String,
    val description: String?,
    val permissions: List<String>?,
    val createdAt: String?
) : AccelByteResponse {
    companion object {
        fun fromJson(json: Json): RoleResponse {
            val actualData = json.asDynamic().data?.unsafeCast<Json>() ?: json
            return RoleResponse(
                id = actualData.unsafeCast<Json>().requireString("id"),
                name = actualData.unsafeCast<Json>().requireString("name"),
                description = actualData.unsafeCast<Json>().optString("description"),
                permissions = actualData.unsafeCast<Json>().optStringList("permissions"),
                createdAt = actualData.unsafeCast<Json>().optString("createdAt")
            )
        }
    }
}

/**
 * The association between a user and a role, indicating that the user has been assigned that role.
 *
 * Returned when listing role assignments for a user or querying which users hold a specific role.
 *
 * @property userId The unique identifier of the user who is assigned the role. Required field.
 * @property roleId The unique identifier of the role that is assigned to the user. Required field.
 * @property assignedAt ISO-8601 timestamp of when the role was assigned to the user. Null if not available.
 * @throws Exception if the JSON is missing [userId] or [roleId].
 */
data class AssignedUserResponse(
    val userId: String,
    val roleId: String,
    val assignedAt: String?
) : AccelByteResponse {
    companion object {
        fun fromJson(json: Json): AssignedUserResponse {
            val actualData = json.asDynamic().data?.unsafeCast<Json>() ?: json
            return AssignedUserResponse(
                userId = actualData.unsafeCast<Json>().requireString("userId"),
                roleId = actualData.unsafeCast<Json>().requireString("roleId"),
                assignedAt = actualData.unsafeCast<Json>().optString("assignedAt")
            )
        }
    }
}

/**
 * OAuth token response returned by the IAM token endpoint.
 *
 * Contains the access and refresh tokens along with metadata about the token grant,
 * such as expiration time, token type, and granted scopes.
 *
 * **Note:** The JSON keys use snake_case convention (`access_token`, `refresh_token`, etc.)
 * as required by the OAuth 2.0 specification.
 *
 * @property accessToken The OAuth access token. Use this token to authenticate API requests. Required field.
 * @property refreshToken The OAuth refresh token. Use this to obtain a new access token when the current one expires. Required field.
 * @property expiresIn The validity duration of the access token in seconds. For example, a value of `3600` means the token expires in one hour. Required field.
 * @property tokenType The token type, typically `"Bearer"``. Required field.
 * @property scope The space-separated list of granted OAuth scopes. Required field. May be an empty string if no scopes were granted.
 * @throws Exception if the JSON is missing any required field ([accessToken], [refreshToken], [expiresIn], [tokenType], or [scope]).
 */
data class TokenResponse(
    val accessToken: String,
    val refreshToken: String,
    val expiresIn: Int,
    val tokenType: String,
    val scope: String
) : AccelByteResponse {
    companion object {
        fun fromJson(json: Json): TokenResponse {
            val actualData = json.asDynamic().data?.unsafeCast<Json>() ?: json
            return TokenResponse(
                accessToken = actualData.unsafeCast<Json>().requireString("access_token"),
                refreshToken = actualData.unsafeCast<Json>().requireString("refresh_token"),
                expiresIn = actualData.unsafeCast<Json>().requireInt("expires_in"),
                tokenType = actualData.unsafeCast<Json>().requireString("token_type"),
                scope = actualData.unsafeCast<Json>().requireString("scope")
            )
        }
    }
}

/**
 * An OAuth client application registered with the IAM service.
 *
 * Represents a client application (such as a game server or backend service) that
 * authenticates using the OAuth client credentials flow.
 *
 * @property clientId The unique identifier for the client application. Used as the client username in OAuth flows. Required field.
 * @property clientName The human-readable name of the client application. Null if not set.
 * @property redirectUri The OAuth 2.0 redirect URI registered for this client. Null if not set. Used in authorization code flows.
 * @property scopes List of OAuth scopes that this client is authorized to request. Null if no specific scopes are assigned.
 * @throws Exception if the JSON is missing [clientId].
 */
data class ClientResponse(
    val clientId: String,
    val clientName: String?,
    val redirectUri: String?,
    val scopes: List<String>?
) : AccelByteResponse {
    companion object {
        fun fromJson(json: Json): ClientResponse {
            val actualData = json.asDynamic().data?.unsafeCast<Json>() ?: json
            return ClientResponse(
                clientId = actualData.unsafeCast<Json>().requireString("clientId"),
                clientName = actualData.unsafeCast<Json>().optString("clientName"),
                redirectUri = actualData.unsafeCast<Json>().optString("redirectUri"),
                scopes = actualData.unsafeCast<Json>().optStringList("scopes")
            )
        }
    }
}

/**
 * A country entry used for region selection in the IAM service.
 *
 * Returned when listing available countries for registration or region-specific
 * configuration.
 *
 * @property countryCode The ISO 3166-1 alpha-2 two-letter country code, e.g., "US" or "JP". Required field.
 * @property displayName The localized, human-readable name of the country, e.g., "United States" or "Japan". Null if not available.
 * @property isAsian Whether the country is classified as an Asian country. Null if classification is not available.
 * @throws Exception if the JSON is missing [countryCode].
 */
data class CountryResponse(
    val countryCode: String,
    val displayName: String?,
    val isAsian: Boolean?
) : AccelByteResponse {
    companion object {
        fun fromJson(json: Json): CountryResponse {
            val actualData = json.asDynamic().data?.unsafeCast<Json>() ?: json
            return CountryResponse(
                countryCode = actualData.unsafeCast<Json>().requireString("countryCode"),
                displayName = actualData.unsafeCast<Json>().optString("displayName"),
                isAsian = actualData.unsafeCast<Json>().optBoolean("isAsian")
            )
        }
    }
}

/**
 * A device registered to a user's account.
 *
 * Represents a hardware device (console, PC, mobile device, etc.) that has been
 * linked to the user's account for authentication or platform linking purposes.
 *
 * @property deviceId The unique identifier for the device. This is typically a platform-specific device hash or UUID. Required field.
 * @property deviceType A string describing the type of device, e.g., "PlayStation", "Xbox", "PC", "iOS". Null if not available.
 * @property createdAt ISO-8601 timestamp of when the device was registered to the account. Null if not available.
 * @throws Exception if the JSON is missing [deviceId].
 */
data class DeviceResponse(
    val deviceId: String,
    val deviceType: String?,
    val createdAt: String?
) : AccelByteResponse {
    companion object {
        fun fromJson(json: Json): DeviceResponse {
            val actualData = json.asDynamic().data?.unsafeCast<Json>() ?: json
            return DeviceResponse(
                deviceId = actualData.unsafeCast<Json>().requireString("deviceId"),
                deviceType = actualData.unsafeCast<Json>().optString("deviceType"),
                createdAt = actualData.unsafeCast<Json>().optString("createdAt")
            )
        }
    }
}

/**
 * A platform namespace descriptor.
 *
 * Represents a namespace within the AccelByte platform, used to partition data
 * and configuration across different titles, studios, or deployment environments.
 *
 * @property namespaceId The unique identifier for the namespace, e.g., "blueberry-studio" or "titanfall2". Required field.
 * @property displayName A human-readable name for the namespace, typically the game or service name. Null if not set.
 * @property enabled Whether the namespace is currently active and accepting traffic. Null if status is not available.
 * @throws Exception if the JSON is missing [namespaceId].
 */
data class NamespaceResponse(
    val namespaceId: String,
    val displayName: String?,
    val enabled: Boolean?
) : AccelByteResponse {
    companion object {
        fun fromJson(json: Json): NamespaceResponse {
            val actualData = json.asDynamic().data?.unsafeCast<Json>() ?: json
            return NamespaceResponse(
                namespaceId = actualData.unsafeCast<Json>().requireString("namespaceId"),
                displayName = actualData.unsafeCast<Json>().optString("displayName"),
                enabled = actualData.unsafeCast<Json>().optBoolean("enabled")
            )
        }
    }
}

/**
 * A single IAM permission entry.
 *
 * Permissions define the granular actions a user or role is allowed to perform
 * on specific resources. Actions are typically represented as integer bitmasks.
 *
 * @property action The permission action as an integer bitmask. The meaning of each bit is service-defined. For example, `1` may represent READ, `2` WRITE, etc. Compare against known constants in the IAM service. Required field.
 * @property description A human-readable description of what this permission controls. Null if not set.
 * @property resource The resource pattern or specific resource identifier this permission applies to, e.g., "ADMIN:USER:*". Null if not set.
 * @throws Exception if the JSON is missing [action].
 */
data class PermissionResponse(
    val action: Int,
    val description: String?,
    val resource: String?
) : AccelByteResponse {
    companion object {
        fun fromJson(json: Json): PermissionResponse {
            val actualData = json.asDynamic().data?.unsafeCast<Json>() ?: json
            return PermissionResponse(
                action = actualData.unsafeCast<Json>().requireInt("action"),
                description = actualData.unsafeCast<Json>().optString("description"),
                resource = actualData.unsafeCast<Json>().optString("resource")
            )
        }
    }
}

/**
 * Pagination cursors for IAM list endpoints.
 *
 * Returned alongside paginated lists to indicate the position of the current page
 * and allow clients to request the next or previous page of results.
 *
 * @property next The URL or cursor token to request the next page of results. Null if there is no next page.
 * @property previous The URL or cursor token to request the previous page of results. Null if there is no previous page (i.e., this is the first page).
 */
data class Paging(
    val next: String?,
    val previous: String?
) {
    companion object {
        fun fromJson(json: Json): Paging = Paging(
            next = json.optString("next"),
            previous = json.optString("previous")
        )
    }
}

/**
 * Generic paginated list wrapper for IAM list responses.
 *
 * Wraps a list of items of type [T] along with optional pagination cursors.
 * The [Paging] object is null when the response is not paginated or when
 * the server does not provide pagination metadata.
 *
 * **Note:** No [fromJson] is provided because the generic type parameter [T]
 * is erased at runtime due to Kotlin/JS generics limitations. Manually
 * deserialize using [Paging.fromJson] for the paging field and construct
 * this data class directly.
 *
 * @param T The type of items contained in [data].
 * @property data The list of items for the current page. May be empty.
 * @property paging The pagination cursors, or null if pagination is not available.
 */
data class PaginatedResponse<T>(
    val data: List<T>,
    val paging: Paging?
)