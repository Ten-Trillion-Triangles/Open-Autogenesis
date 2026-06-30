package org.ttt.autogenesis.accelbyte.models

import kotlin.js.Json

/**
 * A raw JSON envelope returned by the season pass export endpoint.
 *
 * The season pass export produces a full serialized snapshot of a user's season progress
 * as an opaque JSON blob. This response type wraps it without interpreting its contents.
 * downstream consumers should inspect [payload] directly for the exported data shape.
 *
 * @property payload the raw JSON export blob. Structure is service-defined.
 */
data class SeasonpassExportResponse(
    val payload : Json
) : AccelByteResponse {
    companion object
    {
        /**
         * Parses a [SeasonpassExportResponse] from the raw API response body.
         *
         * @param json the raw JSON payload returned by the export endpoint.
         * @return a [SeasonpassExportResponse] wrapping the entire response body as [payload].
         */
        fun fromJson(json : Json) : SeasonpassExportResponse = SeasonpassExportResponse(payload = json)
    }
}
