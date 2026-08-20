package account

import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.ttt.autogenesis.network.RpcCallContext
import org.ttt.autogenesis.network.RpcJson
import structs.account.AccountSettings
import structs.rpcRequests.RevokeByoKeyRequest
import structs.rpcRequests.SubmitByoKeyRequest
import structs.rpcRequests.TestByoKeyRequest
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Tests [ByoCredentialsRpc] end-to-end through the store seam. Verifies:
 * - submitByoKey encrypts the secret, returns metadata, and flips the AccountSettings flag.
 * - testByoKey reports a clean success path and a missing-key failure path.
 * - byoKeyStatus returns an empty status when nothing is on file.
 * - revokeByoKey deletes the slot and flips the flag back to false.
 * - decryptByoKey returns null for unknown users.
 */
class ByoCredentialsRpcTest
{
    private val masterKey: ByteArray = ByteArray(32) { (it + 13).toByte() }
    private lateinit var fakeVfs: FakeVirtualFileSystem
    private val rpcContext: RpcCallContext = RpcCallContext(
        connectionId = "test-connection",
        metadata = mapOf("accelByteId" to "user-1"),
        sender = { /* noop in tests */ }
    )

    @Before
    fun setUp()
    {
        fakeVfs = FakeVirtualFileSystem()
        ByoCredentialStore.vfsFactory = { fakeVfs }
        ByoCredentialStore.masterKey = masterKey
        ByoCredentialsRpc.vfsFactory = { fakeVfs }
    }

    @After
    fun tearDown()
    {
        ByoCredentialStore.vfsFactory = { org.ttt.autogenesis.server.vfs.VirtualFileSystemManager.forUser(it) }
        ByoCredentialStore.masterKey = null
        ByoCredentialsRpc.vfsFactory = { org.ttt.autogenesis.server.vfs.VirtualFileSystemManager.forUser(it) }
    }

    @Test
    fun submitByoKeyStoresEncryptedSecretAndFlipsFlag() = runBlocking {
        val request = validSubmit("user-1", secret = "my-very-secret-key", region = "us-west-2")
        val status = ByoCredentialsRpc.submitByoKey(rpcContext, request)
        assertEquals("aws-bedrock", status.provider)
        assertEquals("us-west-2", status.region)
        assertEquals("MPLE", status.keyFingerprint)
        assertTrue(status.createdAt > 0L)

        // The store should hold an encrypted record.
        assertTrue(ByoCredentialStore.exists("user-1").getOrThrow())

        // The AccountSettings record should now have bringYourOwnApiKey = true.
        val settings = readAccountSettings(fakeVfs, "user-1")
        assertNotNull(settings)
        assertTrue(settings!!.bringYourOwnApiKey, "submitByoKey must flip the flag to true")
    }

    @Test
    fun submitByoKeyPreservesExistingAccountSettings() = runBlocking {
        // Seed an existing settings record with non-default fields.
        val seeded = AccountSettings(
            accelByteUserId = "user-1",
            displayName = "DisplayName",
            billingStatus = structs.account.BillingStatus(credits = 500.0)
        )
        seedAccountSettings(fakeVfs, "user-1", seeded)

        ByoCredentialsRpc.submitByoKey(rpcContext, validSubmit("user-1"))

        val after = readAccountSettings(fakeVfs, "user-1")
        assertNotNull(after)
        assertEquals("DisplayName", after!!.displayName, "displayName must be preserved across submit")
        assertEquals(500.0, after.billingStatus.credits, "credit balance must be preserved across submit")
        assertTrue(after.bringYourOwnApiKey)
    }

    @Test
    fun testByoKeyReturnsSuccessForValidKey() = runBlocking {
        ByoCredentialsRpc.submitByoKey(rpcContext, validSubmit("user-1"))
        val result = ByoCredentialsRpc.testByoKey(rpcContext, TestByoKeyRequest(userId = "user-1"))
        assertTrue(result.success, "testByoKey should pass after a valid submit: ${result.message}")
        assertTrue(result.message.contains("Decryption succeeded"), "message should explain the check ran")
    }

    @Test
    fun testByoKeyReportsFailureWhenNoKeyOnFile() = runBlocking {
        val result = ByoCredentialsRpc.testByoKey(rpcContext, TestByoKeyRequest(userId = "nobody"))
        assertFalse(result.success)
        assertTrue(result.message.contains("No BYO key on file"), "message should explain the failure: ${result.message}")
    }

    @Test
    fun byoKeyStatusReturnsEmptyForUnknownUser() = runBlocking {
        val status = ByoCredentialsRpc.byoKeyStatus(rpcContext, TestByoKeyRequest(userId = "nobody"))
        assertEquals("", status.provider)
        assertEquals("", status.keyFingerprint)
        assertEquals("", status.region)
    }

