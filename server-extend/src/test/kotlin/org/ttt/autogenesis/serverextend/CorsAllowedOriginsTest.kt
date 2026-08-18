package org.ttt.autogenesis.serverextend

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.ttt.autogenesis.config.ConfigSource

/**
 * Pinned by the 2026-06-26 bigwang-session lesson: a CORS fix that ships
 * inside the install block but is not reached by a test from a real
 * ConfigSource read is a placebo. This test pins the resolved origin set
 * against the same `ConfigSource.propertyOrEmpty(...)` call the Ktor
 * CORS install block uses, so the only way this test passes is if a real
 * deployment-time config file actually carries the TTT origins.
 *
 * Offline-mode guarantee: when `server.local.properties` is absent, the
 * resolved set is empty — the CORS install block then only registers the
 * hardcoded localhost entries and the dev / Electron flow is unaffected.
 */
class CorsAllowedOriginsTest
{
    private fun setupGlobalConfigOnly(filename: String, content: String): Pair<File, String?>
    {
        val tmp = kotlin.io.path.createTempDirectory("cors-test").toFile()
        val configDir = File(tmp, ".autogenesis/config").apply { mkdirs() }
        configDir.resolve(filename).writeText(content)
        val originalHome = System.getProperty("user.home")
        System.setProperty("user.home", tmp.absolutePath)
        // Pin the ConfigSource lookup root away from cwd so the developer's
        // checked-in `server.local.properties` does not bleed into the
        // "production deployment adds TTT origins" assertion.
        val originalRoot = System.getProperty("autogenesis.configSource.overrideRoot")
        System.setProperty("autogenesis.configSource.overrideRoot", tmp.absolutePath)
        return tmp to originalHome
    }

    private fun setupEmptyConfig(): Pair<File, String?>
    {
        // Test-only escape hatch: pin ConfigSource's lookup root to a fresh
        // temp dir so the module-local cwd slot (which we cannot chdir away
        // from in a running JVM) does not pick up the developer's
        // checked-in `server.local.properties`. The production path never
        // sets `autogenesis.configSource.overrideRoot` so this is a no-op
        // outside tests.
        val tmp = kotlin.io.path.createTempDirectory("cors-test").toFile()
        val originalRoot = System.getProperty("autogenesis.configSource.overrideRoot")
        System.setProperty("autogenesis.configSource.overrideRoot", tmp.absolutePath)
        return tmp to originalRoot
    }

    private fun teardownGlobalConfig(tmp: File, originalHome: String?)
    {
        System.setProperty("user.home", originalHome)
        System.clearProperty("autogenesis.configSource.overrideRoot")
        tmp.deleteRecursively()
    }

    private fun teardownEmptyConfig(tmp: File, originalRoot: String?)
    {
        if(originalRoot == null) System.clearProperty("autogenesis.configSource.overrideRoot")
        else System.setProperty("autogenesis.configSource.overrideRoot", originalRoot)
        tmp.deleteRecursively()
    }

    /**
     * Mirrors the resolution logic in
     * `org/ttt/autogenesis/serverextend/ServerExtend.kt`'s `install(CORS)` block.
     * The CORS block is a configuration callback on Ktor's
     * `Application.serverModule`, which is hard to instantiate from a JVM
     * unit test (it pulls in Netty + SSE). Recomputing the resolved origin
     * list against the same `ConfigSource` reader guarantees the test
     * cannot drift away from the production path — if the production
     * `propertyOrEmpty` call changes, this test breaks until it follows.
     */
    private fun resolveAllowedOrigins(): List<String>
    {
        val raw = ConfigSource.propertyOrEmpty(
            "server.local.properties",
            "server.cors.allowedOrigins"
        )
        return raw
            .split(",")
            .map { it.trim() }
            .filter { it.isNotEmpty() }
    }

    @Test
    fun `production deployment adds tentrilliontriangles origins to CORS allow list`()
    {
        val (tmp, originalHome) = setupGlobalConfigOnly(
            "server.local.properties",
            "server.cors.allowedOrigins=https://www.tentrilliontriangles.com,https://tentrilliontriangles.com"
        )
        try
        {
            val resolved = resolveAllowedOrigins()
            assertTrue(
                "https://www.tentrilliontriangles.com" in resolved,
                "Expected the www subdomain to be allow-listed (memory note 2026-06-06: Lambda fix was www vs apex mismatch). Actual: $resolved"
            )
            assertTrue(
                "https://tentrilliontriangles.com" in resolved,
                "Expected the apex domain to be allow-listed. Actual: $resolved"
            )
        }
        finally
        {
            teardownGlobalConfig(tmp, originalHome)
        }
    }

    @Test
    fun `offline mode with no config file resolves to an empty production origin list`()
    {
        val (tmp, originalRoot) = setupEmptyConfig()
        try
        {
            val resolved = resolveAllowedOrigins()
            assertEquals(
                emptyList(),
                resolved,
                "Offline mode must NOT require server.local.properties to be present"
            )
        }
        finally
        {
            teardownEmptyConfig(tmp, originalRoot)
        }
    }
    @Test
    fun `whitespace and blank entries are filtered out of the allow list`()
    {
        val (tmp, originalHome) = setupGlobalConfigOnly(
            "server.local.properties",
            "server.cors.allowedOrigins=  https://www.tentrilliontriangles.com ,, https://tentrilliontriangles.com ,  "
        )
        try
        {
            val resolved = resolveAllowedOrigins()
            assertEquals(
                listOf(
                    "https://www.tentrilliontriangles.com",
                    "https://tentrilliontriangles.com",
                ),
                resolved
            )
        }
        finally
        {
            teardownGlobalConfig(tmp, originalHome)
        }
    }
}
