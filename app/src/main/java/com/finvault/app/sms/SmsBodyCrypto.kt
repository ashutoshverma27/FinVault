
package com.finvault.app.sms

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

object SmsBodyCrypto {

    private const val KEY_ALIAS = "finvault_sms_body_key_v1"
    private const val PREFIX = "FV-SMS-V1:"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val IV_LENGTH = 12
    private const val TAG_LENGTH_BITS = 128

    @Volatile
    private var cachedKey: SecretKey? = null

    private fun getKey(): SecretKey {
        cachedKey?.let { return it }

        synchronized(this) {
            cachedKey?.let { return it }

            val keyStore = KeyStore.getInstance("AndroidKeyStore")
            keyStore.load(null)

            val existing = keyStore.getKey(KEY_ALIAS, null) as? SecretKey
            if (existing != null) {
                cachedKey = existing
                return existing
            }

            val generator = KeyGenerator.getInstance(
                KeyProperties.KEY_ALGORITHM_AES,
                "AndroidKeyStore"
            )

            val specification = KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or
                        KeyProperties.PURPOSE_DECRYPT
            )
                .setKeySize(256)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(
                    KeyProperties.ENCRYPTION_PADDING_NONE
                )
                .setRandomizedEncryptionRequired(true)
                .build()

            generator.init(specification)

            return generator.generateKey().also {
                cachedKey = it
            }
        }
    }

    fun encrypt(plainText: String): String {
        if (plainText.isEmpty()) return ""
        if (plainText.startsWith(PREFIX)) return plainText

        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getKey())

        val encrypted = cipher.doFinal(
            plainText.toByteArray(Charsets.UTF_8)
        )

        val payload = cipher.iv + encrypted

        return PREFIX + Base64.encodeToString(
            payload,
            Base64.NO_WRAP
        )
    }

    fun decrypt(storedValue: String): String {
        if (storedValue.isEmpty()) return ""

        // Existing rows may still contain plaintext. The import worker
        // encrypts these rows before beginning the full scan.
        if (!storedValue.startsWith(PREFIX)) {
            return storedValue
        }

        return try {
            val payload = Base64.decode(
                storedValue.removePrefix(PREFIX),
                Base64.NO_WRAP
            )

            require(payload.size > IV_LENGTH)

            val iv = payload.copyOfRange(0, IV_LENGTH)
            val encrypted = payload.copyOfRange(
                IV_LENGTH,
                payload.size
            )

            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(
                Cipher.DECRYPT_MODE,
                getKey(),
                GCMParameterSpec(TAG_LENGTH_BITS, iv)
            )

            String(
                cipher.doFinal(encrypted),
                Charsets.UTF_8
            )
        } catch (_: Exception) {
            "[Unable to decrypt stored SMS]"
        }
    }

    fun isEncrypted(value: String): Boolean =
        value.startsWith(PREFIX)
}
