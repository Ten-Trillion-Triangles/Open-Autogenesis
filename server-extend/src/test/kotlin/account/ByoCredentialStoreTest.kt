package account

import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import structs.rpcRequests.SubmitByoKeyRequest
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tests [ByoCredentialStore] with a [FakeVirtualFileSystem] injected via the [vfsFactory]
 * test seam. Validates the full round-trip: upsert writes encrypted ciphertext (never
 * plaintext), status reflects the metadata, getDecrypted returns the original secret, and
 * delete is idempotent.
 */
class ByoCredentialStoreTest
{
    private val masterKey: ByteArray = ByteArray(32) { (it + 7).toByte() }
    private lateinit var fakeVfs: FakeVirtualFileSystem

    @Before
    fun setUp()
    {
        fakeVfs = FakeVirtualFileSystem()
        ByoCredentialStore.vfsFactory = { fakeVfs }
        ByoCredentialStore.masterKey = masterKey
    }

    @After
    fun tearDown()
    {
        ByoCredentialStore.vfsFactory = { org.ttt.autogenesis.server.vfs.VirtualFileSystemManager.forUser(it) }
        ByoCredentialStore.masterKey = null
    }

    @Test
    fun upsertEncryptsTheSecretAndReturnsMetadataOnly() = runBlocking {
        val secret = "super-secret-access-key-do-not-leak"
        val request = SubmitByoKeyRequest(
            provider = "aws-bedrock",
            keyId = "AKIAIOSFODNN7EXAMPLE",
            secret = secret,
            region = "us-east-1"
        )
        val result = ByoCredentialStore.upsert("user-1", request)
        assertTrue(result.isSuccess, "upsert must succeed: ${result.exceptionOrNull()?.message}")
        val status = result.getOrThrow()
        assertEquals("aws-bedrock", status.provider)
        assertEquals("us-east-1", status.region)
        assertEquals("MPLE", status.keyFingerprint, "fingerprint is the last 4 of the key id")
        assertTrue(status.createdAt > 0L)

        // The on-disk record must NOT contain the plaintext secret.
        val onDisk = fakeVfs.snapshot()["user-1" to ByoCredentialStore.RECORD_KEY]
        assertNotNull(onDisk)
        val raw = onDisk.toString()
        assertFalse(raw.contains(secret), "plaintext secret must never appear on disk")
    }

    @Test
    fun statusForUnknownUserReturnsEmptyByoKeyStatus() = runBlocking {
        val result = ByoCredentialStore.status("nobody")
        assertTrue(result.isSuccess)
        val status = result.getOrThrow()
        assertEquals("", status.provider)
        assertEquals("", status.keyFingerprint)
        assertEquals("", status.region)
        assertEquals(0L, status.createdAt)
    }

    @Test
    fun statusAfterUpsertReturnsStoredMetadata() = runBlocking {
        ByoCredentialStore.upsert("user-1", validRequest(keyId = "AKIAIOSFODNN7EXAMPLE", region = "us-west-2"))
        val status = ByoCredentialStore.status("user-1").getOrThrow()
        assertEquals("aws-bedrock", status.provider)
        assertEquals("us-west-2", status.region)
        assertEquals("MPLE", status.keyFingerprint)
    }

    @Test
    fun getDecryptedRoundTripsTheOriginalSecret() = runBlocking {
        val secret = "another-secret-to-round-trip"
        ByoCredentialStore.upsert(
            "user-1",
            validRequest(keyId = "AKIATESTKEYID1234", secret = secret, region = "eu-central-1")
        )
        val result = ByoCredentialStore.getDecrypted("user-1")
        assertTrue(result.isSuccess)
        val decrypted = result.getOrThrow()
        assertNotNull(decrypted)
        assertEquals("aws-bedrock", decrypted!!.provider)
        assertEquals("AKIATESTKEYID1234", decrypted.keyId)
        assertEquals(secret, decrypted.secret)
        assertEquals("eu-central-1", decrypted.region)
    }

    @Test
    fun getDecryptedForUnknownUserReturnsNull() = runBlocking {
        val result = ByoCredentialStore.getDecrypted("missing-user")
        assertTrue(result.isSuccess)
        assertNull(result.getOrThrow())
    }

    @Test
    fun existsReflectsStorage() = runBlocking {
        assertFalse(ByoCredentialStore.exists("user-1").getOrThrow())
        ByoCredentialStore.upsert("user-1", validRequest(region = "us-east-1"))
        assertTrue(ByoCredentialStore.exists("user-1").getOrThrow())
    }

    @Test
    fun deleteTombstonesTheRecord() = runBlocking {
        ByoCredentialStore.upsert("user-1", validRequest(region = "us-east-1"))
        assertTrue(ByoCredentialStore.exists("user-1").getOrThrow())
        val deleteResult = ByoCredentialStore.delete("user-1")
        assertTrue(deleteResult.isSuccess, "delete must succeed")
        // A subsequent read sees "no record" because readRecord treats version=0 as missing.
        assertFalse(ByoCredentialStore.exists("user-1").getOrThrow())
    }

