package agent.runners

import agent.builders.safety.SafetyClassification
import com.TTT.Pipe.MultimodalContent
import com.TTT.Util.extractJson
import org.ttt.autogenesis.logging.LogCategory
import org.ttt.autogenesis.logging.Logger

/**
 * Routes the player-prompt safety verdict to the appropriate turn
 * handler. When the verdict is unsafe, logs LogCategory.AUTH and
 * routes to TurnHarness.handleAiTakeover — the player's prompt is
 * discarded, the AI generates an action for the player, and the
 * turn continues normally because the safety check failed.
 *
 * When the verdict is safe, returns normally so the calling site
 * continues the regular turn pipeline.
 */
object SafetyGateRouter
{
    /**
     * Classifies a player's free-text action through the safety pipe
     * and, if the verdict is unsafe, routes the turn to AI takeover.
     * Returns true if the caller should continue normal execution,
     * false if the turn was diverted to AI takeover.
     *
     * Suspend: [buildPlayerPromptSafetyAgent] returns an already-initialized
     * pipeline and pipeline.execute() is itself a suspend function, so we
     * compose inside the existing coroutine scope rather than blocking a
     * worker thread with runBlocking. classifyAndRoute is always called
     * from executePlayerTurn, which is itself suspend, so the call site
     * is already on a coroutine.
     */
    suspend fun classifyAndRoute(
        playerName: String,
        action: String,
        takeoverHandler: suspend (String) -> Unit
    ): Boolean
    {
        val verdict = classifyAction(playerName, action)
        if (!verdict.isSafe)
        {
            Logger.warn(
                LogCategory.AUTH,
                "Safety gate: BLOCKED action for $playerName — category: ${verdict.reason}. Routing to AI takeover."
            )
            takeoverHandler(playerName)
            return false
        }
        return true
    }

    private suspend fun classifyAction(
        playerName: String,
        action: String
    ): SafetyClassification
    {
        Logger.info(
            LogCategory.AUTH,
            "Safety gate: classifying player action for $playerName (length=${action.length})"
        )
        val pipeline = agent.builders.safety.buildPlayerPromptSafetyAgent()
        val rawResult = try
        {
            val result = pipeline.execute(MultimodalContent(action)).text
            // Same manner and style as the other gameplay agents — persist the
            // safety pipeline's trace under the current turn folder
            // (Round_<N>_Turn_<M>_<player>/SafetyGate/trace.{json,html}) so
            // investigators can audit what the safety gate actually saw on
            // every player turn, not just whether the verdict was safe.
            agent.runners.saveSystemTrace("SafetyGate", pipeline)
            result
        }
        catch (e: Exception)
        {
            Logger.warn(
                LogCategory.AUTH,
                "Safety gate: classification failed for $playerName — defaulting to safe. Error: ${e.message}"
            )
            return SafetyClassification(
                isSafe = true,
                reason = "classification-failed-default-safe"
            )
        }
        val parsed = extractJson<SafetyClassification>(rawResult)
        if (parsed == null)
        {
            Logger.warn(
                LogCategory.AUTH,
                "Safety gate: could not parse classification JSON for $playerName — defaulting to safe"
            )
            return SafetyClassification(
                isSafe = true,
                reason = "parse-failed-default-safe"
            )
        }
        Logger.info(
            LogCategory.AUTH,
            "Safety gate: verdict for $playerName — isSafe=${parsed.isSafe}, reason='${parsed.reason}'"
        )
        return parsed
    }
}