package accelbyte.backfill

import gameState.WorldManager
import kotlinx.coroutines.CoroutineScope
import org.ttt.autogenesis.logging.LogCategory
import org.ttt.autogenesis.logging.Logger

class BackfillHandler(
    private val worldManager: WorldManager,
    private val scope: CoroutineScope
) {
    fun handleBackfillRequest(request: BackfillTicketRequest): BackfillHandlerResult
    {
        if (worldManager.isDraining() || worldManager.isDrained())
        {
            Logger.debug(
                LogCategory.NETWORK,
                "BackfillHandler: Rejected ticket=${request.ticketId} user=${request.userId} — SERVER_DRAINING"
            )
            return BackfillHandlerResult(
                rejected = true,
                reason = "SERVER_DRAINING"
            )
        }

        val matchEnding = worldManager.isGameActive == false &&
            worldManager.activeSessionId.isNotEmpty()

        if (matchEnding)
        {
            Logger.debug(
                LogCategory.NETWORK,
                "BackfillHandler: Rejected ticket=${request.ticketId} user=${request.userId} — MATCH_ENDING"
            )
            return BackfillHandlerResult(
                rejected = true,
                reason = "MATCH_ENDING"
            )
        }

        val sessionData = try
        {
            org.ttt.autogenesis.server.config.RuleSet.data
        }
        catch (e: UninitializedPropertyAccessException)
        {
            Logger.warn(
                LogCategory.NETWORK,
                "BackfillHandler: RuleSet.data not initialized, assuming capacity available"
            )
            return BackfillHandlerResult(
                rejected = false,
                candidate = "accepted"
            )
        }

        val hasCapacity = sessionData.currentPlayers < sessionData.maxPlayers

        if (hasCapacity)
        {
            Logger.debug(
                LogCategory.NETWORK,
                "BackfillHandler: Accepted ticket=${request.ticketId} user=${request.userId} " +
                    "(currentPlayers=${sessionData.currentPlayers} maxPlayers=${sessionData.maxPlayers})"
            )
            return BackfillHandlerResult(
                rejected = false,
                candidate = "accepted"
            )
        }

        Logger.debug(
            LogCategory.NETWORK,
            "BackfillHandler: Rejected ticket=${request.ticketId} user=${request.userId} — SERVER_FULL " +
                "(currentPlayers=${sessionData.currentPlayers} maxPlayers=${sessionData.maxPlayers})"
        )
        return BackfillHandlerResult(
            rejected = true,
            reason = "SERVER_FULL"
        )
    }
}
