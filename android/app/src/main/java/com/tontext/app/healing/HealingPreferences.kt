package com.tontext.app.healing

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

private const val LOG_TAG = "HealingPreferences"

class HealingPreferences(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(HealingConfig.PREFS_NAME, Context.MODE_PRIVATE)

    var healingEnabled: Boolean
        get() = prefs.getBoolean(HealingConfig.PREF_HEALING_ENABLED, false)
        set(value) = prefs.edit().putBoolean(HealingConfig.PREF_HEALING_ENABLED, value).apply()

    var llmProvider: String
        get() = prefs.getString(HealingConfig.PREF_LLM_PROVIDER, HealingConfig.PROVIDER_ANTHROPIC) ?: HealingConfig.PROVIDER_ANTHROPIC
        set(value) = prefs.edit().putString(HealingConfig.PREF_LLM_PROVIDER, value).apply()

    var apiKey: String
        get() = decryptApiKey()
        set(value) = encryptApiKey(value)

    val isConfigured: Boolean
        get() = healingEnabled && apiKey.isNotEmpty()

    private fun getOrCreateSecretKey(): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val existingKey = keyStore.getEntry(HealingConfig.KEYSTORE_ALIAS, null)
        if (existingKey is KeyStore.SecretKeyEntry) {
            return existingKey.secretKey
        }

        val keyGenerator = KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore"
        )
        keyGenerator.init(
            KeyGenParameterSpec.Builder(
                HealingConfig.KEYSTORE_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return keyGenerator.generateKey()
    }

    private fun encryptApiKey(plaintext: String) {
        if (plaintext.isEmpty()) {
            prefs.edit()
                .remove(HealingConfig.PREF_API_KEY_ENCRYPTED)
                .remove(HealingConfig.PREF_API_KEY_IV)
                .apply()
            return
        }

        try {
            val secretKey = getOrCreateSecretKey()
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, secretKey)
            val encrypted = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
            val iv = cipher.iv

            prefs.edit()
                .putString(HealingConfig.PREF_API_KEY_ENCRYPTED, Base64.encodeToString(encrypted, Base64.NO_WRAP))
                .putString(HealingConfig.PREF_API_KEY_IV, Base64.encodeToString(iv, Base64.NO_WRAP))
                .apply()
        } catch (e: Exception) {
            Log.e(LOG_TAG, "Failed to encrypt API key", e)
        }
    }

    private fun decryptApiKey(): String {
        val encryptedB64 = prefs.getString(HealingConfig.PREF_API_KEY_ENCRYPTED, null) ?: return ""
        val ivB64 = prefs.getString(HealingConfig.PREF_API_KEY_IV, null) ?: return ""

        return try {
            val secretKey = getOrCreateSecretKey()
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            val iv = Base64.decode(ivB64, Base64.NO_WRAP)
            cipher.init(Cipher.DECRYPT_MODE, secretKey, GCMParameterSpec(128, iv))
            val decrypted = cipher.doFinal(Base64.decode(encryptedB64, Base64.NO_WRAP))
            String(decrypted, Charsets.UTF_8)
        } catch (e: Exception) {
            Log.e(LOG_TAG, "Failed to decrypt API key", e)
            ""
        }
    }
}
