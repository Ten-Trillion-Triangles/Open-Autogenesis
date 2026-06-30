package org.ttt.autogenesis.accelbyte.models

import kotlin.js.Json

/**
 * Response indicating whether a user is following a creator.
 *
 * @property userId The unique identifier of the user.
 * @property state Whether the user is following (true) or not (false).
 */
data class CreatorFollowStateResponse(
    val userId : String,
    val state : Boolean
) : AccelByteResponse {
    companion object
    {
        fun fromJson(json : Json) : CreatorFollowStateResponse = CreatorFollowStateResponse(
            userId = json.requireString("userId"),
            state = json.requireBoolean("state")
        )
    }
}

/**
 * Response indicating whether a user has liked content.
 *
 * @property userId The unique identifier of the user.
 * @property state Whether the user has liked the content (true) or not (false).
 */
data class LikeStateResponse(
    val userId : String,
    val state : Boolean
) : AccelByteResponse {
    companion object
    {
        fun fromJson(json : Json) : LikeStateResponse = LikeStateResponse(
            userId = json.requireString("userId"),
            state = json.requireBoolean("state")
        )
    }
}

/**
 * A preview URL for content such as videos or images.
 *
 * @property source The source of this preview (e.g., youtube, twitch).
 * @property url The URL of the preview.
 */
data class PreviewUrlResponse(
    val source : String,
    val url : String
) : AccelByteResponse {
    companion object
    {
        fun fromJson(json : Json) : PreviewUrlResponse = PreviewUrlResponse(
            source = json.requireString("source"),
            url = json.requireString("url")
        )
    }
}

/**
 * A payload URL for downloadable content.
 *
 * @property source The source of this payload.
 * @property url The URL of the payload.
 */
data class PayloadUrlResponse(
    val source : String,
    val url : String
) : AccelByteResponse {
    companion object
    {
        fun fromJson(json : Json) : PayloadUrlResponse = PayloadUrlResponse(
            source = json.requireString("source"),
            url = json.requireString("url")
        )
    }
}

/**
 * Information about a content screenshot.
 *
 * @property screenshotId The unique identifier of this screenshot.
 * @property source The source of this screenshot.
 * @property url The URL of the screenshot.
 * @property description A description of the screenshot.
 * @property fileExtension The file extension of the screenshot.
 * @property contentType The content type of the screenshot.
 */
data class ScreenshotResponseModel(
    val screenshotId : String,
    val source : String,
    val url : String,
    val description : String,
    val fileExtension : String,
    val contentType : String?
) : AccelByteResponse {
    companion object
    {
        fun fromJson(json : Json) : ScreenshotResponseModel = ScreenshotResponseModel(
            screenshotId = json.requireString("screenshotId"),
            source = json.requireString("source"),
            url = json.requireString("url"),
            description = json.requireString("description"),
            fileExtension = json.requireString("fileExtension"),
            contentType = json.optString("contentType")
        )
    }
}

/**
 * Detailed information about downloadable UGC (User Generated Content).
 *
 * @property id The unique identifier of this content.
 * @property name The name of this content.
 * @property userId The creator's user ID.
 * @property namespace The namespace where this content exists.
 * @property channelId The channel this content belongs to.
 * @property createdTime Timestamp when this content was created.
 * @property downloadCount The number of downloads.
 * @property likeCount The number of likes.
 * @property shareCode The share code for this content.
 * @property tags List of tags associated with this content.
 * @property isOfficial Whether this is official content.
 * @property isHidden Whether this content is hidden.
 * @property customAttributes Custom attributes as JSON.
 * @property fileExtension The file extension of the content.
 * @property groups The content groups.
 * @property payloadUrls List of payload URLs for downloading.
 * @property screenshots List of screenshots for this content.
 * @property payload The payload data as a string.
 * @property previewUrls List of preview URLs.
 * @property subType The subtype of this content.
 * @property type The type of this content.
 * @property updatedTime Timestamp when this content was last updated.
 * @property creatorFollowState The creator follow state for the requesting user.
 * @property likeState The like state for the requesting user.
 * @property creatorName The name of the creator.
 */
