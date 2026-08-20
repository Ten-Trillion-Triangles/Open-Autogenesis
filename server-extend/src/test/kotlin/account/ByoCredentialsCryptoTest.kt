package account

import org.junit.Test
import java.util.Base64
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

/**
 * Pure unit tests for [ByoCredentialsCrypto]. The crypto helper has no I/O, no AccelByte
 * dependencies, and no logging — every test below exercises only the AES-256-GCM round-trip
 * and the master-key validation.
 */
class ByoCredentialsCryptoTest
{
    private val masterKey: ByteArray = ByteArray(32) { it.toByte() }

    @Test
    fun encryptThenDecryptReturnsOriginalPlaintext()
    {
        val plaintext = "AKIA-test-secret-value-with-some-padding"
        val payload = ByoCredentialsCrypto.encrypt(plaintext, masterKey)
        val recovered = ByoCredentialsCrypto.decrypt(payload, masterKey)
        assertEquals(plaintext, recovered)
    }

    @Test
    fun encryptProducesDifferentCiphertextForSamePlaintext()
    {
        val plaintext = "same-plaintext"
        val first = ByoCredentialsCrypto.encrypt(plaintext, masterKey)
        val second = ByoCredentialsCrypto.encrypt(plaintext, masterKey)
        assertFalse(
            first.ciphertext.contentEquals(second.ciphertext),
            "Two encryptions of the same plaintext must produce different ciphertext (random IV)"
        )
        assertFalse(
            first.iv.contentEquals(second.iv),
            "Each call must use a fresh IV"
        )
        assertEquals(plaintext, ByoCredentialsCrypto.decrypt(first, masterKey))
        assertEquals(plaintext, ByoCredentialsCrypto.decrypt(second, masterKey))
    }

    @Test
    fun decryptRejectsTamperedCiphertext()
    {
        val payload = ByoCredentialsCrypto.encrypt("correct-horse-battery-staple", masterKey)
        val tampered = payload.copy(
            ciphertext = payload.ciphertext.copyOf().also { it[0] = (it[0].toInt() xor 0x01).toByte() }
        )
        assertFailsWith<ByoCredentialsCrypto.CryptoException> {
            ByoCredentialsCrypto.decrypt(tampered, masterKey)
        }
    }

    @Test
    fun decryptRejectsWrongKey()
    {
        val payload = ByoCredentialsCrypto.encrypt("secret", masterKey)
        val wrongKey = ByteArray(32) { (it + 1).toByte() }
        assertFailsWith<ByoCredentialsCrypto.CryptoException> {
            ByoCredentialsCrypto.decrypt(payload, wrongKey)
        }
    }

    @Test
    fun encryptAcceptsExactlyThirtyTwoByteKey()
    {
        val key = ByteArray(32) { 0x42 }
        val payload = ByoCredentialsCrypto.encrypt("x", key)
        assertEquals("x", ByoCredentialsCrypto.decrypt(payload, key))
    }

    @Test
    fun encryptRejectsShortKey()
    {
        val shortKey = ByteArray(16) { 0x01 }
        assertFailsWith<ByoCredentialsCrypto.CryptoException> {
            ByoCredentialsCrypto.encrypt("anything", shortKey)
        }
    }

    @Test
    fun encryptRejectsLongKey()
    {
        val longKey = ByteArray(64) { 0x01 }
        assertFailsWith<ByoCredentialsCrypto.CryptoException> {
            ByoCredentialsCrypto.encrypt("anything", longKey)
        }
    }

    @Test
    fun encryptRejectsEmptyPlaintext()
    {
        assertFailsWith<ByoCredentialsCrypto.CryptoException> {
            ByoCredentialsCrypto.encrypt("", masterKey)
        }
    }

    @Test
    fun ivIsTwelveBytes()
    {
        val payload = ByoCredentialsCrypto.encrypt("hello", masterKey)
        assertEquals(12, payload.iv.size, "GCM standard IV length is 12 bytes")
    }

    @Test
    fun loadMasterKeyFromEnvAcceptsBase64ThirtyTwoBytes()
    {
        val keyBytes = ByteArray(32) { it.toByte() }
        val b64 = Base64.getEncoder().encodeToString(keyBytes)
        val original = System.getProperty(ByoCredentialsCrypto.MASTER_KEY_ENV)
        try
        {
            System.setProperty(ByoCredentialsCrypto.MASTER_KEY_ENV, b64)
            val resolved = ByoCredentialsCrypto.loadMasterKeyFromEnv()
            assertContentEquals(keyBytes, resolved)
        }
        finally
        {
            if(original == null) System.clearProperty(ByoCredentialsCrypto.MASTER_KEY_ENV)
            else System.setProperty(ByoCredentialsCrypto.MASTER_KEY_ENV, original)
        }
    }

    @Test
    fun loadMasterKeyFromEnvRejectsWrongLength()
    {
        val original = System.getProperty(ByoCredentialsCrypto.MASTER_KEY_ENV)
        try
        {
            System.setProperty(
                ByoCredentialsCrypto.MASTER_KEY_ENV,
                Base64.getEncoder().encodeToString(ByteArray(16) { 1 })
            )
            assertFailsWith<ByoCredentialsCrypto.CryptoException> {
                ByoCredentialsCrypto.loadMasterKeyFromEnv()
            }
        }
        finally
        {
            if(original == null) System.clearProperty(ByoCredentialsCrypto.MASTER_KEY_ENV)
            else System.setProperty(ByoCredentialsCrypto.MASTER_KEY_ENV, original)
        }
    }

    @Test
    fun loadMasterKeyFromEnvFailsWhenUnset()
    {
        val original = System.getProperty(ByoCredentialsCrypto.MASTER_KEY_ENV)
        try
        {
            System.clearProperty(ByoCredentialsCrypto.MASTER_KEY_ENV)
            assertFailsWith<ByoCredentialsCrypto.CryptoException> {
                ByoCredentialsCrypto.loadMasterKeyFromEnv()
            }
        }
        finally
        {
            if(original != null) System.setProperty(ByoCredentialsCrypto.MASTER_KEY_ENV, original)
        }
    }
}