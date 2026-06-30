package accelbyte.session

/**

 * In-process event emitted when the DS Hub fires a MatchmakingV2ServerClaimed notification.
 * Carries the session binding information needed to gate player connections.
 */
data class ServerClaimedEvent(
    val sessionId: String,
    val gameMode: String,
    val matchingAllies: List<String>
)