package accelbyte.ams

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

private val jackson = jacksonObjectMapper().apply {
    findAndRegisterModules()
    configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
}

internal fun Any.toJsonElement() : JsonElement =
    Json.parseToJsonElement(jackson.writeValueAsString(this))

internal inline fun <reified T> JsonElement.toModel() : T =
    jackson.readValue(Json.encodeToString(JsonElement.serializer(), this), T::class.java)