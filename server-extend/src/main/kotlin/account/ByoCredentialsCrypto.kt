package account

import org.ttt.autogenesis.logging.LogCategory
import org.ttt.autogenesis.logging.Logger
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * AES-256-GCM helper for the BYO-key storage layer. Stateless and side-effect-free other
 * than the [Logger] access from [loadMasterKeyFromEnv].
 *
 * Storage format on disk is the [EncryptedPayload] JSON envelope, which contains the
 * ciphertext, the 12-byte IV, and an authentication tag carried as the last 16 bytes of the
 * ciphertext (the GCM convention).
 *
 * @see ByoCredentialStore for how this is wired into the per-user record.
 */
object ByoCredentialsCrypto
{
    /** Environment variable (or system property) name for the base64-encoded 32-byte master key. */
    const val MASTER_KEY_ENV: String = "BYO_KEY_MASTER_KEY"

    /** AES block size for GCM mode. */
    private const val GCM_IV_LENGTH_BYTES: Int = 12

    /** GCM auth tag length in bits. The spec recommends 128. */
    private const val GCM_TAG_LENGTH_BITS: Int = 128

    /** Required key length for AES-256. */
    private const val AES_256_KEY_LENGTH_BYTES: Int = 32

    private val secureRandom: SecureRandom = SecureRandom()

    /**
     * Thrown for any crypto failure the caller can react to: wrong key length, tampered
     * ciphertext, missing env var, etc. The cause is intentionally not preserved — crypto
     * errors should not leak key material into stack traces.
     */
    class CryptoException(message: String) : RuntimeException(message)

    /**
     * Encrypts [plaintext] with the supplied [masterKey] using AES-256-GCM and a fresh random IV.
     *
     * @param plaintext Non-empty secret bytes to encrypt.
     * @param masterKey 32-byte AES-256 key.
     * @return A payload carrying the IV and (ciphertext || gcm-tag) bytes.
     * @throws CryptoException when the key length is wrong or the plaintext is empty.
     */
    fun encrypt(plaintext: String, masterKey: ByteArray): EncryptedPayload
    {
        validateKey(masterKey)
        if(plaintext.isEmpty())
        {
            throw CryptoException("Refusing to encrypt an empty secret")
        }
        val iv = ByteArray(GCM_IV_LENGTH_BYTES).also(secureRandom::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            Cipher.ENCRYPT_MODE,
            SecretKeySpec(masterKey, "AES"),
            GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv)
        )
        val ciphertext = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        return EncryptedPayload(ciphertext = ciphertext, iv = iv)
    }

    /**
     * Decrypts [payload] with [masterKey] and returns the recovered plaintext.
     *
     * @throws CryptoException when the key is wrong, the IV length is wrong, or the
     *         GCM authentication tag fails to verify (tampered or rotated ciphertext).
     */
    fun decrypt(payload: EncryptedPayload, masterKey: ByteArray): String
    {
        validateKey(masterKey)
        if(payload.iv.size != GCM_IV_LENGTH_BYTES)
        {
            throw CryptoException("Invalid IV length: ${payload.iv.size} (expected $GCM_IV_LENGTH_BYTES)")
        }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            Cipher.DECRYPT_MODE,
            SecretKeySpec(masterKey, "AES"),
            GCMParameterSpec(GCM_TAG_LENGTH_BITS, payload.iv)
        )
        val plaintext = try
        {
            cipher.doFinal(payload.ciphertext)
        }
        catch(_: javax.crypto.AEADBadTagException)
        {
            throw CryptoException("Authentication failed: ciphertext was tampered with or the key is wrong")
        }
        catch(_: javax.crypto.BadPaddingException)
        {
            throw CryptoException("Decryption failed: wrong key or corrupt payload")
        }
        catch(_: javax.crypto.IllegalBlockSizeException)
        {
            throw CryptoException("Decryption failed: payload is not a valid AES-GCM block")
        }
        return String(plaintext, Charsets.UTF_8)
    }

    /**
     * Resolves the master key from `BYO_KEY_MASTER_KEY` (env var or system property). The
     * value is expected to be a base64-encoded 32-byte sequence. Resolves on every call so
     * tests can swap the env at runtime; production code should cache the result via
     * [ByoCredentialStore].
     *
     * @throws CryptoException when the value is missing, not valid base64, or not 32 bytes.
     */
    fun loadMasterKeyFromEnv(): ByteArray
    {
        val raw = System.getenv(MASTER_KEY_ENV)
            ?: System.getProperty(MASTER_KEY_ENV)
            ?: throw CryptoException(
                "$MASTER_KEY_ENV env var is required for BYO key storage; " +
                "generate a key with: openssl rand -base64 32"
            )
        val bytes = try
        {
            Base64.getDecoder().decode(raw)
        }
        catch(_: IllegalArgumentException)
        {
            throw CryptoException("$MASTER_KEY_ENV is not valid base64")
        }
        if(bytes.size != AES_256_KEY_LENGTH_BYTES)
        {
            throw CryptoException(
                "$MASTER_KEY_ENV must decode to exactly $AES_256_KEY_LENGTH_BYTES bytes (got ${bytes.size})"
            )
        }
        return bytes
    }

    /** Convenience: base64-encode raw bytes for safe transport inside the record JSON. */
    fun encodeBase64(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)

    /** Convenience: inverse of [encodeBase64]. */
    fun decodeBase64(b64: String): ByteArray = Base64.getDecoder().decode(b64)

    private fun validateKey(masterKey: ByteArray)
    {
        if(masterKey.size != AES_256_KEY_LENGTH_BYTES)
        {
            throw CryptoException(
                "Master key must be exactly $AES_256_KEY_LENGTH_BYTES bytes for AES-256 (got ${masterKey.size})"
            )
        }
    }
}

/**
 * The on-disk representation of an encrypted BYO key: ciphertext (which embeds the GCM auth
 * tag at the tail) plus the IV used to produce it. The metadata fields (provider, region,
 * fingerprint, timestamps) are stored alongside this in [ByoCredentialStore] as plaintext
 * JSON so the operator can query them without re-decrypting.
 */
data class EncryptedPayload(
    val ciphertext: ByteArray,
    val iv: ByteArray
)
{
    override fun equals(other: Any?): Boolean
    {
        if(this === other) return true
        if(other !is EncryptedPayload) return false
        return ciphertext.contentEquals(other.ciphertext) && iv.contentEquals(other.iv)
    }

    override fun hashCode(): Int
    {
        var result = ciphertext.contentHashCode()
        result = 31 * result + iv.contentHashCode()
        return result
    }
}