package org.ttt.autogenesis.accelbyte.models

import kotlin.js.Json

/**
 * Represents an individual UGC (User-Generated Content) content item returned from the API.
 *
 * This is a summary view of a single piece of user-generated content. It contains basic
 * metadata about the content such as its identifier, name, creator, status, and associated
 * tags. Use [ContentDetailResponse] when you need the full set of content attributes.
 *
 * @property id Unique identifier for the content. Always present in API responses.
 * @property name Display name of the content, or null if not set.
 * @property creator User ID or display name of the content's creator, or null if not available.
 * @property status Current lifecycle status of the content (e.g., "ACTIVE", "INACTIVE", "PENDING").
 *        May be null if the content has no assigned status.
 * @property type Content type classification (e.g., "IMAGE", "VIDEO", "AUDIO"). Null if undefined.
 * @property tags List of tags associated with this content for categorization and search.
 *        May be null if no tags have been assigned.
 * @property createdAt ISO 8601 timestamp indicating when the content was first created.
 *        May be null if creation time is not available.
 * @property updatedAt ISO 8601 timestamp indicating when the content was last modified.
 *        May be null if the content has never been updated.
 */
data class ContentResponse(
    val id: String,
    val name: String?,
    val creator: String?,
    val status: String?,
    val type: String?,
    val tags: List<String>?,
    val createdAt: String?,
    val updatedAt: String?
) : AccelByteResponse {
    companion object {
        /**
         * Parses a JSON object into a [ContentResponse] instance.
         *
         * @param json The raw JSON object to parse. May represent a direct content object
         *        or a wrapper containing a nested "data" field.
         * @return A fully populated [ContentResponse].
         * @throws Exception If the JSON structure is unparseable or a required field
         *         (specifically "id") is missing or maltyped.
         */
        fun fromJson(json: Json): ContentResponse {
            val actualData = json.asDynamic().data?.unsafeCast<Json>() ?: json
            return ContentResponse(
                id = actualData.unsafeCast<Json>().requireString("id"),
                name = actualData.unsafeCast<Json>().optString("name"),
                creator = actualData.unsafeCast<Json>().optString("creator"),
                status = actualData.unsafeCast<Json>().optString("status"),
                type = actualData.unsafeCast<Json>().optString("type"),
                tags = actualData.unsafeCast<Json>().optStringList("tags"),
                createdAt = actualData.unsafeCast<Json>().optString("createdAt"),
                updatedAt = actualData.unsafeCast<Json>().optString("updatedAt")
            )
        }
    }
}

/**
 * Represents a UGC channel, which acts as a category bucket for organizing user-generated content.
 *
 * Channels group content by topic, game mode, or any other logical division the platform
 * supports. Each channel has its own namespace scope and optional description.
 *
 * @property id Unique identifier for the channel. Always present in API responses.
 * @property name Human-readable name of the channel, or null if not set.
 * @property namespace The namespace to which this channel belongs, or null if undefined.
 * @property description A short description explaining the channel's purpose, or null if absent.
 */
data class ChannelResponse(
    val id: String,
    val name: String?,
    val namespace: String?,
    val description: String?
) : AccelByteResponse {
    companion object {
        /**
         * Parses a JSON object into a [ChannelResponse] instance.
         *
         * @param json The raw JSON object to parse. May represent a direct channel object
         *        or a wrapper containing a nested "data" field.
         * @return A fully populated [ChannelResponse].
         * @throws Exception If the JSON structure is unparseable or a required field
         *         (specifically "id") is missing or maltyped.
         */
        fun fromJson(json: Json): ChannelResponse {
            val actualData = json.asDynamic().data?.unsafeCast<Json>() ?: json
            return ChannelResponse(
                id = actualData.unsafeCast<Json>().requireString("id"),
                name = actualData.unsafeCast<Json>().optString("name"),
                namespace = actualData.unsafeCast<Json>().optString("namespace"),
                description = actualData.unsafeCast<Json>().optString("description")
            )
        }
    }
}

/**
 * Represents a creator analytics summary returned by the UGC overview endpoint.
 *
 * This model captures aggregate statistics about a creator's presence on the platform,
 * including follower counts and engagement metrics. All fields are required.
 *
 * @property namespace The namespace (game or service) to which this creator profile belongs.
 * @property id The unique user identifier of the creator.
 * @property parentNamespace The parent namespace that owns or oversees this creator's namespace.
 * @property followCount The total number of users currently following this creator.
 * @property followingCount The total number of other creators this creator is following.
 * @property totalLikedContent The total number of likes received across all of the creator's content.
 */
