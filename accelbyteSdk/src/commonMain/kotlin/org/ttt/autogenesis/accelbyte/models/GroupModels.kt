package org.ttt.autogenesis.accelbyte.models

import kotlin.js.Json
import kotlin.js.json

/**
 * Query parameters for searching or listing groups.
 * Use with [org.ttt.autogenesis.accelbyte.facades.GroupFacade.listGroups].
 *
 * @property groupName a partial or full name pattern to filter groups by. Null = no filter.
 * @property limit maximum number of groups to return per page. Defaults to 20.
 */
data class GroupQueryParams(val groupName : String? = null, val limit : Int = 20) : AccelByteRequest
{
    override fun toJson() : Json = json("groupName" to groupName, "limit" to limit)
}

/**
 * Request payload for creating a new group on the platform's group service.
 *
 * @property groupName the display name for the new group.
 * @property groupRegion the operational region or region code for this group.
 */
data class GroupCreateRequest(val groupName : String, val groupRegion : String) : AccelByteRequest
{
    override fun toJson() : Json = json("groupName" to groupName, "groupRegion" to groupRegion)
}
