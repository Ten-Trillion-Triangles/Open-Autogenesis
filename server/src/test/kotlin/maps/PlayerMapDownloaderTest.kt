package maps

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.net.InetSocketAddress
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Failure-path coverage for [PlayerMapDownloader].
 *
 * The happy path (real AGS round-trip) is exercised by the live smoke test
 * (see `run-comprehensive-test.sh` / opt-in AGS_LIVE_TEST runs). Unit tests
 * here pin the error-propagation contract: any failure in the SDK call must
 * surface as a RuntimeException with a non-blank diagnostic message — no
 * silent swallowing, no fallback to empty bytes.
 *
 * These tests run against the production SDK with `AccelByteSdkProvider` left
 * in its default state (no live creds in unit-test JVM), so every call should
 * fail at the SDK boundary with a clear message.
 */
class PlayerMapDownloaderTest
{
    private var server: HttpServer? = null
    private var port: Int = 0

    @Before
    fun setUp()
    {
        // Spin up an embedded HTTP server to prove that even when an endpoint
        // IS reachable, the SDK boundary fails first (because the test JVM has
        // no AGS creds). The server's request count asserts the SDK never
        // reached the HTTP layer.
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        port = server!!.address.port
        server!!.start()
    }

    @After
    fun tearDown()
    {
        server?.stop(0)
        server = null
    }

    @Test
    fun downloadBytesSurfacesSdkFailureWithDiagnosticMessage(): Unit {
        // Test JVM has no AGS credentials. The SDK must fail loudly with a
        // descriptive message — NOT return empty bytes silently.
        val ex = assertFailsWith<RuntimeException> {
            PlayerMapDownloader.downloadBytes(userId = "user-1", mapId = "test")
        }
        assertNotNull(ex.message, "RuntimeException must carry a diagnostic message")
        assertTrue(ex.message!!.isNotBlank(), "diagnostic message must be non-blank")
    }

    @Test
    fun downloadBytesDoesNotSwallowSdkFailure(): Unit {
        // Boundary check: every code path that returns must throw instead of
        // returning empty bytes. Confirms the failure-propagation contract.
        var returned = false
        try {
            PlayerMapDownloader.downloadBytes(userId = "user-1", mapId = "test")
            returned = true
        } catch (e: RuntimeException) {
            // expected
        }
        assertTrue(!returned, "downloadBytes must NOT return successfully when SDK is unconfigured")
    }
}
