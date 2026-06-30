package accelbyte.backfill

import kotlinx.serialization.Serializable

@Serializable
data class BackfillHandlerResult(
    val rejected: Boolean,
    val reason: String? = null,
    val candidate: String? = null
)