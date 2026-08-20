package matchmaking

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Authoritative server-extend-side registry of "sessionId -> dedicated server URL"
 * pairs that are about to be handed back to a client.
 *
 * One entry per [ServerConnector.resolveUrl] call. The entry is removed
 * by every listener wired in [ServerConnector] and
 * [org.ttt.autogenesis.serverextend.ServerExtend] that detects a staleness
 * signal. See `.hermes/plans/2026-06-28_090808-server-extend-url-handover-registry.md`
 * for the exhaustive list of staleness sources and the listener wiring.
 *
 * Thread-safety: backed by a [Mutex] and a private map. All mutating
 * operations ([put], [remove]) are atomic with respect to readers. The
 * map is intentionally kept package-private; it is not a public API
 * surface outside the `server-extend` module.
 *
 * Lifetime: in-memory only. A server-extend restart drops every
 * entry. This is the desired behaviour — no stale entries can survive
 * a restart. There is no persistence API by design; see
 * `UrlHandoverRegistryTest.the registry is in-memory only and has no
 * persistence hooks` for the regression guard.
 */
class UrlHandoverRegistry
{
    private val mutex = Mutex()
    private val entries: MutableMap<String, String> = mutableMapOf()

    suspend fun put(sessionId: String, url: String) = mutex.withLock {
        entries[sessionId] = url
    }

    suspend fun get(sessionId: String): String? = mutex.withLock {
        entries[sessionId]
    }

    suspend fun remove(sessionId: String) = mutex.withLock {
        entries.remove(sessionId)
    }

    /**
     * Returns a snapshot of every sessionId currently in the registry.
     * Used by the AccelByte session staleness poller in
     * [matchmaking.ServerConnector.pollAccelByteSessionsForStaleness]
     * to enumerate which sessionIds to re-validate against the
     * platform. Returns a defensive copy under the existing mutex;
     * callers can iterate without holding the registry lock.
     */
    suspend fun allKeys(): Set<String> = mutex.withLock {
        entries.keys.toSet()
    }
}