package org.ttt.autogenesis.accelbyte.models

import kotlin.js.Json

/**
 * Response model for AccelByte API operation.
 */
data class ImageResponse(
    val imageUrl : String,
    val smallImageUrl : String?,
    val asDescription : String?,
    val caption : String?,
    val width : Int?,
    val height : Int?
) : AccelByteResponse {
    companion object
    {
        fun fromJson(json : Json) : ImageResponse = ImageResponse(
            imageUrl = json.requireString("imageUrl"),
            smallImageUrl = json.optString("smallImageUrl"),
            asDescription = json.optString("as"),
            caption = json.optString("caption"),
            width = json.optInt("width"),
            height = json.optInt("height")
        )
    }
}

/**
 * Response model for AccelByte API operation.
 */
data class LocalizationResponse(
    val title : String?,
    val description : String?
) : AccelByteResponse {
    companion object
    {
        fun fromJson(json : Json) : LocalizationResponse = LocalizationResponse(
            title = json.optString("title"),
            description = json.optString("description")
        )
    }
}
