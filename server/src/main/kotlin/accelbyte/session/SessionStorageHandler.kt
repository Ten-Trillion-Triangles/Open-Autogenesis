package accelbyte.session

import accelbyte.AccelByteSdkProvider
import accelbyte.session.toJsonElement
import accelbyte.session.toModel
import gameState.WorldManager
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import net.accelbyte.sdk.api.session.models.ApimodelsUpdateGameSessionRequest
import net.accelbyte.sdk.api.session.operations.game_session.UpdateGameSession
import org.ttt.autogenesis.logging.LogCategory
import org.ttt.autogenesis.logging.Logger
import serverStructs.PlayerStats

/**
 * Data class representing the game state to be stored in the session service.
 *
 * @property matchOutcome The result of the match (e.g., "victory", "defeat", "draw")
 * @property finalScores Map of player names to their final scores
 * @property playerStats Summary statistics for each player at match end
 * @property endTimestamp ISO-8601 timestamp of when the match ended
 */
@Serializable
data class GameState(
    val matchOutcome: String,
    val finalScores: Map<String, Int>,
    val playerStats: List<PlayerStatSummary>,
    val endTimestamp: String
)

/**
 * Summary of a player's statistics at the end of a match.
 *
 * @property playerName The player's display name
 * @property territoriesHeld Number of territories controlled at match end
 * @property militaryPoints Final military points
 * @property diplomacyPoints Final diplomacy points
 * @property researchPoints Final research points
 * @property isControlledByNpc Whether this player was AI-controlled
 */
@Serializable
data class PlayerStatSummary(
    val playerName: String,
    val territoriesHeld: Int,
    val militaryPoints: Int,
    val diplomacyPoints: Int,
    val researchPoints: Int,
    val isControlledByNpc: Boolean
)

/**
 * Handler for writing session storage data on match end.
 *
 * This handler serializes the game state as JSON and writes it to the AccelByte
 * session service. The write operation is non-blocking and uses exponential backoff
 * retry logic to handle transient failures.
 *
 * @note Write failures do NOT block match end - the operation is fire-and-forget.
 */
