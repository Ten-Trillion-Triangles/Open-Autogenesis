package globals

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Verifies the server-extend port resolution helpers keep local development off the main server port.
 */
class ExtendConfigTest
{
    /**
     * Confirms the default gRPC listener port avoids the main server's 9091 listener.
     */
    @Test
    fun `default grpc port uses the local development fallback`()
    {
        assertEquals(9092, ExtendConfig.resolveGrpcPort(null, null))
    }

    /**
     * Confirms JVM properties override the default gRPC listener port.
     */
    @Test
    fun `system property overrides grpc port`()
    {
        assertEquals(7777, ExtendConfig.resolveGrpcPort(propertyValue = "7777", envValue = null))
    }

    /**
     * Confirms the environment variable override is honored when no JVM property is present.
     */
    @Test
    fun `environment variable overrides grpc port`()
    {
        assertEquals(8888, ExtendConfig.resolveGrpcPort(propertyValue = null, envValue = "8888"))
    }
}

/**
 * Verifies the SERVER_EXTEND_LIVE_MODE env-var / system-property resolver.
 *
 * The resolver is the only thing standing between the operator and the
 * match2 + AMS live path: before this existed, [ExtendConfig.debugMode]
 * was a hardcoded `true` literal, which made the live branch unreachable
 * without a rebuild. These tests pin the precedence rules so a future
 * refactor doesn't silently re-introduce the hardcoded default.
 */
class ExtendConfigLiveModeTest
{
    /**
     * No override → default is `false` (dev mode). The local fast-path stays
     * the default behavior; live mode is opt-in.
     */
    @Test
    fun `default live mode is false (dev mode)`()
    {
        assertEquals(false, ExtendConfig.resolveLiveMode(propertyValue = null, envValue = null))
    }

    /**
     * JVM property takes precedence over the env var.
     */
    @Test
    fun `system property wins over env var`()
    {
        assertEquals(
            true,
            ExtendConfig.resolveLiveMode(propertyValue = "true", envValue = "false")
        )
    }

    /**
     * Env var is honored when no JVM property is set.
     */
    @Test
    fun `environment variable enables live mode`()
    {
        assertEquals(true, ExtendConfig.resolveLiveMode(propertyValue = null, envValue = "true"))
    }

    /**
     * Common truthy spellings all flip the flag, regardless of case.
     * Operators in different shells / CI systems write booleans differently;
     * the resolver should not be picky.
     */
    @Test
    fun `truthy spellings are accepted (true, 1, yes, on, mixed case)`()
    {
        assertEquals(true, ExtendConfig.resolveLiveMode(propertyValue = "1", envValue = null))
        assertEquals(true, ExtendConfig.resolveLiveMode(propertyValue = null, envValue = "YES"))
        assertEquals(true, ExtendConfig.resolveLiveMode(propertyValue = "On", envValue = null))
        assertEquals(true, ExtendConfig.resolveLiveMode(propertyValue = null, envValue = "  true  "))
    }

    /**
     * Anything that is not a recognized truthy value is `false` — including
     * blank strings, garbage, and the literal `"false"`.
     */
    @Test
    fun `unrecognized values fall back to false`()
    {
        assertEquals(false, ExtendConfig.resolveLiveMode(propertyValue = "", envValue = null))
        assertEquals(false, ExtendConfig.resolveLiveMode(propertyValue = null, envValue = "false"))
        assertEquals(false, ExtendConfig.resolveLiveMode(propertyValue = "garbage", envValue = "0"))
    }

    /**
     * `ExtendConfig.debugMode` is the inverse of `liveMode` — the local
     * server's "dev fast-path" is `debugMode = true`. This test guards the
     * invariant against future refactors.
     */
    @Test
    fun `debugMode is the inverse of live mode`()
    {
        val liveMode = ExtendConfig.resolveLiveMode(propertyValue = null, envValue = null)
        assertEquals(!liveMode, ExtendConfig.debugMode)
    }
}
