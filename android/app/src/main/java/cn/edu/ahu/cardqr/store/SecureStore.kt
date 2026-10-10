package cn.edu.ahu.cardqr.store

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
 * Small encrypted key/value store backed by the Android keystore.
 *
 * Values are sealed with AES-256-GCM under a key that is generated inside the keystore and
 * never leaves it, so a copied `SharedPreferences` file is useless elsewhere. The portal's
 * `JSESSIONID` is the only thing that really has to survive restarts -- storing it here is
 * what allows the app to show a working QR code on launch without asking for a captcha.
 */
class SecureStore(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun read(key: String): String? =
        prefs.getString(key, null)?.let { decrypt(it) }

    fun write(key: String, value: String?) {
        if (value == null) {
            remove(key)
        } else {
            prefs.edit().putString(key, encrypt(value)).apply()
        }
    }

    fun remove(key: String) {
        prefs.edit().remove(key).apply()
    }

    /** Wipe everything this store holds, e.g. on an explicit sign-out. */
    fun clear() {
        prefs.edit().clear().apply()
    }

    // ------------------------------------------------------------------ crypto

    private fun secretKey(): SecretKey {
        val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (ks.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return generator.generateKey()
    }

    private fun encrypt(plain: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val body = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(cipher.iv, Base64.NO_WRAP) + ":" +
            Base64.encodeToString(body, Base64.NO_WRAP)
    }

    private fun decrypt(stored: String): String? = try {
        val parts = stored.split(":", limit = 2)
        if (parts.size != 2) {
            null
        } else {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(
                Cipher.DECRYPT_MODE,
                secretKey(),
                GCMParameterSpec(128, Base64.decode(parts[0], Base64.NO_WRAP)),
            )
            String(cipher.doFinal(Base64.decode(parts[1], Base64.NO_WRAP)), Charsets.UTF_8)
        }
    } catch (_: Exception) {
        // A keystore reset (factory reset, restored backup) makes old values unreadable;
        // treat that as "nothing stored" rather than crashing.
        null
    }

    private companion object {
        const val PREFS_NAME = "ahu_card_secure"
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "ahu_card_credentials"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
    }
}
