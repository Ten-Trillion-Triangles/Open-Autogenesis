package org.ttt.autogenesis.accelbyte.models

import kotlin.js.Json

/**
 * Response model for namespace information.
 *
 * @property id Namespace identifier.
 * @property displayName Namespace display name.
 * @property status Namespace status.
 * @property createdAt Timestamp when the namespace was created.
 * @property permissions List of permission strings associated with this namespace.
 */
data class NamespaceInfo(
    val id : String,
    val displayName : String,
    val status : String,
    val createdAt : String,
    val permissions : List<String>
) : AccelByteResponse {
    companion object
    {
        fun fromJson(json : Json) : NamespaceInfo = NamespaceInfo(
            id = json.requireString("id"),
            displayName = json.requireString("displayName"),
            status = json.requireString("status"),
            createdAt = json.requireString("createdAt"),
            permissions = json.optStringList("permissions")
        )
    }
}

/**
 * A paginated list of namespace information entries.
 *
 * @property data The list of namespaces on this page.
 * @property paging Pagination metadata for navigating additional pages.
 */
data class NamespaceListResponse(
    val data : List<NamespaceInfo>,
    val paging : PagingInfo
) : AccelByteResponse {
    companion object
    {
        fun fromJson(json : Json) : NamespaceListResponse = NamespaceListResponse(
            data = json.optJsonList("data").map(NamespaceInfo::fromJson),
            paging = PagingInfo.fromJson(json.requireJson("paging"))
        )
    }
}

/**
 * Detailed information about a specific namespace including configuration.
 *
 * @property namespace The namespace identifier.
 * @property displayName The display name of the namespace.
 * @property status The current status of the namespace.
 * @property createdAt Timestamp when the namespace was created.
 * @property updatedAt Timestamp when the namespace was last updated.
 * @property config The namespace configuration as a JSON object.
 */
data class NamespaceDetailsResponse(
    val namespace : String,
    val displayName : String,
    val status : String,
    val createdAt : String,
    val updatedAt : String,
    val config : Json
) : AccelByteResponse {
    companion object
    {
        fun fromJson(json : Json) : NamespaceDetailsResponse = NamespaceDetailsResponse(
            namespace = json.requireString("namespace"),
            displayName = json.requireString("displayName"),
            status = json.requireString("status"),
            createdAt = json.requireString("createdAt"),
            updatedAt = json.requireString("updatedAt"),
            config = json.requireJson("config")
        )
    }
}

/**
 * Publisher information response from the platform.
 *
 * @property publisherId The unique identifier of the publisher.
 * @property publisherName The display name of the publisher.
 * @property namespace The namespace associated with this publisher.
 * @property config The publisher configuration as a JSON object.
 */
data class PublisherInfoResponse(
    val publisherId : String,
    val publisherName : String,
    val namespace : String,
    val config : Json
) : AccelByteResponse {
    companion object
    {
        fun fromJson(json : Json) : PublisherInfoResponse = PublisherInfoResponse(
            publisherId = json.requireString("publisherId"),
            publisherName = json.requireString("publisherName"),
            namespace = json.requireString("namespace"),
            config = json.requireJson("config")
        )
    }
}

/**
 * Response containing upload slot information for file uploads.
 *
 * @property uploadUrl The pre-signed URL to upload the file to.
 * @property fileId The unique identifier assigned to the uploaded file.
 * @property expiresAt Timestamp when the upload URL expires.
 * @property maxFileSize The maximum file size allowed for this upload.
 * @property folderPath The folder path where the file will be stored.
 */
data class UploadSlotResponse(
    val uploadUrl : String,
    val fileId : String,
    val expiresAt : String,
    val maxFileSize : Long?,
    val folderPath : String?
) : AccelByteResponse {
    companion object
    {
        fun fromJson(json : Json) : UploadSlotResponse = UploadSlotResponse(
            uploadUrl = json.requireString("uploadUrl"),
            fileId = json.requireString("fileId"),
            expiresAt = json.requireString("expiresAt"),
            maxFileSize = json.optLong("maxFileSize"),
            folderPath = json.optString("folderPath")
        )
    }
}

/**
 * Response model for user profile data.
 *
 * @property userId User identifier.
 * @property displayName User display name.
 * @property avatarUrl User avatar URL.
 * @property publicProfile JSON object containing public profile information.
 * @property createdAt Timestamp when the profile was created.
 * @property updatedAt Timestamp when the profile was last updated.
 */
data class UserProfileResponse(
    val userId : String,
    val displayName : String,
    val avatarUrl : String?,
    val publicProfile : Json?,
    val createdAt : String,
    val updatedAt : String
) : AccelByteResponse {
    companion object
    {
        fun fromJson(json : Json) : UserProfileResponse = UserProfileResponse(
            userId = json.requireString("userId"),
            displayName = json.requireString("displayName"),
            avatarUrl = json.optString("avatarUrl"),
            publicProfile = json.optJson("publicProfile"),
            createdAt = json.requireString("createdAt"),
            updatedAt = json.requireString("updatedAt")
        )
    }
}

/**
 * Public-facing user profile returned by public user endpoints.
 *
 * @property userId The unique identifier for the user.
 * @property displayName The user display name.
 * @property avatarUrl URL to the user avatar image.
 * @property publicProfile JSON object containing public profile information.
 */
data class PublicProfile(
    val userId : String,
    val displayName : String,
    val avatarUrl : String?,
    val publicProfile : Json?
) : AccelByteResponse {
    companion object
    {
        fun fromJson(json : Json) : PublicProfile = PublicProfile(
            userId = json.requireString("userId"),
            displayName = json.requireString("displayName"),
            avatarUrl = json.optString("avatarUrl"),
            publicProfile = json.optJson("publicProfile")
        )
    }
}

/**
 * A list of public user profiles.
 *
 * @property data The list of public profiles.
 */
data class UserProfilesResponse(
    val data : List<PublicProfile>
) : AccelByteResponse {
    companion object
    {
        fun fromJson(json : Json) : UserProfilesResponse = UserProfilesResponse(
            data = json.optJsonList("data").map(PublicProfile::fromJson)
        )
    }
}