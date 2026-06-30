package org.ttt.autogenesis.accelbyte.models

import kotlin.js.Json

/**
 * Offset-based pagination cursors for list endpoints that use skip/offset pagination.
 *
 * These are opaque URL strings containing server-encoded offset and limit values.
 * Pass [next] or [previous] directly as the query string on the next/previous request
 * rather than manually constructing offset parameters.
 *
 * @property next a full URL string for the next page, or null if already on the last page.
 * @property previous a full URL string for the previous page, or null if already on the first.
 */
data class OffsetPagination(
    val next : String?,
    val previous : String?
) : AccelByteResponse {
    companion object
    {
        /**
         * Parses an [OffsetPagination] from the raw pagination object returned by the API.
         *
         * @param json the JSON object carrying pagination URLs (fields may be null or omitted).
         * @return an [OffsetPagination] — both cursors may be null on single-page responses.
         */
        fun fromJson(json : Json) : OffsetPagination = OffsetPagination(
            next = json.optString("next"),
            previous = json.optString("previous")
        )
    }
}