data class CreatorOverviewResponse(
    val namespace: String,
    val id: String,
    val parentNamespace: String,
    val followCount: Int,
    val followingCount: Int,
    val totalLikedContent: Int
) : AccelByteResponse {
    companion object {
        /**
         * Parses a JSON object into a [CreatorOverviewResponse] instance.
         *
         * @param json The raw JSON object to parse. May represent a direct creator overview
         *        object or a wrapper containing a nested "data" field.
         * @return A fully populated [CreatorOverviewResponse].
         * @throws Exception If the JSON structure is unparseable or any required field
         *         (namespace, id, parentNamespace, followCount, followingCount, totalLikedContent)
         *         is missing or maltyped.
         */
        fun fromJson(json: Json): CreatorOverviewResponse {
            val actualData = json.asDynamic().data?.unsafeCast<Json>() ?: json
            return CreatorOverviewResponse(
                namespace = actualData.unsafeCast<Json>().requireString("namespace"),
                id = actualData.unsafeCast<Json>().requireString("id"),
                parentNamespace = actualData.unsafeCast<Json>().requireString("parentNamespace"),
                followCount = actualData.unsafeCast<Json>().requireInt("followCount"),
                followingCount = actualData.unsafeCast<Json>().requireInt("followingCount"),
                totalLikedContent = actualData.unsafeCast<Json>().requireInt("totalLikedContent")
            )
        }
    }
}

/**
 * Represents a single user who liked a specific piece of UGC content.
 *
 * This model is typically returned when iterating over the list of users who have liked
 * a piece of content. It identifies the user and provides the timestamp of when the like
 * action occurred.
 *
 * @property userId The unique identifier of the user who liked the content. Always present.
 * @property displayName The user's display name, or null if the user has not set one.
 * @property likedAt ISO 8601 timestamp indicating when the user liked the content.
 *        May be null if the like timestamp is not available.
 */
data class ContentLikersResponse(
    val userId: String,
    val displayName: String?,
    val likedAt: String?
) : AccelByteResponse {
    companion object {
        /**
         * Parses a JSON object into a [ContentLikersResponse] instance.
         *
         * @param json The raw JSON object to parse. May represent a direct liker entry
         *        or a wrapper containing a nested "data" field.
         * @return A fully populated [ContentLikersResponse].
         * @throws Exception If the JSON structure is unparseable or a required field
         *         (specifically "userId") is missing or maltyped.
         */
        fun fromJson(json: Json): ContentLikersResponse {
            val actualData = json.asDynamic().data?.unsafeCast<Json>() ?: json
            return ContentLikersResponse(
                userId = actualData.unsafeCast<Json>().requireString("userId"),
                displayName = actualData.unsafeCast<Json>().optString("displayName"),
                likedAt = actualData.unsafeCast<Json>().optString("likedAt")
            )
        }
    }
}

/**
 * Represents a UGC tag with its associated usage count across the platform.
 *
 * Tags are used to categorize and surface user-generated content. This response
 * indicates how many pieces of content are associated with a given tag.
 *
 * @property id The unique identifier of the tag. Always present.
 * @property name The human-readable name of the tag. Always present.
 * @property count The total number of content items currently using this tag.
 *        May be null if counts are not tracked.
 */
data class TagResponse(
    val id: String,
    val name: String,
    val count: Int?
) : AccelByteResponse {
    companion object {
        /**
         * Parses a JSON object into a [TagResponse] instance.
         *
         * @param json The raw JSON object to parse. May represent a direct tag entry
         *        or a wrapper containing a nested "data" field.
         * @return A fully populated [TagResponse].
         * @throws Exception If the JSON structure is unparseable or a required field
         *         (id or name) is missing or maltyped.
         */
        fun fromJson(json: Json): TagResponse {
            val actualData = json.asDynamic().data?.unsafeCast<Json>() ?: json
            return TagResponse(
                id = actualData.unsafeCast<Json>().requireString("id"),
                name = actualData.unsafeCast<Json>().requireString("name"),
                count = actualData.unsafeCast<Json>().optInt("count")
            )
        }
    }
}

