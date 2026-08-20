package globals

import env.bedrockEnv
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkAll
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Phase 6 of feature/live-pvp-and-billing. Verifies the [BedrockCredentialResolver]
 * via the [ByoKeyProvider] seam (default provider returns null; tests install
 * a custom provider).
 *
 *  - BYO flag set + slot present   -> swaps to BYO keys, byoKey = true
 *  - BYO flag set + slot missing   -> platform keys (matches the costClassFor PRO downgrade)
 *  - BYO flag not set              -> platform keys
 *  - restore swaps back            -> next turn sees the right keys
 *  - decrypt error                 -> platform keys, logs WARN
 */
class BedrockCredentialResolverTest
{
    private lateinit var platformKeys: Pair<String, String>
    private var setKeysCalls: MutableList<Pair<String, String>> = mutableListOf()
    private var installedProvider: ByoKeyProvider = ByoKeyProvider { null }

    @Before
    fun setUp()
    {
        platformKeys = "AKIA_PLATFORM_KEY" to "platform-secret"
        setKeysCalls = mutableListOf()

        // Mark the AWS credentials bootstrap as initialized so the
        // resolver's defensive check proceeds to the BYO path. We do not
        // call AwsCredentialsBootstrap.initialize() in tests because that
        // would try to load real AWS credentials; setting the private
        // `initialized` field via reflection is sufficient for the
        // resolver's contract.
        try
        {
            val field = AwsCredentialsBootstrap::class.java.getDeclaredField("initialized")
            field.isAccessible = true
            field.setBoolean(AwsCredentialsBootstrap, true)
        }
        catch (err: Throwable) {
            // If reflection fails, the test will see the resolver fall back
            // to platform creds and fail the assertion; this is the safe
            // default we want to preserve in production.
        }

        // Mock the bedrockEnv singleton so we can observe setKeys calls without
        // requiring a real AWS credentials bootstrap.
        mockkObject(bedrockEnv)
        every { bedrockEnv.setKeys(any(), any()) } answers {
            setKeysCalls.add(firstArg<String>() to secondArg<String>())
            Unit
        }
        every { bedrockEnv.getKeys() } returns platformKeys
    }

    @After
    fun tearDown()
    {
        BedrockCredentialResolver.installByoKeyProvider(installedProvider)
        // Clear the initialized flag so it doesn't leak into the next test.
        try
        {
            val field = AwsCredentialsBootstrap::class.java.getDeclaredField("initialized")
            field.isAccessible = true
            field.setBoolean(AwsCredentialsBootstrap, false)
        }
        catch (_: Throwable) { }
        unmockkAll()
    }

    @Test
    fun byoFlagSetAndSlotPresentSwapsToByoKeys() = runBlocking {
        val keyId = "AKIAIOSFODNN7EXAMPLE"
        installedProvider = BedrockCredentialResolver.installByoKeyProvider(ByoKeyProvider { _ ->
            ByoKeyMaterial(
                accessKeyId = keyId,
                secretAccessKey = "byo-secret-12345",
                region = "us-east-1"
            )
        })
        // The resolver consults BillingSync.getAccountSettings which uses the
        // global gRPC bridge. Route through a mock that returns the BYO flag.
        mockkObject(accounting.BillingSync, recordPrivateCalls = true)
        io.mockk.coEvery { accounting.BillingSync.getAccountSettings(any(), any()) } returns structs.account.AccountSettings(
            accelByteUserId = "user-1",
            bringYourOwnApiKey = true
        )

        val snapshot = BedrockCredentialResolver.snapshot("user-1")
        try
        {
            assertEquals(platformKeys.first, snapshot.accessKeyId)
            assertEquals(platformKeys.second, snapshot.secretAccessKey)
            assertFalse(snapshot.byoKey, "snapshot should record the pre-swap state (byoKey=false)")

            assertTrue(setKeysCalls.isNotEmpty(), "resolver should have called setKeys at least once")
            val lastCall = setKeysCalls.last()
            assertEquals(keyId, lastCall.first, "resolver should have set BYO access key")
            assertEquals("byo-secret-12345", lastCall.second, "resolver should have set BYO secret")
        }
        finally
        {
            BedrockCredentialResolver.restore(snapshot)
        }
        assertEquals(platformKeys, setKeysCalls.last(), "restore should re-set platform keys")
    }

