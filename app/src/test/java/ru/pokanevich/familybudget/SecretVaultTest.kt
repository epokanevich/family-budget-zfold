package ru.pokanevich.familybudget

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.pokanevich.familybudget.data.SecretVault

class SecretVaultTest {

    @Test
    fun `encrypt and decrypt roundtrip produces prefixed ciphertext`() {
        val provider = SecretVault.inMemoryKeyProviderForTest()
        val secret = "test-secret-api-value-12345"

        val encrypted = SecretVault.encrypt(secret, provider)
        assertTrue(SecretVault.isEncrypted(encrypted))
        assertNotEquals(secret, encrypted)
        assertFalse(encrypted.contains(secret))

        val decrypted = SecretVault.decrypt(encrypted, provider)
        assertEquals(secret, decrypted)
    }

    @Test
    fun `blank secrets stay empty and plaintext legacy values migrate`() {
        val provider = SecretVault.inMemoryKeyProviderForTest()
        assertEquals("", SecretVault.encrypt("   ", provider))
        assertEquals("", SecretVault.decrypt("", provider))

        var migratedValue: String? = null
        val read = SecretVault.readAndMigrateIfPlaintext(
            rawStored = "legacy-plain-token",
            keyProvider = provider
        ) { encrypted ->
            migratedValue = encrypted
        }

        assertEquals("legacy-plain-token", read)
        assertTrue(SecretVault.isEncrypted(migratedValue!!))
        assertEquals("legacy-plain-token", SecretVault.decrypt(migratedValue!!, provider))
    }
}
