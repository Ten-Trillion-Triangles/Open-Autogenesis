package org.ttt.autogenesis.accelbyte.models

import kotlin.js.Json
import kotlin.js.json

/**
 * Query parameters for paginated content listing.
 * Use with [org.ttt.autogenesis.accelbyte.facades.ContentFacade.listContent].
 *
 * @property limit maximum number of content items to return per page. Defaults to 20.
 */
data class ContentQueryParams(val limit : Int = 20) : AccelByteRequest
{
    override fun toJson() : Json = json("limit" to limit)
}

/**
 * Filter parameters for refined content searches.
 * Combine with [ContentQueryParams] for tagged, typed, paginated content retrieval.
 *
 * @property tags list of tag strings; content must carry at least one of these tags.
 *                Null = no tag filtering.
 * @property type content type identifier to filter by (e.g., "IMAGE", "VIDEO", "DOC").
 *               Null = no type filtering.
 */
data class ContentFilterParams(val tags : List<String>? = null, val type : String? = null) : AccelByteRequest
{
    override fun toJson() : Json = jsonOf("tags" to tags?.toTypedArray(), "type" to type)
}

/**
 * Request payload for creating a new content item in the platform content service.
 *
 * @property name a human-readable display name for the content item.
 * @property type the content type — must match a type registered and enabled on the platform.
 * @property data an arbitrary JSON blob containing the content's metadata and/or payload.
 */
data class ContentCreateRequest(val name : String, val type : String, val data : Json) : AccelByteRequest
{
    override fun toJson() : Json = json("name" to name, "type" to type, "data" to data)
}
