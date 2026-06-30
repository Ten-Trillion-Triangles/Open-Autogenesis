package accelbyte.dsm

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.jupiter.api.Tag
import java.io.File
import java.util.Properties
import java.util.UUID
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Phase 2 of feature/live-pvp-and-billing. Integration test that runs the
 * dedicated-server registration lifecycle against a real AccelByte
 * namespace. Tagged `@Tag("sandbox")` so `./gradlew :server:test` skips it.
 * Run with `./gradlew :server:testSandbox` after populating
 * `server/accelbyte.local.properties` (or env) with:
 *
 *  - AB_DS_ID
 *  - AB_NAMESPACE
 *  - AB_BASE_URL
 *  - AB_CLIENT_ID
 *  - AB_CLIENT_SECRET
 *  - AB_REGION
 *
 * The test:
 *  1. Builds a unique serverId (`ds-it-<8-char-uuid>`).
 *  2. Calls [DedicatedServerRegistration.start] with a fresh serverId.
 *  3. Advances a `TestScope` virtual clock by 90 s (3 heartbeats at 30 s).
 *  4. Asserts the heartbeat coroutine is active.
 *  5. Calls [DedicatedServerRegistration.drain] with `reason = "sandbox_it_drain"`.
 *  6. Advances another 5 s, asserts the heartbeat coroutine is no longer active.
 *  7. Teardown: [DedicatedServerRegistration.stop] (idempotent).
 *
 * The test does NOT mock the DSM client — it hits the real AccelByte
 * platform. Network or auth failures cause the test to fail with the SDK's
 * own error message, which is intentional: a broken DSM integration is
 * exactly the regression we want to catch.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@Tag("sandbox")
class DedicatedServerRegistrationSandboxTest
{
    private lateinit var props: SandboxProps
    private lateinit var serverId: String

    @Before
    fun setUp()
    {
        props = SandboxProps.load()
        assumeTrue(
            "DSMC sandbox test requires AB_DS_ID / AB_NAMESPACE / AB_BASE_URL / " +
                "AB_CLIENT_ID / AB_CLIENT_SECRET / AB_REGION in env or " +
                "server/accelbyte.local.properties. Skipping.",
            props.valid
        )
        serverId = "ds-it-${UUID.randomUUID().toString().take(8)}"
    }

    @Test
    fun registerHeartbeatDrainLifecycle() = runTest {
        // Use the production backoff sleep so registration retries work as
        // they would in production. The virtual clock below drives the
        // heartbeat, not the backoff (which is real-time in this lifecycle).
        DedicatedServerRegistration.start(
            serverId = serverId,
            region = props.region,
            namespace = props.namespace
        )

        val heartbeatJob = DedicatedServerRegistration.heartbeatJobForTest()
        assertNotNull(heartbeatJob, "heartbeat job should be active after start()")
        assertTrue(heartbeatJob.isActive, "heartbeat coroutine should be running after start()")

        // 90 s of virtual time = 3 heartbeats at the 30 s interval. The
        // TestScope clock advances in lockstep; the production heartbeat
        // uses real time, so the assertions are about the coroutine's
        // liveness, not about how many heartbeats actually fired.
        advanceTimeBy(90_000L)
        assertTrue(heartbeatJob.isActive, "heartbeat should still be running after 90s")

        // Drain: cancels the heartbeat + best-effort DSM shutdown.
        DedicatedServerRegistration.drain("sandbox_it_drain")

        // The drain call is synchronous; the cancellation is observable
        // immediately.
        advanceTimeBy(5_000L)
        val heartbeatAfterDrain = DedicatedServerRegistration.heartbeatJobForTest()
        assertFalse(
            heartbeatAfterDrain?.isActive ?: false,
            "heartbeat coroutine should be cancelled after drain()"
        )

        // Idempotent teardown.
        DedicatedServerRegistration.stop()
    }
}

/**
 * Reads the sandbox test config from `server/accelbyte.local.properties` first,
 * then env vars. Returns `valid = false` when any required key is missing.
 */
internal data class SandboxProps(
    val namespace: String,
    val baseUrl: String,
    val clientId: String,
    val clientSecret: String,
    val region: String,
    val dsId: String
)
{
    val valid: Boolean
        get() = namespace.isNotBlank() && baseUrl.isNotBlank() &&
            clientId.isNotBlank() && clientSecret.isNotBlank() &&
            region.isNotBlank() && dsId.isNotBlank()

    companion object
    {
        fun load(): SandboxProps
        {
            val file = File("accelbyte.local.properties")
            val fileProps = Properties().apply {
                if (file.exists())
                {
                    file.inputStream().use { load(it) }
                }
            }
            fun pick(key: String): String =
                System.getenv(key) ?: System.getProperty(key) ?: fileProps.getProperty(key).orEmpty()
            return SandboxProps(
                namespace = pick("AB_NAMESPACE"),
                baseUrl = pick("AB_BASE_URL"),
                clientId = pick("AB_CLIENT_ID"),
                clientSecret = pick("AB_CLIENT_SECRET"),
                region = pick("AB_REGION"),
                dsId = pick("AB_DS_ID")
            )
        }
    }
}
