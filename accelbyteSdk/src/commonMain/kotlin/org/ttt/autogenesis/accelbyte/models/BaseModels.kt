package org.ttt.autogenesis.accelbyte.models

import kotlin.js.Json
import kotlin.js.json

/**
 * Marker interface for any data class that represents a JSON-serializable request body
 * sent to an AccelByte API endpoint.
 *
 * All classes implementing this interface must provide a [toJson] method that produces
 * the exact payload shape the underlying TypeScript SDK expects. This allows facades to
 * remain thin — they simply call request.toJson() and forward the result to the module API.
 *
 * Usage:
 * 
 *
 * @see AccelByteResponse for the corresponding response marker
 */
interface AccelByteRequest
{
    /**
     * Serializes this request object into the JSON payload accepted by the AccelByte API.
     *
     * The returned [Json] must match the schema expected by the underlying TypeScript SDK
     * binding (e.g., field names must use camelCase where the API expects them).
     *
     * @return a [Json] object ready to be passed to the SDK binding. Never returns null.
     */
    fun toJson() : Json
}

/**
 * Marker interface for any data class that represents a deserialized response from an
 * AccelByte API endpoint.
 *
 * Unlike [AccelByteRequest], this interface carries no contract — response types are
 * plain Kotlin data classes with a companion object that provides a fromJson factory
 * for parsing the raw SDK payload.
 *
 * @see AccelByteRequest for the corresponding request marker
 */
interface AccelByteResponse

/**
 * Represents the pagination metadata returned by paginated AccelByte list endpoints.
 *
 * Not all endpoints return the same fields; [total], [offset], and [limit] are the common
 * subset. Use [fromJson] to construct an instance from the raw API response.
 *
 * @param total the total number of records matching the query across all pages
 * @param offset the number of records skipped before the current page
 * @param limit the maximum number of records returned per page (may be less on the last page)
 */
data class PagingInfo(
    val total : Int,
    val offset : Int,
    val limit : Int
) {
    companion object
    {
        /**
         * Parses a [PagingInfo] from a raw JSON response object.
         *
         * All fields are optional in the API response; missing or null values default to 0.
         *
         * @param json the JSON object returned as part of a paginated list response
         * @return a [PagingInfo] with sensible defaults for any absent fields
         */
        fun fromJson(json : Json) : PagingInfo = PagingInfo(
            total = json.optInt("total") ?: 0,
            offset = json.optInt("offset") ?: 0,
            limit = json.optInt("limit") ?: 0
        )
    }
}

/**
 * Builds a [Json] object from a list of key-value pairs, automatically omitting any pair
 * whose value is null.
 *
 * This is the primary utility for constructing request bodies inside [AccelByteRequest.toJson]
 * implementations. It filters out null values so the resulting payload only contains fields
 * the API actually expects, avoiding explicit null checks for every optional field.
 *
 * @param pairs vararg of String to Any? — the key and value for each field
 * @return a [Json] object containing all non-null pairs, or an empty JSON object if
 *         every value was null
 *
 * @example
 * 
 */
fun jsonOf(vararg pairs : Pair<String, Any?>) : Json
{
    val filtered = pairs.filter { it.second != null }.toTypedArray()
    return if (filtered.isEmpty()) json() else json(*filtered)
}

/**
 * Extension on [Json] that sets [value] under [key] only if [value] is non-null.
 *
 * Unlike [jsonOf], this mutates an existing [Json] object in place. Prefer [jsonOf] for
 * building new payloads; use this only when adding optional fields to an already-constructed
 * [Json] is more convenient.
 *
 * @param key the JSON property name
 * @param value the value to assign; ignored if null
 */
fun Json.putIfNotNull(key : String, value : Any?)
{
    if (value != null) {
        this[key] = value
    }
}

/**
 * Reads a required string field from [Json]. Throws if the field is absent or null.
 *
 * @param key the JSON property name
 * @return the non-null string value
 * @throws IllegalArgumentException if the field is missing or not a string
 */
fun Json.requireString(key : String) : String = optString(key) ?: error("Missing required string '$key'")

/**
 * Reads an optional string field from [Json], returning null if absent or null.
 *
 * @param key the JSON property name
 * @return the string value, or null if the field is absent or explicitly null
 */
fun Json.optString(key : String) : String? = this[key] as? String

/**
 * Reads a required boolean field from [Json]. Throws if the field is absent or null.
 *
 * @param key the JSON property name
 * @return the non-null boolean value
 * @throws IllegalArgumentException if the field is missing or not a boolean
 */
