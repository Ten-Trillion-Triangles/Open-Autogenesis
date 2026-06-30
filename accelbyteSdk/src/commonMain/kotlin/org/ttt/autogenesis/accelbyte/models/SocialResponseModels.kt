package org.ttt.autogenesis.accelbyte.models

import kotlin.js.Json

/**
 * A single stat entry for a user.
 *
 * @property statCode The code identifying this stat.
 * @property value The value of this stat.
 * @property namespace The namespace where this stat exists.
 * @property userId The user this stat belongs to.
 * @property valueType The type of the stat value.
 * @property createdAt Timestamp when this stat was created.
 * @property updatedAt Timestamp when this stat was last updated.
 */
data class StatItem(
    val statCode : String,
    val value : Double?,
    val namespace : String?,
    val userId : String?,
    val valueType : String?,
    val createdAt : String?,
    val updatedAt : String?
) : AccelByteResponse {
    companion object
    {
        fun fromJson(json : Json) : StatItem = StatItem(
            statCode = json.requireString("statCode"),
            value = json.optDouble("value") ?: json.optNumber("value")?.toDouble(),
            namespace = json.optString("namespace"),
            userId = json.optString("userId"),
            valueType = json.optString("valueType"),
            createdAt = json.optString("createdAt"),
            updatedAt = json.optString("updatedAt")
        )
    }
}

/**
 * A paginated list of stat items for a user.
 *
 * @property data The list of stat items on this page.
 * @property paging Pagination metadata for navigating additional pages.
 */
data class StatItemsResponse(
    val data : List<StatItem>,
    val paging : PagingInfo
) : AccelByteResponse {
    companion object
    {
        fun fromJson(json : Json) : StatItemsResponse = StatItemsResponse(
            data = json.optJsonList("data").map(StatItem::fromJson),
            paging = PagingInfo.fromJson(json.requireJson("paging"))
        )
    }
}