    @Test
    fun byoKeyStatusReturnsMetadataAfterSubmit() = runBlocking {
        ByoCredentialsRpc.submitByoKey(rpcContext, validSubmit("user-1", region = "eu-central-1"))
        val status = ByoCredentialsRpc.byoKeyStatus(rpcContext, TestByoKeyRequest(userId = "user-1"))
        assertEquals("aws-bedrock", status.provider)
        assertEquals("eu-central-1", status.region)
    }

    @Test
    fun revokeByoKeyDeletesTheSlotAndFlipsTheFlagBack() = runBlocking {
        ByoCredentialsRpc.submitByoKey(rpcContext, validSubmit("user-1"))
        assertTrue(ByoCredentialStore.exists("user-1").getOrThrow())
        assertTrue(readAccountSettings(fakeVfs, "user-1")!!.bringYourOwnApiKey)

        val result = ByoCredentialsRpc.revokeByoKey(rpcContext, RevokeByoKeyRequest(userId = "user-1"))
        assertTrue(result, "revokeByoKey should return true on success")

        assertFalse(ByoCredentialStore.exists("user-1").getOrThrow(), "store should no longer report the key")
        val after = readAccountSettings(fakeVfs, "user-1")
        assertNotNull(after)
        assertFalse(after!!.bringYourOwnApiKey, "revokeByoKey must flip the flag back to false")
    }

    @Test
    fun revokeByoKeyIsIdempotent() = runBlocking {
        val first = ByoCredentialsRpc.revokeByoKey(rpcContext, RevokeByoKeyRequest(userId = "user-with-no-key"))
        val second = ByoCredentialsRpc.revokeByoKey(rpcContext, RevokeByoKeyRequest(userId = "user-with-no-key"))
        assertTrue(first)
        assertTrue(second, "revoke on a non-existent user must be a no-op success")
    }

    @Test
    fun decryptByoKeyReturnsThePlaintextForKnownUser() = runBlocking {
        ByoCredentialsRpc.submitByoKey(rpcContext, validSubmit("user-1", secret = "round-trip-secret"))
        val decrypted = ByoCredentialsRpc.decryptByoKey("user-1")
        assertNotNull(decrypted)
        assertEquals("round-trip-secret", decrypted!!.secret)
        assertEquals("AKIAIOSFODNN7EXAMPLE", decrypted.keyId)
    }

    @Test
    fun decryptByoKeyReturnsNullForUnknownUser() = runBlocking {
        val decrypted = ByoCredentialsRpc.decryptByoKey("nobody")
        assertEquals(null, decrypted)
    }

    @Test
    fun submitByoKeyPropagatesStoreFailure()
    {
        // Force a store failure by handing it a bad submit (unknown provider).
        val bad = SubmitByoKeyRequest(
            userId = "user-1",
            provider = "openai",       // <-- rejected
            keyId = "AKIAIOSFODNN7EXAMPLE",
            secret = "secret",
            region = "us-east-1"
        )
        var thrown: Throwable? = null
        runBlocking {
            try
            {
                ByoCredentialsRpc.submitByoKey(rpcContext, bad)
            }
            catch(err: Throwable)
            {
                thrown = err
            }
        }
        assertNotNull(thrown, "submitByoKey should rethrow the store's failure")
    }

    private fun validSubmit(userId: String, secret: String = "very-long-secret-access-key-for-testing-only", region: String = "us-east-1"): SubmitByoKeyRequest =
        SubmitByoKeyRequest(
            userId = userId,
            provider = "aws-bedrock",
            keyId = "AKIAIOSFODNN7EXAMPLE",
            secret = secret,
            region = region
        )

    private suspend fun seedAccountSettings(vfs: FakeVirtualFileSystem, userId: String, settings: AccountSettings)
    {
        val payload = RpcJson.encodeToJsonElement(AccountSettings.serializer(), settings)
        val envelope = kotlinx.serialization.json.buildJsonObject {
            put("value", payload)
        }
        vfs.saveUserRecord(userId, "account-settings", envelope)
    }

    private suspend fun readAccountSettings(vfs: FakeVirtualFileSystem, userId: String): AccountSettings?
    {
        val record = vfs.fetchUserRecord(userId, "account-settings").getOrNull() ?: return null
        val value = record.value ?: return null
        val unwrapped = if(value is kotlinx.serialization.json.JsonObject && value.containsKey("value")) value["value"]!! else value
        return RpcJson.decodeFromJsonElement(AccountSettings.serializer(), unwrapped)
    }
}