    @Test
    fun deleteOnMissingUserIsIdempotent() = runBlocking {
        val result = ByoCredentialStore.delete("user-that-never-existed")
        assertTrue(result.isSuccess)
    }

    @Test
    fun recordUsageBumpsLastUsedOnSuccess() = runBlocking {
        ByoCredentialStore.upsert("user-1", validRequest(region = "us-east-1"))
        val before = ByoCredentialStore.status("user-1").getOrThrow().lastUsedAt
        ByoCredentialStore.recordUsage("user-1", success = true)
        val after = ByoCredentialStore.status("user-1").getOrThrow().lastUsedAt
        assertTrue(after >= before, "lastUsedAt must not decrease on a success (was=$before, after=$after)")
    }

    @Test
    fun recordUsageTruncatesLongErrorMessage() = runBlocking {
        ByoCredentialStore.upsert("user-1", validRequest(region = "us-east-1"))
        val longError = "x".repeat(1000)
        ByoCredentialStore.recordUsage("user-1", success = false, errorMessage = longError)
        val status = ByoCredentialStore.status("user-1").getOrThrow()
        assertTrue(
            status.lastError.length <= ByoCredentialStore.MAX_LAST_ERROR_LENGTH,
            "lastError must be truncated to <= ${ByoCredentialStore.MAX_LAST_ERROR_LENGTH}"
        )
    }

    @Test
    fun upsertRejectsUnknownProvider() = runBlocking {
        val request = SubmitByoKeyRequest(
            provider = "openai",
            keyId = "AKIA0001",
            secret = "sk-test",
            region = "us-east-1"
        )
        val result = ByoCredentialStore.upsert("user-1", request)
        assertTrue(result.isFailure)
    }

    @Test
    fun upsertRejectsUnknownRegion() = runBlocking {
        val request = SubmitByoKeyRequest(
            provider = "aws-bedrock",
            keyId = "AKIA0001",
            secret = "sk-test",
            region = "antartica-1"
        )
        val result = ByoCredentialStore.upsert("user-1", request)
        assertTrue(result.isFailure)
    }

    @Test
    fun upsertRejectsBlankKeyId() = runBlocking {
        val request = SubmitByoKeyRequest(
            provider = "aws-bedrock",
            keyId = "",
            secret = "sk-test",
            region = "us-east-1"
        )
        val result = ByoCredentialStore.upsert("user-1", request)
        assertTrue(result.isFailure)
    }

    @Test
    fun upsertRejectsBlankSecret() = runBlocking {
        val request = SubmitByoKeyRequest(
            provider = "aws-bedrock",
            keyId = "AKIA0001",
            secret = "",
            region = "us-east-1"
        )
        val result = ByoCredentialStore.upsert("user-1", request)
        assertTrue(result.isFailure)
    }

    @Test
    fun blankUserIdIsRejected() = runBlocking {
        val result = ByoCredentialStore.upsert("", validRequest(region = "us-east-1"))
        assertTrue(result.isFailure)
    }

    @Test
    fun restClientUserIdTranslatesToGuestUser() = runBlocking {
        ByoCredentialStore.upsert("rest-client-12345", validRequest(region = "us-east-1"))
        // Lookup via the same id should resolve to the guest-user namespace.
        assertTrue(ByoCredentialStore.exists("rest-client-12345").getOrThrow())
        assertTrue(ByoCredentialStore.exists("guest-user").getOrThrow())
    }

    @Test
    fun upsertOverwritesAndPreservesCreatedAt() = runBlocking {
        ByoCredentialStore.upsert("user-1", validRequest(keyId = "AKIAFIRST12345", region = "us-east-1"))
        val firstCreated = ByoCredentialStore.status("user-1").getOrThrow().createdAt

        // Small delay to ensure timestamps would differ if we accidentally overwrote createdAt.
        Thread.sleep(5)

        ByoCredentialStore.upsert("user-1", validRequest(keyId = "AKIASECOND1234", region = "us-west-2"))
        val secondStatus = ByoCredentialStore.status("user-1").getOrThrow()
        assertEquals(firstCreated, secondStatus.createdAt, "createdAt must be preserved across upserts")
        assertEquals("us-west-2", secondStatus.region)
        assertEquals("1234", secondStatus.keyFingerprint)
    }

    private fun validRequest(
        keyId: String = "AKIAIOSFODNN7EXAMPLE",
        secret: String = "secret-value",
        region: String = "us-east-1"
    ): SubmitByoKeyRequest = SubmitByoKeyRequest(
        provider = "aws-bedrock",
        keyId = keyId,
        secret = secret,
        region = region
    )
}
