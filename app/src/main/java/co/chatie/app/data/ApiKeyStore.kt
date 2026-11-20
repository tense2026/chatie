package co.chatie.app.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * The gateway key, sealed with an AES-GCM key that lives in the Android
 * keystore (hardware-backed where the device has a TEE or StrongBox) and never
 * leaves it. Only the ciphertext is written to preferences.
 *
 * The key is never put in the chat database, never logged, and leaves the
 * device only as the Authorization header on requests to the gateway host.
 *
 * androidx.security:security-crypto would do the same thing, but Jetpack has
 * deprecated it, so this uses the platform APIs directly.
 */
class ApiKeyStore(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun save(key: String) {
        val trimmed = key.trim()
        if (trimmed.isEmpty()) {
            clear()
            return
        }
        val cipher = Cipher.getInstance(TRANSFORMATION).apply {
            init(Cipher.ENCRYPT_MODE, secretKey())
        }
        val ciphertext = cipher.doFinal(trimmed.toByteArray(Charsets.UTF_8))
        // iv is 12 bytes for GCM; prepend it so one blob holds everything.
        val blob = cipher.iv + ciphertext
        prefs.edit()
            .putString(VALUE, Base64.encodeToString(blob, Base64.NO_WRAP))
            .apply()
    }

    fun load(): String? {
        val stored = prefs.getString(VALUE, null) ?: return null
        return runCatching {
            val blob = Base64.decode(stored, Base64.NO_WRAP)
            val iv = blob.copyOfRange(0, GCM_IV_BYTES)
            val ciphertext = blob.copyOfRange(GCM_IV_BYTES, blob.size)
            val cipher = Cipher.getInstance(TRANSFORMATION).apply {
                init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(GCM_TAG_BITS, iv))
            }
            String(cipher.doFinal(ciphertext), Charsets.UTF_8)
        }.getOrNull()?.takeIf { it.isNotBlank() }
    }

    fun clear() {
        prefs.edit().remove(VALUE).apply()
    }

    fun hasKey(): Boolean = prefs.contains(VALUE)

    private fun secretKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }

    companion object {
        private const val PREFS = "chatie_secure"
        private const val VALUE = "baze_api_key"
        private const val ALIAS = "chatie_api_key_v1"
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val GCM_IV_BYTES = 12
        private const val GCM_TAG_BITS = 128

        /** Last four characters, for showing which key is in use. */
        fun hint(key: String): String = if (key.length <= 4) "••••" else "••••${key.takeLast(4)}"
    }
}
