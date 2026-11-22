package com.hopla.tv

import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * The playlist link holds the account password, so it is stored encrypted (AES-GCM) with a key that lives
 * in the device's Android Keystore and never leaves it.
 */
object Vault {
    private const val ALIAS = "hopla-link"

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build(),
        )
        return gen.generateKey()
    }

    fun seal(text: String): String {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, key())
        return Base64.encodeToString(c.iv + c.doFinal(text.toByteArray()), Base64.NO_WRAP)
    }

    fun open(sealed: String): String? = runCatching {
        val b = Base64.decode(sealed, Base64.NO_WRAP)
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, b, 0, 12))
        String(c.doFinal(b, 12, b.size - 12))
    }.getOrNull()
}

/** Reads the link, moving an old plain-text copy into the vault on the way. */
fun SharedPreferences.readLink(): String {
    getString("linkSealed", null)?.let { Vault.open(it) }?.let { return it }
    val plain = getString("link", null) ?: return ""
    saveLink(plain)
    return plain
}

fun SharedPreferences.saveLink(link: String) {
    val sealed = runCatching { Vault.seal(link) }.getOrNull()
    // Never lose the link: only drop the plain copy once the sealed one is proven to open.
    if (sealed != null && Vault.open(sealed) == link) edit().putString("linkSealed", sealed).remove("link").apply()
    else edit().putString("link", link).apply()
}