object SessionStorageHandler
{
    private const val MAX_RETRIES = 3
    private const val BASE_DELAY_MS = 1000L

    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
    }

    /**
     * Writes the game state to the session service storage.
     *
     * This operation is asynchronous and will NOT block match end processing.
     * On failure, retries up to [MAX_RETRIES] times with exponential backoff.
     *
     * @param sessionId The session ID to write storage for
     * @param gameState The game state data to serialize and store
     * @return [Result.success] if the write was initiated successfully, [Result.failure] otherwise
     */
    fun writeSessionStorage(sessionId: String, gameState: GameState): Result<Unit>
    {
        return runCatching {
            val jsonData = json.encodeToString(GameState.serializer(), gameState)
            Logger.debug(
                LogCategory.DATABASE,
                "SessionStorageHandler.writeSessionStorage: Serialized game state for session=$sessionId (${jsonData.length} bytes)"
            )

// Fire-and-forget so it doesn't block match end
        kotlinx.coroutines.GlobalScope.launch {
                writeWithRetry(sessionId, jsonData)
            }
        }
    }

    /**
     * Writes the game state with retry logic using exponential backoff.
     *
     * @param sessionId The session ID
     * @param jsonData The JSON-serialized game state
     */
    private suspend fun writeWithRetry(sessionId: String, jsonData: String)
    {
        var lastException: Exception? = null

        for (attempt in 1..MAX_RETRIES)
        {
            try
            {
                writeSessionStorageSync(sessionId, jsonData)
                Logger.info(
                    LogCategory.DATABASE,
                    "SessionStorageHandler: Successfully wrote game state to session=$sessionId on attempt $attempt"
                )
                return
            }
            catch (e: Exception)
            {
                lastException = e
                Logger.warn(
                    LogCategory.DATABASE,
                    "SessionStorageHandler: Write attempt $attempt/$MAX_RETRIES failed for session=$sessionId: ${e.message}"
                )

                if (attempt < MAX_RETRIES)
                {
                    val delayMs = BASE_DELAY_MS * (1 shl (attempt - 1)) // exponential backoff: 1s, 2s, 4s
                    Logger.debug(
                        LogCategory.DATABASE,
                        "SessionStorageHandler: Retrying in ${delayMs}ms..."
                    )
                    delay(delayMs)
                }
            }
        }

        // All retries exhausted
        Logger.error(
            LogCategory.DATABASE,
            "SessionStorageHandler: All $MAX_RETRIES attempts failed for session=$sessionId. Last error: ${lastException?.message}"
        )
    }

    /**
     * Synchronously writes the game state to the session service.
     *
     * @param sessionId The session ID
     * @param jsonData The JSON-serialized game state
     * @throws Exception if the write operation fails
     */
    private fun writeSessionStorageSync(sessionId: String, jsonData: String)
    {
        val sdk = AccelByteSdkProvider.sdk
        val namespace = AccelByteSdkProvider.namespace

        val storageJson = Json.parseToJsonElement(jsonData)
        val body = mapOf("storage" to storageJson).toJsonElement().toModel<ApimodelsUpdateGameSessionRequest>()

        val op = UpdateGameSession.builder()
            .namespace(namespace)
            .sessionId(sessionId)
            .body(body)
            .build()

        val wrapper = net.accelbyte.sdk.api.session.wrappers.GameSession(sdk)
        wrapper.updateGameSession(op)
    }

    /**
     * Reads the game state from the session service storage.
     *
     * This operation is used for crash recovery to reconstruct game state after server restart.
     * On failure, returns [Result.failure] so crash recovery does NOT proceed with empty state.
     *
     * @param partyId The session/party ID to read storage for
     * @return [Result.success] with [GameState] if storage exists, [Result.success] with null if no storage,
     *         or [Result.failure] if the read operation itself failed
     */
    fun readSessionStorage(partyId: String): Result<GameState?>
    {
        return runCatching {
            val sessionResult = Session.getSession(partyId)
            val session = sessionResult.getOrElse { e ->
                Logger.error(
                    LogCategory.DATABASE,
                    "SessionStorageHandler.readSessionStorage: Failed to get session for partyId=$partyId: ${e.message}"
                )
                throw e
            }

            val storageJson = session.storage
            if (storageJson == null) {
                Logger.debug(
                    LogCategory.DATABASE,
                    "SessionStorageHandler.readSessionStorage: No storage found for partyId=$partyId (new session)"
                )
                return@runCatching null
            }

            val jsonString = storageJson.toString()
            Logger.debug(
                LogCategory.DATABASE,
                "SessionStorageHandler.readSessionStorage: Retrieved storage for partyId=$partyId (${jsonString.length} bytes)"
            )

            json.decodeFromString(GameState.serializer(), jsonString)
        }
    }

    /**
     * Builds a [GameState] from the current [WorldManager] state.
     *
     * @param matchOutcome The outcome string (e.g., "victory", "defeat")
     * @param winnerName The name of the winning player (optional)
     * @return A [GameState] ready to be written to session storage
     */
    fun buildGameState(matchOutcome: String, winnerName: String?): GameState
    {
        val finalScores = mutableMapOf<String, Int>()
        val playerSummaries = mutableListOf<PlayerStatSummary>()

        val world = WorldManager.world

        // Calculate territories held per player
        val territoryCountByOwner = mutableMapOf<String, Int>()
        world.mapTiles.forEach { tile ->
            if (!tile.isDestroyed && tile.ruler.isNotBlank())
            {
                territoryCountByOwner[tile.ruler] = (territoryCountByOwner[tile.ruler] ?: 0) + 1
            }
        }

        // Build player stats
        world.activePlayers.forEach { player ->
            finalScores[player.name] = territoryCountByOwner[player.name] ?: 0

            val stats = WorldManager.findPlayerFromStats(player.name)
            val isNpc = stats?.isControlledByNpc ?: false

            playerSummaries.add(
                PlayerStatSummary(
                    playerName = player.name,
                    territoriesHeld = territoryCountByOwner[player.name] ?: 0,
                    militaryPoints = player.militaryPoints,
                    diplomacyPoints = player.diplomacyPoints,
                    researchPoints = player.researchPoints,
                    isControlledByNpc = isNpc
                )
            )
        }

        val timestamp = java.time.Instant.now().toString()

        return GameState(
            matchOutcome = matchOutcome,
            finalScores = finalScores.toMap(),
            playerStats = playerSummaries,
            endTimestamp = timestamp
        )
    }
}
