package org.ttt.autogenesis.accelbyte.models

import kotlin.js.Json
import kotlin.js.json

/**
 * Standard offset-based pagination parameters used by most list endpoints.
 *
 * @property limit maximum number of records to return per page. Defaults to 20.
 * @property offset number of records to skip. Defaults to 0 (first page).
 */
data class PaginationParams(val limit : Int = 20, val offset : Int = 0) : AccelByteRequest
{
    override fun toJson() : Json = json("limit" to limit, "offset" to offset)
}

/**
 * A simpler pagination variant that caps results without allowing offset-based navigation.
 *
 * @property limit maximum number of records to return. Defaults to 20.
 */
data class LimitOnlyParams(val limit : Int = 20) : AccelByteRequest
{
    override fun toJson() : Json = json("limit" to limit)
}

/**
 * Request to filter results by active/enabled state.
 *
 * @property activeOnly when true, returns only active or enabled records.
 *                      false returns only inactive ones. Defaults to true.
 */
data class BooleanFilterParams(val activeOnly : Boolean = true) : AccelByteRequest
{
    override fun toJson() : Json = json("activeOnly" to activeOnly)
}

/**
 * Request that embeds a raw string filter expression on top of standard pagination.
 *
 * @property filter a server-side filter expression string (syntax is endpoint-specific).
 */
data class StringFilterParams(val filter : String) : AccelByteRequest
{
    override fun toJson() : Json = json("filter" to filter)
}

/**
 * Request scoped to a single user by their unique user identifier.
 *
 * @property userId the target user's unique identifier.
 */
data class UserIdParams(val userId : String) : AccelByteRequest
{
    override fun toJson() : Json = json("userId" to userId)
}

/**
 * Request scoped to a list of users by their unique user identifiers.
 *
 * @property userIds the list of target user identifiers.
 */
data class UserIdsParams(val userIds : List<String>) : AccelByteRequest
{
    override fun toJson() : Json = json("userIds" to userIds.toTypedArray())
}

/**
 * Request scoped to a single platform namespace.
 *
 * @property namespace the target namespace identifier.
 */
data class NamespaceParams(val namespace : String) : AccelByteRequest
{
    override fun toJson() : Json = json("namespace" to namespace)
}

/**
 * Request with an optional force flag to override server-side safeguards.
 *
 * @property force when true, bypasses confirmation prompts and soft constraints.
 *                Defaults to false (safe mode).
 */
data class ForceActionParams(val force : Boolean = false) : AccelByteRequest
{
    override fun toJson() : Json = json("force" to force)
}
