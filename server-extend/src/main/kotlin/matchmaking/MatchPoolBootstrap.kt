package matchmaking

import net.accelbyte.sdk.api.match2.models.ApiMatchFunctionOverride
import net.accelbyte.sdk.api.match2.models.ApiMatchPool
import net.accelbyte.sdk.api.match2.operations.match_pools.CreateMatchPool
import net.accelbyte.sdk.api.match2.operations.match_pools.MatchPoolDetails
import net.accelbyte.sdk.api.match2.operations.match_pools.UpdateMatchPool
import net.accelbyte.sdk.api.match2.wrappers.MatchPools
import net.accelbyte.sdk.core.HttpResponseException
import org.ttt.autogenesis.logging.LogCategory
import org.ttt.autogenesis.logging.Logger
import org.ttt.autogenesis.serverextend.config.AccelByteConfig

/**
 * Idempotently reconciles the [MatchmakingLadder] pools against the AccelByte
 * match2 service. Called once from [org.ttt.autogenesis.serverextend.ServerExtend]'s
 * main entry point after [AccelByteConfig] is initialized.
 *
 * For each [MatchPoolSpec] the bootstrap either:
 *  - creates a new pool via [CreateMatchPool] when no row exists, or
 *  - calls [UpdateMatchPool] to align the row's `ticket_expiration_seconds`,
 *    `match_function`, `match_function_override`, and `session_template` with
 *    the current spec.
 *
 * Network or authorization errors are logged and swallowed: the operator may
 * need to create the pools manually in dev or in environments where the
 * service account does not have the `NAMESPACE:{namespace}:MATCHMAKING:POOL
 * [CREATE]` permission. The matchmaking flow still works against pools that
 * already exist.
 */
object MatchPoolBootstrap
{
    /**
     * The match function name registered with the match2 platform.
     *
     * MUST stay in lockstep with `matchFunctionName` declared in the root
     * `build.gradle.kts` (line 175). When the platform routes tickets into
     * the matchmaker gRPC service, it does so by `match_function` name —
     * the pool's `match_function` field and the registered function name
     * must be exactly equal, otherwise match2 cannot route tickets and
     * the pool produces zero matches.
     *
     * Block-2 fix: the legacy value `"custom"` was a placeholder that did
     * not match the registered name. The deployment-time value is
     * `"autogenesis-matchmaker"`. The Gradle `verifyExtendDeployment`
     * task asserts this wiring at deploy time; see `build.gradle.kts:553`.
     */
    private const val CUSTOM_MATCH_FUNCTION = "autogenesis-matchmaker"

    /**
     * Runs the bootstrap. Safe to invoke from a non-suspending context; the
     * underlying SDK calls are blocking. The ladder is sourced from
     * [ServerConnector.ladder] so tests can substitute it.
     */
    fun run()
    {
        val namespace = AccelByteConfig.getNamespace()
        if(namespace.isBlank())
        {
            Logger.warn(
                LogCategory.SYSTEM,
                "MatchPoolBootstrap: skipping — AB_NAMESPACE is blank"
            )
            return
        }

        val matchPools = MatchPools(ServerConnector.sdk)
        reconcileLadder(matchPools, namespace, ServerConnector.ladder)
    }

    /**
     * Idempotently reconciles every tier in [ladder] against the AccelByte
     * match2 service via [matchPools]. Package-internal so unit tests can
     * drive the bootstrap with a mocked wrapper and a custom ladder.
     *
     * Errors per tier are logged and swallowed: a transient SDK failure on
     * one pool must not abort the reconciliation of the rest of the ladder.
     */
    internal fun reconcileLadder(
        matchPools: MatchPools,
        namespace: String,
        ladder: MatchmakingLadder
    )
    {
        // Phase 7 plan case 1: blank namespace -> no SDK call. The production
        // run() also checks namespace but the check is duplicated here so the
        // helper is testable in isolation.
        if (namespace.isBlank())
        {
            Logger.warn(
                LogCategory.SYSTEM,
                "MatchPoolBootstrap.reconcileLadder: namespace is blank, skipping"
            )
            return
        }
        for(spec in ladder.tiers)
        {
            try
            {
                reconcile(matchPools, namespace, spec)
            }
            catch(err: Throwable)
            {
                Logger.warn(
                    LogCategory.SYSTEM,
                    "MatchPoolBootstrap: failed to reconcile pool '${spec.name}': ${err.message}"
                )
            }
        }
    }