/**
 * Represents a UGC content type definition, describing a category of content supported by the platform.
 *
 * Content types classify user-generated content (e.g., "SKIN", "MAP", "AUDIO", "VIDEO") and
 * may carry additional descriptive metadata. The platform administrator defines these types.
 *
 * @property id The unique identifier of the content type. Always present.
 * @property name The human-readable name of the content type. Always present.
 * @property description A detailed explanation of what this content type represents, or null if absent.
 */
data class TypeResponse(
    val id: String,
    val name: String,
    val description: String?
) : AccelByteResponse {
    companion object {
        /**
         * Parses a JSON object into a [TypeResponse] instance.
         *
         * @param json The raw JSON object to parse. May represent a direct type entry
         *        or a wrapper containing a nested "data" field.
         * @return A fully populated [TypeResponse].
         * @throws Exception If the JSON structure is unparseable or a required field
         *         (id or name) is missing or maltyped.
         */
        fun fromJson(json: Json): TypeResponse {
            val actualData = json.asDynamic().data?.unsafeCast<Json>() ?: json
            return TypeResponse(
                id = actualData.unsafeCast<Json>().requireString("id"),
                name = actualData.unsafeCast<Json>().requireString("name"),
                description = actualData.unsafeCast<Json>().optString("description")
            )
        }
    }
}

/**
 * Cursor-based pagination object used for paginating UGC API list responses.
 *
 * This model carries the opaque cursor strings that encode the current position in
 * a paginated result set. Pass [next] or [previous] as the  query parameter
 * to retrieve the next or previous page of results.
 *
 * @property next An opaque cursor string pointing to the next page of results,
 *        or null if there are no further results.
 * @property previous An opaque cursor string pointing to the previous page of results,
 *        or null if this is the first page.
 */
data class PagingCursor(
    val next: String?,
    val previous: String?
) {
    companion object {
        /**
         * Parses a JSON object into a [PagingCursor] instance.
         *
         * @param json The raw JSON object containing pagination cursor fields.
         * @return A fully populated [PagingCursor].
         * @throws Exception If the JSON structure is unparseable.
         */
        fun fromJson(json: Json): PagingCursor = PagingCursor(
            next = json.optString("next"),
            previous = json.optString("previous")
        )
    }
}

/**
 * Represents a signed URL for uploading or downloading UGC content, along with its expiry time.
 *
 * Signed URLs allow secure, time-limited access to content storage without requiring
 * the client to authenticate directly. The URL expires at the timestamp specified in
 * [expiresAt]; attempt to use it after expiry will result in an authorization failure.
 *
 * @property url The pre-signed URL string used to upload or download content.
 *        Always present and non-null.
 * @property expiresAt ISO 8601 timestamp indicating when the signed URL will expire
 *        and can no longer be used for authentication. May be null if expiry is not enforced.
 */
data class PayloadUrl(
    val url: String,
    val expiresAt: String?
) : AccelByteResponse {
    companion object {
        /**
         * Parses a JSON object into a [PayloadUrl] instance.
         *
         * @param json The raw JSON object to parse. May represent a direct payload URL object
         *        or a wrapper containing a nested "data" field.
         * @return A fully populated [PayloadUrl].
         * @throws Exception If the JSON structure is unparseable or a required field
         *         (specifically "url") is missing or maltyped.
         */
        fun fromJson(json: Json): PayloadUrl {
            val actualData = json.asDynamic().data?.unsafeCast<Json>() ?: json
            return PayloadUrl(
                url = actualData.unsafeCast<Json>().requireString("url"),
                expiresAt = actualData.unsafeCast<Json>().optString("expiresAt")
            )
        }
    }
}

/**
 * Represents the current user's like state for a piece of UGC content, along with the total like count.
 *
 * This model provides both the current user's personal like status (whether they have liked
 * the content) and the aggregate like count visible to all users.
 *
 * @property liked True if the current user has liked this content; false otherwise. Always present.
 * @property count The total number of likes this content has received from all users. Always present.
 */
