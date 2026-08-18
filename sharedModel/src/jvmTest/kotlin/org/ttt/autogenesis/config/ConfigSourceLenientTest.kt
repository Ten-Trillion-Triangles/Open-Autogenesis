package org.ttt.autogenesis.config

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Tests for [ConfigSource.propertyOrEmpty] — the lenient variant added
 * alongside the strict [ConfigSource.property]. Pinned by the 2026-06-26
 * bigwang session: a lenient reader that silently swallows the wrong input
 * would mask missing-config bugs, so the contract is strict in the other
 * direction — empty string means "key absent OR blank", never a throw.
 */
class ConfigSourceLenientTest
{
    private fun setupGlobalConfigOnly(filename: String, content: String): Pair<File, String?>
    {
        val tmp = kotlin.io.path.createTempDirectory("configsource-lenient-test").toFile()
        val configDir = File(tmp, ".autogenesis/config").apply { mkdirs() }
        configDir.resolve(filename).writeText(content)
        val originalHome = System.getProperty("user.home")
        System.setProperty("user.home", tmp.absolutePath)
        // Pin the ConfigSource lookup root away from cwd so the
        // developer's checked-in `*.local.properties` files do not bleed
        // into the test's "key present" or "file absent" assertions.
        val originalRoot = System.getProperty("autogenesis.configSource.overrideRoot")
        System.setProperty("autogenesis.configSource.overrideRoot", tmp.absolutePath)
        return tmp to originalHome
    }

    private fun setupEmpty(): Pair<File, String?>
    {
        // Test-only escape hatch for "no file anywhere on disk" — pin the
        // lookup root to a fresh empty dir so the developer's checked-in
        // `*.local.properties` files do not satisfy the lookup.
        val tmp = kotlin.io.path.createTempDirectory("configsource-lenient-test").toFile()
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

    private fun teardownEmpty(tmp: File, originalRoot: String?)
    {
        if(originalRoot == null) System.clearProperty("autogenesis.configSource.overrideRoot")
        else System.setProperty("autogenesis.configSource.overrideRoot", originalRoot)
        tmp.deleteRecursively()
    }

    @Test
    fun `propertyOrEmpty returns the trimmed value when present`()
    {
        val (tmp, originalHome) = setupGlobalConfigOnly(
            "server.local.properties",
            "server.cors.allowedOrigins=https://www.tentrilliontriangles.com"
        )
        try
        {
            val value = ConfigSource.propertyOrEmpty("server.local.properties", "server.cors.allowedOrigins")
            assertEquals("https://www.tentrilliontriangles.com", value)
        }
        finally
        {
            teardownGlobalConfig(tmp, originalHome)
        }
    }

    @Test
    fun `propertyOrEmpty returns empty when file is absent`()
    {
        val (tmp, originalRoot) = setupEmpty()
        try
        {
            val value = ConfigSource.propertyOrEmpty("server.local.properties", "server.cors.allowedOrigins")
            assertEquals("", value)
        }
        finally
        {
            teardownEmpty(tmp, originalRoot)
        }
    }
    @Test
    fun `propertyOrEmpty returns empty when key is absent`()
    {
        val (tmp, originalHome) = setupGlobalConfigOnly(
            "server.local.properties",
            "server.rest.port=9080"
        )
        try
        {
            val value = ConfigSource.propertyOrEmpty("server.local.properties", "server.cors.allowedOrigins")
            assertEquals("", value)
        }
        finally
        {
            teardownGlobalConfig(tmp, originalHome)
        }
    }

    @Test
    fun `propertyOrEmpty returns empty when value is blank`()
    {
        val (tmp, originalHome) = setupGlobalConfigOnly(
            "server.local.properties",
            "server.cors.allowedOrigins=   "
        )
        try
        {
            val value = ConfigSource.propertyOrEmpty("server.local.properties", "server.cors.allowedOrigins")
            assertEquals("", value)
        }
        finally
        {
            teardownGlobalConfig(tmp, originalHome)
        }
    }

    @Test
    fun `propertyOrEmpty trims surrounding whitespace`()
    {
        val (tmp, originalHome) = setupGlobalConfigOnly(
            "server.local.properties",
            "server.cors.allowedOrigins=  https://www.tentrilliontriangles.com  "
        )
        try
        {
            val value = ConfigSource.propertyOrEmpty("server.local.properties", "server.cors.allowedOrigins")
            assertEquals("https://www.tentrilliontriangles.com", value)
        }
        finally
        {
            teardownGlobalConfig(tmp, originalHome)
        }
    }
}
