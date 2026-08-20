package org.ttt.autogenesis.config

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import java.io.File

/**
 * JVM-side tests for ConfigSource.property().
 *
 * The global slot is `~/.autogenesis/config/<base>.local.properties` — i.e.
 * the file MUST live at `<user.home>/.autogenesis/config/...`. We override
 * `user.home` to a temp dir and create the file at the matching nested path.
 *
 * The module-local slot (`<cwd>/<base>.local.properties`) is hard to test
 * in isolation because the JVM process cwd is fixed at JVM startup; we
 * cover it indirectly by ensuring the resolution order checks it first.
 */
class ConfigSourceTest {
    private fun setupGlobalConfig(content: String): Pair<File, String> {
        val tmp = kotlin.io.path.createTempDirectory("configsource-test").toFile()
        val configDir = File(tmp, ".autogenesis/config").apply { mkdirs() }
        configDir.resolve("accelbyte.local.properties").writeText(content)
        val originalHome = System.getProperty("user.home")
        System.setProperty("user.home", tmp.absolutePath)
        return tmp to originalHome
    }

    private fun setupGlobalConfigOnly(filename: String, content: String): Pair<File, String> {
        val tmp = kotlin.io.path.createTempDirectory("configsource-test").toFile()
        val configDir = File(tmp, ".autogenesis/config").apply { mkdirs() }
        configDir.resolve(filename).writeText(content)
        val originalHome = System.getProperty("user.home")
        System.setProperty("user.home", tmp.absolutePath)
        return tmp to originalHome
    }

    private fun teardownGlobalConfig(tmp: File, originalHome: String?) {
        System.setProperty("user.home", originalHome)
        tmp.deleteRecursively()
    }

    @Test
    fun `reads property from global local-properties slot`() {
        val (tmp, originalHome) = setupGlobalConfig(
            """
            AB_NAMESPACE=test-ns
            AB_CLIENT_ID=test-id
            """.trimIndent()
        )
        try {
            val value = ConfigSource.property("accelbyte.properties", "AB_NAMESPACE")
            assertEquals("test-ns", value)
        } finally {
            teardownGlobalConfig(tmp, originalHome)
        }
    }

    @Test
    fun `falls back to global non-local properties when local is absent`() {
        val (tmp, originalHome) = setupGlobalConfigOnly(
            "accelbyte.properties",
            "AB_NAMESPACE=default-ns"
        )
        try {
            val value = ConfigSource.property("accelbyte.properties", "AB_NAMESPACE")
            assertEquals("default-ns", value)
        } finally {
            teardownGlobalConfig(tmp, originalHome)
        }
    }

    @Test
    fun `local-properties wins over non-local`() {
        val tmp = kotlin.io.path.createTempDirectory("configsource-test").toFile()
        val configDir = File(tmp, ".autogenesis/config").apply { mkdirs() }
        configDir.resolve("accelbyte.local.properties").writeText("AB_NAMESPACE=local-wins")
        configDir.resolve("accelbyte.properties").writeText("AB_NAMESPACE=default-loses")
        val originalHome = System.getProperty("user.home")
        System.setProperty("user.home", tmp.absolutePath)
        try {
            val value = ConfigSource.property("accelbyte.properties", "AB_NAMESPACE")
            assertEquals("local-wins", value)
        } finally {
            System.setProperty("user.home", originalHome)
            tmp.deleteRecursively()
        }
    }

    @Test
    fun `throws when file missing in any candidate location`() {
        val tmp = kotlin.io.path.createTempDirectory("configsource-test").toFile()
        val originalHome = System.getProperty("user.home")
        System.setProperty("user.home", tmp.absolutePath)
        try {
            assertFailsWith<IllegalStateException> {
                ConfigSource.property("missing.properties", "ANY_KEY")
            }
        } finally {
            System.setProperty("user.home", originalHome)
            tmp.deleteRecursively()
        }
    }

    @Test
    fun `throws when key missing`() {
        val (tmp, originalHome) = setupGlobalConfig("AB_NAMESPACE=test-ns")
        try {
            assertFailsWith<IllegalStateException> {
                ConfigSource.property("accelbyte.properties", "AB_CLIENT_ID")
            }
        } finally {
            teardownGlobalConfig(tmp, originalHome)
        }
    }
}
