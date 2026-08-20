package structs.accelbyte.common

import kotlinx.serialization.json.Json

/**
 * Base interface for AccelByte models with JSON serialization support
 */
interface AccelByteSerializable {
    /**
     * Serialize to JSON string compatible with original AccelByte models
     */
    fun toJsonString(): String
}

/**
 * JSON configuration matching AccelByte SDK requirements
 */
val AccelByteJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = false
    isLenient = true
}