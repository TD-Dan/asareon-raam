package asareon.raam.feature.filesystem

import asareon.raam.util.LogLevel
import asareon.raam.util.PlatformDependencies
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/**
 * ## Mandate
 * Provides simple, "good enough" encryption to obfuscate sensitive data at rest.
 *
 * ### Security Model
 * This is NOT a cryptographically secure implementation for protecting against a dedicated
 * attacker. It uses a simple XOR cipher with a hardcoded key. Its sole purpose is to
 * prevent secrets (like API keys) from being stored in plaintext on disk, defeating
 * casual inspection and automated file scanners. This is a deliberate trade-off to
 * avoid complex cryptographic library dependencies in the KMP project.
 *
 * All encrypted output is prefixed with a marker to allow for transparent decryption.
 */
@OptIn(ExperimentalEncodingApi::class)
internal class CryptoManager(
    private val platformDependencies: PlatformDependencies
) {

    // A hardcoded key defined as raw bytes to be less conspicuous in the compiled binary.
    // This matches the user's request for a key that "looks like" executable data.
    // CORRECTED: Explicitly cast each Int literal to a Byte.
    private val masterKey: ByteArray = byteArrayOf(
        0x41.toByte(), 0x55.toByte(), 0x46.toByte(), 0x5F.toByte(), 0x4B.toByte(), 0x45.toByte(), 0x59.toByte(), 0x5F.toByte(), 0xDE.toByte(), 0xAD.toByte(), 0xBE.toByte(), 0xEF.toByte(), 0xCA.toByte(), 0xFE.toByte(), 0xBA.toByte(), 0xBE.toByte(),
        0x12.toByte(), 0x34.toByte(), 0x56.toByte(), 0x78.toByte(), 0x9A.toByte(), 0xBC.toByte(), 0xDE.toByte(), 0xF0.toByte(), 0x2A.toByte(), 0x6C.toByte(), 0x9E.toByte(), 0x4D.toByte(), 0x8F.toByte(), 0x0B.toByte(), 0x5A.toByte(), 0x7C.toByte()
    )

    private val encryptionPrefix = "[RAAM_ENC_V1]"

    /**
     * Checks if a given string is prefixed with the encryption marker.
     *
     * @param data The string to check.
     * @return True if the string is encrypted, false otherwise.
     */
    fun isEncrypted(data: String): Boolean {
        return data.startsWith(encryptionPrefix)
    }

    /**
     * Encrypts a plaintext string using a simple XOR cipher and Base64 encoding.
     * The output is prefixed with a recognizable marker.
     *
     * @param plaintext The string to encrypt.
     * @return A prefixed, Base64-encoded ciphertext string.
     */
    fun encrypt(plaintext: String): String {
        val plaintextBytes = plaintext.encodeToByteArray()
        val encryptedBytes = ByteArray(plaintextBytes.size)

        for (i in plaintextBytes.indices) {
            encryptedBytes[i] = (plaintextBytes[i].toInt() xor masterKey[i % masterKey.size].toInt()).toByte()
        }
        return "$encryptionPrefix${Base64.encode(encryptedBytes)}"
    }

    /**
     * Decrypts a string that was encrypted by this manager.
     * It first checks for the encryption prefix. If the prefix is missing, it returns
     * the original input string, assuming it was plaintext.
     *
     * @param ciphertext The string to decrypt.
     * @param returnPlaintextSilently Don't warn if the ciphertext was not encrypted.
     * @return The decrypted plaintext string, or the original input if it's not encrypted.
     */
    fun decrypt(ciphertext: String, returnPlaintextSilently : Boolean = false): String {
        if (!isEncrypted(ciphertext)) {
            // Not our encrypted data, return as-is.
            if (!returnPlaintextSilently) { platformDependencies.log(LogLevel.WARN, "CryptoManager", "Decryption failed: not a valid cipher.") }
            return ciphertext
        }

        return try {
            val base64Content = ciphertext.removePrefix(encryptionPrefix)
            val encryptedBytes = Base64.decode(base64Content)
            val decryptedBytes = ByteArray(encryptedBytes.size)

            for (i in encryptedBytes.indices) {
                decryptedBytes[i] = (encryptedBytes[i].toInt() xor masterKey[i % masterKey.size].toInt()).toByte()
            }
            decryptedBytes.decodeToString()
        } catch (e: Exception) {
            // If any error occurs (e.g., invalid Base64), log the failure and return the original text to avoid data loss.
            platformDependencies.log(LogLevel.ERROR, "CryptoManager", "Decryption failed for ciphertext, returning original.", e)
            ciphertext
        }
    }
}