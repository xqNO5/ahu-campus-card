package cn.edu.ahu.cardqr.data

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
 * Persistence contract for the account credentials.
 *
 * Split from the implementation so [AhuClient] stays free of Android types and the whole
 * login/card pipeline can be exercised on a plain JVM by tools/verify-android.ps1.
 */
interface CredentialStore {
    fun save(username: String, password: String)
    fun load(): SecureStore.Credentials?
    fun clear()
    fun lastUsername(): String?
    fun rememberUsername(username: String)
}

/**
 * Stores the account credentials encrypted with a key that never leaves the Android
 * keystore.  The ciphertext is AES-256-GCM with a random IV per write, so a copied
 * SharedPreferences file is useless without the device's keystore entry.
 *
 * Only the account and password are persisted; the `synjones-auth` token is deliberately
 * kept in memory because it is short-lived and is cheap to re-mint from the CAS session.
 */
class SecureStore(context: Context) : CredentialStore {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    data class Credentials(val username: String, val password: String)

    override fun save(username: String, password: String) {
        prefs.edit()
            .putString(KEY_USER, encrypt(username))
            .putString(KEY_PASS, encrypt(password))
            .apply()
    }

    override fun load(): Credentials? {
        val u = prefs.getString(KEY_USER, null)?.let { decrypt(it) } ?: return null
        val p = prefs.getString(KEY_PASS, null)?.let { decrypt(it) } ?: return null
        if (u.isEmpty() || p.isEmpty()) return null
        return Credentials(u, p)
    }

    override fun clear() {
        prefs.edit().remove(KEY_USER).remove(KEY_PASS).apply()
    }

    override fun lastUsername(): String? =
        prefs.getString(KEY_LAST_USER, null)?.takeIf { it.isNotEmpty() }

    override fun rememberUsername(username: String) {
        prefs.edit().putString(KEY_LAST_USER, username).apply()
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
        val iv = cipher.iv
        val body = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(iv, Base64.NO_WRAP) + ":" + Base64.encodeToString(body, Base64.NO_WRAP)
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
        // A keystore reset (factory reset, restored backup) makes old data unreadable.
        null
    }

    private companion object {
        const val PREFS_NAME = "ahu_card_secure"
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "ahu_card_credentials"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val KEY_USER = "u"
        const val KEY_PASS = "p"
        const val KEY_LAST_USER = "last_u"
    }
}