    @Test
    fun byoFlagSetButSlotMissingFallsBackToPlatform() = runBlocking {
        installedProvider = BedrockCredentialResolver.installByoKeyProvider(ByoKeyProvider { _ -> null })
        mockkObject(accounting.BillingSync, recordPrivateCalls = true)
        io.mockk.coEvery { accounting.BillingSync.getAccountSettings(any(), any()) } returns structs.account.AccountSettings(
            accelByteUserId = "user-2",
            bringYourOwnApiKey = true
        )

        val snapshot = BedrockCredentialResolver.snapshot("user-2")
        try
        {
            val byoCall = setKeysCalls.firstOrNull { it.first != platformKeys.first }
            assertNull(byoCall, "resolver should NOT swap when slot is missing")
        }
        finally
        {
            BedrockCredentialResolver.restore(snapshot)
        }
    }

    @Test
    fun byoFlagNotSetKeepsPlatformKeys() = runBlocking {
        installedProvider = BedrockCredentialResolver.installByoKeyProvider(ByoKeyProvider { _ ->
            ByoKeyMaterial(
                accessKeyId = "AKIA_BYO",
                secretAccessKey = "byo-secret",
                region = "us-west-2"
            )
        })
        mockkObject(accounting.BillingSync, recordPrivateCalls = true)
        io.mockk.coEvery { accounting.BillingSync.getAccountSettings(any(), any()) } returns structs.account.AccountSettings(
            accelByteUserId = "user-3",
            bringYourOwnApiKey = false
        )

        val snapshot = BedrockCredentialResolver.snapshot("user-3")
        try
        {
            val byoCall = setKeysCalls.firstOrNull { it.first != platformKeys.first }
            assertNull(byoCall, "resolver should not swap when BYO flag is false")
        }
        finally
        {
            BedrockCredentialResolver.restore(snapshot)
        }
    }

    @Test
    fun restoreSwapsBackToPlatformKeys() = runBlocking {
        installedProvider = BedrockCredentialResolver.installByoKeyProvider(ByoKeyProvider { _ ->
            ByoKeyMaterial(
                accessKeyId = "AKIA_BYO",
                secretAccessKey = "byo-secret",
                region = "us-west-2"
            )
        })
        mockkObject(accounting.BillingSync, recordPrivateCalls = true)
        io.mockk.coEvery { accounting.BillingSync.getAccountSettings(any(), any()) } returns structs.account.AccountSettings(
            accelByteUserId = "user-1",
            bringYourOwnApiKey = true
        )

        val snap1 = BedrockCredentialResolver.snapshot("user-1")
        assertTrue(setKeysCalls.any { it.first == "AKIA_BYO" }, "snapshot should have swapped to BYO")
        BedrockCredentialResolver.restore(snap1)
        assertEquals(platformKeys, setKeysCalls.last(), "restore should put platform keys back")
    }

    @Test
    fun decryptErrorFallsBackToPlatformKeys() = runBlocking {
        installedProvider = BedrockCredentialResolver.installByoKeyProvider(ByoKeyProvider { _ ->
            throw RuntimeException("decrypt failed")
        })
        mockkObject(accounting.BillingSync, recordPrivateCalls = true)
        io.mockk.coEvery { accounting.BillingSync.getAccountSettings(any(), any()) } returns structs.account.AccountSettings(
            accelByteUserId = "user-4",
            bringYourOwnApiKey = true
        )

        val snapshot = BedrockCredentialResolver.snapshot("user-4")
        try
        {
            val byoCall = setKeysCalls.firstOrNull { it.first != platformKeys.first }
            assertNull(byoCall, "resolver should not swap when provider throws")
        }
        finally
        {
            BedrockCredentialResolver.restore(snapshot)
        }
    }

    @Test
    fun blankActorIsNoOp() = runBlocking {
        installedProvider = BedrockCredentialResolver.installByoKeyProvider(ByoKeyProvider { _ -> null })
        val snapshot = BedrockCredentialResolver.snapshot("")
        assertEquals(platformKeys.first, snapshot.accessKeyId)
        assertEquals(platformKeys.second, snapshot.secretAccessKey)
        assertFalse(snapshot.byoKey)
        BedrockCredentialResolver.restore(snapshot)
    }

    @Test
    fun nullRestoreIsTolerated() = runBlocking {
        BedrockCredentialResolver.restore(null)
    }
}