package org.ttt.autogenesis.accelbyte.models

import kotlin.js.Json

/**
 * Cursor-based pagination cursors returned by list endpoints that support server-driven
 * cursor navigation.
 *
 * Pass the [next] or [previous] URL strings directly as query parameters on subsequent calls.
 * The [first] and [last] cursors navigate to the absolute first and last pages.
 *
 * @property first a URL pointing to the first page of the result set.
 * @property last a URL pointing to the last page of the result set.
 * @property next a URL for the next page, or null if already on the last page.
 * @property previous a URL for the previous page, or null if already on the first page.
 */
data class CursorPagination(
    val first : String,
    val last : String,
    val next : String,
    val previous : String
) : AccelByteResponse {
    companion object
    {
        /**
         * Parses a [CursorPagination] from the raw cursor object in the API response.
         *
         * @param json the JSON object carrying the four cursor URL fields from the API.
         * @return a fully populated [CursorPagination].
         * @throws IllegalArgumentException if any required field is absent or not a string.
         */
        fun fromJson(json : Json) : CursorPagination = CursorPagination(
            first = json.requireString("first"),
            last = json.requireString("last"),
            next = json.requireString("next"),
            previous = json.requireString("previous")
        )
    }
}
