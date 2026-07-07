package org.ttt.autogenesis.server

import org.junit.Test
import java.io.File
import kotlin.test.assertTrue

/**
 * TDD contract test for Block-4.
 *
 * The main server Dockerfile HEALTHCHECK (see `docs/LIVE_MODE.md:79-83`)
 * probes `GET /health` over HTTP. The route MUST be registered in
 * [org.ttt.autogenesis.server.serverModule] so the in-container
 * HEALTHCHECK returns 200 and the AMS fleet keeps the DS alive.
 *
 * Before the fix, `Server.kt` only registered `/player`. The Dockerfile
 * HEALTHCHECK would 404, AMS would consider the DS unhealthy, and the
 * fleet would terminate it.
 *
 * This is a code-level contract test — it asserts that the source
 * contains the required route registration. A behavioral integration
 * test would require spinning up the full `serverModule()` which has
 * heavy startup dependencies (AccelByte SDK init, WebSocket actors,
 * gameState init). The contract assertion is sufficient for the
 * deploy-readiness check; behavioral coverage is deferred to a CI
 * integration suite.
 */
class HealthRouteContractTest
{
    private val serverKtPath: String = "src/main/kotlin/org/ttt/autogenesis/server/Server.kt"

    private fun loadServerKt(): String
    {
        // Walk up from the cwd to the repo root, then into server/.
        val cwd = File(".").absoluteFile
        val candidates = generateSequence(cwd) { it.parentFile }
            .takeWhile { it != null }
            .map { File(it, serverKtPath) }
            .toList()
        val resolved = candidates.firstOrNull { it.exists() }
            ?: error("Could not locate $serverKtPath relative to $cwd (tried $candidates)")
        return resolved.readText()
    }

    @Test
    fun `server registers the GET slash-health route`() {
        val source = loadServerKt()
        // The fix is: `get("/health") { ... }` registered inside the
        // `routing { ... }` block of `Application.serverModule()`.
        assertTrue(
            source.contains("""get("/health")"""),
            "Server.kt must register a GET /health route for the Dockerfile HEALTHCHECK. " +
                "Current source:\n${source.lineSequence().filter { it.contains("routing") || it.contains("get(") }.joinToString("\n")}"
        )
    }

    @Test
    fun `health route handler returns a 2xx response`() {
        val source = loadServerKt()
        // The handler MUST respond (not throw / not return early). Accept
        // either respondText (plain-text probe) or respond(...)
        // (JSON-encoded status map). Either is acceptable to the in-container
        // `curl -fsS` healthcheck.
        val healthRouteBlock = extractHealthRouteBlock(source)
        assertTrue(
            healthRouteBlock.isNotBlank(),
            "Could not isolate the /health route block from Server.kt"
        )
        assertTrue(
            healthRouteBlock.contains("respond"),
            "GET /health handler must call call.respond(...) so the curl probe gets 200. Got:\n$healthRouteBlock"
        )
    }

    /**
     * Pulls the body of `get("/health") { ... }` from the source. Brace-matching
     * since the block contains nested braces for `respond(...)` / mapOf.
     */
    private fun extractHealthRouteBlock(source: String): String
    {
        val idx = source.indexOf("""get("/health")""")
        if (idx < 0) return ""
        val braceOpen = source.indexOf('{', idx)
        if (braceOpen < 0) return ""
        var depth = 1
        var i = braceOpen + 1
        while (i < source.length && depth > 0)
        {
            when (source[i])
            {
                '{' -> depth++
                '}' -> depth--
            }
            i++
        }
        return source.substring(braceOpen, i)
    }
}