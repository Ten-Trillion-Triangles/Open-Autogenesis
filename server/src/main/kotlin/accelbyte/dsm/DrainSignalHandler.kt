package accelbyte.dsm

import gameState.WorldManager
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.ttt.autogenesis.logging.LogCategory
import org.ttt.autogenesis.logging.Logger

class DrainSignalHandler(
    private val worldManager: WorldManager,
    private val dsm: DSM
) {
    companion object {
        private const val DRAIN_TIMEOUT_MS = 60_000L
        private const val DRAIN_POLL_INTERVAL_MS = 1_000L
    }

    suspend fun handleDrainSignal() {
        worldManager.setDraining()
        Logger.info(LogCategory.SYSTEM, "DrainSignalHandler: Drain signal received, entering DRAINING state")

        val startTime = System.currentTimeMillis()
        while (worldManager.activeSessionCount > 0) {
            if (System.currentTimeMillis() - startTime > DRAIN_TIMEOUT_MS) {
                Logger.warn(
                    LogCategory.SYSTEM,
                    "DrainSignalHandler: Timeout waiting for sessions to drain " +
                        "(activeSessionCount=${worldManager.activeSessionCount}), proceeding to shutdown"
                )
                break
            }
            delay(DRAIN_POLL_INTERVAL_MS)
        }

        Logger.info(LogCategory.SYSTEM, "DrainSignalHandler: Calling DSM shutdown")
        val shutdownPayload = buildJsonObject {
            put("serverId", JsonPrimitive(""))
            put("reason", JsonPrimitive("drain_signal_received"))
        }

        val shutdownResult = dsm.shutdownDedicatedServer(shutdownPayload)
        if (shutdownResult.isFailure) {
            Logger.error(
                LogCategory.SYSTEM,
                "DrainSignalHandler: DSM shutdown failed: ${shutdownResult.exceptionOrNull()?.message}"
            )
        } else {
            Logger.info(LogCategory.SYSTEM, "DrainSignalHandler: DSM shutdown succeeded")
        }

        worldManager.setDrained()
        Logger.info(LogCategory.SYSTEM, "DrainSignalHandler: Drain complete, server is DRAINED")
    }
}
