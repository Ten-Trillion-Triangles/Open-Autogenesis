package accelbyte.backfill

import kotlinx.serialization.Serializable

@Serializable
data class BackfillTicketRequest(
    val ticketId: String,
    val userId: String,
    val sessionId: String
)