data class ContentDownloadResponseModel(
    val id : String,
    val name : String,
    val userId : String,
    val namespace : String,
    val channelId : String,
    val createdTime : String,
    val downloadCount : Int,
    val likeCount : Int,
    val shareCode : String,
    val tags : List<String>,
    val isOfficial : Boolean,
    val isHidden : Boolean,
    val customAttributes : Json?,
    val fileExtension : String?,
    val groups : List<String>?,
    val payloadUrls : List<PayloadUrlResponse>?,
    val screenshots : List<ScreenshotResponseModel>?,
    val payload : String?,
    val previewUrls : List<PreviewUrlResponse>?,
    val subType : String?,
    val type : String?,
    val updatedTime : String?,
    val creatorFollowState : CreatorFollowStateResponse?,
    val likeState : LikeStateResponse?,
    val creatorName : String?
) : AccelByteResponse {
    companion object
    {
        fun fromJson(json : Json) : ContentDownloadResponseModel = ContentDownloadResponseModel(
            id = json.requireString("id"),
            name = json.requireString("name"),
            userId = json.requireString("userId"),
            namespace = json.requireString("namespace"),
            channelId = json.requireString("channelId"),
            createdTime = json.requireString("createdTime"),
            downloadCount = json.requireInt("downloadCount"),
            likeCount = json.requireInt("likeCount"),
            shareCode = json.requireString("shareCode"),
            tags = json.optJsonArray("tags")?.mapNotNull { it?.toString() } ?: emptyList(),
            isOfficial = json.requireBoolean("isOfficial"),
            isHidden = json.requireBoolean("isHidden"),
            customAttributes = json.optJson("customAttributes"),
            fileExtension = json.optString("fileExtension"),
            groups = json.optJsonArray("groups")?.mapNotNull { it?.toString() },
            payloadUrls = json.optJsonList("payloadURL").map(PayloadUrlResponse::fromJson).takeIf { it.isNotEmpty() },
            screenshots = json.optJsonList("screenshots").map(ScreenshotResponseModel::fromJson).takeIf { it.isNotEmpty() },
            payload = json.optString("payload"),
            previewUrls = json.optJsonList("previewURL").map(PreviewUrlResponse::fromJson).takeIf { it.isNotEmpty() },
            subType = json.optString("subType"),
            type = json.optString("type"),
            updatedTime = json.optString("updatedTime"),
            creatorFollowState = json.optJson("creatorFollowState")?.let(CreatorFollowStateResponse::fromJson),
            likeState = json.optJson("likeState")?.let(LikeStateResponse::fromJson),
            creatorName = json.optString("creatorName")
        )
    }
}

/**
 * A paginated list of UGC content.
 *
 * @property data The list of content entries on this page.
 * @property paging Pagination metadata for navigating additional pages.
 */
data class ContentListResponse(
    val data : List<ContentDownloadResponseModel>,
    val paging : CursorPagination
) : AccelByteResponse {
    companion object
    {
        fun fromJson(json : Json) : ContentListResponse = ContentListResponse(
            data = json.optJsonList("data").map(ContentDownloadResponseModel::fromJson),
            paging = CursorPagination.fromJson(json.requireJson("paging"))
        )
    }
}

/**
 * A paginated list of downloadable UGC content.
 *
 * @property data The list of content download entries on this page.
 * @property paging Pagination metadata for navigating additional pages.
 */
data class PaginatedContentDownloadResponse(
    val data : List<ContentDownloadResponseModel>,
    val paging : CursorPagination
) : AccelByteResponse {
    companion object
    {
        fun fromJson(json : Json) : PaginatedContentDownloadResponse = PaginatedContentDownloadResponse(
            data = json.optJsonList("data").map(ContentDownloadResponseModel::fromJson),
            paging = CursorPagination.fromJson(json.requireJson("paging"))
        )
    }
}

/**
 * Overview statistics for a content creator.
 *
 * @property id The unique identifier of this creator.
 * @property namespace The namespace where this creator exists.
 * @property parentNamespace The parent namespace.
 * @property followCount The number of followers.
 * @property followingCount The number of accounts this creator follows.
 * @property totalLikedContent The total number of content liked by this creator.
 */
data class CreatorOverviewResponseModel(
    val id : String,
    val namespace : String,
    val parentNamespace : String,
    val followCount : Int,
    val followingCount : Int,
    val totalLikedContent : Int
) : AccelByteResponse {
    companion object
    {
        fun fromJson(json : Json) : CreatorOverviewResponseModel = CreatorOverviewResponseModel(
            id = json.requireString("id"),
            namespace = json.requireString("namespace"),
            parentNamespace = json.requireString("parentNamespace"),
            followCount = json.requireInt("followCount"),
            followingCount = json.requireInt("followingCount"),
            totalLikedContent = json.requireInt("totalLikedContent")
        )
    }
}

/**
 * A paginated list of creator overviews.
 *
 * @property data The list of creator overview entries on this page.
 * @property paging Pagination metadata for navigating additional pages.
 */
data class PaginatedCreatorOverviewResponse(
    val data : List<CreatorOverviewResponseModel>,
    val paging : CursorPagination
) : AccelByteResponse {
    companion object
    {
        fun fromJson(json : Json) : PaginatedCreatorOverviewResponse = PaginatedCreatorOverviewResponse(
            data = json.optJsonList("data").map(CreatorOverviewResponseModel::fromJson),
            paging = CursorPagination.fromJson(json.requireJson("paging"))
        )
    }
}

/**
 * Response for a user follow action.
 *
 * @property userId The unique identifier of the user.
 * @property followStatus Whether the user is following (true) or not (false).
 */
data class UserFollowResponseModel(
    val userId : String,
    val followStatus : Boolean
) : AccelByteResponse {
    companion object
    {
        fun fromJson(json : Json) : UserFollowResponseModel = UserFollowResponseModel(
            userId = json.requireString("userId"),
            followStatus = json.requireBoolean("followStatus")
        )
    }
}
