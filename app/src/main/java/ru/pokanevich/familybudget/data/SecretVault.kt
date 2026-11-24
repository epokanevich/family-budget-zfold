package ru.pokanevich.familybudget.data

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Хранилище чувствительных пользовательских секретов (ключи API, токены) поверх AndroidKeyStore (AES-256-GCM).
 * Автоматически расшифровывает и мигрирует устаревшие plaintext-значения из SharedPreferences.
 */
object SecretVault {

    private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    private const val KEY_ALIAS = "family_budget_secret_v1"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val PREFIX = "enc:v1:"
    private const val GCM_TAG_BITS = 128
    private const val GCM_IV_BYTES = 12

    interface KeyProvider {
        fun getOrCreateKey(): SecretKey?
    }

    private object AndroidKeyStoreProvider : KeyProvider {
        override fun getOrCreateKey(): SecretKey? = runCatching {
            val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
            (ks.getKey(KEY_ALIAS, null) as? SecretKey) ?: run {
                val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
                val spec = KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build()
                generator.init(spec)
                generator.generateKey()
            }
        }.getOrNull()
    }

    internal fun inMemoryKeyProviderForTest(keyBytes: ByteArray = ByteArray(32) { (it + 17).toByte() }): KeyProvider {
        val secretKey: SecretKey = SecretKeySpec(keyBytes.copyOf(32), "AES")
        return object : KeyProvider {
            override fun getOrCreateKey(): SecretKey = secretKey
        }
    }

    fun isEncrypted(stored: String): Boolean = stored.startsWith(PREFIX)

    fun encrypt(plaintext: String, keyProvider: KeyProvider = AndroidKeyStoreProvider): String {
        val trimmed = plaintext.trim()
        if (trimmed.isEmpty()) return ""
        val key = keyProvider.getOrCreateKey() ?: return trimmed
        return runCatching {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            val iv = ByteArray(GCM_IV_BYTES).also { SecureRandom().nextBytes(it) }
            cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, iv))
            val encrypted = cipher.doFinal(trimmed.toByteArray(Charsets.UTF_8))
            val b64 = Base64.getEncoder()
            "$PREFIX${b64.encodeToString(iv)}:${b64.encodeToString(encrypted)}"
        }.getOrElse { trimmed }
    }

    fun decrypt(stored: String, keyProvider: KeyProvider = AndroidKeyStoreProvider): String {
        val trimmed = stored.trim()
        if (trimmed.isEmpty()) return ""
        if (!trimmed.startsWith(PREFIX)) return trimmed

        val parts = trimmed.removePrefix(PREFIX).split(':', limit = 2)
        if (parts.size != 2) return ""
        val key = keyProvider.getOrCreateKey() ?: return ""
        return runCatching {
            val b64 = Base64.getDecoder()
            val iv = b64.decode(parts[0])
            val payload = b64.decode(parts[1])
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, iv))
            String(cipher.doFinal(payload), Charsets.UTF_8).trim()
        }.getOrDefault("")
    }

    /**
     * Читает секрет и при обнаружении старого plaintext-значения в хранилище вызывает [onMigrateToEncrypted],
     * чтобы незаметно перевести значение в зашифрованный формат.
     */
    fun readAndMigrateIfPlaintext(
        rawStored: String,
        keyProvider: KeyProvider = AndroidKeyStoreProvider,
        onMigrateToEncrypted: (String) -> Unit
    ): String {
        val trimmed = rawStored.trim()
        if (trimmed.isEmpty()) return ""
        if (isEncrypted(trimmed)) {
            return decrypt(trimmed, keyProvider)
        }
        val encrypted = encrypt(trimmed, keyProvider)
        if (isEncrypted(encrypted)) {
            onMigrateToEncrypted(encrypted)
        }
        return trimmed
    }
}