data class LikeState(
    val liked: Boolean,
    val count: Int
) : AccelByteResponse {
    companion object {
        /**
         * Parses a JSON object into a [LikeState] instance.
         *
         * @param json The raw JSON object to parse. May represent a direct like state object
         *        or a wrapper containing a nested "data" field.
         * @return A fully populated [LikeState].
         * @throws Exception If the JSON structure is unparseable or a required field
         *         (liked or count) is missing or maltyped.
         */
        fun fromJson(json: Json): LikeState {
            val actualData = json.asDynamic().data?.unsafeCast<Json>() ?: json
            return LikeState(
                liked = actualData.unsafeCast<Json>().requireBoolean("liked"),
                count = actualData.unsafeCast<Json>().requireInt("count")
            )
        }
    }
}

/**
 * Represents the current user's follow state for a creator, along with the total follower count.
 *
 * This model provides both the current user's personal follow status (whether they follow
 * the creator) and the aggregate follower count visible to all users.
 *
 * @property following True if the current user is following this creator; false otherwise. Always present.
 * @property followCount The total number of users following this creator. Always present.
 */
data class FollowState(
    val following: Boolean,
    val followCount: Int
) : AccelByteResponse {
    companion object {
        /**
         * Parses a JSON object into a [FollowState] instance.
         *
         * @param json The raw JSON object to parse. May represent a direct follow state object
         *        or a wrapper containing a nested "data" field.
         * @return A fully populated [FollowState].
         * @throws Exception If the JSON structure is unparseable or a required field
         *         (following or followCount) is missing or maltyped.
         */
        fun fromJson(json: Json): FollowState {
            val actualData = json.asDynamic().data?.unsafeCast<Json>() ?: json
            return FollowState(
                following = actualData.unsafeCast<Json>().requireBoolean("following"),
                followCount = actualData.unsafeCast<Json>().requireInt("followCount")
            )
        }
    }
}

/**
 * Represents the full detail view of a single piece of UGC content.
 *
 * This model extends the summary fields of [ContentResponse] with additional attributes
 * such as the channel assignment and custom attributes. It is used when loading the
 * complete representation of a specific content item.
 *
 * @property id Unique identifier for the content. Always present in API responses.
 * @property name Display name of the content, or null if not set.
 * @property type Content type classification (e.g., "IMAGE", "VIDEO"), or null if undefined.
 * @property status Current lifecycle status of the content (e.g., "ACTIVE", "INACTIVE").
 *        May be null if no status is assigned.
 * @property tags List of tags associated with this content, or null if no tags are assigned.
 * @property creator User ID or display name of the content's creator, or null if not available.
 * @property channelId The identifier of the channel to which this content belongs, or null if unassigned.
 * @property customAttributes Arbitrary JSON blob containing content-type-specific custom attributes.
 *        May be null if no custom attributes have been set.
 * @property createdAt ISO 8601 timestamp indicating when the content was created. May be null.
 * @property updatedAt ISO 8601 timestamp indicating when the content was last modified. May be null.
 */
data class ContentDetailResponse(
    val id: String,
    val name: String?,
    val type: String?,
    val status: String?,
    val tags: List<String>?,
    val creator: String?,
    val channelId: String?,
    val customAttributes: Json?,
    val createdAt: String?,
    val updatedAt: String?
) : AccelByteResponse {
    companion object {
        /**
         * Parses a JSON object into a [ContentDetailResponse] instance.
         *
         * @param json The raw JSON object to parse. May represent a direct content detail object
         *        or a wrapper containing a nested "data" field.
         * @return A fully populated [ContentDetailResponse].
         * @throws Exception If the JSON structure is unparseable or a required field
         *         (specifically "id") is missing or maltyped.
         */
        fun fromJson(json: Json): ContentDetailResponse {
            val actualData = json.asDynamic().data?.unsafeCast<Json>() ?: json
            return ContentDetailResponse(
                id = actualData.unsafeCast<Json>().requireString("id"),
                name = actualData.unsafeCast<Json>().optString("name"),
                type = actualData.unsafeCast<Json>().optString("type"),
                status = actualData.unsafeCast<Json>().optString("status"),
                tags = actualData.unsafeCast<Json>().optStringList("tags"),
                creator = actualData.unsafeCast<Json>().optString("creator"),
                channelId = actualData.unsafeCast<Json>().optString("channelId"),
                customAttributes = actualData.unsafeCast<Json>().optJson("customAttributes"),
                createdAt = actualData.unsafeCast<Json>().optString("createdAt"),
                updatedAt = actualData.unsafeCast<Json>().optString("updatedAt")
            )
        }
    }
}
