package app.auf.feature.filesystem

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/**
 * Tier 1 Unit Test for the CryptoManager component.
 *
 * Mandate (P-TEST-001, T1): To test the component's internal logic in complete isolation.
 */
class FileSystemFeatureT1CryptoManagerTest {

    private val cryptoManager = CryptoManager()

    @Test
    fun `encrypt then decrypt returns original plaintext`() {
        // Arrange
        val originalText = "This is a secret API key: sk-1234567890abcdef"

        // Act
        val ciphertext = cryptoManager.encrypt(originalText)
        val decryptedText = cryptoManager.decrypt(ciphertext)

        // Assert
        assertNotEquals(originalText, ciphertext, "Ciphertext should not be the same as plaintext.")
        assertEquals(originalText, decryptedText, "Decrypted text should match the original.")
    }

    @Test
    fun `decrypt on a non-encrypted string returns the original string`() {
        // Arrange
        val plaintext = "This is just a regular settings file content."

        // Act
        val result = cryptoManager.decrypt(plaintext)

        // Assert
        assertEquals(plaintext, result, "Decrypting a non-encrypted string should be a no-op.")
    }

    @Test
    fun `decrypt on a malformed or corrupted string returns the original malformed string`() {
        // Arrange
        val malformedCiphertext = "[AUF_ENC_V1]not_valid_base64_$$$"

        // Act
        val result = cryptoManager.decrypt(malformedCiphertext)

        // Assert
        assertEquals(malformedCiphertext, result, "Decrypting a malformed string should return the original to prevent data loss.")
    }

    @Test
    fun `encrypting and decrypting an empty string works correctly`() {
        // Arrange
        val emptyString = ""

        // Act
        val ciphertext = cryptoManager.encrypt(emptyString)
        val decryptedText = cryptoManager.decrypt(ciphertext)

        // Assert
        assertNotEquals(emptyString, ciphertext)
        assertEquals(emptyString, decryptedText)
    }

    @Test
    fun `two different plaintexts produce two different ciphertexts`() {
        // Arrange
        val text1 = "api-key-1"
        val text2 = "api-key-2"

        // Act
        val cipher1 = cryptoManager.encrypt(text1)
        val cipher2 = cryptoManager.encrypt(text2)

        // Assert
        assertNotEquals(cipher1, cipher2)
    }
}