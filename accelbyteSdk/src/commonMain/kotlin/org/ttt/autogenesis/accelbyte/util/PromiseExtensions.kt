package org.ttt.autogenesis.accelbyte.util

import kotlin.js.Json
import kotlin.js.Promise

/**
 * Maps a Promise<Json> to Promise<R> using the provided transform function.
 * @param transform Function to convert Json to R
 * @return Promise resolving to transformed result
 */
internal fun <R> Promise<Json>.mapJson(transform : (Json) -> R) : Promise<R> = then(transform)

/**
 * Maps a Promise<Json?> to Promise<R?> using the provided transform function.
 * Handles nullable Json values safely.
 * @param transform Function to convert Json to R
 * @return Promise resolving to transformed result or null
 */
internal fun <R> Promise<Json?>.mapJson(transform : (Json) -> R?) : Promise<R?> = then { it?.let(transform) }

/**
 * Maps a Promise<*> to Promise<Unit>, discarding the raw payload.
 * @return Promise resolving to Unit
 */
internal fun Promise<*>.mapToUnit() : Promise<Unit> = then { }

/**
 * Maps a Promise<Json> to Promise<List<R>> by treating Json as an array.
 * @param transform Function to convert each Json element to R
 * @return Promise resolving to list of transformed results
 */
internal fun <R> Promise<Json>.mapJsonList(transform : (Json) -> R) : Promise<List<R>> = then { json ->
    val array = json as? Array<Json>
    val list = array?.map(transform) ?: emptyList()
    list
}

/**
 * Maps a Promise<Array<Json>> to Promise<List<R>> using the provided transform function.
 * @param transform Function to convert each Json element to R
 * @return Promise resolving to list of transformed results
 */
internal fun <R> Promise<Array<Json>>.mapJsonArray(transform : (Json) -> R) : Promise<List<R>> =
    then { array -> array.map(transform) }

internal fun jsErrorAsThrowable(error : Any?) : Throwable {
    // In Kotlin/JS, Throwable is the JS Error object. We can cast it to dynamic.
    val dyn = error.asDynamic()
    
    // Try to extract Axios response data
    val responseData = try {
        dyn?.response?.data
    } catch (e: Throwable) {
        null
    }

    if (responseData != null) {
        val json = JSON.stringify(responseData)
        return RuntimeException("API Error: $json")
    }

    // Fallback for other errors
    return when (error) {
        is Throwable -> error
        is String -> RuntimeException(error)
        else -> {
            val msg = try {
                JSON.stringify(error)
            } catch (e: Throwable) {
                error?.toString() ?: "Unknown JS error"
            }
            RuntimeException(msg)
        }
    }
}

internal fun <T> Promise<T>.propagateJsErrors() : Promise<T> {
    return this.asDynamic().catch { error: Any? ->
        throw jsErrorAsThrowable(error)
    }.unsafeCast<Promise<T>>()
}

/**
 * Extracts the AccelByte error code from a RuntimeException thrown by the SDK.
 * @param error The exception to parse
 * @return The error code if found, null otherwise
 */
fun parseAccelByteErrorCode(error: Throwable): Int? {
    val message = error.message ?: return null
    if (!message.startsWith("API Error: ")) return null
    
    val jsonStart = message.indexOf('{')
    if (jsonStart == -1) return null
    
    val jsonStr = message.substring(jsonStart)
    return try {
        val json = JSON.parse<dynamic>(jsonStr)
        json.errorCode as? Int
    } catch (e: Throwable) {
        null
    }
}
