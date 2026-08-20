package matchmaking

import kotlinx.coroutines.runBlocking
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * TDD tests for [UrlHandoverRegistry] — the in-memory `sessionId -> url`
 * map that server-extend uses to record every URL handed back to a
 * client via `server.extend.resolveUrl`. Entries are removed by every
 * listener that detects a staleness signal (player vanish, AGS session
 * deletion, matchmaking timeout, etc.). See
 * `.hermes/plans/2026-06-28_090808-server-extend-url-handover-registry.md`
 * for the exhaustive list of staleness sources and listener wiring.
 *
 * The registry is intentionally in-memory only. There is no disk write
 * API — a server-extend restart drops every entry, which is the
 * desired behaviour (no stale entries can survive a restart).
 *
 * The registry methods are `suspend` because they take a Mutex;
 * each test body wraps in `runBlocking` for that reason (the
 * `RestPlayerConnectionManagerAwaitSessionTest` uses the same pattern
 * for the same reason).
 */
class UrlHandoverRegistryTest
{
    @Test
    fun `put then get returns the stored URL`() : Unit = runBlocking {
        val registry = UrlHandoverRegistry()
        registry.put("session-abc", "10.0.0.5:7777")
        assertEquals("10.0.0.5:7777", registry.get("session-abc"))
    }

    @Test
    fun `get returns null for an unknown sessionId`() : Unit = runBlocking {
        val registry = UrlHandoverRegistry()
        assertNull(registry.get("never-stored"))
    }

    @Test
    fun `remove evicts the entry and get returns null afterwards`() : Unit = runBlocking {
        val registry = UrlHandoverRegistry()
        registry.put("session-abc", "10.0.0.5:7777")
        registry.remove("session-abc")
        assertNull(registry.get("session-abc"))
    }

    @Test
    fun `remove on an unknown sessionId is a no-op and does not throw`() : Unit = runBlocking {
        val registry = UrlHandoverRegistry()
        registry.remove("never-stored") // must not throw
    }

    /**
     * The registry is intentionally in-memory only. A server-extend
     * restart (planned shutdown, OOM, pod kill) drops the entire
     * map. There is no disk write API by design — no stale entries
     * can survive a restart.
     *
     * This test pins that property via reflection: any method whose
     * name suggests persistence (save/load/persist/restore/serialize/
     * deserialize/writeTo/readFrom) on the registry's public surface
     * trips the assertion. A future refactor that accidentally adds
     * such an API would re-introduce the stale-after-restart problem
     * this plan explicitly avoids. The cost of a JVM restart
     * dropping the map is zero; the cost of persisting stale entries
     * is real user impact.
     */
    @Test
    fun `the registry is in-memory only and has no persistence hooks`() {
        val registry = UrlHandoverRegistry()
        val publicSurface = registry::class.java.methods.map { it.name }.toSet()
        val persistenceMarkers = setOf(
            "save", "load", "persist", "restore",
            "serialize", "deserialize",
            "writeTo", "readFrom"
        )
        val leaked = publicSurface.intersect(persistenceMarkers)
        assertEquals(emptySet(), leaked,
            "UrlHandoverRegistry must stay in-memory; found persistence method names: $leaked")
    }
}