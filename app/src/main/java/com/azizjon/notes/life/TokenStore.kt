package com.azizjon.notes.life

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
 * A token (the GitHub token, or the AI gateway's), sealed with an AES-256-GCM key that never leaves
 * the Android Keystore. The key needs no screen unlock, so syncs in the background can use it. The
 * files are left out of backups (`backup_rules.xml`, `data_extraction_rules.xml`), and a restored
 * copy could not be opened anyway.
 */
class TokenStore(context: Context, prefsName: String = PREFS_NAME, private val keyAlias: String = KEY_ALIAS) {
    private val prefs = context.getSharedPreferences(prefsName, Context.MODE_PRIVATE)

    fun save(token: String) {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val sealed = cipher.doFinal(token.toByteArray(Charsets.UTF_8))
        check(prefs.edit().putString(KEY_TOKEN, encode(cipher.iv) + ":" + encode(sealed)).commit()) {
            "Could not save the token"
        }
    }

    fun read(): String? {
        val parts = prefs.getString(KEY_TOKEN, null)?.split(':')?.takeIf { it.size == 2 } ?: return null
        return runCatching {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, decode(parts[0])))
            cipher.doFinal(decode(parts[1])).toString(Charsets.UTF_8)
        }.getOrNull()
    }

    fun hasToken(): Boolean = prefs.contains(KEY_TOKEN)

    fun clear() {
        prefs.edit().remove(KEY_TOKEN).commit()
    }

    private fun key(): SecretKey {
        val keyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (keyStore.getKey(keyAlias, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(keyAlias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }

    private fun encode(bytes: ByteArray) = Base64.encodeToString(bytes, Base64.NO_WRAP)
    private fun decode(text: String) = Base64.decode(text, Base64.NO_WRAP)

    companion object {
        const val PREFS_NAME = "life_secrets"
        private const val KEY_TOKEN = "token"
        private const val KEY_ALIAS = "life_repo_token"

        /** The AI gateway's key, for the assistant. */
        const val GATEWAY_PREFS_NAME = "assistant_secrets"
        const val GATEWAY_KEY_ALIAS = "assistant_gateway_token"
        private const val KEYSTORE = "AndroidKeyStore"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
    }
}
