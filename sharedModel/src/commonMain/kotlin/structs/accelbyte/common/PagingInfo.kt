package structs.accelbyte.common

import kotlinx.serialization.Serializable

/**
 * Paging information returned on list responses.
 */
@Serializable
data class PagingInfo(
    val total: Int,
    val offset: Int,
    val limit: Int
)