package org.ttt.autogenesis.logging

import kotlin.PublishedApi

private const val MAX_LOG_VALUE_LENGTH = 400

@PublishedApi
internal fun formatInputs(inputs: String?): String =
    inputs?.let { " [$it]" } ?: ""

@PublishedApi
internal fun formatResultValue(value: Any?): String =
    when {
        value == null -> ""
        value === Unit -> ""
        else -> " result=${value.toString().truncateForLog(MAX_LOG_VALUE_LENGTH)}"
    }

@PublishedApi
internal fun String.truncateForLog(maxLength: Int): String =
    if (length <= maxLength) this else substring(0, maxLength) + "…"

/**
 * Logs a [Result] producing operation with lifecycle events (start, success, failure).
 */
inline fun <T> logOperation(
    category: LogCategory,
    action: String,
    inputs: String? = null,
    crossinline block: () -> Result<T>
): Result<T>
{
    val context = formatInputs(inputs)
    Logger.debug(category, "➡ $action$context")
    val result = block()
    result.fold(
        onSuccess = {
            Logger.info(category, "✅ $action$context${formatResultValue(it)}")
        },
        onFailure = {
            Logger.error(category, "❌ $action$context: ${it.message ?: it::class.simpleName}")
        }
    )
    return result
}

/**
 * Convenience helper that wraps a [block] in [runCatching] and logs through [logOperation].
 */
inline fun <T> logCatching(
    category: LogCategory,
    action: String,
    inputs: String? = null,
    crossinline block: () -> T
): Result<T>
    = logOperation(category, action, inputs) { runCatching { block() } }

/**
 * Suspended version of [logOperation] that can wrap suspend helpers.
 */
suspend inline fun <T> logOperationSuspend(
    category: LogCategory,
    action: String,
    inputs: String? = null,
    crossinline block: suspend () -> Result<T>
): Result<T>
{
    val context = formatInputs(inputs)
    Logger.debug(category, "➡ $action$context")
    val result = block()
    result.fold(
        onSuccess = {
            Logger.info(category, "✅ $action$context${formatResultValue(it)}")
        },
        onFailure = {
            Logger.error(category, "❌ $action$context: ${it.message ?: it::class.simpleName}")
        }
    )
    return result
}

/**
 * Suspended convenience helper similar to [logCatching].
 */
suspend inline fun <T> logCatchingSuspend(
    category: LogCategory,
    action: String,
    inputs: String? = null,
    crossinline block: suspend () -> T
): Result<T>
    = logOperationSuspend(category, action, inputs) { runCatching { block() } }