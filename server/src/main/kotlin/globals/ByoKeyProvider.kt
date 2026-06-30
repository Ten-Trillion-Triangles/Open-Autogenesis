package globals

/**
 * Per-player BYO key lookup seam. The game server's [BedrockCredentialResolver]
 * consults this provider to decide whether the active actor has a usable
 * BYO key on file and, if so, what the decrypted material looks like.
 *
 * The provider is a small one-method interface so the server module does not
 * need a hard dependency on `server-extend` (which owns the encrypted BYO
 * store). The default implementation returns `null` for every user, which
 * keeps the BYO path a no-op until server-extend installs the real provider
 * at startup.
 *
 * Wiring (in `server-extend`, in production): the BYO RPC layer is the
 * natural installation point — after [org.ttt.autogenesis.serverextend.ServerExtend]
 * has booted, it can set [BedrockCredentialResolver.byoKeyProvider] to a
 * lambda that calls into [account.ByoCredentialStore.getDecrypted]. The
 * server module's [org.ttt.autogenesis.server.TurnHarness] then uses the
 * resolver exactly as the plan specifies, without ever importing the
 * `account` package.
 *
 * Phase 6 of feature/live-pvp-and-billing.
 */
fun interface ByoKeyProvider
{
    /**
     * Returns the decrypted BYO key material for [userId], or `null` when
     * the player does not have a usable BYO slot on file.
     *
     * The implementation must never throw. A failed decrypt, missing slot,
     * or transient VFS error all map to `null` so the resolver can fall
     * back to platform credentials without crashing the turn.
     */
    suspend fun getDecrypted(userId: String): ByoKeyMaterial?
}

/**
 * Plain-old-data snapshot of the BYO credentials the resolver needs to swap
 * `bedrockEnv` over to the player's provider. Carries only the fields the
 * SDK actually consumes: access key id, secret, and the pinned region.
 */
data class ByoKeyMaterial(
    val accessKeyId: String,
    val secretAccessKey: String,
    val region: String
)
