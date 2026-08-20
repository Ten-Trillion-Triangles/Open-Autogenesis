package org.ttt.autogenesis.kvisionapp

import globals.ServerExtendConfig
import kotlinx.coroutines.await
import org.ttt.autogenesis.logging.LogCategory
import org.ttt.autogenesis.logging.Logger
import kotlin.js.Promise

/**
 * Simple detector that uses basic HTTP fetch to check if local server is running.
 */
object LocalDevDetector {
    private val defaultBaseUrl: String
        get() = ServerExtendConfig.localServerUrl

    /**
     * Simple HTTP GET to /player endpoint to check if local server is alive.
     */
    suspend fun detectLocalDev(baseUrl: String = defaultBaseUrl): Boolean
    {
        Logger.info(LogCategory.NETWORK, "LocalDevDetector: checking $baseUrl/player")
        return try {
            val response = kotlinx.browser.window.fetch("$baseUrl/player").await()
            val success = response.ok
            Logger.info(LogCategory.NETWORK, "LocalDevDetector: result = $success (status: ${response.status})")
            success
        } catch (e: Exception) {
            Logger.info(LogCategory.NETWORK, "LocalDevDetector: failed - ${e.message}")
            false
        }
    }
}