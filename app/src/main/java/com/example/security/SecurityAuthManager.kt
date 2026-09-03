package com.example.security

import android.content.Context
import android.util.Base64
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

class SecurityAuthManager(context: Context) {
    private val appContext = context.applicationContext

    private val sharedPrefs by lazy {
        val masterKey = MasterKey.Builder(appContext)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            appContext,
            "vault_prefs",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    fun hasPin(): Boolean {
        return sharedPrefs.contains("vault_pin_hash") && sharedPrefs.contains("vault_pin_salt")
    }

    fun verifyPin(pin: String): Boolean {
        val storedHashBase64 = sharedPrefs.getString("vault_pin_hash", null) ?: return false
        val storedSaltBase64 = sharedPrefs.getString("vault_pin_salt", null) ?: return false
        return try {
            val storedHash = Base64.decode(storedHashBase64, Base64.DEFAULT)
            val salt = Base64.decode(storedSaltBase64, Base64.DEFAULT)
            val attemptedHash = hashPin(pin, salt)
            attemptedHash.contentEquals(storedHash)
        } catch (e: Exception) {
            false
        }
    }

    fun setPin(pin: String) {
        val salt = ByteArray(16)
        SecureRandom().nextBytes(salt)
        val hash = hashPin(pin, salt)

        sharedPrefs.edit()
            .putString("vault_pin_hash", Base64.encodeToString(hash, Base64.DEFAULT))
            .putString("vault_pin_salt", Base64.encodeToString(salt, Base64.DEFAULT))
            .apply()
    }

    fun changePin(oldPin: String, newPin: String): Boolean {
        if (verifyPin(oldPin)) {
            setPin(newPin)
            return true
        }
        return false
    }

    fun clearPin() {
        sharedPrefs.edit().clear().apply()
    }

    private fun hashPin(pin: String, salt: ByteArray): ByteArray {
        val spec = PBEKeySpec(pin.toCharArray(), salt, 100000, 256)
        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        return factory.generateSecret(spec).encoded
    }
}
