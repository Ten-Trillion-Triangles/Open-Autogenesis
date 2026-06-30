package structs

import kotlinx.serialization.Serializable

@Serializable
data class CountStreamRequest(
    val start: Int,
    val count: Int
)

@Serializable
data class CountStreamResponse(
    val value: Int
)