    private fun reconcile(matchPools: MatchPools, namespace: String, spec: MatchPoolSpec)
    {
        val existing = fetchExisting(matchPools, namespace, spec.name)
        if(existing == null)
        {
            Logger.info(
                LogCategory.SYSTEM,
                "MatchPoolBootstrap: creating pool '${spec.name}' (maxPlayers=${spec.targetPlayers}, ticketExpiration=${spec.holdSeconds}s, sessionTemplate=${spec.sessionTemplateName})"
            )
            val body = ApiMatchPool.builder()
                .name(spec.name)
                .matchFunction(CUSTOM_MATCH_FUNCTION)
                .matchFunctionOverride(
                    ApiMatchFunctionOverride.builder()
                        .makeMatches(CUSTOM_MATCH_FUNCTION)
                        .build()
                )
                .ticketExpirationSeconds(spec.holdSeconds)
                .sessionTemplate(spec.sessionTemplateName)
                .build()
            val op = CreateMatchPool.builder()
                .namespace(namespace)
                .body(body)
                .build()
            matchPools.createMatchPool(op)
            Logger.info(LogCategory.SYSTEM, "MatchPoolBootstrap: pool '${spec.name}' created")
            return
        }

        // Already present: align the configurable fields. We never override
        // `name` (immutable on the platform side).
        val needsUpdate = existing.ticketExpirationSeconds != spec.holdSeconds
            || existing.sessionTemplate != spec.sessionTemplateName
            || existing.matchFunction != CUSTOM_MATCH_FUNCTION
        if(!needsUpdate)
        {
            Logger.debug(
                LogCategory.SYSTEM,
                "MatchPoolBootstrap: pool '${spec.name}' already aligned; skipping update"
            )
            return
        }

        Logger.info(
            LogCategory.SYSTEM,
            "MatchPoolBootstrap: updating pool '${spec.name}' (ticketExpiration=${existing.ticketExpirationSeconds}→${spec.holdSeconds}s, sessionTemplate=${existing.sessionTemplate}→${spec.sessionTemplateName})"
        )
        val config = net.accelbyte.sdk.api.match2.models.ApiMatchPoolConfig.builder()
            .matchFunction(CUSTOM_MATCH_FUNCTION)
            .matchFunctionOverride(
                ApiMatchFunctionOverride.builder()
                    .makeMatches(CUSTOM_MATCH_FUNCTION)
                    .build()
            )
            .ticketExpirationSeconds(spec.holdSeconds)
            .sessionTemplate(spec.sessionTemplateName)
            .build()
        val op = UpdateMatchPool.builder()
            .namespace(namespace)
            .pool(spec.name)
            .body(config)
            .build()
        matchPools.updateMatchPool(op)
        Logger.info(LogCategory.SYSTEM, "MatchPoolBootstrap: pool '${spec.name}' updated")
    }

    /**
     * Returns the existing pool row, or `null` when the platform responds with
     * 404 (the pool is not provisioned). Any other error is logged and treated
     * as "no existing pool" so the caller creates a new one.
     */
    private fun fetchExisting(matchPools: MatchPools, namespace: String, name: String): ApiMatchPool?
    {
        try
        {
            val op = MatchPoolDetails.builder()
                .namespace(namespace)
                .pool(name)
                .build()
            return matchPools.matchPoolDetails(op)
        }
        catch(err: HttpResponseException)
        {
            // 404 means the pool does not exist; signal "create" by returning
            // null. Any other HTTP error is re-thrown so the per-spec
            // try/catch in reconcileLadder can log+swallow without creating
            // a pool (per plan case 6).
            if(err.httpCode == 404) return null
            throw err
        }
        catch(err: Throwable)
        {
            // Network / SDK failure: re-throw so the per-spec try/catch in
            // reconcileLadder logs and continues to the next tier.
            throw err
        }
    }
}