fun Json.requireBoolean(key : String) : Boolean = optBoolean(key) ?: error("Missing required boolean '$key'")

/**
 * Reads an optional boolean field from [Json], returning null if absent or null.
 *
 * @param key the JSON property name
 * @return the boolean value, or null if the field is absent or explicitly null
 */
fun Json.optBoolean(key : String) : Boolean? = this[key] as? Boolean

/**
 * Reads a required numeric field from [Json] as a [Number]. Throws if absent or null.
 *
 * Use [requireInt], [requireLong], or [requireDouble] when you need a specific numeric type.
 *
 * @param key the JSON property name
 * @return the non-null [Number]
 * @throws IllegalArgumentException if the field is missing or not numeric
 */
fun Json.requireNumber(key : String) : Number = optNumber(key) ?: error("Missing required number '$key'")

/**
 * Reads an optional numeric field from [Json] as a [Number], returning null if absent or null.
 *
 * @param key the JSON property name
 * @return the [Number], or null if the field is absent or explicitly null
 */
fun Json.optNumber(key : String) : Number? = this[key] as? Number

/**
 * Reads a required nested JSON object from [Json]. Throws if the field is absent or null.
 *
 * @param key the JSON property name
 * @return the nested [Json] object
 * @throws IllegalArgumentException if the field is missing or not an object
 */
fun Json.requireJson(key : String) : Json = optJson(key) ?: error("Missing required object '$key'")

/**
 * Reads an optional nested JSON object from [Json], returning null if absent or null.
 *
 * @param key the JSON property name
 * @return the nested [Json] object, or null if absent or explicitly null
 */
fun Json.optJson(key : String) : Json? = this[key] as? Json

/**
 * Reads an optional JSON array field from [Json], returning null if absent or null.
 *
 * @param key the JSON property name
 * @return the array of [Json] objects, or null if absent
 */
fun Json.optJsonArray(key : String) : Array<Json>? = this[key] as? Array<Json>

/**
 * Reads an optional JSON array field from [Json], returning an empty list if absent or null.
 *
 * @param key the JSON property name
 * @return a list of [Json] objects, or an empty list if the field is absent
 */
fun Json.optJsonList(key : String) : List<Json> = optJsonArray(key)?.toList()?.filterNotNull() ?: emptyList()

/**
 * Reads an optional JSON array field from [Json] as a list of strings, returning an empty
 * list if absent or null. Handles arrays that may contain non-string elements by filtering
 * them out.
 *
 * @param key the JSON property name
 * @return a list of string values, or an empty list if the field is absent
 */
fun Json.optStringList(key : String) : List<String> = (this[key] as? Array<*>)?.mapNotNull { it?.toString() } ?: emptyList()

/**
 * Reads a required integer field from [Json]. Throws if absent, null, or not a number.
 *
 * @param key the JSON property name
 * @return the integer value
 * @throws IllegalArgumentException if the field is missing, null, or not numeric
 */
fun Json.requireInt(key : String) : Int = requireNumber(key).toInt()

/**
 * Reads an optional integer field from [Json], returning null if absent or null.
 *
 * @param key the JSON property name
 * @return the integer value, or null if absent
 */
fun Json.optInt(key : String) : Int? = optNumber(key)?.toInt()

/**
 * Reads an optional long integer field from [Json], returning null if absent or null.
 *
 * @param key the JSON property name
 * @return the long value, or null if absent
 */
fun Json.optLong(key : String) : Long? = optNumber(key)?.toLong()

/**
 * Reads a required double-precision float field from [Json]. Throws if absent or null.
 *
 * @param key the JSON property name
 * @return the double value
 * @throws IllegalArgumentException if the field is missing, null, or not numeric
 */
fun Json.requireDouble(key : String) : Double = requireNumber(key).toDouble()

/**
 * Reads an optional double-precision float field from [Json], returning null if absent or null.
 *
 * @param key the JSON property name
 * @return the double value, or null if absent
 */
fun Json.optDouble(key : String) : Double? = optNumber(key)?.toDouble()

/**
 * Converts an [AccelByteRequest] to [Json], returning null if the receiver is null.
 *
 * Convenience overload for call sites that hold a nullable request reference and need a
 * nullable JSON parameter for an SDK binding that accepts Json | undefined.
 *
 * @receiver the request to serialize, or null
 * @return the JSON payload, or null if the receiver was null
 */
fun AccelByteRequest?.toJsonOrNull() : Json? = this?.toJson()
